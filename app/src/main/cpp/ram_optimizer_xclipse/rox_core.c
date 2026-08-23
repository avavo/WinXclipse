/* ─────────────────────────────────────────────
 * rox_core.c (revisado)
 * Correções:
 *
 * - FIX: data race em g_initialized
 * - FIX: cooldown usando CLOCK_MONOTONIC
 * - FIX: clamp superior do feedback
 * - FIX: proteção contra overflow
 * - FIX: shutdown race/use-after-free
 * - FIX: hysteresis térmica
 * - FIX: não sobrescreve flush_profile adaptativo após majflt sample
 * - FIX: revalidação de init após lock em apply_profile()
 * - IMPROVEMENT: lock global de estado
 * - REFACTOR: decisão base de perfil compartilhada por flush/diagnose
 * ───────────────────────────────────────────── */

#include <stdio.h>
#include <string.h>
#include <stdatomic.h>
#include <time.h>
#include <unistd.h>
#include <pthread.h>

#include "rox.h"
#include "rox_gtt_react.h"
#include "rox_internal.h"
#include "rox_ringbuf.h"
#include "rox_feedback.h"
#include "rox_thermal.h"

static atomic_int g_initialized = 0;

static pthread_mutex_t g_state_lock = PTHREAD_MUTEX_INITIALIZER;

static rox_profile_t g_profile = ROPT_PROFILE_MEDIUM;

/* cooldown monotônico */
#define ROPT_FLUSH_COOLDOWN_SEC 2

#define ROPT_MAJFLT_SAMPLE_MS        80
#define ROPT_MAJFLT_RATE_THRESHOLD   5
#define ROPT_MAJFLT_SUSPEND_SEC      2

/* hysteresis térmica */
#define ROPT_THERMAL_HYSTERESIS_SEC 5

/* moderação quando o processo já está usando swap */
#define ROPT_VMSWAP_AGGRESSIVE_LIMIT_KB (64 * 1024)

static _Atomic long long g_last_flush_mono = 0;
static _Atomic long long g_last_majflt_suspend = 0;
static _Atomic long long g_last_thermal_hot = 0;
static _Atomic int g_flush_active = 0;

/* ───────────────────────────────────────────── */

static long long mono_sec(void)
{
    struct timespec ts;

    clock_gettime(CLOCK_MONOTONIC, &ts);

    return (long long)ts.tv_sec;
}

static int flush_cooldown_ok(void)
{
    long long last = atomic_load(&g_last_flush_mono);

    if (last == 0)
        return 1;

    return (mono_sec() - last) >= ROPT_FLUSH_COOLDOWN_SEC;
}

static void flush_mark(void)
{
    atomic_store(&g_last_flush_mono, mono_sec());
}

/* ─────────────────────────────────────────────
 * Decisão de perfil
 *
 * Mantém a lógica base em um único lugar para evitar divergência entre
 * rox_flush() e rox_diagnose(). O resto são guardas de segurança:
 * thermal, majflt e VmSwap.
 * ───────────────────────────────────────────── */

static unsigned int rox_compute_avail_threshold(const rox_psi_t *psi,
                                                  int feedback_adj)
{
    unsigned int threshold;

    threshold =
        (psi && psi->available && psi->full_avg60 > 1.0f)
        ? 20U
        : 15U;

    threshold = (unsigned int)((int)threshold + feedback_adj);

    if ((int)threshold < 5)
        threshold = 5;

    if (threshold > 40)
        threshold = 40;

    return threshold;
}

static unsigned long rox_effective_denom_kb(unsigned long total_kb,
                                              unsigned long available_kb)
{
    unsigned long system_denom;
    long long cg_limit;

    system_denom = total_kb;

    if (available_kb > 0 &&
        (available_kb * 3ULL) < system_denom) {

        system_denom = available_kb * 3ULL;
    }

    cg_limit = rox_cgroup_limit_kb();

    if (cg_limit > 0 &&
        (unsigned long)cg_limit < system_denom) {

        return (unsigned long)cg_limit;
    }

    return system_denom;
}

static rox_profile_t rox_choose_base_profile(const rox_stats_t *ram,
                                                 int frag,
                                                 const rox_psi_t *psi,
                                                 rox_gtt_level_t gtt_level)
{
    unsigned int avail_threshold;
    unsigned long denom_kb;
    int feedback_adj;

    feedback_adj = rox_feedback_get_threshold_adjustment();

    avail_threshold =
        rox_compute_avail_threshold(psi, feedback_adj);

    denom_kb =
        rox_effective_denom_kb(ram->total_kb, ram->available_kb);

    if (denom_kb > 0 &&
        ram->available_kb <
        ((denom_kb / 100ULL) * avail_threshold)) {

        return ROPT_PROFILE_AGGRESSIVE;
    }

    if (frag > 60)
        return ROPT_PROFILE_AGGRESSIVE;

    if (frag > 35) {
        return
            (gtt_level >= ROPT_GTT_AGGRESSIVE)
            ? ROPT_PROFILE_AGGRESSIVE
            : ROPT_PROFILE_MEDIUM;
    }

    if (gtt_level >= ROPT_GTT_AGGRESSIVE)
        return ROPT_PROFILE_AGGRESSIVE;

    if (gtt_level >= ROPT_GTT_MEDIUM)
        return ROPT_PROFILE_MEDIUM;

    return ROPT_PROFILE_LIGHT;
}

static rox_profile_t rox_apply_thermal_guard(rox_profile_t profile)
{
    long long now;
    rox_thermal_level_t thermal;

    now = mono_sec();
    thermal = rox_thermal_get_level();

    if (thermal >= ROPT_THERMAL_HOT) {

        atomic_store(&g_last_thermal_hot, now);

        if (profile == ROPT_PROFILE_AGGRESSIVE)
            return ROPT_PROFILE_MEDIUM;

        if (profile == ROPT_PROFILE_MEDIUM)
            return ROPT_PROFILE_LIGHT;

        return profile;
    }

    {
        long long hot_last =
            atomic_load(&g_last_thermal_hot);

        if (hot_last != 0 &&
            (now - hot_last) <
            ROPT_THERMAL_HYSTERESIS_SEC) {

            if (profile == ROPT_PROFILE_AGGRESSIVE)
                return ROPT_PROFILE_MEDIUM;
        }
    }

    return profile;
}

static rox_profile_t rox_apply_thermal_guard_passive(rox_profile_t profile,
                                                           int thermal_level)
{
    long long now;
    long long hot_last;

    if (thermal_level >= ROPT_THERMAL_HOT) {
        if (profile == ROPT_PROFILE_AGGRESSIVE)
            return ROPT_PROFILE_MEDIUM;

        if (profile == ROPT_PROFILE_MEDIUM)
            return ROPT_PROFILE_LIGHT;

        return profile;
    }

    now = mono_sec();
    hot_last = atomic_load(&g_last_thermal_hot);

    if (hot_last != 0 &&
        (now - hot_last) < ROPT_THERMAL_HYSTERESIS_SEC) {

        if (profile == ROPT_PROFILE_AGGRESSIVE)
            return ROPT_PROFILE_MEDIUM;
    }

    return profile;
}

static rox_profile_t rox_apply_swap_guard(rox_profile_t profile)
{
    long long vmswap;

    if (profile != ROPT_PROFILE_AGGRESSIVE)
        return profile;

    vmswap = rox_self_vmswap_kb();

    if (vmswap > ROPT_VMSWAP_AGGRESSIVE_LIMIT_KB)
        return ROPT_PROFILE_MEDIUM;

    return profile;
}

/* ───────────────────────────────────────────── */

int rox_init(void)
{
    pthread_mutex_lock(&g_state_lock);

    if (atomic_load(&g_initialized)) {
        pthread_mutex_unlock(&g_state_lock);
        return ROPT_OK;
    }

    rox_stats_t stats;

    if (rox_read_meminfo(&stats) != ROPT_OK) {
        pthread_mutex_unlock(&g_state_lock);
        return ROPT_ERR_INIT;
    }

    g_profile = ROPT_PROFILE_MEDIUM;

    rox_feedback_init();
    rox_thermal_init();

    {
        int r = rox_mallopt_apply(ROPT_PROFILE_MEDIUM);

        if (r != ROPT_OK && r != ROPT_ERR_NODEV) {
            pthread_mutex_unlock(&g_state_lock);
            return r;
        }
    }

    rox_ringbuf_init();

    atomic_store(&g_initialized, 1);

    pthread_mutex_unlock(&g_state_lock);

    return ROPT_OK;
}

/* ───────────────────────────────────────────── */

void rox_shutdown(void)
{
    pthread_mutex_lock(&g_state_lock);

    if (!atomic_load(&g_initialized)) {
        pthread_mutex_unlock(&g_state_lock);
        return;
    }

    /*
     * Marca como desligado.
     * Flushes que reacordarem após soltar o lock durante amostragem
     * precisam revalidar g_initialized antes de tocar recursos globais.
     */
    atomic_store(&g_initialized, 0);

    atomic_thread_fence(memory_order_seq_cst);

    rox_ringbuf_destroy();

    pthread_mutex_unlock(&g_state_lock);
}

/* ───────────────────────────────────────────── */

int rox_apply_profile(rox_profile_t profile)
{
    int ret;

    if ((int)profile < 0 || profile > ROPT_PROFILE_MAX_VALID)
        return ROPT_ERR_PARAM;

    pthread_mutex_lock(&g_state_lock);

    if (!atomic_load(&g_initialized)) {
        pthread_mutex_unlock(&g_state_lock);
        return ROPT_ERR_INIT;
    }

    g_profile = profile;

    ret = rox_mallopt_apply(profile);

    if (ret != ROPT_OK && ret != ROPT_ERR_NODEV) {
        pthread_mutex_unlock(&g_state_lock);
        return ret;
    }

    ret = rox_heap_compact(profile);

    pthread_mutex_unlock(&g_state_lock);

    return ret;
}

/* ───────────────────────────────────────────── */

int rox_flush(void)
{
    rox_stats_t     ram;
    rox_gtt_state_t gtt;
    rox_psi_t       psi;

    rox_profile_t   flush_profile;
    rox_gtt_level_t gtt_level;

    int frag;
    int ret;

    if (!atomic_load(&g_initialized))
        return ROPT_ERR_INIT;

    if (atomic_exchange(&g_flush_active, 1))
        return ROPT_OK;

/*
 * Usar somente quando g_state_lock estiver travado.
 * Sim, macro em C é uma faca sem cabo. Segura direito.
 */
#define ROPT_FLUSH_RETURN_UNLOCK(value) \
    do { \
        atomic_store(&g_flush_active, 0); \
        pthread_mutex_unlock(&g_state_lock); \
        return (value); \
    } while (0)

    pthread_mutex_lock(&g_state_lock);

    /* recheck após lock */
    if (!atomic_load(&g_initialized)) {
        ROPT_FLUSH_RETURN_UNLOCK(ROPT_ERR_INIT);
    }

    /*
     * Cooldown:
     * bypass apenas quando RAM está realmente crítica.
     */
    if (!flush_cooldown_ok()) {

        rox_stats_t quick;
        int emergency = 0;

        if (rox_read_meminfo(&quick) == ROPT_OK &&
            quick.total_kb > 0 &&
            quick.available_kb < (quick.total_kb / 10ULL)) {

            emergency = 1;
        }

        if (!emergency) {
            ROPT_FLUSH_RETURN_UNLOCK(ROPT_OK);
        }
    }

    ret = rox_read_meminfo(&ram);

    if (ret != ROPT_OK) {
        ROPT_FLUSH_RETURN_UNLOCK(ret);
    }

    frag = rox_read_fragmentation();

    memset(&gtt, 0, sizeof(gtt));
    (void)rox_gtt_read(&gtt);

    gtt_level = rox_gtt_classify(&gtt);

    memset(&psi, 0, sizeof(psi));
    (void)rox_psi_read(&psi);

    flush_profile =
        rox_choose_base_profile(&ram, frag, &psi, gtt_level);

    flush_profile =
        rox_apply_thermal_guard(flush_profile);

    /*
     * majflt active:
     * se o processo está gerando major faults em ritmo alto,
     * evita compactação agressiva para não piorar page fault/swap.
     */
    if (flush_profile >= ROPT_PROFILE_MEDIUM) {

        long long last_suspend =
            atomic_load(&g_last_majflt_suspend);

        if (last_suspend != 0 &&
            (mono_sec() - last_suspend) <
            ROPT_MAJFLT_SUSPEND_SEC) {

            flush_profile = ROPT_PROFILE_LIGHT;
        }

        else {

            long long flt_before;
            long long flt_after;
            long long flt_delta;

            long rate;

            flt_before = rox_self_majflt();

            if (flt_before >= 0) {

                /*
                 * Libera o lock durante a amostragem para não bloquear
                 * init/shutdown/apply_profile por 80ms.
                 *
                 * Importante:
                 * flush_profile NÃO deve ser sobrescrito após o sleep.
                 * Ele representa a decisão adaptativa calculada a partir
                 * de RAM/GTT/PSI/fragmentação/thermal.
                 */
                pthread_mutex_unlock(&g_state_lock);

                usleep(
                    (unsigned int)
                    (ROPT_MAJFLT_SAMPLE_MS * 1000)
                );

                pthread_mutex_lock(&g_state_lock);

                /*
                 * Shutdown pode ter ocorrido durante o sleep.
                 * Não toque ringbuf, heap ou GTT depois disso.
                 */
                if (!atomic_load(&g_initialized)) {
                    ROPT_FLUSH_RETURN_UNLOCK(ROPT_ERR_INIT);
                }

                flt_after = rox_self_majflt();

                if (flt_after >= flt_before) {

                    flt_delta = flt_after - flt_before;

                    rate =
                        (long)
                        (flt_delta * 1000L /
                        ROPT_MAJFLT_SAMPLE_MS);

                    if (rate >=
                        ROPT_MAJFLT_RATE_THRESHOLD) {

                        rox_ringbuf_push(
                            ROPT_EVT_MAJFLT_SKIP,
                            flush_profile,
                            gtt_level,
                            -1,
                            ROPT_RAM_UNMEASURED,
                            ROPT_RAM_UNMEASURED,
                            gtt.gtt_pressure_pct
                        );

                        atomic_store(
                            &g_last_majflt_suspend,
                            mono_sec()
                        );

                        flush_profile =
                            ROPT_PROFILE_LIGHT;
                    }
                }
            }
        }
    }

    /*
     * VmSwap:
     * modera AGGRESSIVE quando o processo já está em swap.
     * PAGEOUT sobre páginas já swappadas pode gerar cascata de page faults.
     */
    flush_profile = rox_apply_swap_guard(flush_profile);

    /* snapshot de RSS antes do flush */
    rox_feedback_snapshot_t fb_snap;
    rox_feedback_pre_flush(&fb_snap);

    {
        int mr = rox_mallopt_apply(flush_profile);

        if (mr != ROPT_OK &&
            mr != ROPT_ERR_NODEV) {

            ROPT_FLUSH_RETURN_UNLOCK(mr);
        }
    }

    ret = rox_heap_compact(flush_profile);

    if (ret != ROPT_OK) {

        ROPT_FLUSH_RETURN_UNLOCK(ret);
    }

    /* snapshot depois do flush e recalibração */
    rox_feedback_post_flush(&fb_snap, flush_profile);

    /*
     * Detecção de fragmentação de heap.
     * Executado após post_flush para que get_ineffective_count()
     * reflita o flush atual e não o anterior.
     */
    {
        long long rss = rox_smaps_rss_kb();
        long long vsz = rox_self_vsz_kb();

        if (rss > 0 && vsz > 0) {
            if ((rss * 100 / vsz) < 30 ||
                rox_feedback_get_ineffective_count() >= 2) {

                /*
                 * ROPT_ERR_NODEV esperado em bionic.
                 * Descarte explícito.
                 */
                (void)rox_mallopt_apply_perturb();
            }
        }
    }

    ret = rox_gtt_react_level(gtt_level);

    if (ret != ROPT_OK) {

        ROPT_FLUSH_RETURN_UNLOCK(ret);
    }

    /* registra evento de flush no ringbuffer */
    {
        rox_stats_t ram_after;
        unsigned long after_kb = 0;

        if (rox_read_meminfo(&ram_after) == ROPT_OK)
            after_kb = ram_after.available_kb;

        rox_ringbuf_push(
            ROPT_EVT_FLUSH,
            flush_profile,
            gtt_level,
            -1,
            ram.available_kb,
            after_kb,
            gtt.gtt_pressure_pct
        );
    }

    flush_mark();

    atomic_store(&g_flush_active, 0);
    pthread_mutex_unlock(&g_state_lock);

#undef ROPT_FLUSH_RETURN_UNLOCK

    return ROPT_OK;
}

/* ───────────────────────────────────────────── */

int rox_diagnose(rox_diagnostic_t *out)
{
    rox_psi_t psi;
    rox_gtt_state_t gtt;
    rox_gtt_level_t gtt_level;
    rox_profile_t suggested;

    long long gtt_kb = 0;

    int ret;

    if (!out)
        return ROPT_ERR_PARAM;

    memset(out, 0, sizeof(*out));

    ret = rox_read_meminfo(&out->ram);
    if (ret != ROPT_OK)
        return ret;

    out->ram.fragmentation = rox_read_fragmentation();

    memset(&psi, 0, sizeof(psi));
    (void)rox_psi_read(&psi);

    out->psi_available = psi.available;
    out->psi_some_avg10 = psi.some_avg10;
    out->psi_some_avg60 = psi.some_avg60;
    out->psi_full_avg10 = psi.full_avg10;
    out->psi_full_avg60 = psi.full_avg60;
    out->psi_level = rox_psi_level(&psi);

    memset(&gtt, 0, sizeof(gtt));
    if (rox_gtt_read(&gtt) != ROPT_OK)
        memset(&gtt, 0, sizeof(gtt));

    gtt_level = rox_gtt_classify(&gtt);

    out->gtt_is_xclipse = gtt.is_xclipse;
    out->gtt_available = gtt.gtt_available;
    out->gtt_sysfs_error = gtt.gtt_sysfs_error;
    out->gtt_level = (int)gtt_level;
    out->gtt_pressure_pct = gtt.gtt_pressure_pct;
    out->vram_pressure_pct = gtt.vram_pressure_pct;
    out->gpu_utilization_pct = gtt.gpu_utilization_pct;

    if (gtt.gtt_available && gtt.gtt_used_bytes > 0)
        gtt_kb = gtt.gtt_used_bytes / 1024;

    out->gtt_pressure_margin_kb =
        (long long)out->ram.available_kb - gtt_kb;

    out->feedback_threshold_adjustment =
        rox_feedback_get_threshold_adjustment();

    /* Diagnose é passivo: não tenta reinicializar thermal aqui. */
    out->thermal_temp_c = rox_thermal_get_temp();
    out->thermal_level = (int)rox_thermal_get_level();

    suggested =
        rox_choose_base_profile(&out->ram,
                                  out->ram.fragmentation,
                                  &psi,
                                  gtt_level);

    /*
     * Diagnose tenta espelhar a lógica térmica do flush.
     * Como o estado de hysteresis é global e monotônico, essa função
     * ainda é passiva: calcula, não aplica ação de memória.
     */
    suggested =
        rox_apply_thermal_guard_passive(suggested,
                                          out->thermal_level);

    /* Espelha a moderação por VmSwap do flush. */
    suggested = rox_apply_swap_guard(suggested);

    out->suggested_profile = suggested;

    out->would_act =
        (out->psi_level > 0 ||
         gtt_level != ROPT_GTT_NORMAL ||
         out->ram.fragmentation > 35 ||
         suggested != ROPT_PROFILE_LIGHT)
        ? 1
        : 0;

    return ROPT_OK;
}

/* ───────────────────────────────────────────── */

int rox_get_stats(rox_stats_t *out)
{
    int ret;

    if (!out)
        return ROPT_ERR_PARAM;

    if (!atomic_load(&g_initialized))
        return ROPT_ERR_INIT;

    ret = rox_read_meminfo(out);

    if (ret != ROPT_OK)
        return ret;

    out->fragmentation = rox_read_fragmentation();

    return ROPT_OK;
}

/* ───────────────────────────────────────────── */

#define ROPT_CORE_STRINGIFY_IMPL(x) #x
#define ROPT_CORE_STRINGIFY(x)      ROPT_CORE_STRINGIFY_IMPL(x)

const char *rox_version(void)
{
    static const char version[] =
        ROPT_CORE_STRINGIFY(ROPT_VERSION_MAJOR) "."
        ROPT_CORE_STRINGIFY(ROPT_VERSION_MINOR) "."
        ROPT_CORE_STRINGIFY(ROPT_VERSION_PATCH);

    return version;
}
