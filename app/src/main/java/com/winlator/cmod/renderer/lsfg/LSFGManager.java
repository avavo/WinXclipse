package com.winlator.cmod.renderer.lsfg;

import com.winlator.cmod.renderer.GLRenderer;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/** Chooses real/generated frames and derives a stable interpolation cadence. */
public final class LSFGManager {
    private static final float DEFAULT_DELTA_NANOS = 1_000_000_000.0f / 30.0f;
    public static final int BACKEND_GLES = 0;
    public static final int BACKEND_NATIVE = 1;
    public static final int BACKEND_AUTO = 2;

    private final GLRenderer renderer;
    private final float[] deltaHistory = new float[8];
    private final AtomicInteger generatedFrameCount = new AtomicInteger();
    private final AtomicInteger actualRealFrameCount = new AtomicInteger();
    private final AtomicInteger gameFrameCount = new AtomicInteger();

    private volatile boolean active;
    private volatile boolean pendingRealFrame;
    /** 0 means automatic mode driven by targetFPS; otherwise fixed 1.5x-10x. */
    private float requestedMultiplier;
    private float effectiveMultiplier = 2.0f;
    private int targetFPS = 60;
    private int realFramesCaptured;
    private int framesSinceReal;
    private int historyIndex;
    private float typicalDeltaNanos = DEFAULT_DELTA_NANOS;
    private long lastRealFrameTimeNanos;
    private long pendingRealFrameTimeNanos;
    private long presentedRealFrameTimeNanos;
    private boolean renderingGeneratedFrame;
    private boolean presentedRealFrame;
    private boolean lowLatencyMode;
    private float estimatedLatencyNanos;
    private volatile int backendMode = BACKEND_GLES;
    private volatile String backendName = "GLES";
    private volatile String backendState = "Off";
    private volatile String backendFailure = "";

    public LSFGManager(GLRenderer renderer) {
        this.renderer = renderer;
        Arrays.fill(deltaHistory, DEFAULT_DELTA_NANOS);
    }

    public void setEnabled(boolean enabled) {
        if (active == enabled) return;
        active = enabled;
        resetTiming();
        if (enabled) {
            backendState = "Starting";
            backendFailure = "";
        }
        else if (backendFailure.isEmpty()) backendState = "Off";
        renderer.xServerView.setApexMode(enabled);
        if (enabled) renderer.startApexChoreographer();
        else renderer.stopApexChoreographer();
    }

    private void resetTiming() {
        resetFrameCounts();
        realFramesCaptured = 0;
        framesSinceReal = 0;
        typicalDeltaNanos = DEFAULT_DELTA_NANOS;
        lastRealFrameTimeNanos = 0;
        pendingRealFrameTimeNanos = 0;
        presentedRealFrameTimeNanos = 0;
        presentedRealFrame = false;
        estimatedLatencyNanos = 0;
        effectiveMultiplier = requestedMultiplier > 0.0f ? requestedMultiplier : 2.0f;
        historyIndex = 0;
        pendingRealFrame = false;
        renderingGeneratedFrame = false;
        Arrays.fill(deltaHistory, DEFAULT_DELTA_NANOS);
    }

    public void resetTimingState() {
        resetTiming();
    }

    public boolean isActive() {
        return active;
    }

    public float getMultiplier() {
        return effectiveMultiplier;
    }

    public void setMultiplier(float multiplier) {
        requestedMultiplier = multiplier >= 1.5f
                ? Math.min(10.0f, multiplier) : 0.0f;
        effectiveMultiplier = requestedMultiplier > 0.0f
                ? requestedMultiplier : Math.max(1.0f, effectiveMultiplier);
    }

    public float getRequestedMultiplier() {
        return requestedMultiplier;
    }

    public void setTargetFPS(int targetFPS) {
        this.targetFPS = Math.max(15, Math.min(240, targetFPS));
    }

    public int getTargetFPS() {
        return targetFPS;
    }

    public boolean isPendingRealFrame() {
        return pendingRealFrame;
    }

    public void notifyRealFramePending() {
        markFramePending(true);
    }

    /**
     * Invalidates the captured scene after a structural X11 change without
     * counting that change as a game Present.  Window map/resize/z-order
     * notifications otherwise inflate the source FPS shown by telemetry.
     */
    public void notifySceneChangePending() {
        markFramePending(false);
    }

    private void markFramePending(boolean gamePresent) {
        if (!active) return;
        pendingRealFrame = true;
        pendingRealFrameTimeNanos = System.nanoTime();
        if (gamePresent) gameFrameCount.incrementAndGet();
        renderer.xServerView.requestRender();
    }

    public boolean prepareFrame() {
        if (!active || realFramesCaptured < 2) {
            renderingGeneratedFrame = false;
            return false;
        }
        renderingGeneratedFrame = effectiveMultiplier > 1.0f && !pendingRealFrame;
        return renderingGeneratedFrame;
    }

    public boolean isGeneratedFrame() {
        return renderingGeneratedFrame;
    }

    public float getInterpolationFactor() {
        if (!active || realFramesCaptured < 2) return lowLatencyMode ? 1.0f : 0.0f;
        if (!renderingGeneratedFrame) return lowLatencyMode ? 1.0f : 0.0f;
        float phase = Math.min(0.99f, (framesSinceReal + 1.0f) / effectiveMultiplier);
        // Backward interpolation is cleaner but holds one game frame. The
        // low-latency path presents the newest real frame immediately and
        // extrapolates future samples from the latest motion field.
        return lowLatencyMode ? 1.0f + phase : phase;
    }

    public void onFrameCaptured() {
        boolean submittedByGame = pendingRealFrame;
        if (submittedByGame) {
            actualRealFrameCount.incrementAndGet();
            pendingRealFrame = false;
            presentedRealFrame = true;
            presentedRealFrameTimeNanos = pendingRealFrameTimeNanos;
        }
        else presentedRealFrame = false;
        realFramesCaptured++;
        framesSinceReal = 0;

        long now = System.nanoTime();
        if (submittedByGame && lastRealFrameTimeNanos > 0) {
            deltaHistory[historyIndex] = now - lastRealFrameTimeNanos;
            historyIndex = (historyIndex + 1) % deltaHistory.length;
            float[] sorted = deltaHistory.clone();
            Arrays.sort(sorted);
            float median = sorted[sorted.length / 2];
            typicalDeltaNanos = typicalDeltaNanos == DEFAULT_DELTA_NANOS
                    ? median : typicalDeltaNanos * 0.85f + median * 0.15f;
            updateMultiplier();
        }
        if (submittedByGame) lastRealFrameTimeNanos = now;
    }

    private void updateMultiplier() {
        if (requestedMultiplier > 0.0f) {
            effectiveMultiplier = requestedMultiplier;
            return;
        }
        float targetDelta = 1_000_000_000.0f / Math.max(1, targetFPS);
        effectiveMultiplier = Math.max(1.0f,
                Math.min(10.0f, typicalDeltaNanos / targetDelta));
    }

    public long getOutputFrameIntervalNanos() {
        if (requestedMultiplier > 0.0f) {
            return (long)(typicalDeltaNanos / Math.max(1.0f, requestedMultiplier));
        }
        return 1_000_000_000L / Math.max(1, targetFPS);
    }

    public void onPostDraw() {
        if (active && renderingGeneratedFrame) {
            generatedFrameCount.incrementAndGet();
            framesSinceReal++;
        }
        else if (active && presentedRealFrame) {
            long now = System.nanoTime();
            long queueDelay = presentedRealFrameTimeNanos > 0
                    ? Math.max(0, now - presentedRealFrameTimeNanos) : 0;
            float sample = queueDelay + (lowLatencyMode ? 0 : typicalDeltaNanos);
            estimatedLatencyNanos = estimatedLatencyNanos <= 0
                    ? sample : estimatedLatencyNanos * 0.85f + sample * 0.15f;
            presentedRealFrame = false;
        }
    }

    public int getRealFramesCaptured() {
        return realFramesCaptured;
    }

    public long getTypicalDeltaNanos() {
        return (long)typicalDeltaNanos;
    }

    public int getActualRealFrameCount() {
        return actualRealFrameCount.get();
    }

    public int getGameFrameCount() {
        return gameFrameCount.get();
    }

    public int getGeneratedFrameCount() {
        return generatedFrameCount.get();
    }

    public void resetFrameCounts() {
        actualRealFrameCount.set(0);
        generatedFrameCount.set(0);
        gameFrameCount.set(0);
    }

    public int consumeActualRealFrameCount() {
        return actualRealFrameCount.getAndSet(0);
    }

    public int consumeGeneratedFrameCount() {
        return generatedFrameCount.getAndSet(0);
    }

    public int consumeGameFrameCount() {
        return gameFrameCount.getAndSet(0);
    }

    public float getEstimatedLatencyMs() {
        return estimatedLatencyNanos / 1_000_000.0f;
    }

    public void setLowLatencyMode(boolean enabled) {
        lowLatencyMode = enabled;
    }

    public boolean isLowLatencyMode() {
        return lowLatencyMode;
    }

    public void setBackendMode(int mode) {
        backendMode = Math.max(BACKEND_GLES, Math.min(BACKEND_AUTO, mode));
        backendName = backendMode == BACKEND_NATIVE ? "libapex"
                : backendMode == BACKEND_AUTO ? "Auto" : "GLES";
    }

    public int getBackendMode() {
        return backendMode;
    }

    public void reportBackendReady(String name) {
        backendName = name;
        backendState = "Active";
        backendFailure = "";
    }

    public void reportBackendFallback(String reason) {
        backendName = "GLES";
        backendState = "Fallback";
        backendFailure = reason == null ? "" : reason;
    }

    public void reportBackendFailure(String reason) {
        backendState = "Error";
        backendFailure = reason == null || reason.trim().isEmpty()
                ? "Unknown backend failure" : reason.trim();
    }

    public String getBackendName() {
        return backendName;
    }

    public String getBackendState() {
        return backendState;
    }

    public String getBackendFailure() {
        return backendFailure;
    }
}
