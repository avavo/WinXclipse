#include <malloc.h>
#include <sys/mman.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdatomic.h>

#include "nramv.h"
#include "nramv_internal.h"

#ifndef MADV_PAGEOUT
#define MADV_PAGEOUT 21
#endif

/* ── Limiar mínimo de RSS para varredura completa do maps ── */
#define NRAMV_MADVISE_MIN_RSS_KB (32 * 1024)

#if defined(__ANDROID__)
__attribute__((weak)) int malloc_trim(size_t pad);
#endif

static int nramv_malloc_trim(size_t pad)
{
#if defined(__ANDROID__)
    if (!malloc_trim)
        return 0;
#endif
    return malloc_trim(pad);
}

/* ─────────────────────────────────────────────
 * HEAP TRIM CORE
 * ───────────────────────────────────────────── */

int nramv_heap_trim(void)
{
    (void)nramv_malloc_trim(0);
    return NRAMV_OK;
}

/* ─────────────────────────────────────────────
 * CACHE GLOBAL (lazyfree)
 * ───────────────────────────────────────────── */

static _Atomic long long g_cached_lazyfree_kb;

static int mapping_is_safe_candidate(const char *name, const char *perms)
{
    if (!perms || perms[0] != 'r' || perms[1] != 'w')
        return 0;

    /* Nunca toque stacks, mappings especiais, bibliotecas ou arquivos.
     * MADV_DONTNEED/MADV_FREE em região viva é corrupção esperando aplauso.
     * Aqui só usamos MADV_PAGEOUT, que preserva conteúdo, mas ainda assim
     * evitamos regiões óbvias de execução/stack para reduzir stutter. */
    if (!name || name[0] == '\0')
        return 1; /* anon rw */

    if (strcmp(name, "[heap]") == 0)
        return 1;

    if (strncmp(name, "[anon:", 6) == 0)
        return 1;

    return 0;
}

/* ─────────────────────────────────────────────
 * MADVISE PASS
 *
 * Importante: não usamos MADV_FREE nem MADV_DONTNEED aqui. Eles podem
 * descartar conteúdo de páginas ainda vivas e corromper o processo.
 * MADV_PAGEOUT preserva conteúdo e apenas empurra páginas para reclaim/swap
 * quando o kernel suporta. Se não suporta, o passe vira best-effort no-op.
 * ───────────────────────────────────────────── */

int nramv_heap_madvise(void)
{
    FILE *f;
    char line[512];
    unsigned long start, end;
    char perms[8];
    char name[256];

    atomic_store(&g_cached_lazyfree_kb, -1LL);

    long long rss = nramv_smaps_rss_kb();
    if (rss >= 0 && rss < NRAMV_MADVISE_MIN_RSS_KB)
        return NRAMV_OK;

    long long lazy = nramv_smaps_lazyfree_kb();
    atomic_store(&g_cached_lazyfree_kb, lazy);

    f = fopen("/proc/self/maps", "r");
    if (!f)
        return NRAMV_ERR_NODEV;

    while (fgets(line, sizeof(line), f)) {
        name[0] = '\0';

        if (sscanf(line, "%lx-%lx %7s %*s %*s %*s %255s",
                   &start, &end, perms, name) < 3)
            continue;

        if (end <= start)
            continue;

        if (!mapping_is_safe_candidate(name, perms))
            continue;

        (void)madvise((void *)start, end - start, MADV_PAGEOUT);
    }

    fclose(f);
    return NRAMV_OK;
}

/* ─────────────────────────────────────────────
 * SECOND PASS
 * Mantido por compatibilidade interna. Sem MADV_FREE destrutivo.
 * ───────────────────────────────────────────── */

int nramv_heap_madvise_free(void)
{
    return nramv_heap_madvise();
}

/* ─────────────────────────────────────────────
 * PROFILE COMPACT
 * ───────────────────────────────────────────── */

int nramv_heap_compact(nramv_profile_t profile)
{
    int ret = NRAMV_OK;

    switch (profile) {

    case NRAMV_PROFILE_LIGHT:
        return nramv_heap_trim();

    case NRAMV_PROFILE_MEDIUM:
        ret = nramv_heap_trim();
        if (ret == NRAMV_OK)
            ret = nramv_heap_madvise();
        return ret;

    case NRAMV_PROFILE_AGGRESSIVE:
        ret = nramv_heap_madvise();
        if (ret == NRAMV_OK)
            ret = nramv_heap_trim();

        (void)nramv_heap_madvise_free();
        (void)nramv_malloc_trim(0);

        return ret;

    default:
        return NRAMV_ERR_PARAM;
    }
}
