#ifndef NRAMV_DEBUG_H
#define NRAMV_DEBUG_H

#include <stdio.h>
#include <stdint.h>
#include "nramv.h"
#include "nramv_ringbuf.h"

#ifdef __cplusplus
extern "C" {
#endif

/* ─────────────────────────────────────────────
 * nramv_debug.h (corrigido)
 * ───────────────────────────────────────────── */

/* ❗ CORREÇÃO 1:
 * Estes arrays NÃO podem ser definidos em header como "static const"
 * em sistemas com múltiplos .c, porque criam cópias por translation unit.
 *
 * Isso não quebra sempre, mas:
 * - aumenta binário
 * - dificulta comparação de ponteiros
 * - cria inconsistência em tooling futuro
 *
 * Melhor: declarar como extern e definir em .c
 */

extern const char * const NRAMV_EVT_KIND_NAMES[];
extern const char * const NRAMV_PROFILE_NAMES[];
extern const char * const NRAMV_GTT_LEVEL_NAMES[];

#define NRAMV_EVT_KIND_COUNT 6
#define NRAMV_PROFILE_COUNT 3
#define NRAMV_GTT_LEVEL_COUNT 5

/* ── Snapshot agregado ─────────────────────── */
typedef struct {
    int     total_events;
    int     flush_count;
    int     psi_react_count;
    int     psi_trend_count;
    int     gtt_react_count;
    int     majflt_skip_count;
    long    total_ram_freed_kb;
    long    avg_ram_freed_kb;
    int64_t first_ts_ms;
    int64_t last_ts_ms;
} nramv_debug_snapshot_t;

/* ── API ───────────────────────────────────── */

int nramv_debug_dump(FILE *fp, int count);
int nramv_debug_dump_logcat(int count);
int nramv_debug_snapshot(nramv_debug_snapshot_t *out);
uint32_t nramv_debug_total_events(void);

#ifdef __cplusplus
}
#endif

#endif /* NRAMV_DEBUG_H */
