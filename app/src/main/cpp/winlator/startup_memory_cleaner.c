#include <jni.h>
#include <malloc.h>

/* Release allocator-owned unused pages, with no allocation-pressure pass. */
JNIEXPORT jint JNICALL
Java_com_winlator_cmod_core_StartupMemoryCleaner_nativeTrim(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    int accepted = 0;
#ifdef M_PURGE_ALL
    accepted = mallopt(M_PURGE_ALL, 0);
#endif
#ifdef M_PURGE
    if (!accepted) accepted = mallopt(M_PURGE, 0);
#endif
    return accepted;
}
