#include <stdio.h>
#include <stdatomic.h>
#include <pthread.h>

#include "rox_feedback.h"
#include "rox_internal.h"

/* ─────────────────────────────────────────────
 * Adição 9 — Feedback Loop Dinâmico (v2)
 *
 * Revisão atual:
 *  - janela deslizante protegida por mutex
 *  - contadores expostos continuam atômicos
 *  - leituras ruins de RSS não contaminam a janela adaptativa
 * ───────────────────────────────────────────── */

#define WINDOW_SIZE        8      /* flushes na janela deslizante */
#define ADJ_SCALE          5      /* fator de mapeamento [-1,+1] -> [-5,+5] */
#define THRESHOLD_ADJ_MIN (-ADJ_SCALE)
#define THRESHOLD_ADJ_MAX  ( ADJ_SCALE)

/* Janela circular de resultados: 1 = eficaz, 0 = ineficaz */
static uint8_t  g_window[WINDOW_SIZE];
static int      g_window_head  = 0;
static int      g_window_count = 0;   /* quantos slots preenchidos */

static pthread_mutex_t g_feedback_lock = PTHREAD_MUTEX_INITIALIZER;

static _Atomic int g_threshold_adj = 0;
static _Atomic int g_ineffective_trims = 0;  /* contador bruto exposto via API */

static long long feedback_threshold_for_profile(long long rss_before,
                                                rox_profile_t profile)
{
    long long threshold;

    switch (profile) {
    case ROPT_PROFILE_LIGHT:
        threshold = (rss_before * 25) / 10000; /* 0.25% */
        if (threshold < 512)
            threshold = 512;
        break;

    case ROPT_PROFILE_AGGRESSIVE:
        threshold = (rss_before * 3) / 100;    /* 3% */
        if (threshold < 4096)
            threshold = 4096;
        break;

    case ROPT_PROFILE_MEDIUM:
    default:
        threshold = rss_before / 100;          /* 1% */
        if (threshold < 2048)
            threshold = 2048;
        break;
    }

    return threshold;
}

static void feedback_window_push_locked(int effective)
{
    int eff_count = 0;
    int ineff_count;
    int score;

    g_window[g_window_head] = (uint8_t)(effective ? 1 : 0);
    g_window_head = (g_window_head + 1) % WINDOW_SIZE;

    if (g_window_count < WINDOW_SIZE)
        g_window_count++;

    /* Só age após ter pelo menos metade da janela preenchida. */
    if (g_window_count < WINDOW_SIZE / 2)
        return;

    for (int i = 0; i < g_window_count; i++)
        eff_count += g_window[i];

    ineff_count = g_window_count - eff_count;

    /* score em [-window_count, +window_count], normalizado. */
    score = (eff_count - ineff_count) * ADJ_SCALE / g_window_count;

    if (score < THRESHOLD_ADJ_MIN)
        score = THRESHOLD_ADJ_MIN;

    if (score > THRESHOLD_ADJ_MAX)
        score = THRESHOLD_ADJ_MAX;

    atomic_store(&g_threshold_adj, score);
}

void rox_feedback_init(void)
{
    pthread_mutex_lock(&g_feedback_lock);

    atomic_store(&g_threshold_adj, 0);
    atomic_store(&g_ineffective_trims, 0);

    for (int i = 0; i < WINDOW_SIZE; i++)
        g_window[i] = 0;

    g_window_head  = 0;
    g_window_count = 0;

    pthread_mutex_unlock(&g_feedback_lock);
}

void rox_feedback_pre_flush(rox_feedback_snapshot_t *snap)
{
    if (!snap)
        return;

    snap->rss_before = rox_smaps_rss_kb();
    snap->rss_after = 0;
}

void rox_feedback_post_flush(rox_feedback_snapshot_t *snap,
                               rox_profile_t profile)
{
    long long freed_kb;
    long long threshold;
    int effective;

    if (!snap || snap->rss_before <= 0)
        return;

    if (snap->rss_after <= 0)
        snap->rss_after = rox_smaps_rss_kb();

    /* Falha de leitura: não contamina janela, só bump bruto. */
    if (snap->rss_after <= 0) {
        atomic_fetch_add(&g_ineffective_trims, 1);
        return;
    }

    freed_kb = snap->rss_before - snap->rss_after;
    threshold = feedback_threshold_for_profile(snap->rss_before, profile);
    effective = (freed_kb >= threshold) ? 1 : 0;

    pthread_mutex_lock(&g_feedback_lock);

    if (!effective) {
        atomic_fetch_add(&g_ineffective_trims, 1);
    } else {
        int cur = atomic_load(&g_ineffective_trims);
        if (cur > 0)
            atomic_fetch_sub(&g_ineffective_trims, 1);
    }

    feedback_window_push_locked(effective);

    pthread_mutex_unlock(&g_feedback_lock);
}

int rox_feedback_get_threshold_adjustment(void)
{
    return atomic_load(&g_threshold_adj);
}

int rox_feedback_get_ineffective_count(void)
{
    return atomic_load(&g_ineffective_trims);
}
