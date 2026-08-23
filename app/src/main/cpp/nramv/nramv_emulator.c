#include <stdio.h>
#include <string.h>
#include <stdint.h>

#include "nramv.h"
#include "nramv_emulator.h"
#include "nramv_internal.h"

/* ─────────────────────────────────────────────
 * helpers centralizados (evita divergência lógica)
 * ───────────────────────────────────────────── */

static int get_stats(nramv_stats_t *s)
{
    return nramv_get_stats(s);
}

static int pressure_pct(void)
{
    nramv_stats_t s;

    if (get_stats(&s) != NRAMV_OK || s.total_kb == 0)
        return -1;

    return (int)((s.available_kb * 100) / s.total_kb);
}

static int usage_pct(void)
{
    int p = pressure_pct();
    return (p < 0) ? -1 : (100 - p);
}

static int pressure_level_from_ram(void)
{
    int pct = pressure_pct();

    if (pct < 0) return 0;
    if (pct < 5)  return 3;
    if (pct < 15) return 2;
    if (pct < 30) return 1;
    return 0;
}

static nramv_profile_t safe_profile(int level)
{
    if (level <= 0) return NRAMV_PROFILE_LIGHT;
    if (level == 1) return NRAMV_PROFILE_MEDIUM;
    return NRAMV_PROFILE_AGGRESSIVE;
}

/* ════════════════════════════════════════════════════════
 * WINLATOR
 * ════════════════════════════════════════════════════════ */

int winlator_mem_init(void)        { return nramv_init(); }
int winlator_mem_flush(void)       { return nramv_flush(); }

long winlator_mem_available_kb(void)
{
    nramv_stats_t s;
    if (get_stats(&s) != NRAMV_OK) return -1;
    return (long)s.available_kb;
}

int winlator_mem_optimize(int level)
{
    return nramv_apply_profile(safe_profile(level));
}

int winlator_mem_pressure(void)
{
    return usage_pct();
}

int winlator_mem_stats_str(char *buf, int buf_size)
{
    nramv_stats_t s;

    if (!buf || buf_size < 64)
        return NRAMV_ERR_PARAM;

    if (get_stats(&s) != NRAMV_OK) {
        snprintf(buf, buf_size, "nramv: stats unavailable");
        return NRAMV_ERR_INIT;
    }

    snprintf(buf, buf_size,
             "nramv v%s | total=%luMB avail=%luMB frag=%d%%",
             nramv_version(),
             s.total_kb / 1024,
             s.available_kb / 1024,
             s.fragmentation);

    return NRAMV_OK;
}

/* aliases wlt_ */
int wlt_mem_init(void)          { return winlator_mem_init(); }
int wlt_mem_flush(void)         { return winlator_mem_flush(); }
int wlt_mem_optimize(int level) { return winlator_mem_optimize(level); }
long wlt_mem_available_kb(void) { return winlator_mem_available_kb(); }
int wlt_mem_pressure(void)      { return winlator_mem_pressure(); }

/* ════════════════════════════════════════════════════════
 * LUDASHI
 * ════════════════════════════════════════════════════════ */

int ludashi_mem_init(void) { return nramv_init(); }

int ludashi_mem_optimize(int mode)
{
    return nramv_apply_profile(
        mode == 1 ? NRAMV_PROFILE_AGGRESSIVE : NRAMV_PROFILE_MEDIUM
    );
}

long ludashi_mem_free_kb(void)
{
    nramv_stats_t s;
    if (get_stats(&s) != NRAMV_OK) return -1;
    return (long)s.free_kb;
}

long ludashi_mem_total_kb(void)
{
    nramv_stats_t s;
    if (get_stats(&s) != NRAMV_OK) return -1;
    return (long)s.total_kb;
}

int ludashi_mem_usage_pct(void)
{
    return usage_pct();
}

/* aliases lds_ */
int lds_mem_init(void)         { return ludashi_mem_init(); }
int lds_mem_optimize(int mode) { return ludashi_mem_optimize(mode); }
long lds_mem_free_kb(void)     { return ludashi_mem_free_kb(); }
int lds_mem_usage_pct(void)    { return ludashi_mem_usage_pct(); }

/* ════════════════════════════════════════════════════════
 * GAMENATIVE
 * ════════════════════════════════════════════════════════ */

int gn_ram_init(void)  { return nramv_init(); }
int gn_ram_flush(void) { return nramv_flush(); }

int gn_ram_boost(void)
{
    int ret = nramv_apply_profile(NRAMV_PROFILE_AGGRESSIVE);
    return ret; /* sem auto-rollback silencioso */
}

int gn_ram_available_pct(void)
{
    return pressure_pct();
}

long gn_ram_available_kb(void)
{
    nramv_stats_t s;
    if (get_stats(&s) != NRAMV_OK) return -1;
    return (long)s.available_kb;
}

/* ════════════════════════════════════════════════════════
 * BANNERHUB
 * ════════════════════════════════════════════════════════ */

int bannerhub_memory_init(void)  { return nramv_init(); }
int bannerhub_memory_flush(void) { return nramv_flush(); }

int bannerhub_memory_optimize(int level)
{
    return nramv_apply_profile(safe_profile(level));
}

long bannerhub_memory_available_kb(void)
{
    nramv_stats_t s;
    if (get_stats(&s) != NRAMV_OK) return -1;
    return (long)s.available_kb;
}

/* aliases bh_ */
int bh_mem_init(void)          { return bannerhub_memory_init(); }
int bh_mem_flush(void)         { return bannerhub_memory_flush(); }
int bh_mem_optimize(int level) { return bannerhub_memory_optimize(level); }
long bh_mem_available_kb(void) { return bannerhub_memory_available_kb(); }

/* ════════════════════════════════════════════════════════
 * EDEN / YUZU
 * ════════════════════════════════════════════════════════ */

int eden_mem_init(void)  { return nramv_init(); }
int eden_mem_flush(void) { return nramv_flush(); }

int eden_mem_pressure_react(void)
{
    return nramv_pressure_react();
}

long eden_mem_available_kb(void)
{
    nramv_stats_t s;
    if (get_stats(&s) != NRAMV_OK) return -1;
    return (long)s.available_kb;
}

int eden_mem_pressure_level(void)
{
    return pressure_level_from_ram();
}

/* aliases yuzu_ */
int yuzu_mem_init(void)            { return eden_mem_init(); }
int yuzu_mem_flush(void)           { return eden_mem_flush(); }
int yuzu_mem_pressure_react(void)  { return eden_mem_pressure_react(); }
long yuzu_mem_available_kb(void)   { return eden_mem_available_kb(); }
int yuzu_mem_pressure_level(void)  { return eden_mem_pressure_level(); }
