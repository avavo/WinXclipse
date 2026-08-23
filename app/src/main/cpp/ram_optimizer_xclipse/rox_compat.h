#ifndef ROPT_COMPAT_H
#define ROPT_COMPAT_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ─────────────────────────────────────────────
 * ABI version
 * ───────────────────────────────────────────── */

#define ROPT_COMPAT_ABI_VERSION 1

/* ─────────────────────────────────────────────
 * Export macro
 * ───────────────────────────────────────────── */

#ifndef ROPT_API

    #if defined(__GNUC__)
        #define ROPT_API \
            __attribute__((visibility("default")))
    #else
        #define ROPT_API
    #endif

#endif

/* ─────────────────────────────────────────────
 * Compat layer
 * ───────────────────────────────────────────── */

/* ABI version */
ROPT_API int rox_compat_version(void);

/* ════════════════════════════════════════
 * DIALETO VORTEK
 * ════════════════════════════════════════ */

ROPT_API int vrt_ram_init(void);

ROPT_API int vrt_ram_optimize(int level);

ROPT_API int vrt_ram_flush(void);

ROPT_API int64_t vrt_ram_available_kb(void);

/* ════════════════════════════════════════
 * DIALETO EXYNOS TOOLS
 * ════════════════════════════════════════ */

ROPT_API int ext_mem_init(void);

/* profile_name:
 *   "light"
 *   "medium"
 *   "aggressive"
 *
 * Comparação:
 *   - case-insensitive
 *   - UTF-8 safe
 */
ROPT_API int
ext_mem_set_profile(const char *profile_name);

ROPT_API int ext_mem_usage_percent(void);

/* ════════════════════════════════════════
 * DIALETO MDIEX
 * ════════════════════════════════════════ */

ROPT_API int mdx_init_ram_layer(void);

ROPT_API int
mdx_ram_optimize_flags(unsigned int flags);

/* bit 0 = VM knobs
 * bit 1 = heap compact
 * bit 2 = SGPU purge
 */

typedef struct {

    uint64_t available_kb;

    uint64_t total_kb;

    int32_t frag_index;

    int32_t reserved;

} mdx_ram_info_t;

ROPT_API int
mdx_get_ram_info(mdx_ram_info_t *out);

#ifdef __cplusplus
}
#endif

#endif
