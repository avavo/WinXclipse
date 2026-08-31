package com.winlator.cmod.renderer.lsfg;

import com.winlator.cmod.renderer.GLRenderer;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/** Chooses real/generated frames and derives a stable interpolation cadence. */
public final class LSFGManager {
    private static final float DEFAULT_DELTA_NANOS = 1_000_000_000.0f / 30.0f;

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
    private boolean renderingGeneratedFrame;

    public LSFGManager(GLRenderer renderer) {
        this.renderer = renderer;
        Arrays.fill(deltaHistory, DEFAULT_DELTA_NANOS);
    }

    public void setEnabled(boolean enabled) {
        if (active == enabled) return;
        active = enabled;
        resetTiming();
        renderer.xServerView.setApexMode(enabled);
        if (enabled) renderer.startApexChoreographer();
        else renderer.stopApexChoreographer();
    }

    private void resetTiming() {
        realFramesCaptured = 0;
        framesSinceReal = 0;
        typicalDeltaNanos = DEFAULT_DELTA_NANOS;
        lastRealFrameTimeNanos = 0;
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
        if (!active) return;
        pendingRealFrame = true;
        gameFrameCount.incrementAndGet();
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
        if (!active || realFramesCaptured < 2 || !renderingGeneratedFrame) return 0.0f;
        return Math.min(0.99f, (framesSinceReal + 1.0f) / effectiveMultiplier);
    }

    public void onFrameCaptured() {
        boolean submittedByGame = pendingRealFrame;
        if (submittedByGame) {
            actualRealFrameCount.incrementAndGet();
            pendingRealFrame = false;
        }
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
}
