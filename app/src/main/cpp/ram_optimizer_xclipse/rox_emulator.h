#ifndef ROPT_EMULATOR_H
#define ROPT_EMULATOR_H

#ifdef __cplusplus
extern "C" {
#endif

#include <stdint.h>

/* ─────────────────────────────────────────────
 * visibility control
 * ───────────────────────────────────────────── */

#ifndef ROPT_API
#if defined(__GNUC__)
#define ROPT_API __attribute__((visibility("default")))
#else
#define ROPT_API
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

ROPT_API int  winlator_mem_init(void);
ROPT_API int  winlator_mem_flush(void);
ROPT_API int  winlator_mem_optimize(int level);
ROPT_API long winlator_mem_available_kb(void);
ROPT_API int  winlator_mem_pressure(void);
ROPT_API int  winlator_mem_stats_str(char *buf, int buf_size);

/* aliases */
ROPT_API int  wlt_mem_init(void);
ROPT_API int  wlt_mem_flush(void);
ROPT_API int  wlt_mem_optimize(int level);
ROPT_API long wlt_mem_available_kb(void);
ROPT_API int  wlt_mem_pressure(void);

/* ═══════════════════════════════════════════════
 * LUDASHI
 * ═══════════════════════════════════════════════ */

ROPT_API int   ludashi_mem_init(void);
ROPT_API int   ludashi_mem_optimize(int mode);
ROPT_API long  ludashi_mem_free_kb(void);
ROPT_API long  ludashi_mem_total_kb(void);
ROPT_API int   ludashi_mem_usage_pct(void);

/* aliases */
ROPT_API int   lds_mem_init(void);
ROPT_API int   lds_mem_optimize(int mode);
ROPT_API long  lds_mem_free_kb(void);
ROPT_API int   lds_mem_usage_pct(void);

/* ═══════════════════════════════════════════════
 * GAMENATIVE
 * ═══════════════════════════════════════════════ */

ROPT_API int   gn_ram_init(void);
ROPT_API int   gn_ram_flush(void);
ROPT_API int   gn_ram_boost(void);
ROPT_API int   gn_ram_available_pct(void);
ROPT_API long  gn_ram_available_kb(void);

/* ═══════════════════════════════════════════════
 * BANNERHUB
 * ═══════════════════════════════════════════════ */

ROPT_API int   bannerhub_memory_init(void);
ROPT_API int   bannerhub_memory_flush(void);
ROPT_API int   bannerhub_memory_optimize(int level);
ROPT_API long  bannerhub_memory_available_kb(void);

/* aliases */
ROPT_API int   bh_mem_init(void);
ROPT_API int   bh_mem_flush(void);
ROPT_API int   bh_mem_optimize(int level);
ROPT_API long  bh_mem_available_kb(void);

/* ═══════════════════════════════════════════════
 * EDEN / YUZU
 * ═══════════════════════════════════════════════ */

ROPT_API int   eden_mem_init(void);
ROPT_API int   eden_mem_flush(void);
ROPT_API int   eden_mem_pressure_react(void);
ROPT_API long  eden_mem_available_kb(void);
ROPT_API int   eden_mem_pressure_level(void);

/* aliases */
ROPT_API int   yuzu_mem_init(void);
ROPT_API int   yuzu_mem_flush(void);
ROPT_API int   yuzu_mem_pressure_react(void);
ROPT_API long  yuzu_mem_available_kb(void);
ROPT_API int   yuzu_mem_pressure_level(void);

#ifdef __cplusplus
}
#endif

#endif /* ROPT_EMULATOR_H */
