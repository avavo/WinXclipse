package com.winlator.cmod.renderer;

/** Rates measured over the same compositor sampling window, never predicted from a multiplier. */
public final class FrameRateSample {
    public static final long MAX_SAMPLE_AGE_NANOS = 1_500_000_000L;
    public final float sourceFps;
    public final float outputFps;
    public final float generatedFps;

    private FrameRateSample(float sourceFps, float outputFps, float generatedFps) {
        this.sourceFps = sourceFps;
        this.outputFps = outputFps;
        this.generatedFps = generatedFps;
    }

    public static FrameRateSample fromCounts(int source, int realOutput, int generatedOutput,
                                             long elapsedNanos) {
        if (elapsedNanos <= 0) return new FrameRateSample(0, 0, 0);
        double rate = 1_000_000_000.0 / elapsedNanos;
        return new FrameRateSample((float)(Math.max(0, source) * rate),
                (float)(((long)Math.max(0, realOutput) + Math.max(0, generatedOutput)) * rate),
                (float)(Math.max(0, generatedOutput) * rate));
    }

    /** A stalled game does not keep its last nonzero FG rate indefinitely. */
    public static float freshOutput(float outputFps, long sampleTimeNanos, long nowNanos) {
        if (sampleTimeNanos <= 0 || nowNanos < sampleTimeNanos
                || nowNanos - sampleTimeNanos > MAX_SAMPLE_AGE_NANOS
                || !Float.isFinite(outputFps)) return 0;
        return Math.max(0, outputFps);
    }
}
