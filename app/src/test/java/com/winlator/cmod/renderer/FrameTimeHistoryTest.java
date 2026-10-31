package com.winlator.cmod.renderer;
import org.junit.Test;
import static org.junit.Assert.*;
public class FrameTimeHistoryTest {
    @Test public void retainsOnlyLatestIntervalsInChronologicalOrder() {
        FrameTimeHistory history = new FrameTimeHistory(3);
        long now = 0;
        history.frame(now);
        for (int i = 1; i <= 5; i++) history.frame(now += i * 1_000_000L);
        assertArrayEquals(new double[]{3, 4, 5}, history.snapshot(), 0);
        assertEquals(5, history.latest(now), 0);
    }
    @Test public void pauseDoesNotEnterGraphAsAStall() {
        FrameTimeHistory history = new FrameTimeHistory(3);
        history.frame(1); history.frame(16_000_001);
        history.boundary(); history.frame(20_000_000_001L); history.frame(20_017_000_001L);
        assertArrayEquals(new double[]{16, 17}, history.snapshot(), 0);
        assertTrue(Double.isNaN(history.latest(30_000_000_001L)));
    }
    @Test public void duplicateTimestampsAreIgnoredAndResetClearsValues() {
        FrameTimeHistory history = new FrameTimeHistory(3);
        history.frame(1); history.frame(1); history.frame(0); history.frame(10_000_001);
        assertArrayEquals(new double[]{10}, history.snapshot(), 0);
        history.clear(); assertEquals(0, history.snapshot().length);
        assertTrue(Double.isNaN(history.latest(20_000_000)));
    }
}
