package com.winlator.cmod.core;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Java handle for the NRAMV unified-memory manager compiled into
 * libwinlator.
 *
 * The native flush() recomputes its own adaptive profile from live
 * RAM/PSI/GTT/thermal state, so the baseline chosen here is only the
 * starting trim level. Escalation happens through {@link #escalate()},
 * which forces the AGGRESSIVE mallopt/compaction pass immediately - used
 * by the HUD RAM alert before the internal thresholds would catch up.
 */
public final class Nramv {
    public static final int PROFILE_LIGHT = 0;
    public static final int PROFILE_MEDIUM = 1;
    public static final int PROFILE_AGGRESSIVE = 2;

    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "NramvDriver");
                t.setDaemon(true);
                return t;
            });

    private static volatile int baselineProfile = PROFILE_MEDIUM;

    static {
        System.loadLibrary("winlator");
    }

    private Nramv() {}

    public static void setBaselineProfile(int profile) {
        baselineProfile = profile;
    }

    /** Forces the AGGRESSIVE pass right now (off the UI thread). */
    public static void escalate() {
        EXECUTOR.execute(() -> {
            nativeApplyProfile(PROFILE_AGGRESSIVE);
            nativeFlush();
        });
    }

    /** Returns to the session baseline once pressure subsides. */
    public static void restoreBaseline() {
        EXECUTOR.execute(() -> nativeApplyProfile(baselineProfile));
    }

    public static int initBaseline() {
        EXECUTOR.execute(() -> nativeApplyProfile(baselineProfile));
        return nativeInit();
    }

    public static native int nativeInit();
    public static native void nativeShutdown();
    public static native int nativeApplyProfile(int profile);
    public static native int nativeFlush();
    public static native String nativeVersion();
}
