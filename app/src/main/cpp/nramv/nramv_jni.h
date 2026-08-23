#ifndef NRAMV_JNI_H
#define NRAMV_JNI_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Java/Kotlin side expected class:
 *   package com.nramv;
 *   final class NramvDriver
 *
 * Registered native methods:
 *   static native int    nramvInit();
 *   static native void   nramvShutdown();
 *   static native int    nramvApplyProfile(int profile);
 *   static native int    nramvFlush();
 *   static native long[] nramvGetStats();
 *   static native String nramvVersion();
 */
#ifndef NRAMV_JNI_CLASS
#define NRAMV_JNI_CLASS "com/nramv/NramvDriver"
#endif

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved);
JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved);

#ifdef __cplusplus
}
#endif

#endif /* NRAMV_JNI_H */
