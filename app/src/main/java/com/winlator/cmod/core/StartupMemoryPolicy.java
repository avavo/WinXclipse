package com.winlator.cmod.core;

/** Bounds the optional Mali-inspired allocation pass. Never used during gameplay. */
public final class StartupMemoryPolicy {
    public static final long MIB = 1024L * 1024L;
    public static final long MAX_BYTES = 256 * MIB;
    public static final int MAX_MILLIS = 750;

    private StartupMemoryPolicy() {}

    public static long reserve(long total) {
        return Math.max(1024 * MIB, total / 8);
    }

    public static long budget(long total, long available, boolean lowMemory, boolean wineRunning) {
        if (total <= 0 || available <= 0 || available > total || lowMemory || wineRunning) return 0;
        long headroom = available - reserve(total);
        if (headroom <= 0) return 0;
        long budget = Math.min(MAX_BYTES, Math.min(headroom / 4, available / 16));
        return budget >= 16 * MIB ? budget / MIB * MIB : 0;
    }
}
