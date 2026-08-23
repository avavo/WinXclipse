package com.winlator.cmod.core;

/**
 * Java handle for the NRAMV unified-memory manager compiled into
 * libwinlator. Profiles escalate from LIGHT (conservative cleanup) to
 * AGGRESSIVE (proactive reclaim on 12 GB-class flagships).
 */
public final class Nramv {
    public static final int PROFILE_LIGHT = 0;
    public static final int PROFILE_MEDIUM = 1;
    public static final int PROFILE_AGGRESSIVE = 2;

    static {
        System.loadLibrary("winlator");
    }

    private Nramv() {}

    public static native int nativeInit();
    public static native void nativeShutdown();
    public static native int nativeApplyProfile(int profile);
    public static native int nativeFlush();
    public static native long[] nativeGetStats();
    public static native String nativeVersion();
}
