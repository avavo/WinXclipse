#include <stdint.h>
#include <string.h>
#include <strings.h>

#include "rox.h"
#include "rox_compat.h"
#include "rox_internal.h"

/* ─────────────────────────────────────────────
 * Helpers
 * ───────────────────────────────────────────── */

static int profile_from_level(
    int level,
    rox_profile_t *out)
{
    if (!out)
        return ROPT_ERR_PARAM;

    switch (level) {

        case 0:
            *out = ROPT_PROFILE_LIGHT;
            return ROPT_OK;

        case 1:
            *out = ROPT_PROFILE_MEDIUM;
            return ROPT_OK;

        case 2:
            *out = ROPT_PROFILE_AGGRESSIVE;
            return ROPT_OK;

        default:
            return ROPT_ERR_PARAM;
    }
}

static int profile_from_name(
    const char *name,
    rox_profile_t *out)
{
    if (!name || !out)
        return ROPT_ERR_PARAM;

    if (strcasecmp(name, "light") == 0 ||
        strcasecmp(name, "leve") == 0) {

        *out = ROPT_PROFILE_LIGHT;
        return ROPT_OK;
    }

    if (strcasecmp(name, "medium") == 0 ||
        strcasecmp(name, "medio") == 0 ||
        strcasecmp(name, "médio") == 0) {

        *out = ROPT_PROFILE_MEDIUM;
        return ROPT_OK;
    }

    if (strcasecmp(name, "aggressive") == 0 ||
        strcasecmp(name, "agressivo") == 0) {

        *out = ROPT_PROFILE_AGGRESSIVE;
        return ROPT_OK;
    }

    return ROPT_ERR_PARAM;
}

/* ─────────────────────────────────────────────
 * DIALETO VORTEK
 * ───────────────────────────────────────────── */

int vrt_ram_init(void)
{
    return rox_init();
}

int vrt_ram_optimize(int level)
{
    rox_profile_t profile;

    int ret =
        profile_from_level(level, &profile);

    if (ret != ROPT_OK)
        return ret;

    return rox_apply_profile(profile);
}

int vrt_ram_flush(void)
{
    return rox_flush();
}

int64_t vrt_ram_available_kb(void)
{
    rox_stats_t stats;

    if (rox_get_stats(&stats) != ROPT_OK)
        return -1;

    return (int64_t)stats.available_kb;
}

/* ─────────────────────────────────────────────
 * DIALETO EXYNOS TOOLS
 * ───────────────────────────────────────────── */

int ext_mem_init(void)
{
    return rox_init();
}

int ext_mem_set_profile(const char *profile_name)
{
    rox_profile_t profile;

    int ret =
        profile_from_name(profile_name,
                          &profile);

    if (ret != ROPT_OK)
        return ret;

    return rox_apply_profile(profile);
}

int ext_mem_usage_percent(void)
{
    rox_stats_t stats;

    if (rox_get_stats(&stats) != ROPT_OK)
        return -1;

    if (stats.total_kb == 0)
        return -1;

    if (stats.available_kb > stats.total_kb)
        return 0;

    unsigned long used =
        stats.total_kb - stats.available_kb;

    return (int)(
        ((unsigned long long)used * 100ULL)
        / stats.total_kb
    );
}

/* ─────────────────────────────────────────────
 * DIALETO MDIEX
 * ───────────────────────────────────────────── */

int mdx_init_ram_layer(void)
{
    return rox_init();
}

int mdx_ram_optimize_flags(unsigned int flags)
{
    int ret = ROPT_OK;
    int err;

    rox_profile_t p =
        ROPT_PROFILE_MEDIUM;

    /* VM */
    if (flags & 0x01) {

        err = rox_apply_vm_profile(p);

        if (err != ROPT_OK &&
            ret == ROPT_OK) {

            ret = err;
        }
    }

    /* heap */
    if (flags & 0x02) {

        err = rox_heap_compact(p);

        if (err != ROPT_OK &&
            ret == ROPT_OK) {

            ret = err;
        }
    }

    /* sgpu */
    if (flags & 0x04) {

        err = rox_sgpu_purge(p);

        if (err != ROPT_OK &&
            ret == ROPT_OK) {

            ret = err;
        }
    }

    return ret;
}

int mdx_get_ram_info(mdx_ram_info_t *out)
{
    rox_stats_t stats;

    int ret;

    if (!out)
        return ROPT_ERR_PARAM;

    ret = rox_get_stats(&stats);

    if (ret != ROPT_OK)
        return ret;

    out->available_kb = stats.available_kb;
    out->total_kb     = stats.total_kb;
    out->frag_index   = (int)stats.fragmentation;

    return ROPT_OK;
}
