#include <jni.h>
#include "mdiex_core.h"
#include "mdiex_xclipse_policy.h"

/* Exposes the MdiEx device classification and per-Xclipse policy to Java.
 * The same information is published as MDIEX_PROFILE for the guest-side
 * driver stack; here we let the UI/session code read it too. */

JNIEXPORT jstring JNICALL
Java_com_winlator_cmod_core_MdiExBridge_nativeProfileName(JNIEnv *env, jclass clazz) {
    (void)clazz;
    mdiex_init_from_env_or_auto();
    const char *profile = mdiex_get_profile_name();
    return (*env)->NewStringUTF(env, profile ? profile : "native_fallback");
}

JNIEXPORT jstring JNICALL
Java_com_winlator_cmod_core_MdiExBridge_nativeSocName(JNIEnv *env, jclass clazz) {
    (void)clazz;
    mdiex_init_from_env_or_auto();
    const char *soc = mdiex_get_soc_name();
    return (*env)->NewStringUTF(env, soc ? soc : "unknown");
}

JNIEXPORT jint JNICALL
Java_com_winlator_cmod_core_MdiExBridge_nativeRamGB(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    mdiex_init_from_env_or_auto();
    return (jint) mdiex_get_ram_gb();
}

JNIEXPORT jstring JNICALL
Java_com_winlator_cmod_core_MdiExBridge_nativePolicySummary(JNIEnv *env, jclass clazz) {
    (void)clazz;
    char summary[512];
    if (!mdiex_xclipse_policy_summary(summary, sizeof(summary)))
        return (*env)->NewStringUTF(env, "");
    return (*env)->NewStringUTF(env, summary);
}
