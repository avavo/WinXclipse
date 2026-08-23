#include <stdio.h>
#include <string.h>
#include <stdlib.h>

#include "rox.h"
#include "rox_internal.h"

/* ─────────────────────────────
 * RSS do processo via smaps_rollup, com fallback para /proc/self/status
 * ───────────────────────────── */
long long rox_smaps_rss_kb(void)
{
    FILE *f = fopen("/proc/self/smaps_rollup", "r");
    char line[256];
    unsigned long value;

    if (f) {
        while (fgets(line, sizeof(line), f)) {
            if (sscanf(line, "Rss: %lu", &value) == 1) {
                fclose(f);
                return (long long)value;
            }
        }
        fclose(f);
    }

    f = fopen("/proc/self/status", "r");
    if (!f)
        return -1;

    while (fgets(line, sizeof(line), f)) {
        if (sscanf(line, "VmRSS: %lu", &value) == 1) {
            fclose(f);
            return (long long)value;
        }
    }

    fclose(f);
    return -1;
}

/* ─────────────────────────────
 * Meminfo básico
 * ───────────────────────────── */
int rox_read_meminfo(rox_stats_t *out)
{
    FILE *f;
    char line[256];
    unsigned long value;

    if (!out)
        return ROPT_ERR_PARAM;

    memset(out, 0, sizeof(*out));

    f = fopen("/proc/meminfo", "r");
    if (!f)
        return ROPT_ERR_NODEV;

    while (fgets(line, sizeof(line), f)) {
        if (sscanf(line, "MemTotal: %lu", &value) == 1)
            out->total_kb = value;
        else if (sscanf(line, "MemFree: %lu", &value) == 1)
            out->free_kb = value;
        else if (sscanf(line, "MemAvailable: %lu", &value) == 1)
            out->available_kb = value;
        else if (sscanf(line, "Cached: %lu", &value) == 1)
            out->cached_kb = value;
    }

    fclose(f);

    if (!out->total_kb)
        return ROPT_ERR_NODEV;

    if (!out->available_kb)
        out->available_kb = out->free_kb + out->cached_kb;

    return ROPT_OK;
}

/* ─────────────────────────────
 * Fragmentação (heurística simples)
 * ───────────────────────────── */
int rox_read_fragmentation(void)
{
    FILE *f = fopen("/proc/buddyinfo", "r");
    char line[256];
    unsigned long small = 0;
    unsigned long large = 0;

    if (!f)
        return 0;

    while (fgets(line, sizeof(line), f)) {
        char *p;
        unsigned long s = 0;
        unsigned long l = 0;
        unsigned long v2;
        int n = 0;

        if (strstr(line, "Normal"))
            p = strstr(line, "Normal");
        else if (strstr(line, "DMA32"))
            p = strstr(line, "DMA32");
        else if (strstr(line, "HighMem"))
            p = strstr(line, "HighMem");
        else if (strstr(line, "DMA"))
            p = strstr(line, "DMA");
        else
            continue;

        while (*p && (*p < '0' || *p > '9'))
            p++;

        while (n < 11 && sscanf(p, "%lu", &v2) == 1) {
            if (n <= 2)
                s += v2;
            else
                l += v2;

            while (*p && *p != ' ' && *p != '\t')
                p++;
            while (*p == ' ' || *p == '\t')
                p++;
            n++;
        }

        if (s + l > 0) {
            small += s;
            large += l;
        }
    }

    fclose(f);

    if (!(small + large))
        return 0;

    return (int)((small * 100) / (small + large));
}

/* ─────────────────────────────
 * LazyFree total
 * ───────────────────────────── */
long long rox_smaps_lazyfree_kb(void)
{
    FILE *f = fopen("/proc/self/smaps", "r");
    char line[256];
    unsigned long value;
    long long total = 0;

    if (!f)
        return -1;

    while (fgets(line, sizeof(line), f)) {
        if (sscanf(line, "LazyFree: %lu", &value) == 1)
            total += (long long)value;
    }

    fclose(f);
    return total;
}

/* ─────────────────────────────
 * cgroup limit
 * ───────────────────────────── */
long long rox_cgroup_limit_kb(void)
{
    FILE *f = fopen("/proc/self/cgroup", "r");
    char line[256], path[192] = "";

    if (!f)
        return 0;

    while (fgets(line, sizeof(line), f)) {
        char *p = strchr(line, ':');
        if (!p)
            continue;
        p++;

        char *q = strchr(p, ':');
        if (!q)
            continue;

        if (!strncmp(p, "memory", (size_t)(q - p)) || p == q) {
            q++;
            strncpy(path, q, sizeof(path) - 1);
            path[sizeof(path) - 1] = '\0';
            path[strcspn(path, "\n")] = 0;
            break;
        }
    }

    fclose(f);

    if (!path[0])
        return 0;

    char file[256];
    snprintf(file, sizeof(file),
             "/sys/fs/cgroup/memory%s/memory.limit_in_bytes", path);

    f = fopen(file, "r");
    if (!f) {
        snprintf(file, sizeof(file),
                 "/sys/fs/cgroup%s/memory.max", path);
        f = fopen(file, "r");
    }

    if (!f)
        return 0;

    char cg_line[64];
    if (!fgets(cg_line, sizeof(cg_line), f)) {
        fclose(f);
        return 0;
    }
    fclose(f);

    if (strncmp(cg_line, "max", 3) == 0)
        return 0;

    long long limit = 0;
    if (sscanf(cg_line, "%lld", &limit) != 1)
        return 0;

    if (limit <= 0 || limit > (long long)8LL * 1024 * 1024 * 1024 * 1024)
        return 0;

    return limit / 1024;
}

/* ─────────────────────────────
 * majflt do processo
 * ───────────────────────────── */
long long rox_self_majflt(void)
{
    FILE *f = fopen("/proc/self/stat", "r");
    char buf[1024], *p;
    long long maj = 0;

    if (!f)
        return -1;

    if (!fgets(buf, sizeof(buf), f)) {
        fclose(f);
        return -1;
    }
    fclose(f);

    p = strrchr(buf, ')');
    if (!p)
        return -1;
    p++;

    for (int i = 1; i <= 9; i++) {
        while (*p == ' ')
            p++;
        if (!*p)
            return -1;
        while (*p && *p != ' ')
            p++;
    }

    while (*p == ' ')
        p++;
    if (!*p)
        return -1;

    if (sscanf(p, "%lld", &maj) != 1)
        return -1;

    return maj;
}

/* ─────────────────────────────
 * swap + vsz
 * ───────────────────────────── */
long long rox_self_vmswap_kb(void)
{
    FILE *f = fopen("/proc/self/status", "r");
    char line[256];
    unsigned long v;

    if (!f)
        return 0;

    while (fgets(line, sizeof(line), f)) {
        if (sscanf(line, "VmSwap: %lu", &v) == 1) {
            fclose(f);
            return (long long)v;
        }
    }

    fclose(f);
    return 0;
}

long long rox_self_vsz_kb(void)
{
    FILE *f = fopen("/proc/self/status", "r");
    char line[256];
    unsigned long v;

    if (!f)
        return 0;

    while (fgets(line, sizeof(line), f)) {
        if (sscanf(line, "VmSize: %lu", &v) == 1) {
            fclose(f);
            return (long long)v;
        }
    }

    fclose(f);
    return 0;
}

/* ─────────────────────────────
 * VM profile bridge
 * ───────────────────────────── */
int rox_apply_vm_profile(rox_profile_t profile)
{
    return rox_mallopt_apply(profile);
}
