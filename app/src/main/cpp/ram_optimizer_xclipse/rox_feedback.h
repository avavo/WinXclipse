#ifndef ROPT_FEEDBACK_H
#define ROPT_FEEDBACK_H

#include <stdint.h>
#include "rox.h"

/* ─────────────────────────────────────────────
 * Adição 9 — Feedback Loop Dinâmico
 * ───────────────────────────────────────────── */

/* Snapshot de RSS para cálculo de eficácia */
typedef struct {
    long long rss_before;
    long long rss_after;
} rox_feedback_snapshot_t;

/* Inicializa o módulo de feedback */
void rox_feedback_init(void);

/* Snapshot antes do flush */
void rox_feedback_pre_flush(rox_feedback_snapshot_t *snap);

/* Snapshot depois do flush + ajuste adaptativo */
void rox_feedback_post_flush(rox_feedback_snapshot_t *snap,
                               rox_profile_t profile);

/* Retorna ajuste dinâmico atual (-10 a +5 aprox.) */
int rox_feedback_get_threshold_adjustment(void);

/* Retorna contador interno de flushes ineficazes consecutivos */
int rox_feedback_get_ineffective_count(void);

#endif /* ROPT_FEEDBACK_H */
