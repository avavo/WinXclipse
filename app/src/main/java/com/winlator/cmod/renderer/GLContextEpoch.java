package com.winlator.cmod.renderer;

/** Each GLSurfaceView context creation gets a fresh identity, even on a reused GL thread. */
public final class GLContextEpoch {
    private static final java.util.concurrent.atomic.AtomicLong NEXT = new java.util.concurrent.atomic.AtomicLong();
    private static final ThreadLocal<Long> CURRENT = ThreadLocal.withInitial(() -> 0L);
    private GLContextEpoch() {}
    public static long current() { return CURRENT.get(); }
    public static void beginContext() { CURRENT.set(NEXT.incrementAndGet()); }
}
