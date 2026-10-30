package com.winlator.cmod.renderer.lsfg;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class LSFGManagerTest {
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private final AtomicInteger requests = new AtomicInteger();
    private final LSFGManager manager = new LSFGManager(requests::incrementAndGet, enabled -> {}, clock::get);

    private void realFrame() {
        clock.addAndGet(33_333_333L);
        manager.notifyRealFramePending();
        assertFalse(manager.prepareFrame());
        assertTrue(manager.shouldCaptureFrame());
        manager.onFrameCaptured();
        manager.onPostDraw();
    }

    @Test public void realCounterOnlyIncrementsAfterCompletedDraw() {
        manager.setEnabled(true);
        manager.notifyRealFramePending();
        manager.prepareFrame();
        manager.onFrameCaptured();
        assertEquals(1, manager.getGameFrameCount());
        assertEquals(0, manager.getActualRealFrameCount());
        manager.onPostDraw();
        assertEquals(1, manager.getActualRealFrameCount());
    }

    @Test public void generatedBudgetCannotRedrawAnIdleGameForever() {
        manager.setMultiplier(2);
        manager.setEnabled(true);
        realFrame(); realFrame();
        assertTrue(manager.prepareFrame());
        assertFalse(manager.shouldCaptureFrame());
        manager.onPostDraw();
        assertEquals(1, manager.getGeneratedFrameCount());
        assertFalse(manager.prepareFrame());
        assertFalse(manager.shouldCaptureFrame());
        manager.onPostDraw();
        assertEquals(1, manager.getGeneratedFrameCount());
        assertEquals(2, manager.getActualRealFrameCount());
    }

    @Test public void fractionalMultiplierCarriesHalfAFrame() {
        manager.setMultiplier(1.5f);
        manager.setEnabled(true);
        realFrame(); realFrame();
        assertFalse(manager.prepareFrame());
        realFrame();
        assertTrue(manager.prepareFrame());
        manager.onPostDraw();
        assertFalse(manager.prepareFrame());
        assertEquals(1, manager.getGeneratedFrameCount());
    }

    @Test public void coalescedPresentsRemainDistinctFromActualOutput() {
        manager.setEnabled(true);
        manager.notifyRealFramePending();
        manager.notifyRealFramePending();
        manager.notifyRealFramePending();
        manager.prepareFrame(); manager.onFrameCaptured(); manager.onPostDraw();
        assertEquals(3, manager.consumeGameFrameCount());
        assertEquals(1, manager.consumeActualRealFrameCount());
        assertEquals(0, manager.consumeGeneratedFrameCount());
        assertEquals(0, manager.getActualRealFrameCount());
    }

    @Test public void structuralChangesResetHistoryWithoutCountingGamePresents() {
        manager.setMultiplier(2);
        manager.setEnabled(true);
        realFrame(); realFrame();
        manager.resetFrameCounts();
        manager.notifySceneChangePending();
        assertFalse(manager.prepareFrame());
        assertTrue(manager.shouldCaptureFrame());
        assertTrue(manager.onFrameCaptured());
        manager.onPostDraw();
        assertFalse(manager.shouldScheduleGeneratedFrame());
        assertEquals(0, manager.getGameFrameCount());
        assertEquals(0, manager.getActualRealFrameCount());
    }

    @Test public void loadingStallRequiresTwoFreshFramesBeforeGeneration() {
        manager.setMultiplier(2);
        manager.setEnabled(true);
        realFrame(); realFrame();
        clock.addAndGet(1_000_000_000L);
        manager.notifyRealFramePending(); manager.prepareFrame();
        assertTrue(manager.onFrameCaptured());
        manager.onPostDraw();
        assertEquals(1, manager.getRealFramesCaptured());
        assertFalse(manager.prepareFrame());
        realFrame();
        assertTrue(manager.prepareFrame());
    }
}
