package com.winlator.cmod.renderer;

import android.app.Application;
import com.winlator.cmod.renderer.effects.Effect;
import com.winlator.cmod.renderer.lsfg.LSFGEffect;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class EffectComposerRecoveryTest {
    private static final class Composer extends EffectComposer {
        int width = 640, height = 480, attempts, directFrames, fgFailures, cursorRestores;
        boolean reject = true, directReject;
        Composer() { super(null); }
        @Override protected int surfaceWidth() { return width; }
        @Override protected int surfaceHeight() { return height; }
        @Override protected void requestRender() {}
        @Override protected void reportFrameGenerationFailure() { fgFailures++; }
        @Override protected void restoreCursor() { cursorRestores++; }
        @Override protected void drawDirect() {
            directFrames++;
            if (directReject) throw new IllegalStateException("core renderer rejected");
        }
        @Override protected void renderPipeline(LSFGEffect lsfg, boolean active) {
            attempts++;
            if (reject) throw new IllegalStateException("fragment shader rejected");
        }
    }

    @Test public void failedOptionalPipelineIsAttemptedOnceWhileDirectFramesContinue() {
        Composer composer = new Composer();
        Effect selected = new Effect() {};
        composer.addEffect(selected);
        for (int i = 0; i < 300; i++) composer.render();
        assertEquals(1, composer.attempts);
        assertEquals(300, composer.directFrames);
        assertEquals(300, composer.cursorRestores);
        assertEquals(0, composer.fgFailures);
        assertSame(selected, composer.getEffect(selected.getClass()));
        assertEquals("fragment shader rejected", composer.getRuntimeFailure());
        assertFalse(composer.isRendering());
    }

    @Test public void resizeAndFreshContextEachAllowOneNewAttempt() {
        Composer composer = new Composer();
        composer.render(); composer.render();
        composer.width = 800;
        composer.render(); composer.render();
        assertEquals(2, composer.attempts);
        GLContextEpoch.beginContext();
        composer.reject = false;
        composer.render(); composer.render();
        assertEquals(4, composer.attempts);
        assertEquals("", composer.getRuntimeFailure());
        assertEquals(4, composer.directFrames);
    }

    @Test public void changingEffectsRetriesButAddingTheSameEffectDoesNotCreateARetryLoop() {
        Composer composer = new Composer();
        Effect effect = new Effect() {};
        composer.addEffect(effect);
        composer.render();
        composer.addEffect(effect); composer.render();
        assertEquals(1, composer.attempts);
        composer.removeEffect(effect);
        composer.reject = false;
        composer.render();
        assertEquals(2, composer.attempts);
        assertEquals("", composer.getRuntimeFailure());
    }

    @Test public void contextInvalidationReleasesTheFaultWithoutDiscardingSelections() {
        Composer composer = new Composer();
        Effect effect = new Effect() {};
        composer.addEffect(effect); composer.render();
        composer.invalidateBuffers();
        composer.reject = false;
        composer.render();
        assertEquals(2, composer.attempts);
        assertSame(effect, composer.getEffect(effect.getClass()));
    }

    @Test public void aFailureInTheDirectPathDoesNotLeaveTheComposerRecursivelyLocked() {
        Composer composer = new Composer();
        composer.directReject = true;
        composer.render(); composer.render();
        assertEquals(1, composer.attempts);
        assertEquals(2, composer.directFrames);
        assertFalse(composer.isRendering());
        assertEquals("fragment shader rejected", composer.getRuntimeFailure());
        composer.directReject = false;
        composer.render();
        assertEquals(3, composer.directFrames);
        assertEquals(1, composer.attempts);
        int previousLogs = org.robolectric.shadows.ShadowLog.getLogsForTag("EffectComposer").size();
        Composer later = new Composer();
        later.render();
        later.directReject = true;
        for (int i = 0; i < 100; i++) later.render();
        assertEquals(previousLogs + 2, org.robolectric.shadows.ShadowLog.getLogsForTag("EffectComposer").size());
        assertFalse(later.isRendering());
    }
}
