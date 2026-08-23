#include "mdiex_loader.h"
#include "mdiex_core.h"
#ifdef MDIEX_RUNTIME_MONOLITHIC
#include "mdiex_sched.h"
#include "mdiex_memshim.h"
#include "mdiex_ahb.h"
#include "mdiex_surface.h"
#endif
#include <stdlib.h>
#include <string.h>
#include <stdatomic.h>
#ifdef __ANDROID__
#include <dlfcn.h>
#endif

#ifndef MDIEX_HAS_NRAMV
#define MDIEX_HAS_NRAMV 1
#endif

#ifndef MDIEX_HAS_HELIX
#define MDIEX_HAS_HELIX 1
#endif

static atomic_int g_loader_ready = 0;

#ifdef __ANDROID__
static void* g_handles[8];
static int g_handle_count = 0;
#endif

#if !defined(MDIEX_RUNTIME_MONOLITHIC) || MDIEX_HAS_NRAMV || MDIEX_HAS_HELIX
static void try_load(const char* name, int global_symbols) {
#ifdef __ANDROID__
    if (!name || !*name) return;
    int flags = RTLD_NOW | (global_symbols ? RTLD_GLOBAL : RTLD_LOCAL);
    void* h = dlopen(name, flags);
    if (!h) {
        mdiex_log_line("MdiExLoader", dlerror());
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

int mdiex_loader_init(void) {
    if (atomic_exchange(&g_loader_ready, 1)) return 1;

    mdiex_init_from_env_or_auto();

#if MDIEX_HAS_NRAMV
    setenv("NRAMV_ENABLE", "1", 0);
#else
    /* Build-time truth wins here: preserving a user-provided "1" when the
     * library is absent would advertise a ghost module. Very spiritual, very
     * useless. */
    setenv("NRAMV_ENABLE", "0", 1);
#endif
    setenv("MDIEX_VERSION", "3.6", 0);
    /* MDIEX_PROFILE is published by Core so later meta overrides cannot leave
     * a stale native_fallback value behind. The loader is not the source of
     * truth, no matter how much it would like a tiny crown. */

#ifdef MDIEX_RUNTIME_MONOLITHIC
    /* In monolithic runtime mode this DSO already contains Core/Loader/Sched/
     * MemShim/AHB/Surface. Do not dlopen split runtime siblings or the process
     * can end up with two independent copies of the same counters and hints. */
    mdiex_sched_init();
    mdiex_memshim_init();
    mdiex_ahb_init();
    mdiex_surface_init();
#else
    /* Split runtime modules are linked against libmdiex_core.so already.
     * Do not dlopen libmdiex_core.so here: on Android linker namespaces that
     * can create a second core instance, and duplicate state is the kind of
     * comedy only crash logs enjoy. */
    try_load("libmdiex_sched.so", 0);
    try_load("libmdiex_memshim.so", 0);
    try_load("libmdiex_ahb.so", 0);
    try_load("libmdiex_surface.so", 0);
#endif
#if MDIEX_HAS_NRAMV
    try_load("libnramv.so", 0);
#endif
#if MDIEX_HAS_HELIX
    try_load("libLayer_Helix.so", 0);
#endif
    mdiex_log_line("MdiExLoader", "loader initialized");
    return 1;
}

__attribute__((constructor)) static void mdiex_loader_ctor(void) { mdiex_loader_init(); }
