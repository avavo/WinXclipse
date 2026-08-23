#include <malloc.h>
#include "nramv.h"
#include "nramv_internal.h"

#if defined(M_TRIM_THRESHOLD) && defined(M_MMAP_THRESHOLD) && defined(M_ARENA_MAX)
#define NRAMV_HAS_MALLOPT 1
#else
#define NRAMV_HAS_MALLOPT 0
#endif

#ifndef M_PERTURB
#define M_PERTURB -6
#endif

/* ───────────────────────────────────────────── */

int nramv_mallopt_apply(nramv_profile_t profile)
{
#if !NRAMV_HAS_MALLOPT
    (void)profile;
    return NRAMV_ERR_NODEV;
#else

    int trim_threshold;
    int mmap_threshold;
    int arena_max;

    switch (profile) {
    case NRAMV_PROFILE_LIGHT:
        trim_threshold = 256 * 1024;
        mmap_threshold = 256 * 1024;
        arena_max      = 2;
        break;

    case NRAMV_PROFILE_MEDIUM:
        trim_threshold = 128 * 1024;
        mmap_threshold = 128 * 1024;
        arena_max      = 1;
        break;

    case NRAMV_PROFILE_AGGRESSIVE:
        trim_threshold = 64 * 1024;
        mmap_threshold = 64 * 1024;
        arena_max      = 1;
        break;

    default:
        return NRAMV_ERR_PARAM;
    }

    /* mallopt pode falhar silenciosamente em bionic.
     * então NÃO usamos isso como critério de erro. */
    (void)mallopt(M_TRIM_THRESHOLD, trim_threshold);
    (void)mallopt(M_MMAP_THRESHOLD, mmap_threshold);
    (void)mallopt(M_ARENA_MAX, arena_max);

    return NRAMV_OK;
#endif
}

/* ───────────────────────────────────────────── */

int nramv_mallopt_apply_perturb(void)
{
#if NRAMV_HAS_MALLOPT

    /* perturb é opcional e não crítico */
    (void)mallopt(M_PERTURB, 0x55);

    return NRAMV_OK;
#else
    return NRAMV_ERR_NODEV;
#endif
}

/* ───────────────────────────────────────────── */

int nramv_mallopt_reset(void)
{
#if NRAMV_HAS_MALLOPT

    /* reset sempre “best effort” */
    (void)mallopt(M_TRIM_THRESHOLD, 128 * 1024);
    (void)mallopt(M_MMAP_THRESHOLD, 128 * 1024);
    (void)mallopt(M_ARENA_MAX, 0);

    (void)mallopt(M_PERTURB, 0);

    return NRAMV_OK;
#else
    return NRAMV_ERR_NODEV;
#endif
}
