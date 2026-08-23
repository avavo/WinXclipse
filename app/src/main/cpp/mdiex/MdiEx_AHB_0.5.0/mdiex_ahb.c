#include "mdiex_ahb.h"
#include "mdiex_core.h"
#include <stdatomic.h>

static atomic_ullong g_live = 0;
static atomic_ullong g_total_allocs = 0;
static atomic_ullong g_total_releases = 0;
static atomic_int g_ahb_ready = 0;

void mdiex_ahb_record_allocate(unsigned int width, unsigned int height, unsigned int layers) {
    if (width == 0 || height == 0 || layers == 0) {
        mdiex_log_line("MdiExAHB", "ignored invalid AHB allocation with zero extent/layers");
        return;
    }
    atomic_fetch_add(&g_total_allocs, 1);
    atomic_fetch_add(&g_live, 1);
    mdiex_send_hint(MDIEX_HINT_TEXTURE_CACHE_PRESSURE);
}
void mdiex_ahb_record_release(void) {
    unsigned long long prev = atomic_load(&g_live);
    while (prev != 0) {
        if (atomic_compare_exchange_weak(&g_live, &prev, prev - 1)) {
            atomic_fetch_add(&g_total_releases, 1);
            return;
        }
    }
}
unsigned long long mdiex_ahb_get_live_estimate(void) { return atomic_load(&g_live); }

int mdiex_ahb_init(void) {
    if (atomic_exchange(&g_ahb_ready, 1)) return 1;
    mdiex_init_from_env_or_auto();
    mdiex_log_line("MdiExAHB", "AHB monitor initialized");
    return 1;
}
__attribute__((constructor)) static void mdiex_ahb_ctor(void) { mdiex_ahb_init(); }
