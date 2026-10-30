package com.winlator.cmod.renderer;

import org.junit.Test;
import static org.junit.Assert.*;

public class FrameRateSampleTest {
    @Test public void coalescedSourceFramesDoNotInflateOutput() {
        FrameRateSample sample = FrameRateSample.fromCounts(100, 30, 30, 1_000_000_000L);
        assertEquals(100, sample.sourceFps, 0.001f);
        assertEquals(60, sample.outputFps, 0.001f);
        assertEquals(30, sample.generatedFps, 0.001f);
    }

    @Test public void enabledButNoGeneratedOutputReportsOnlyRealDraws() {
        assertEquals(30, FrameRateSample.fromCounts(30, 30, 0, 1_000_000_000L).outputFps, 0.001f);
    }

    @Test public void ratesUseActualElapsedWindow() {
        FrameRateSample sample = FrameRateSample.fromCounts(15, 15, 15, 500_000_000L);
        assertEquals(30, sample.sourceFps, 0.001f);
        assertEquals(60, sample.outputFps, 0.001f);
    }

    @Test public void invalidWindowAndCountsAreSafe() {
        assertEquals(0, FrameRateSample.fromCounts(10, 10, 10, 0).outputFps, 0);
        assertEquals(0, FrameRateSample.fromCounts(-1, -1, -1, 1_000_000_000L).outputFps, 0);
        assertTrue(Float.isFinite(FrameRateSample.fromCounts(0, Integer.MAX_VALUE,
                Integer.MAX_VALUE, 1).outputFps));
    }

    @Test public void staleOrInvalidTelemetryCannotKeepHeadlineFpsAlive() {
        long at = 2_000_000_000L;
        assertEquals(60, FrameRateSample.freshOutput(60, at, at + 500_000_000L), 0);
        assertEquals(0, FrameRateSample.freshOutput(60, at, at + 2_000_000_000L), 0);
        assertEquals(0, FrameRateSample.freshOutput(Float.NaN, at, at), 0);
        assertEquals(0, FrameRateSample.freshOutput(60, at, at - 1), 0);
    }
}
