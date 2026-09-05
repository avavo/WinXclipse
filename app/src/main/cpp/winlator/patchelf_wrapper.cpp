#include <jni.h>

// NOTE: the patchelf bridge is intentionally unimplemented (nothing in the app
// uses PatchElf). Every entry point returns a safe "failure" default so the
// Java side degrades gracefully instead of reading undefined garbage off the
// stack (falling off a non-void JNI function is undefined behavior and used to
// fake success, e.g. a non-zero handle from createElfObject).

extern "C"
JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_core_PatchElf_createElfObject(JNIEnv *env, jobject thiz, jstring path) {
    (void)env; (void)thiz; (void)path;
    return 0;
}
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_core_PatchElf_destroyElfObject(JNIEnv *env, jobject thiz, jlong object_ptr) {
    (void)env; (void)thiz; (void)object_ptr;
    return JNI_FALSE;
}
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_core_PatchElf_isChanged(JNIEnv *env, jobject thiz, jlong object_ptr) {
    (void)env; (void)thiz; (void)object_ptr;
    return JNI_FALSE;
}
extern "C"
JNIEXPORT jstring JNICALL
Java_com_winlator_cmod_core_PatchElf_getInterpreter(JNIEnv *env, jobject thiz, jlong object_ptr) {
    (void)env; (void)thiz; (void)object_ptr;
    return nullptr;
}
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_core_PatchElf_setInterpreter(JNIEnv *env, jobject thiz, jlong object_ptr,
                                               jstring interpreter) {
    (void)env; (void)thiz; (void)object_ptr; (void)interpreter;
    return JNI_FALSE;
}
extern "C"
JNIEXPORT jstring JNICALL
Java_com_winlator_cmod_core_PatchElf_getOsAbi(JNIEnv *env, jobject thiz, jlong object_ptr) {
    (void)env; (void)thiz; (void)object_ptr;
    return nullptr;
}
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_core_PatchElf_replaceOsAbi(JNIEnv *env, jobject thiz, jlong object_ptr,
                                             jstring os_abi) {
    (void)env; (void)thiz; (void)object_ptr; (void)os_abi;
    return JNI_FALSE;
}
extern "C"
JNIEXPORT jstring JNICALL
Java_com_winlator_cmod_core_PatchElf_getSoName(JNIEnv *env, jobject thiz, jlong object_ptr) {
    (void)env; (void)thiz; (void)object_ptr;
    return nullptr;
}
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_core_PatchElf_replaceSoName(JNIEnv *env, jobject thiz, jlong object_ptr,
                                              jstring so_name) {
    (void)env; (void)thiz; (void)object_ptr; (void)so_name;
    return JNI_FALSE;
}
extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_winlator_cmod_core_PatchElf_getRPath(JNIEnv *env, jobject thiz, jlong object_ptr) {
    (void)env; (void)thiz; (void)object_ptr;
    return nullptr;
}
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_core_PatchElf_addRPath(JNIEnv *env, jobject thiz, jlong object_ptr,
                                         jstring rpath) {
    (void)env; (void)thiz; (void)object_ptr; (void)rpath;
    return JNI_FALSE;
}
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_core_PatchElf_removeRPath(JNIEnv *env, jobject thiz, jlong object_ptr,
                                            jstring rpath) {
    (void)env; (void)thiz; (void)object_ptr; (void)rpath;
    return JNI_FALSE;
}
extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_winlator_cmod_core_PatchElf_getNeeded(JNIEnv *env, jobject thiz, jlong object_ptr) {
    (void)env; (void)thiz; (void)object_ptr;
    return nullptr;
}
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_core_PatchElf_addNeeded(JNIEnv *env, jobject thiz, jlong object_ptr,
                                          jstring needed) {
    (void)env; (void)thiz; (void)object_ptr; (void)needed;
    return JNI_FALSE;
}
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_core_PatchElf_removeNeeded(JNIEnv *env, jobject thiz, jlong object_ptr,
                                             jstring needed) {
    (void)env; (void)thiz; (void)object_ptr; (void)needed;
    return JNI_FALSE;
}
