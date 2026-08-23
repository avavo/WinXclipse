#include <stdio.h>
#include <string.h>
#include <ctype.h>
#include <stdlib.h>
#include <dirent.h>

#include "nramv.h"
#include "nramv_internal.h"

/* ─────────────────────────────────────────────
 * nramv_sgpu.c
 * Leitura de métricas SGPU via sysfs (sem root)
 * ───────────────────────────────────────────── */

#define SGPU_SYSFS_BASE    "/sys/class/drm"
#define SGPU_MEM_GTT       "mem_info_gtt_used"
#define SGPU_MEM_GTT_TOTAL "mem_info_gtt_total"
#define SGPU_MEM_VRAM      "mem_info_vram_used"

static const char *sgpu_sysfs_base(void)
{
    const char *v = getenv("NRAMV_SGPU_SYSFS_BASE");
    return (v && v[0]) ? v : SGPU_SYSFS_BASE;
}

static int sgpu_find_sysfs_path(char *out, size_t size)
{
    DIR *dir;
    struct dirent *e;
    char path[2048];
    char fallback[2048];
    FILE *f;
    int has_fallback = 0;

    dir = opendir(sgpu_sysfs_base());
    if (!dir)
        return NRAMV_ERR_NODEV;

    while ((e = readdir(dir))) {
        if (e->d_name[0] == '.')
            continue;

        snprintf(path, sizeof(path),
                 "%s/%s/device/%s",
                 sgpu_sysfs_base(), e->d_name, SGPU_MEM_GTT);

        f = fopen(path, "r");
        if (!f)
            continue;
        fclose(f);

        if (strstr(e->d_name, "sgpu")) {
            snprintf(out, size, "%s/%s/device",
                     sgpu_sysfs_base(), e->d_name);
            closedir(dir);
            return NRAMV_OK;
        }

        if (!has_fallback) {
            snprintf(fallback, sizeof(fallback),
                     "%s/%s/device",
                     sgpu_sysfs_base(), e->d_name);
            has_fallback = 1;
        }
    }

    closedir(dir);

    if (has_fallback) {
        snprintf(out, size, "%s", fallback);
        return NRAMV_OK;
    }

    return NRAMV_ERR_NODEV;
}

static long long sgpu_read(const char *base, const char *file)
{
    char path[2048];
    char buf[128];
    char *p;
    FILE *f;
    long long v = -1;

    snprintf(path, sizeof(path), "%s/%s", base, file);

    f = fopen(path, "r");
    if (!f)
        return -1;

    if (fgets(buf, sizeof(buf), f)) {
        p = buf;
        while (*p && *p != '-' && !isdigit((unsigned char)*p))
            p++;

        if (*p)
            v = atoll(p);
    }

    fclose(f);
    return v;
}

int nramv_sgpu_read_mem(nramv_sgpu_mem_t *out)
{
    char base[2048];
    int ret;

    if (!out)
        return NRAMV_ERR_PARAM;

    ret = sgpu_find_sysfs_path(base, sizeof(base));
    if (ret != NRAMV_OK)
        return ret;

    out->gtt_used_bytes  = sgpu_read(base, SGPU_MEM_GTT);
    out->gtt_total_bytes = sgpu_read(base, SGPU_MEM_GTT_TOTAL);
    out->vram_used_bytes = sgpu_read(base, SGPU_MEM_VRAM);

    out->gtt_pressure = (out->gtt_total_bytes > 0)
        ? (int)((out->gtt_used_bytes * 100) / out->gtt_total_bytes)
        : 0;

    return NRAMV_OK;
}

/* Sem root: só reduz pressão de heap local */
int nramv_sgpu_purge(nramv_profile_t profile)
{
    return nramv_heap_compact(profile);
}
