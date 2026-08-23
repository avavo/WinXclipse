#ifndef ROPT_DEBUG_H
#define ROPT_DEBUG_H

#include <stdio.h>
#include <stdint.h>
#include "rox.h"
#include "rox_ringbuf.h"

#ifdef __cplusplus
extern "C" {
#endif

/* ─────────────────────────────────────────────
 * rox_debug.h (corrigido)
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

extern const char * const ROPT_EVT_KIND_NAMES[];
extern const char * const ROPT_PROFILE_NAMES[];
extern const char * const ROPT_GTT_LEVEL_NAMES[];

#define ROPT_EVT_KIND_COUNT 6
#define ROPT_PROFILE_COUNT 3
#define ROPT_GTT_LEVEL_COUNT 5

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
} rox_debug_snapshot_t;

/* ── API ───────────────────────────────────── */

int rox_debug_dump(FILE *fp, int count);
int rox_debug_dump_logcat(int count);
int rox_debug_snapshot(rox_debug_snapshot_t *out);
uint32_t rox_debug_total_events(void);

#ifdef __cplusplus
}
#endif

#endif /* ROPT_DEBUG_H */
