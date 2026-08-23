#ifndef NRAMV_EMULATOR_H
#define NRAMV_EMULATOR_H

#ifdef __cplusplus
extern "C" {
#endif

#include <stdint.h>

/* ─────────────────────────────────────────────
 * visibility control
 * ───────────────────────────────────────────── */

#ifndef NRAMV_API
#if defined(__GNUC__)
#define NRAMV_API __attribute__((visibility("default")))
#else
#define NRAMV_API
#endif
#endif

/* ─────────────────────────────────────────────
 * NOTE IMPORTANTE (corrige ambiguidade estrutural)
 *
 * Este header define apenas contratos.
 * Nenhuma lógica, nenhum estado, nenhum enum duplicado.
 * ───────────────────────────────────────────── */

/* ═══════════════════════════════════════════════
 * WINLATOR
 * ═══════════════════════════════════════════════ */

NRAMV_API int  winlator_mem_init(void);
NRAMV_API int  winlator_mem_flush(void);
NRAMV_API int  winlator_mem_optimize(int level);
NRAMV_API long winlator_mem_available_kb(void);
NRAMV_API int  winlator_mem_pressure(void);
NRAMV_API int  winlator_mem_stats_str(char *buf, int buf_size);

/* aliases */
NRAMV_API int  wlt_mem_init(void);
NRAMV_API int  wlt_mem_flush(void);
NRAMV_API int  wlt_mem_optimize(int level);
NRAMV_API long wlt_mem_available_kb(void);
NRAMV_API int  wlt_mem_pressure(void);

/* ═══════════════════════════════════════════════
 * LUDASHI
 * ═══════════════════════════════════════════════ */

NRAMV_API int   ludashi_mem_init(void);
NRAMV_API int   ludashi_mem_optimize(int mode);
NRAMV_API long  ludashi_mem_free_kb(void);
NRAMV_API long  ludashi_mem_total_kb(void);
NRAMV_API int   ludashi_mem_usage_pct(void);

/* aliases */
NRAMV_API int   lds_mem_init(void);
NRAMV_API int   lds_mem_optimize(int mode);
NRAMV_API long  lds_mem_free_kb(void);
NRAMV_API int   lds_mem_usage_pct(void);

/* ═══════════════════════════════════════════════
 * GAMENATIVE
 * ═══════════════════════════════════════════════ */

NRAMV_API int   gn_ram_init(void);
NRAMV_API int   gn_ram_flush(void);
NRAMV_API int   gn_ram_boost(void);
NRAMV_API int   gn_ram_available_pct(void);
NRAMV_API long  gn_ram_available_kb(void);

/* ═══════════════════════════════════════════════
 * BANNERHUB
 * ═══════════════════════════════════════════════ */

NRAMV_API int   bannerhub_memory_init(void);
NRAMV_API int   bannerhub_memory_flush(void);
NRAMV_API int   bannerhub_memory_optimize(int level);
NRAMV_API long  bannerhub_memory_available_kb(void);

/* aliases */
NRAMV_API int   bh_mem_init(void);
NRAMV_API int   bh_mem_flush(void);
NRAMV_API int   bh_mem_optimize(int level);
NRAMV_API long  bh_mem_available_kb(void);

/* ═══════════════════════════════════════════════
 * EDEN / YUZU
 * ═══════════════════════════════════════════════ */

NRAMV_API int   eden_mem_init(void);
NRAMV_API int   eden_mem_flush(void);
NRAMV_API int   eden_mem_pressure_react(void);
NRAMV_API long  eden_mem_available_kb(void);
NRAMV_API int   eden_mem_pressure_level(void);

/* aliases */
NRAMV_API int   yuzu_mem_init(void);
NRAMV_API int   yuzu_mem_flush(void);
NRAMV_API int   yuzu_mem_pressure_react(void);
NRAMV_API long  yuzu_mem_available_kb(void);
NRAMV_API int   yuzu_mem_pressure_level(void);

#ifdef __cplusplus
}
#endif

#endif /* NRAMV_EMULATOR_H */
