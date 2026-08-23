#include <jni.h>
#include "wxp_core.h"

/* Exposes the per-Xclipse policy profile to Java as the WinXclipse policy bridge. The same
 * information is published as MDIEX_PROFILE for the guest-side driver
 * stack; here the session code reads it before assembling guest env vars. */

JNIEXPORT jstring JNICALL
Java_com_winlator_cmod_core_WinXclipsePolicy_nativeProfileName(JNIEnv *env, jclass clazz) {
    (void)clazz;
    wxp_init_from_env_or_auto();
    const char *profile = wxp_get_profile_name();
    return (*env)->NewStringUTF(env, profile ? profile : "native_fallback");
}

