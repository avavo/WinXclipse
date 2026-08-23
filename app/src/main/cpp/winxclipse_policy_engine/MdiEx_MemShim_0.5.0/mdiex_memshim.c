#include "mdiex_memshim.h"
#include "mdiex_core.h"
#include "mdiex_xclipse_policy.h"

#include <stdatomic.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <dlfcn.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <pthread.h>
#include <errno.h>
#include <string.h>
#include <strings.h>
#include <stdio.h>

#ifndef MDIEX_MEMSHIM_ENABLE_INTERPOSE_SYMBOLS
#define MDIEX_MEMSHIM_ENABLE_INTERPOSE_SYMBOLS 1
#endif

static atomic_ullong g_large_allocs = 0;
static atomic_ullong g_malloc_calls = 0;
static atomic_ullong g_mmap_calls = 0;
static atomic_int g_memshim_ready = 0;
static atomic_int g_interpose_enabled = 0;
static atomic_int g_fragmentation_pressure_ticks = 0;

static void mdiex_memshim_poll_fragmentation_hints(void) {
    int pressure = 0;
    pressure += mdiex_consume_hint(MDIEX_HINT_GPU_MEMORY_FRAGMENTATION);
    pressure += mdiex_consume_hint(MDIEX_HINT_GPU_SMALL_ALLOC_CHURN);
    pressure += mdiex_consume_hint(MDIEX_HINT_GPU_HEAP_PRESSURE);
    if (pressure > 0) {
        atomic_store(&g_fragmentation_pressure_ticks, 32);
        mdiex_send_hint(MDIEX_HINT_MEMORY_PRESSURE);
        return;
    }

    int old = atomic_load(&g_fragmentation_pressure_ticks);
    while (old > 0 &&
           !atomic_compare_exchange_weak(&g_fragmentation_pressure_ticks, &old, old - 1)) {
        /* retry with updated old */
    }
}


static size_t mdiex_memshim_large_alloc_threshold(void) {
    size_t threshold = (size_t)4u * 1024u * 1024u;
    mdiex_memshim_poll_fragmentation_hints();
    const int pressure_ticks = atomic_load(&g_fragmentation_pressure_ticks);
    MdiExXclipsePolicy policy;
    if (!mdiex_get_xclipse_policy(&policy)) {
        return pressure_ticks > 0 ? (size_t)2u * 1024u * 1024u : threshold;
    }

    if (policy.ram_gb > 0 && policy.ram_gb < 8) {
        threshold = (size_t)2u * 1024u * 1024u;
    } else if (policy.thermal == MDIEX_THERMAL_TIGHT ||
               policy.bandwidth == MDIEX_BANDWIDTH_LOW ||
               policy.bandwidth == MDIEX_BANDWIDTH_CRITICAL ||
               policy.avoid_aggressive_upload) {
        threshold = (size_t)3u * 1024u * 1024u;
    } else if (policy.ram_gb >= 12 && policy.bandwidth == MDIEX_BANDWIDTH_HIGH &&
               policy.thermal == MDIEX_THERMAL_RELAXED) {
        threshold = (size_t)8u * 1024u * 1024u;
    } else if (policy.ram_gb >= 12 && policy.bandwidth == MDIEX_BANDWIDTH_HIGH) {
        threshold = (size_t)6u * 1024u * 1024u;
    }

    if (pressure_ticks > 0 && threshold > (size_t)2u * 1024u * 1024u) {
        threshold /= 2u;
        if (threshold < (size_t)2u * 1024u * 1024u)
            threshold = (size_t)2u * 1024u * 1024u;
    }
    return threshold;
}


#if MDIEX_MEMSHIM_ENABLE_INTERPOSE_SYMBOLS
static atomic_int g_symbols_resolved = 0;
static atomic_int g_resolving = 0;
static atomic_uint g_emergency_count = 0;

static pthread_mutex_t g_resolve_lock = PTHREAD_MUTEX_INITIALIZER;
static __thread int g_in_hook = 0;

static void* (*real_malloc_fn)(size_t) = 0;
static void* (*real_calloc_fn)(size_t, size_t) = 0;
static void* (*real_realloc_fn)(void*, size_t) = 0;
static void  (*real_free_fn)(void*) = 0;
static void* (*real_mmap_fn)(void*, size_t, int, int, int, off_t) = 0;
static int   (*real_munmap_fn)(void*, size_t) = 0;

#define MDIEX_EMERGENCY_MAGIC 0x4d454d4552473336ull

typedef struct MdiExEmergencyHeader {
    uint64_t magic;
    size_t total_size;
    size_t user_size;
    struct MdiExEmergencyHeader* next;
} MdiExEmergencyHeader;

static pthread_mutex_t g_emergency_lock = PTHREAD_MUTEX_INITIALIZER;
static MdiExEmergencyHeader* g_emergency_head = NULL;

static size_t page_size_safe(void) {
    long p = sysconf(_SC_PAGESIZE);
    return (p > 0) ? (size_t)p : 4096u;
}

static void* emergency_alloc(size_t size) {
    size_t page = page_size_safe();
    size_t need = sizeof(MdiExEmergencyHeader) + size;
    if (need < size) return NULL;
    size_t total = (need + page - 1u) & ~(page - 1u);

#ifdef SYS_mmap
    void* mem = (void*)syscall(SYS_mmap, NULL, total,
                               PROT_READ | PROT_WRITE,
                               MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
#else
    void* mem = MAP_FAILED;
#endif
    if (mem == MAP_FAILED) return NULL;

    MdiExEmergencyHeader* h = (MdiExEmergencyHeader*)mem;
    h->magic = MDIEX_EMERGENCY_MAGIC;
    h->total_size = total;
    h->user_size = size;

    pthread_mutex_lock(&g_emergency_lock);
    h->next = g_emergency_head;
    g_emergency_head = h;
    atomic_fetch_add(&g_emergency_count, 1u);
    pthread_mutex_unlock(&g_emergency_lock);

    return (void*)(h + 1);
}

static MdiExEmergencyHeader* emergency_find_locked(void* ptr, MdiExEmergencyHeader*** prev_next_out) {
    MdiExEmergencyHeader** pp = &g_emergency_head;
    while (*pp) {
        MdiExEmergencyHeader* h = *pp;
        if ((void*)(h + 1) == ptr) {
            if (prev_next_out) *prev_next_out = pp;
            return h;
        }
        pp = &h->next;
    }
    return NULL;
}

static int emergency_free(void* ptr) {
    if (!ptr) return 1;

    pthread_mutex_lock(&g_emergency_lock);
    MdiExEmergencyHeader** prev_next = NULL;
    MdiExEmergencyHeader* h = emergency_find_locked(ptr, &prev_next);
    if (!h || h->magic != MDIEX_EMERGENCY_MAGIC) {
        pthread_mutex_unlock(&g_emergency_lock);
        return 0;
    }
    if (prev_next) *prev_next = h->next;
    atomic_fetch_sub(&g_emergency_count, 1u);
    size_t total = h->total_size;
    h->magic = 0;
    pthread_mutex_unlock(&g_emergency_lock);
#ifdef SYS_munmap
    (void)syscall(SYS_munmap, (void*)h, total);
#endif
    return 1;
}

static size_t emergency_size(void* ptr) {
    if (!ptr) return 0;
    pthread_mutex_lock(&g_emergency_lock);
    MdiExEmergencyHeader* h = emergency_find_locked(ptr, NULL);
    size_t out = (h && h->magic == MDIEX_EMERGENCY_MAGIC) ? h->user_size : 0;
    pthread_mutex_unlock(&g_emergency_lock);
    return out;
}

static int memshim_parse_interpose_env(void) {
    const char* v = getenv("MDIEX_MEMSHIM_INTERPOSE");
    return v && (strcmp(v, "1") == 0 || strcasecmp(v, "true") == 0 || strcasecmp(v, "on") == 0);
}

static int memshim_interpose_enabled(void) {
    return atomic_load(&g_interpose_enabled) != 0;
}

static void resolve_symbols(void) {
    if (atomic_load(&g_symbols_resolved)) return;

    if (g_in_hook || atomic_load(&g_resolving)) return;

    pthread_mutex_lock(&g_resolve_lock);
    if (atomic_load(&g_symbols_resolved)) {
        pthread_mutex_unlock(&g_resolve_lock);
        return;
    }

    atomic_store(&g_resolving, 1);
    g_in_hook++;
    real_malloc_fn = (void* (*)(size_t))dlsym(RTLD_NEXT, "malloc");
    real_calloc_fn = (void* (*)(size_t, size_t))dlsym(RTLD_NEXT, "calloc");
    real_realloc_fn = (void* (*)(void*, size_t))dlsym(RTLD_NEXT, "realloc");
    real_free_fn = (void (*)(void*))dlsym(RTLD_NEXT, "free");
    real_mmap_fn = (void* (*)(void*, size_t, int, int, int, off_t))dlsym(RTLD_NEXT, "mmap");
    real_munmap_fn = (int (*)(void*, size_t))dlsym(RTLD_NEXT, "munmap");
    g_in_hook--;
    atomic_store(&g_resolving, 0);

    if (real_malloc_fn && real_calloc_fn && real_realloc_fn && real_free_fn && real_mmap_fn && real_munmap_fn) {
        atomic_store(&g_symbols_resolved, 1);
    }
    pthread_mutex_unlock(&g_resolve_lock);
}

#endif /* MDIEX_MEMSHIM_ENABLE_INTERPOSE_SYMBOLS */

void mdiex_memshim_record_alloc(size_t size) {
    if (size >= mdiex_memshim_large_alloc_threshold()) {
        atomic_fetch_add(&g_large_allocs, 1);
        mdiex_send_hint(MDIEX_HINT_MEMORY_PRESSURE);
    }
}

unsigned long long mdiex_memshim_get_large_alloc_count(void) {
    return atomic_load(&g_large_allocs);
}

unsigned long long mdiex_memshim_get_malloc_call_count(void) {
    return atomic_load(&g_malloc_calls);
}

unsigned long long mdiex_memshim_get_mmap_call_count(void) {
    return atomic_load(&g_mmap_calls);
}

#if MDIEX_MEMSHIM_ENABLE_INTERPOSE_SYMBOLS
void* malloc(size_t size) {
    if (!real_malloc_fn && (g_in_hook || atomic_load(&g_resolving)))
        return emergency_alloc(size);
    if (!real_malloc_fn) resolve_symbols();
    if (!real_malloc_fn) return emergency_alloc(size);

    void* p = real_malloc_fn(size);
    if (p && !g_in_hook && memshim_interpose_enabled()) {
        atomic_fetch_add(&g_malloc_calls, 1);
        mdiex_memshim_record_alloc(size);
    }
    return p;
}

void* calloc(size_t nmemb, size_t size) {
    if (size != 0 && nmemb > SIZE_MAX / size) return NULL;
    size_t total = nmemb * size;

    if (!real_calloc_fn && (g_in_hook || atomic_load(&g_resolving))) {
        void* p = emergency_alloc(total);
        if (p) memset(p, 0, total);
        return p;
    }
    if (!real_calloc_fn) resolve_symbols();
    if (!real_calloc_fn) {
        void* p = emergency_alloc(total);
        if (p) memset(p, 0, total);
        return p;
    }

    void* p = real_calloc_fn(nmemb, size);
    if (p && !g_in_hook && memshim_interpose_enabled()) {
        atomic_fetch_add(&g_malloc_calls, 1);
        mdiex_memshim_record_alloc(total);
    }
    return p;
}

void* realloc(void* ptr, size_t size) {
    if (!ptr) return malloc(size);
    if (size == 0) { free(ptr); return NULL; }

    size_t old_emergency = emergency_size(ptr);
    if (old_emergency) {
        void* np = malloc(size);
        if (np) {
            memcpy(np, ptr, old_emergency < size ? old_emergency : size);
            emergency_free(ptr);
        }
        return np;
    }

    if (!real_realloc_fn && (g_in_hook || atomic_load(&g_resolving)))
        return NULL;
    if (!real_realloc_fn) resolve_symbols();
    if (!real_realloc_fn) return NULL;

    void* p = real_realloc_fn(ptr, size);
    if (p && !g_in_hook && memshim_interpose_enabled()) {
        atomic_fetch_add(&g_malloc_calls, 1);
        mdiex_memshim_record_alloc(size);
    }
    return p;
}

void free(void* ptr) {
    if (!ptr) return;
    if (atomic_load(&g_emergency_count) && emergency_free(ptr)) return;
    if (!real_free_fn && (g_in_hook || atomic_load(&g_resolving))) return;
    if (!real_free_fn) resolve_symbols();
    if (real_free_fn) real_free_fn(ptr);
}

void* mmap(void* addr, size_t length, int prot, int flags, int fd, off_t offset) {
    if (!real_mmap_fn && (g_in_hook || atomic_load(&g_resolving))) {
#ifdef SYS_mmap
        void* p = (void*)syscall(SYS_mmap, addr, length, prot, flags, fd, offset);
        return (p == MAP_FAILED) ? MAP_FAILED : p;
#else
        return MAP_FAILED;
#endif
    }
    if (!real_mmap_fn) resolve_symbols();
    if (!real_mmap_fn) return MAP_FAILED;
    void* p = real_mmap_fn(addr, length, prot, flags, fd, offset);
    if (p != MAP_FAILED && !g_in_hook && memshim_interpose_enabled()) {
        atomic_fetch_add(&g_mmap_calls, 1);
        mdiex_memshim_record_alloc(length);
    }
    return p;
}

int munmap(void* addr, size_t length) {
    if (!real_munmap_fn && (g_in_hook || atomic_load(&g_resolving))) {
#ifdef SYS_munmap
        return (int)syscall(SYS_munmap, addr, length);
#else
        return -1;
#endif
    }
    if (!real_munmap_fn) resolve_symbols();
    if (!real_munmap_fn) return -1;
    return real_munmap_fn(addr, length);
}

#endif /* MDIEX_MEMSHIM_ENABLE_INTERPOSE_SYMBOLS */

int mdiex_memshim_init(void) {
    if (atomic_exchange(&g_memshim_ready, 1)) return 1;
#if MDIEX_MEMSHIM_ENABLE_INTERPOSE_SYMBOLS
    atomic_store(&g_interpose_enabled, memshim_parse_interpose_env());
    resolve_symbols();
    const char* msg = memshim_interpose_enabled()
        ? "memory shim initialized with guarded malloc/mmap interpose enabled"
        : "memory shim initialized in passive mode; set MDIEX_MEMSHIM_INTERPOSE=1 for LD_PRELOAD hooks";
#else
    atomic_store(&g_interpose_enabled, 0);
    const char* msg = "memory monitor initialized inside monolithic runtime; malloc/mmap interpose symbols disabled";
#endif
    mdiex_init_from_env_or_auto();
    char policy_msg[192];
    snprintf(policy_msg, sizeof(policy_msg), "%s; large_alloc_threshold=%zu", msg,
             mdiex_memshim_large_alloc_threshold());
    mdiex_log_line("MdiExMemShim", policy_msg);
    return 1;
}

__attribute__((constructor)) static void mdiex_memshim_ctor(void) { mdiex_memshim_init(); }
