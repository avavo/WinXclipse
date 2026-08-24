#include <malloc.h>
#include "rox.h"
#include "rox_internal.h"

#if defined(M_TRIM_THRESHOLD) && defined(M_MMAP_THRESHOLD) && defined(M_ARENA_MAX)
#define ROPT_HAS_MALLOPT 1
#else
#define ROPT_HAS_MALLOPT 0
#endif

#ifndef M_PERTURB
#define M_PERTURB -6
#endif

/* ───────────────────────────────────────────── */

int rox_mallopt_apply(rox_profile_t profile)
{
#if !ROPT_HAS_MALLOPT
    (void)profile;
    return ROPT_ERR_NODEV;
#else

    int trim_threshold;
    int mmap_threshold;
    int arena_max;

    switch (profile) {
    case ROPT_PROFILE_LIGHT:
        trim_threshold = 256 * 1024;
        mmap_threshold = 256 * 1024;
        arena_max      = 2;
        break;

    case ROPT_PROFILE_MEDIUM:
        trim_threshold = 128 * 1024;
        mmap_threshold = 128 * 1024;
        arena_max      = 1;
        break;

    case ROPT_PROFILE_AGGRESSIVE:
        trim_threshold = 64 * 1024;
        mmap_threshold = 64 * 1024;
        arena_max      = 1;
        break;

    default:
        return ROPT_ERR_PARAM;
    }

    /* mallopt no bionic não honra M_TRIM/M_MMAP/Arena (só decay/purge):
     * retorna 0 para opções não suportadas. Se NENHUMA opção foi aceita,
     * reportar NODEV em vez de OK — antes, o perfil "aplicado" era mentira
     * e mascarava por que a RAM não caía. Em glibc os três costumam ser
     * honrados e seguimos reportando OK. */
    {
        int honored = 0;

        honored += mallopt(M_TRIM_THRESHOLD, trim_threshold) ? 1 : 0;
        honored += mallopt(M_MMAP_THRESHOLD, mmap_threshold) ? 1 : 0;
        honored += mallopt(M_ARENA_MAX, arena_max) ? 1 : 0;

        return honored > 0 ? ROPT_OK : ROPT_ERR_NODEV;
    }
#endif
}

/* ───────────────────────────────────────────── */

int rox_mallopt_apply_perturb(void)
{
#if ROPT_HAS_MALLOPT

    /* perturb é opcional e não crítico */
    (void)mallopt(M_PERTURB, 0x55);

    return ROPT_OK;
#else
    return ROPT_ERR_NODEV;
#endif
}

/* ───────────────────────────────────────────── */

int rox_mallopt_reset(void)
{
#if ROPT_HAS_MALLOPT

    /* reset sempre “best effort” */
    (void)mallopt(M_TRIM_THRESHOLD, 128 * 1024);
    (void)mallopt(M_MMAP_THRESHOLD, 128 * 1024);
    (void)mallopt(M_ARENA_MAX, 0);

    (void)mallopt(M_PERTURB, 0);

    return ROPT_OK;
#else
    return ROPT_ERR_NODEV;
#endif
}
