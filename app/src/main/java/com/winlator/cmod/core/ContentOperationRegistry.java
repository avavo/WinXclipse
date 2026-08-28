package com.winlator.cmod.core;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-wide accounting for content downloads/installations.  Operations
 * may continue while the user navigates through the Android UI, but entering
 * Wine is blocked while files that a container can consume are being changed.
 */
public final class ContentOperationRegistry {
    public enum Kind { DOWNLOAD, INSTALL }

    private static final AtomicInteger downloads = new AtomicInteger();
    private static final AtomicInteger installs = new AtomicInteger();
    private static final CopyOnWriteArrayList<Runnable> idleCallbacks =
            new CopyOnWriteArrayList<>();

    private ContentOperationRegistry() {}

    public static Token begin(Kind kind) {
        (kind == Kind.DOWNLOAD ? downloads : installs).incrementAndGet();
        return new Token(kind);
    }

    public static boolean hasActiveOperations() {
        return downloads.get() > 0 || installs.get() > 0;
    }

    public static int getActiveDownloads() {
        return downloads.get();
    }

    public static int getActiveInstalls() {
        return installs.get();
    }

    public static String describe() {
        return getActiveDownloads() + " download(s), " + getActiveInstalls() + " installation(s)";
    }

    /** Runs once all content writes have completed. Safe to call from the UI thread. */
    public static void runWhenIdle(Runnable callback) {
        if (callback == null) return;
        if (!hasActiveOperations()) {
            callback.run();
            return;
        }
        idleCallbacks.add(callback);
        // Close the race where the final operation ended immediately before add().
        dispatchIdleCallbacks();
    }

    private static void dispatchIdleCallbacks() {
        if (hasActiveOperations()) return;
        for (Runnable callback : idleCallbacks) {
            if (!idleCallbacks.remove(callback)) continue;
            try { callback.run(); }
            catch (RuntimeException ignored) {}
        }
    }

    public static final class Token implements AutoCloseable {
        private final Kind kind;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Token(Kind kind) {
            this.kind = kind;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            AtomicInteger counter = kind == Kind.DOWNLOAD ? downloads : installs;
            counter.updateAndGet(value -> Math.max(0, value - 1));
            dispatchIdleCallbacks();
        }
    }
}
