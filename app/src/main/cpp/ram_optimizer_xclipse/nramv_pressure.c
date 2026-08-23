#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdatomic.h>
#include <time.h>  /* clock_gettime / CLOCK_MONOTONIC */

#include "nramv.h"
#include "nramv_gtt_react.h"
#include "nramv_internal.h"
#include "nramv_ringbuf.h"

/* ─────────────────────────────────────────────
 * nramv_pressure.c (corrigido)
 * PSI + GTT com consistência de estado
 *
 * Correções desta revisão:
 *  - guarda anti-reentrância para proteger a janela PSI
 *  - cooldown passa a ser marcado também quando não há pressão
 *  - todos os retornos limpam g_pressure_active
 * ───────────────────────────────────────────── */

#define NRAMV_PRESSURE_COOLDOWN_SEC 1
#define PSI_PATH "/proc/pressure/memory"

static _Atomic long long g_last_pressure_mono = 0;
static _Atomic int g_pressure_active = 0;

static long long pressure_mono_sec(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec;
}

static int pressure_cooldown_ok(void)
{
    long long last = atomic_load(&g_last_pressure_mono);

    if (last == 0)
        return 1;

    return (pressure_mono_sec() - last) >= NRAMV_PRESSURE_COOLDOWN_SEC;
}

static void pressure_mark(void)
{
    atomic_store(&g_last_pressure_mono, pressure_mono_sec());
}

/* ─────────────────────────────
 * PSI WINDOW
 * ───────────────────────────── */

#define NRAMV_PSI_WINDOW_SIZE 3
#define NRAMV_PSI_TREND_MIN 0.05f

typedef struct {
    float samples[NRAMV_PSI_WINDOW_SIZE];
    int head;
    int count;
} nramv_psi_window_t;

static nramv_psi_window_t g_psi_window = {{0}, 0, 0};

static void psi_window_push(float v)
{
    g_psi_window.samples[g_psi_window.head] = v;
    g_psi_window.head = (g_psi_window.head + 1) % NRAMV_PSI_WINDOW_SIZE;

    if (g_psi_window.count < NRAMV_PSI_WINDOW_SIZE)
        g_psi_window.count++;
}

static int psi_window_trending_up(void)
{
    int oldest;
    float prev;

    if (g_psi_window.count < NRAMV_PSI_WINDOW_SIZE)
        return 0;

    oldest = g_psi_window.head;
    prev = g_psi_window.samples[oldest];

    if (prev < NRAMV_PSI_TREND_MIN)
        return 0;

    for (int i = 1; i < NRAMV_PSI_WINDOW_SIZE; i++) {
        float curr = g_psi_window.samples[(oldest + i) % NRAMV_PSI_WINDOW_SIZE];
        if (curr <= prev)
            return 0;
        prev = curr;
    }

    return 1;
}

/* ─────────────────────────────
 * PSI
 * ───────────────────────────── */

int nramv_psi_read(nramv_psi_t *out)
{
    FILE *f;
    char line[256];

    if (!out)
        return NRAMV_ERR_PARAM;

    memset(out, 0, sizeof(*out));

    f = fopen(PSI_PATH, "r");
    if (!f) {
        out->available = 0;
        return NRAMV_OK;
    }

    out->available = 1;

    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "some", 4) == 0) {
            (void)sscanf(line, "some avg10=%f avg60=%f",
                         &out->some_avg10, &out->some_avg60);
        }

        if (strncmp(line, "full", 4) == 0) {
            (void)sscanf(line, "full avg10=%f avg60=%f",
                         &out->full_avg10, &out->full_avg60);
        }
    }

    fclose(f);
    return NRAMV_OK;
}

int nramv_psi_level(const nramv_psi_t *psi)
{
    if (!psi || !psi->available)
        return 0;

    if (psi->full_avg10 > 5.0f || psi->some_avg10 > 40.0f)
        return 3;

    if (psi->full_avg10 > 1.0f || psi->some_avg10 > 20.0f)
        return 2;

    if (psi->some_avg10 > 5.0f)
        return 1;

    return 0;
}

/* ─────────────────────────────
 * CORE REACT
 * ───────────────────────────── */

int nramv_pressure_react(void)
{
    nramv_psi_t psi;
    nramv_gtt_state_t gtt;
    nramv_gtt_level_t gtt_level;
    int psi_base;
    int psi_final;
    int gtt_as_psi;
    int worst;
    int ret = NRAMV_OK;

    if (atomic_exchange(&g_pressure_active, 1))
        return NRAMV_OK;

#define NRAMV_PRESSURE_RETURN(value) \
    do { \
        atomic_store(&g_pressure_active, 0); \
        return (value); \
    } while (0)

#define NRAMV_PRESSURE_MARK_RETURN(value) \
    do { \
        pressure_mark(); \
        atomic_store(&g_pressure_active, 0); \
        return (value); \
    } while (0)

    if (!pressure_cooldown_ok())
        NRAMV_PRESSURE_RETURN(NRAMV_OK);

    if (nramv_psi_read(&psi) != NRAMV_OK)
        NRAMV_PRESSURE_MARK_RETURN(NRAMV_ERR_NODEV);

    psi_base = nramv_psi_level(&psi);
    psi_final = psi_base;

    /* g_pressure_active serializa esta janela contra chamadas concorrentes. */
    psi_window_push(psi.full_avg10);

    if (psi.available &&
        psi_base < 2 &&
        psi_window_trending_up()) {
        psi_final = psi_base + 1;
    }

    if (nramv_gtt_read(&gtt) != NRAMV_OK)
        memset(&gtt, 0, sizeof(gtt));

    gtt_level = nramv_gtt_classify(&gtt);

    if (!gtt.is_xclipse)
        gtt_as_psi = 0;
    else if (gtt_level == NRAMV_GTT_CRITICAL)
        gtt_as_psi = 3;
    else
        gtt_as_psi = (int)gtt_level;

    if (gtt_as_psi > 3)
        gtt_as_psi = 3;

    worst = (psi_final > gtt_as_psi) ? psi_final : gtt_as_psi;

    /* flush emergencial combinado */
    if (worst >= 2 && gtt_level >= NRAMV_GTT_MEDIUM) {
        pressure_mark();
        ret = nramv_flush();
        NRAMV_PRESSURE_RETURN(ret);
    }

    switch (worst) {
    case 0:
        NRAMV_PRESSURE_MARK_RETURN(NRAMV_OK);

    case 1:
        ret = nramv_apply_profile(NRAMV_PROFILE_LIGHT);
        break;

    case 2:
        ret = nramv_apply_profile(NRAMV_PROFILE_MEDIUM);
        break;

    case 3:
        pressure_mark();
        ret = nramv_flush();
        NRAMV_PRESSURE_RETURN(ret);

    default:
        ret = NRAMV_OK;
        break;
    }

    (void)nramv_gtt_react_level(gtt_level);

    NRAMV_PRESSURE_MARK_RETURN(ret);

#undef NRAMV_PRESSURE_MARK_RETURN
#undef NRAMV_PRESSURE_RETURN
}
