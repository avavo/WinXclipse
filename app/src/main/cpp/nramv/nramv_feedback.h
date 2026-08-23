#ifndef NRAMV_FEEDBACK_H
#define NRAMV_FEEDBACK_H

#include <stdint.h>
#include "nramv.h"

/* ─────────────────────────────────────────────
 * Adição 9 — Feedback Loop Dinâmico
 * ───────────────────────────────────────────── */

/* Snapshot de RSS para cálculo de eficácia */
typedef struct {
    long long rss_before;
    long long rss_after;
} nramv_feedback_snapshot_t;

/* Inicializa o módulo de feedback */
void nramv_feedback_init(void);

/* Snapshot antes do flush */
void nramv_feedback_pre_flush(nramv_feedback_snapshot_t *snap);

/* Snapshot depois do flush + ajuste adaptativo */
void nramv_feedback_post_flush(nramv_feedback_snapshot_t *snap,
                               nramv_profile_t profile);

/* Retorna ajuste dinâmico atual (-10 a +5 aprox.) */
int nramv_feedback_get_threshold_adjustment(void);

/* Retorna contador interno de flushes ineficazes consecutivos */
int nramv_feedback_get_ineffective_count(void);

#endif /* NRAMV_FEEDBACK_H */
