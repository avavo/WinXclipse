#include "mdiex_surface.h"
#include "mdiex_core.h"

#include <stdatomic.h>
#include <stdint.h>
#include <time.h>
#include <pthread.h>

static atomic_ullong g_frames = 0;
static atomic_ullong g_last_frame_ns = 0;
static atomic_ullong g_avg_delta_ns = 0;
static atomic_ullong g_jitter_ns = 0;
static atomic_uint g_last_width = 0;
static atomic_uint g_last_height = 0;
static atomic_int g_surface_ready = 0;
static pthread_mutex_t g_pacing_lock = PTHREAD_MUTEX_INITIALIZER;

static unsigned long long now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (unsigned long long)ts.tv_sec * 1000000000ull +
           (unsigned long long)ts.tv_nsec;
}

void mdiex_surface_record_frame(unsigned int width, unsigned int height) {
    const unsigned long long now = now_ns();

    pthread_mutex_lock(&g_pacing_lock);
    const unsigned long long prev = atomic_load(&g_last_frame_ns);
    atomic_store(&g_last_frame_ns, now);

    if (width != 0 && height != 0) {
        atomic_store(&g_last_width, width);
        atomic_store(&g_last_height, height);
    }
    atomic_fetch_add(&g_frames, 1);

    if (prev != 0 && now > prev) {
        const unsigned long long delta = now - prev;
        unsigned long long avg = atomic_load(&g_avg_delta_ns);
        if (avg == 0) avg = delta;
        else avg = ((avg * 7ull) + delta) / 8ull;
        atomic_store(&g_avg_delta_ns, avg);

        const unsigned long long diff = (delta > avg) ? (delta - avg) : (avg - delta);
        unsigned long long jitter = atomic_load(&g_jitter_ns);
        if (jitter == 0) jitter = diff;
        else jitter = ((jitter * 7ull) + diff) / 8ull;
        atomic_store(&g_jitter_ns, jitter);
    }
    pthread_mutex_unlock(&g_pacing_lock);
}

unsigned long long mdiex_surface_get_frame_count(void) { return atomic_load(&g_frames); }
unsigned long long mdiex_surface_get_avg_frame_delta_ns(void) { return atomic_load(&g_avg_delta_ns); }
unsigned long long mdiex_surface_get_jitter_ns(void) { return atomic_load(&g_jitter_ns); }

int mdiex_surface_get_frame_pacing_state(void) {
    unsigned long long avg = atomic_load(&g_avg_delta_ns);
    unsigned long long jitter = atomic_load(&g_jitter_ns);
    if (avg == 0) return MDIEX_SURFACE_PACING_UNKNOWN;
    if (jitter > avg / 2ull) return MDIEX_SURFACE_PACING_BAD;
    if (jitter > avg / 4ull) return MDIEX_SURFACE_PACING_UNSTABLE;
    return MDIEX_SURFACE_PACING_STABLE;
}

void mdiex_surface_get_last_extent(unsigned int* width, unsigned int* height) {
    if (width) *width = atomic_load(&g_last_width);
    if (height) *height = atomic_load(&g_last_height);
}

int mdiex_surface_init(void) {
    if (atomic_exchange(&g_surface_ready, 1)) return 1;
    mdiex_init_from_env_or_auto();
    mdiex_log_line("MdiExSurface", "surface monitor initialized with frame pacing state");
    return 1;
}
__attribute__((constructor)) static void mdiex_surface_ctor(void) { mdiex_surface_init(); }
