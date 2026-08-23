#include <jni.h>
#include "nramv.h"

/* Thin JNI bridge so the app process can drive the NRAMV unified-memory
 * manager directly from Java. The upstream RegisterNatives-based shim
 * (nramv_jni.c) targets a different class name and its own JNI_OnLoad,
 * which cannot coexist inside libwinlator. */

JNIEXPORT jint JNICALL
Java_com_winlator_cmod_core_RamOptimizerXclipse_nativeInit(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    return nramv_init();
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_core_RamOptimizerXclipse_nativeShutdown(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    nramv_shutdown();
}

JNIEXPORT jint JNICALL
Java_com_winlator_cmod_core_RamOptimizerXclipse_nativeApplyProfile(JNIEnv *env, jclass clazz, jint profile) {
    (void)env; (void)clazz;
    if (profile < NRAMV_PROFILE_LIGHT || profile > NRAMV_PROFILE_MAX_VALID)
        return NRAMV_ERR_PARAM;
    return nramv_apply_profile((nramv_profile_t) profile);
}

JNIEXPORT jint JNICALL
Java_com_winlator_cmod_core_RamOptimizerXclipse_nativeFlush(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    return nramv_flush();
}

JNIEXPORT jstring JNICALL
Java_com_winlator_cmod_core_RamOptimizerXclipse_nativeVersion(JNIEnv *env, jclass clazz) {
    (void)clazz;
    const char *version = nramv_version();
    return (*env)->NewStringUTF(env, version ? version : "");
}

