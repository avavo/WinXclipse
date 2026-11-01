// Opt-in allocator policy inside Wine and its Bionic child processes.
#include <malloc.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

__attribute__((constructor)) static void configure_guest_memory(void) {
    const char *enabled = getenv("WINXCLIPSE_RAM_AGGRESSIVE");
    if (!enabled || strcmp(enabled, "1")) return;
    int decay = mallopt(M_DECAY_TIME, 0);
    int cache = 0;
#ifdef M_CACHE_COUNT_MAX
    cache = mallopt(M_CACHE_COUNT_MAX, 0);
#endif
    int purge = mallopt(M_PURGE, 0);
    __android_log_print(ANDROID_LOG_INFO, "GuestMemory", "Unused-page release: decay=%d cache=%d purge=%d", decay, cache, purge);
}
