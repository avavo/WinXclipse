#include "wxp_loader.h"
#include "wxp_core.h"
#ifdef WXP_RUNTIME_MONOLITHIC
#include "wxp_sched.h"
#include "wxp_memshim.h"
#include "wxp_ahb.h"
#include "wxp_surface.h"
#endif
#include <stdlib.h>
#include <string.h>
#include <stdatomic.h>
#ifdef __ANDROID__
#include <dlfcn.h>
#endif

#ifndef WXP_HAS_NRAMV
#define WXP_HAS_NRAMV 1
#endif

#ifndef WXP_HAS_HELIX
#define WXP_HAS_HELIX 1
#endif

static atomic_int g_loader_ready = 0;

#ifdef __ANDROID__
static void* g_handles[8];
static int g_handle_count = 0;
#endif

#if !defined(WXP_RUNTIME_MONOLITHIC) || WXP_HAS_NRAMV || WXP_HAS_HELIX
static void try_load(const char* name, int global_symbols) {
#ifdef __ANDROID__
    if (!name || !*name) return;
    int flags = RTLD_NOW | (global_symbols ? RTLD_GLOBAL : RTLD_LOCAL);
    void* h = dlopen(name, flags);
    if (!h) {
        wxp_log_line("WinXclipsePolicyLoader", dlerror());
        return;
    }
    if (g_handle_count < (int)(sizeof(g_handles) / sizeof(g_handles[0]))) {
        g_handles[g_handle_count++] = h;
    }
#else
    (void)name;
    (void)global_symbols;
#endif
}
#endif

int wxp_loader_init(void) {
    if (atomic_exchange(&g_loader_ready, 1)) return 1;

    wxp_init_from_env_or_auto();

    /* MDIEX_PROFILE is published by Core so later meta overrides cannot leave
     * a stale native_fallback value behind. The loader is not the source of
     * truth, no matter how much it would like a tiny crown. */
    setenv("MDIEX_VERSION", "3.6", 0);

#ifdef WXP_RUNTIME_MONOLITHIC
    /* In monolithic runtime mode this DSO already contains Core/Loader/Sched/
     * MemShim/AHB/Surface — and NRAMV is likewise compiled in (rox_*.c), while
     * Helix is an implicit Vulkan layer discovered through its JSON manifest
     * by the guest Vulkan loader, not something we dlopen from here.
     * Do NOT dlopen split runtime siblings or the process can end up with two
     * independent copies of the same counters and hints. */
    wxp_sched_init();
    wxp_memshim_init();
    wxp_ahb_init();
    wxp_surface_init();
#else
    /* Split runtime modules are linked against libwxp_core.so already.
     * Do not dlopen libwxp_core.so here: on Android linker namespaces that
     * can create a second core instance, and duplicate state is the kind of
     * comedy only crash logs enjoy. */
    try_load("libwxp_sched.so", 0);
    try_load("libwxp_memshim.so", 0);
    try_load("libwxp_ahb.so", 0);
    try_load("libwxp_surface.so", 0);
#endif
    wxp_log_line("WinXclipsePolicyLoader", "loader initialized");
    return 1;
}

__attribute__((constructor)) static void wxp_loader_ctor(void) { wxp_loader_init(); }
