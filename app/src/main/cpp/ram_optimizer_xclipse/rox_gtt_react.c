#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <dirent.h>
#include <ctype.h>
#include <stdatomic.h>
#include <time.h>       /* clock_gettime / CLOCK_MONOTONIC */
#include <pthread.h>

#include "rox.h"
#include "rox_gtt_react.h"
#include "rox_internal.h"
#include "rox_ringbuf.h"

#define SGPU_SYSFS_BASE          "/sys/class/drm"
#define SGPU_DIRECT_BASE         "/sys/devices/platform/22200000.sgpu"
#define SGPU_FW_VERSION          "fw_version/sgpu_fw_version"

#define SGPU_GTT_USED            "mem_info_gtt_used"
#define SGPU_GTT_TOTAL           "mem_info_gtt_total"
#define SGPU_VRAM_USED           "mem_info_vram_used"
#define SGPU_VRAM_TOTAL          "mem_info_vram_total"

#define SGPU_GPU_UTIL_DIRECT     "gpu_busy_percent"
#define SGPU_CU_UTIL_DIRECT      "cu_occupancy"
#define SGPU_FREQ_CUR_DIRECT     "pp_dpm_sclk"
#define SGPU_FREQ_MAX_DIRECT     "pp_od_clk_voltage"

#define SGPU_GPU_UTIL_DEVFREQ    "interface/current_utilization"
#define SGPU_CU_UTIL_DEVFREQ     "interface/current_cu_utilization"
#define SGPU_KERNEL_PAGES        "interface/total_kernel_pages"

#define GTT_LEVEL_LIGHT          35
#define GTT_LEVEL_MEDIUM         55
#define GTT_LEVEL_AGGRESSIVE     80
#define GTT_LEVEL_CRITICAL       90
#define GTT_UTIL_PREEMPT_PCT     95
#define GTT_KERNEL_PRESSURE_KB   (8 * 1024)

static pthread_mutex_t g_callback_lock = PTHREAD_MUTEX_INITIALIZER;
static rox_gtt_callback_t g_callback = NULL;
static void *g_cb_userdata = NULL;

static _Atomic long long g_cache_pressure_restore_at = 0;

static const char *gtt_sysfs_base(void)
{
    const char *v = getenv("ROPT_SGPU_SYSFS_BASE");
    return (v && v[0]) ? v : SGPU_SYSFS_BASE;
}

static const char *gtt_direct_base(void)
{
    const char *v = getenv("ROPT_SGPU_DIRECT_BASE");
    return (v && v[0]) ? v : SGPU_DIRECT_BASE;
}

static int file_exists_under(const char *base, const char *file)
{
    char path[2048];
    FILE *f;

    snprintf(path, sizeof(path), "%s/%s", base, file);
    f = fopen(path, "r");
    if (!f)
        return 0;

    fclose(f);
    return 1;
}

static int gtt_join_path(char *out, size_t size, const char *base, const char *name)
{
    size_t lb;
    size_t ln;

    if (!out || size == 0 || !base || !name)
        return 0;

    lb = strlen(base);
    ln = strlen(name);

    if (lb + 1 + ln + 1 > size)
        return 0;

    memcpy(out, base, lb);
    out[lb] = '/';
    memcpy(out + lb + 1, name, ln);
    out[lb + 1 + ln] = '\0';
    return 1;
}

static int gtt_find_base(char *out, size_t size)
{
    DIR *dir;
    struct dirent *e;
    char fallback[2048] = {0};
    const char *sysfs_override;
    const char *direct_override;
    int has_fallback = 0;

    sysfs_override = getenv("ROPT_SGPU_SYSFS_BASE");
    direct_override = getenv("ROPT_SGPU_DIRECT_BASE");

    /*
     * Testes e mocks podem sobrescrever ROPT_SGPU_SYSFS_BASE.
     * Nesse caso, nao deixe o caminho direto real do aparelho ganhar
     * prioridade, senao rox_gtt_read() ignora o mock e le o sysfs real.
     * Se ROPT_SGPU_DIRECT_BASE for definido explicitamente, ele ainda
     * pode ser usado como override direto.
     */
    if ((!sysfs_override || !sysfs_override[0]) &&
        (file_exists_under(gtt_direct_base(), SGPU_GTT_USED) ||
         file_exists_under(gtt_direct_base(), SGPU_FW_VERSION))) {
        snprintf(out, size, "%s", gtt_direct_base());
        return ROPT_OK;
    }

    if (direct_override && direct_override[0] &&
        (file_exists_under(direct_override, SGPU_GTT_USED) ||
         file_exists_under(direct_override, SGPU_FW_VERSION))) {
        snprintf(out, size, "%s", direct_override);
        return ROPT_OK;
    }

    dir = opendir(gtt_sysfs_base());
    if (!dir)
        return ROPT_ERR_NODEV;

    while ((e = readdir(dir)) != NULL) {
        char base[2048];

        if (e->d_name[0] == '.')
            continue;

        snprintf(base, sizeof(base), "%s/%s/device",
                 gtt_sysfs_base(), e->d_name);

        if (!file_exists_under(base, SGPU_GTT_USED))
            continue;

        if (strstr(e->d_name, "sgpu") || strstr(e->d_name, "xclipse")) {
            snprintf(out, size, "%s", base);
            closedir(dir);
            return ROPT_OK;
        }

        if (!has_fallback) {
            snprintf(fallback, sizeof(fallback), "%s", base);
            has_fallback = 1;
        }
    }

    closedir(dir);

    if (has_fallback) {
        snprintf(out, size, "%s", fallback);
        return ROPT_OK;
    }

    return ROPT_ERR_NODEV;
}

static int gtt_find_devfreq_path(const char *device, char *out, size_t size)
{
    DIR *dir;
    struct dirent *e;
    char base[2048];
    char fallback[2048] = {0};
    int has_fallback = 0;
    const char *dev_name;
    int can_match_dev_name;

    snprintf(base, sizeof(base), "%s/devfreq", device);
    dir = opendir(base);
    if (!dir)
        return ROPT_ERR_NODEV;

    dev_name = strrchr(device, '/');
    dev_name = dev_name ? dev_name + 1 : device;
    can_match_dev_name = strcmp(dev_name, "device") != 0;

    while ((e = readdir(dir)) != NULL) {
        if (e->d_name[0] == '.')
            continue;

        if ((can_match_dev_name && strstr(e->d_name, dev_name)) ||
            strstr(e->d_name, "sgpu") ||
            strstr(e->d_name, "xclipse")) {
            if (!gtt_join_path(out, size, base, e->d_name)) {
                closedir(dir);
                return ROPT_ERR_NODEV;
            }
            closedir(dir);
            return ROPT_OK;
        }

        if (!has_fallback) {
            if (gtt_join_path(fallback, sizeof(fallback), base, e->d_name))
                has_fallback = 1;
        }
    }

    closedir(dir);

    if (has_fallback) {
        snprintf(out, size, "%s", fallback);
        return ROPT_OK;
    }

    return ROPT_ERR_NODEV;
}

static long long gtt_read_ll(const char *base, const char *file)
{
    char path[2048];
    char buf[128];
    FILE *f;
    char *p;
    long long v = -1;

    snprintf(path, sizeof(path), "%s/%s", base, file);

    f = fopen(path, "r");
    if (!f)
        return -1;

    if (fgets(buf, sizeof(buf), f)) {
        p = buf;
        while (*p && *p != '-' && !isdigit((unsigned char)*p))
            p++;
        if (*p)
            v = atoll(p);
    }

    fclose(f);
    return v;
}

static int gtt_read_pct(const char *base, const char *file)
{
    long long v = gtt_read_ll(base, file);

    if (v < 0)
        return -1;
    if (v > 100)
        return 100;

    return (int)v;
}

static long long gtt_mono_sec(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec;
}

static void try_kernel_compact(void)
{
    FILE *f = fopen("/proc/sys/vm/compact_memory", "w");
    if (!f)
        return;

    fprintf(f, "1\n");
    fclose(f);
}

static void try_cache_pressure(int value)
{
    FILE *f = fopen("/proc/sys/vm/vfs_cache_pressure", "w");
    if (!f)
        return;

    fprintf(f, "%d\n", value);
    fclose(f);
}

static void schedule_cache_pressure_restore(void)
{
    atomic_store(&g_cache_pressure_restore_at, gtt_mono_sec() + 2);
}

static void maybe_restore_cache_pressure(void)
{
    long long restore_at = atomic_load(&g_cache_pressure_restore_at);

    if (restore_at == 0)
        return;

    if (gtt_mono_sec() >= restore_at) {
        try_cache_pressure(100);
        atomic_store(&g_cache_pressure_restore_at, 0);
    }
}

static rox_profile_t profile_for_level(rox_gtt_level_t level)
{
    if (level >= ROPT_GTT_AGGRESSIVE)
        return ROPT_PROFILE_AGGRESSIVE;
    if (level >= ROPT_GTT_MEDIUM)
        return ROPT_PROFILE_MEDIUM;
    return ROPT_PROFILE_LIGHT;
}

static int gtt_react_level_with_state(rox_gtt_level_t level,
                                      const rox_gtt_state_t *state)
{
    rox_profile_t profile;
    int ret;
    int mr;
    int gtt_pct = state ? state->gtt_pressure_pct : -1;

    maybe_restore_cache_pressure();

    if (level == ROPT_GTT_NORMAL)
        return ROPT_OK;

    if (level < ROPT_GTT_NORMAL || level >= ROPT_GTT_LEVEL_COUNT)
        return ROPT_OK;

    profile = profile_for_level(level);

    mr = rox_mallopt_apply(profile);
    if (mr != ROPT_OK && mr != ROPT_ERR_NODEV)
        return mr;

    ret = rox_heap_compact(profile);

    if (level >= ROPT_GTT_MEDIUM)
        try_kernel_compact();

    /* FIX Bug 7: para GTT_AGGRESSIVE, vfs_cache_pressure era definido
     * como 200 mas nunca restaurado — o kernel ficava com pressão elevada
     * indefinidamente mesmo após a pressão GPU normalizar. Agenda restore
     * para ambos os níveis que alteram cache_pressure. */
    if (level == ROPT_GTT_AGGRESSIVE) {
        try_cache_pressure(200);
        schedule_cache_pressure_restore();
    }

    if (level == ROPT_GTT_CRITICAL) {
        try_cache_pressure(500);
        schedule_cache_pressure_restore();
    }

    if (ret == ROPT_OK) {
        rox_ringbuf_push(ROPT_EVT_GTT_REACT,
                           profile,
                           level,
                           -1,
                           ROPT_RAM_UNMEASURED,
                           ROPT_RAM_UNMEASURED,
                           gtt_pct);
    }

    {
        rox_gtt_callback_t cb;
        void *ud;

        pthread_mutex_lock(&g_callback_lock);
        cb = g_callback;
        ud = g_cb_userdata;
        pthread_mutex_unlock(&g_callback_lock);

        if (cb) {
            rox_gtt_event_t evt = { level, state };
            cb(&evt, ud);
        }
    }

    return ret;
}

void rox_gtt_set_callback(rox_gtt_callback_t cb, void *userdata)
{
    pthread_mutex_lock(&g_callback_lock);
    g_cb_userdata = userdata;
    g_callback = cb;
    pthread_mutex_unlock(&g_callback_lock);
}

int rox_gtt_read(rox_gtt_state_t *out)
{
    char base[2048];
    char devfreq[2048];
    int ret;
    int util;
    int cu;

    if (!out)
        return ROPT_ERR_PARAM;

    memset(out, 0, sizeof(*out));

    ret = gtt_find_base(base, sizeof(base));
    if (ret != ROPT_OK) {
        out->is_xclipse = 0;
        out->gtt_available = 0;
        out->gtt_sysfs_error = 0;
        return ROPT_OK;
    }

    out->is_xclipse = 1;

    out->gtt_used_bytes = gtt_read_ll(base, SGPU_GTT_USED);
    out->gtt_total_bytes = gtt_read_ll(base, SGPU_GTT_TOTAL);
    out->vram_used_bytes = gtt_read_ll(base, SGPU_VRAM_USED);
    out->vram_total_bytes = gtt_read_ll(base, SGPU_VRAM_TOTAL);

    out->gtt_available =
        (out->gtt_used_bytes >= 0 && out->gtt_total_bytes > 0) ? 1 : 0;
    out->gtt_sysfs_error = out->gtt_available ? 0 : 1;

    if (out->gtt_available) {
        out->gtt_pressure_pct =
            (int)((out->gtt_used_bytes * 100) / out->gtt_total_bytes);
    }

    if (out->vram_used_bytes >= 0 && out->vram_total_bytes > 0) {
        out->vram_pressure_pct =
            (int)((out->vram_used_bytes * 100) / out->vram_total_bytes);
    }

    util = gtt_read_pct(base, SGPU_GPU_UTIL_DIRECT);
    cu = gtt_read_pct(base, SGPU_CU_UTIL_DIRECT);

    out->cur_freq_khz = gtt_read_ll(base, SGPU_FREQ_CUR_DIRECT);
    out->max_freq_khz = gtt_read_ll(base, SGPU_FREQ_MAX_DIRECT);

    if (gtt_find_devfreq_path(base, devfreq, sizeof(devfreq)) == ROPT_OK) {
        long long cur = gtt_read_ll(devfreq, "cur_freq");
        long long max = gtt_read_ll(devfreq, "max_freq");
        int devfreq_util = gtt_read_pct(devfreq, SGPU_GPU_UTIL_DEVFREQ);
        int devfreq_cu = gtt_read_pct(devfreq, SGPU_CU_UTIL_DEVFREQ);

        if (out->cur_freq_khz < 0)
            out->cur_freq_khz = cur;
        if (out->max_freq_khz < 0)
            out->max_freq_khz = max;
        if (util < 0)
            util = devfreq_util;
        if (cu < 0)
            cu = devfreq_cu;

        out->total_kernel_pages = gtt_read_ll(devfreq, SGPU_KERNEL_PAGES);
    }

    out->gpu_utilization_pct = (util >= 0) ? util : 0;
    out->cu_utilization_pct = (cu >= 0) ? cu : 0;

    if (out->cur_freq_khz < 0)
        out->cur_freq_khz = 0;
    if (out->max_freq_khz < 0)
        out->max_freq_khz = 0;
    if (out->total_kernel_pages < 0)
        out->total_kernel_pages = 0;

    return ROPT_OK;
}

rox_gtt_level_t rox_gtt_classify(const rox_gtt_state_t *state)
{
    int gtt;
    int vram;

    if (!state || !state->is_xclipse)
        return ROPT_GTT_NORMAL;

    if (!state->gtt_available) {
        int gpu = state->gpu_utilization_pct;

        if (gpu >= 90)
            return ROPT_GTT_AGGRESSIVE;
        if (gpu >= 75)
            return ROPT_GTT_MEDIUM;
        if (gpu >= 50)
            return ROPT_GTT_LIGHT;

        return ROPT_GTT_NORMAL;
    }

    gtt = state->gtt_pressure_pct;
    vram = state->vram_pressure_pct;

    if (gtt >= GTT_LEVEL_CRITICAL)
        return ROPT_GTT_CRITICAL;

    if (gtt >= GTT_LEVEL_AGGRESSIVE ||
        (gtt >= GTT_LEVEL_MEDIUM && vram >= 80))
        return ROPT_GTT_AGGRESSIVE;

    if (state->total_kernel_pages > 0) {
        long long kpages_kb = state->total_kernel_pages * 4LL;

        if (kpages_kb > GTT_KERNEL_PRESSURE_KB &&
            gtt >= GTT_LEVEL_MEDIUM)
            return ROPT_GTT_AGGRESSIVE;

        if (kpages_kb > GTT_KERNEL_PRESSURE_KB &&
            gtt >= GTT_LEVEL_LIGHT)
            return ROPT_GTT_MEDIUM;
    }

    if (state->gpu_utilization_pct >= GTT_UTIL_PREEMPT_PCT) {
        if (gtt >= GTT_LEVEL_MEDIUM)
            return ROPT_GTT_AGGRESSIVE;
        if (gtt >= GTT_LEVEL_LIGHT)
            return ROPT_GTT_MEDIUM;
    }

    if (gtt >= GTT_LEVEL_MEDIUM)
        return ROPT_GTT_MEDIUM;
    if (gtt >= GTT_LEVEL_LIGHT)
        return ROPT_GTT_LIGHT;

    return ROPT_GTT_NORMAL;
}

int rox_gtt_react_level(rox_gtt_level_t level)
{
    return gtt_react_level_with_state(level, NULL);
}

int rox_gtt_react(void)
{
    rox_gtt_state_t state;
    rox_gtt_level_t level;
    int ret;

    ret = rox_gtt_read(&state);
    if (ret != ROPT_OK)
        return ret;

    level = rox_gtt_classify(&state);
    return gtt_react_level_with_state(level, &state);
}

int rox_gtt_diagnose(rox_gtt_diag_t *out)
{
    long long gtt_kb = 0;
    long long ram_avail_kb;
    int ret;

    if (!out)
        return ROPT_ERR_PARAM;

    memset(out, 0, sizeof(*out));

    ret = rox_gtt_read(&out->gtt);
    if (ret != ROPT_OK)
        return ret;

    ret = rox_read_meminfo(&out->ram);
    if (ret != ROPT_OK)
        return ret;

    out->level = rox_gtt_classify(&out->gtt);

    if (out->gtt.gtt_available && out->gtt.gtt_used_bytes > 0)
        gtt_kb = out->gtt.gtt_used_bytes / 1024;

    ram_avail_kb = (long long)out->ram.available_kb;
    out->gtt_pressure_margin_kb = ram_avail_kb - gtt_kb;

    return ROPT_OK;
}
