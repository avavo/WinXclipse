package com.winlator.cmod.core;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Java handle for the NRAMV unified-memory manager compiled into
 * libwinlator.
 *
 * The native flush() recomputes its own adaptive profile from live
 * RAM/PSI/GTT/thermal state, so the baseline chosen here is only the
 * starting trim level. Escalation happens through {@link #escalate()},
 * which applies the AGGRESSIVE profile immediately - the native flush that
 * follows may still pick a lighter profile if thermal/major-fault guards
 * kick in. Used by the HUD RAM alert before the internal thresholds would
 * catch up.
 */
public final class RamOptimizerXclipse {
    public static final int PROFILE_LIGHT = 0;
    public static final int PROFILE_MEDIUM = 1;
    public static final int PROFILE_AGGRESSIVE = 2;

    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "RamOptimizerXclipseDriver");
                t.setDaemon(true);
                return t;
            });

    private static volatile int baselineProfile = PROFILE_MEDIUM;

    /**
     * Generation of the current native session. shutdownFor(long) captures
     * it at request time and re-checks on the driver thread: a queued
     * shutdown from a destroyed activity must never kill the nativeInit of
     * a session that started afterwards (fast activity recreation), or the
     * whole new session silently degrades to ERR_INIT no-ops.
     */
    private static final AtomicLong SESSION_GEN = new AtomicLong(0);

    static {
        System.loadLibrary("winlator");
    }

    private RamOptimizerXclipse() {}

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

    /**
     * Shuts the native manager down on the driver thread. nativeShutdown must
     * take the native state lock, which an in-flight flush holds for its whole
     * compaction sweep; running it inline from onDestroy would block the UI
     * thread behind that sweep.
     *
     * Use {@link #beginSession()}/{@link #shutdownFor(long)} so a shutdown
     * enqueued by an outgoing activity is discarded if a new session has
     * already initialized.
     */
    public static void shutdown() {
        EXECUTOR.execute(RamOptimizerXclipse::nativeShutdown);
    }

    /** Starts a logical session and returns its generation token. */
    public static long beginSession() {
        return SESSION_GEN.incrementAndGet();
    }

    /** Like {@link #shutdown()}, but a no-op if a newer session began. */
    public static void shutdownFor(long sessionGen) {
        EXECUTOR.execute(() -> {
            if (sessionGen != SESSION_GEN.get()) return;
            nativeShutdown();
        });
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

