#include <jni.h>
#include "mdiex_core.h"

/* Exposes the MdiEx per-Xclipse policy profile to Java. The same
 * information is published as MDIEX_PROFILE for the guest-side driver
 * stack; here the session code reads it before assembling guest env vars. */

JNIEXPORT jstring JNICALL
Java_com_winlator_cmod_core_MdiExBridge_nativeProfileName(JNIEnv *env, jclass clazz) {
    (void)clazz;
    mdiex_init_from_env_or_auto();
    const char *profile = mdiex_get_profile_name();
    return (*env)->NewStringUTF(env, profile ? profile : "native_fallback");
}
