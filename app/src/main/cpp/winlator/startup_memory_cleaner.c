/* Mali-inspired prelaunch allocation pass.
 * Reference: GunaCharanTeja/WinlatorMali @ 428bd94 (MIT).
 * See assets/licenses/winlator-mali.txt. Bounds/cancellation are WinXclipse adaptations.
 */
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>

#define MIB (1024L * 1024L)
#define CHUNK_BYTES (8L * MIB)
#define MAX_CHUNKS 32

static int64_t monotonic_ms(void) {
    struct timespec time;
    if (clock_gettime(CLOCK_MONOTONIC, &time) != 0) return -1;
    return (int64_t)time.tv_sec * 1000 + time.tv_nsec / 1000000;
}

static int64_t available_bytes(void) {
    FILE *input = fopen("/proc/meminfo", "r");
    if (!input) return -1;
    char line[256];
    unsigned long long kb = 0;
    int found = 0;
    while (fgets(line, sizeof(line), input)) {
        if (sscanf(line, "MemAvailable: %llu kB", &kb) == 1) { found = 1; break; }
    }
    fclose(input);
    return found && kb <= INT64_MAX / 1024 ? (int64_t)kb * 1024 : -1;
}

static int should_stop(JNIEnv *env, jobject cancelled, jmethodID check, int64_t deadline) {
    int64_t now = monotonic_ms();
    if (now < 0 || now >= deadline) return 1;
    jboolean stopped = (*env)->CallBooleanMethod(env, cancelled, check);
    return (*env)->ExceptionCheck(env) || stopped;
}

JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_core_StartupMemoryCleaner_nativePrepare(
        JNIEnv *env, jclass clazz, jlong budget, jlong reserve, jint max_ms, jobject cancelled) {
    (void)clazz;
    if (!cancelled || budget <= 0 || budget > MAX_CHUNKS * CHUNK_BYTES
            || reserve < 1024L * MIB || max_ms <= 0 || max_ms > 750) return 0;
    jclass callback = (*env)->GetObjectClass(env, cancelled);
    jmethodID check = (*env)->GetMethodID(env, callback, "getAsBoolean", "()Z");
    (*env)->DeleteLocalRef(env, callback);
    if (!check) return 0;
    long page = sysconf(_SC_PAGESIZE);
    int64_t start = monotonic_ms();
    if (page <= 0 || start < 0) return 0;
    void *chunks[MAX_CHUNKS] = {0};
    size_t sizes[MAX_CHUNKS] = {0};
    int count = 0, stop = 0;
    int64_t allocated = 0, touched = 0;
    while (allocated < budget && count < MAX_CHUNKS
            && !should_stop(env, cancelled, check, start + max_ms)) {
        size_t size = (size_t)(budget - allocated);
        if (size > CHUNK_BYTES) size = CHUNK_BYTES;
        int64_t available = available_bytes();
        if (available < reserve + (int64_t)size) break;
        void *memory = mmap(NULL, size, PROT_READ | PROT_WRITE,
                            MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (memory == MAP_FAILED) break;
        chunks[count] = memory;
        sizes[count++] = size;
        allocated += (int64_t)size;
        // Only our new mappings are touched. No PAGEOUT, signals, priority
        // changes, allocator policy changes, or interaction with live Wine.
        for (size_t offset = 0; offset < size; offset += (size_t)page) {
            if (offset % MIB == 0 && should_stop(env, cancelled, check, start + max_ms)) {
                stop = 1;
                break;
            }
            ((volatile unsigned char *)memory)[offset] = 1;
            touched += (size - offset < (size_t)page) ? (int64_t)(size - offset) : page;
        }
        if (stop) break;
    }
    // Always release every owned mapping, including interrupted chunks.
    for (int i = 0; i < count; i++) munmap(chunks[i], sizes[i]);
    return touched;
}
