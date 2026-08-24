#include <malloc.h>
#include <sys/mman.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdatomic.h>

#include "rox.h"
#include "rox_internal.h"

#ifndef MADV_PAGEOUT
#define MADV_PAGEOUT 21
#endif

/* ── Limiar mínimo de RSS para varredura completa do maps ── */
#define ROPT_MADVISE_MIN_RSS_KB (32 * 1024)

/* Mapeamentos menores que isso são ruído para o sweep de PAGEOUT. */
#define ROPT_MADVISE_MIN_MAPPING_BYTES (4ull * 1024 * 1024)

#if defined(__ANDROID__)
__attribute__((weak)) int malloc_trim(size_t pad);
#endif

static int rox_malloc_trim(size_t pad)
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

int rox_heap_trim(void)
{
    (void)rox_malloc_trim(0);
    return ROPT_OK;
}

/* ─────────────────────────────────────────────
 * CACHE GLOBAL (lazyfree)
 * ───────────────────────────────────────────── */

static _Atomic long long g_cached_lazyfree_kb;

static int mapping_is_safe_candidate(const char *name, const char *perms,
                                     unsigned long long size_bytes)
{
    /* Regiões anon do runtime Android: PAGEOUT aqui desativa páginas de
     * código JIT, stacks de GC e metadados de alocador que são tocados a
     * cada frame — o refault custa mais do que a RAM devolvida. */
    static const char *const k_skip[] = {
        "[anon:dalvik",    "[anon:scudo",   "[anon:jemalloc",
        "[anon:libc",      "[anon:linker",  "[anon:bionic",
        "[anon:memfd",     "[anon:ashmem",  "[anon:ASHMEM",
        "[anon:stack_and_tls", NULL
    };

    if (!perms || perms[0] != 'r' || perms[1] != 'w')
        return 0;

    if (size_bytes < ROPT_MADVISE_MIN_MAPPING_BYTES)
        return 0;

    /* Nunca toque stacks, mappings especiais, bibliotecas ou arquivos.
     * MADV_DONTNEED/MADV_FREE em região viva é corrupção esperando aplauso.
     * Aqui só usamos MADV_PAGEOUT, que preserva conteúdo, mas ainda assim
     * evitamos regiões óbvias de execução/stack para reduzir stutter.
     * Limitação conhecida: stacks de threads aparecem como anon sem nome;
     * o filtro de tamanho é a única proteção para elas. */
    if (!name || name[0] == '\0')
        return 1; /* anon rw */

    if (strcmp(name, "[heap]") == 0)
        return 1;

    if (strncmp(name, "[anon:", 6) == 0) {
        int i;
        for (i = 0; k_skip[i]; i++) {
            if (strncmp(name, k_skip[i], strlen(k_skip[i])) == 0)
                return 0;
        }
        return 1;
    }

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

int rox_heap_madvise(void)
{
    FILE *f;
    char line[512];
    unsigned long start, end;
    char perms[8];
    char name[256];

    atomic_store(&g_cached_lazyfree_kb, -1LL);

    long long rss = rox_smaps_rss_kb();
    if (rss >= 0 && rss < ROPT_MADVISE_MIN_RSS_KB)
        return ROPT_OK;

    long long lazy = rox_smaps_lazyfree_kb();
    atomic_store(&g_cached_lazyfree_kb, lazy);

    f = fopen("/proc/self/maps", "r");
    if (!f)
        return ROPT_ERR_NODEV;

    while (fgets(line, sizeof(line), f)) {
        name[0] = '\0';

        if (sscanf(line, "%lx-%lx %7s %*s %*s %*s %255s",
                   &start, &end, perms, name) < 3)
            continue;

        if (end <= start)
            continue;

        if (!mapping_is_safe_candidate(name, perms, (unsigned long long)(end - start)))
            continue;

        (void)madvise((void *)start, end - start, MADV_PAGEOUT);
    }

    fclose(f);
    return ROPT_OK;
}

/* ─────────────────────────────────────────────
 * SECOND PASS
 * Mantido por compatibilidade interna. Sem MADV_FREE destrutivo.
 * ───────────────────────────────────────────── */

int rox_heap_madvise_free(void)
{
    return rox_heap_madvise();
}

/* ─────────────────────────────────────────────
 * PROFILE COMPACT
 * ───────────────────────────────────────────── */

int rox_heap_compact(rox_profile_t profile)
{
    int ret = ROPT_OK;

    /* Entradas externas (mdx_ram_optimize_flags, emuladores etc.) podiam
     * disparar a varredura completa antes do init ou depois do shutdown. */
    if (!rox_core_is_initialized())
        return ROPT_ERR_INIT;

    switch (profile) {

    case ROPT_PROFILE_LIGHT:
        return rox_heap_trim();

    case ROPT_PROFILE_MEDIUM:
        ret = rox_heap_trim();
        if (ret == ROPT_OK)
            ret = rox_heap_madvise();
        return ret;

    case ROPT_PROFILE_AGGRESSIVE:
        /* Uma única passada de madvise + trim: rodar o sweep duas vezes
         * (madvise_free era alias de madvise) só queimava CPU sob lock. */
        ret = rox_heap_madvise();
        if (ret == ROPT_OK)
            ret = rox_heap_trim();

        return ret;

    default:
        return ROPT_ERR_PARAM;
    }
}
