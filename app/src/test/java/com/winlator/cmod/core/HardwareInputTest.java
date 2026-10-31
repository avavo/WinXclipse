package com.winlator.cmod.core;

import android.view.InputDevice;
import android.view.MotionEvent;
import com.winlator.cmod.math.XForm;
import org.junit.Test;
import static org.junit.Assert.*;

public class HardwareInputTest {
    private static final int L = MotionEvent.BUTTON_PRIMARY, R = MotionEvent.BUTTON_SECONDARY, M = MotionEvent.BUTTON_TERTIARY;

    @Test public void keyboardAndDpadDoNotBecomeGamepadsBecauseTheyShareClassButton() {
        assertFalse(InputSources.controller(InputDevice.SOURCE_KEYBOARD));
        assertFalse(InputSources.controller(InputDevice.SOURCE_KEYBOARD | InputDevice.SOURCE_DPAD));
        assertFalse(InputSources.controller(InputDevice.SOURCE_KEYBOARD | InputDevice.SOURCE_CLASS_JOYSTICK));
        assertTrue(InputSources.controller(InputDevice.SOURCE_GAMEPAD));
        assertTrue(InputSources.controller(InputDevice.SOURCE_JOYSTICK));
    }

    @Test public void mouseRangesCannotBeInterpretedAsGamepadAxes() {
        assertFalse(InputSources.controller(InputDevice.SOURCE_MOUSE));
        assertFalse(InputSources.controller(InputDevice.SOURCE_MOUSE_RELATIVE));
        assertFalse(InputSources.controller(InputDevice.SOURCE_TOUCHPAD));
        assertFalse(InputSources.includes(InputDevice.SOURCE_TRACKBALL, InputDevice.SOURCE_MOUSE_RELATIVE));
    }

    @Test public void aCompositeDevicesKeyboardEventsRemainKeyboardEvents() {
        assertFalse(InputSources.controllerKey(InputDevice.SOURCE_KEYBOARD, false));
        assertTrue(InputSources.controllerKey(InputDevice.SOURCE_GAMEPAD, false));
        assertTrue(InputSources.controllerKey(InputDevice.SOURCE_DPAD, false));
        assertTrue(InputSources.controllerKey(InputDevice.SOURCE_KEYBOARD, true));
        assertTrue(InputSources.controllerKey(InputDevice.SOURCE_UNKNOWN, false));
    }

    @Test public void secondaryDownNeverInventsAPrimaryClick() {
        HardwareMouseButtons buttons = new HardwareMouseButtons();
        assertEquals(R, buttons.update(1, MotionEvent.ACTION_DOWN, 0, R).pressed);
        assertEquals(R, buttons.state());
        assertEquals(R, buttons.update(1, MotionEvent.ACTION_UP, 0, 0).released);
    }

    @Test public void buttonAndDownDuplicatesProduceOnlyOnePressAndRelease() {
        HardwareMouseButtons buttons = new HardwareMouseButtons();
        assertEquals(L, buttons.update(1, MotionEvent.ACTION_DOWN, 0, L).pressed);
        assertEquals(0, buttons.update(1, MotionEvent.ACTION_BUTTON_PRESS, L, L).pressed);
        assertEquals(L, buttons.update(1, MotionEvent.ACTION_UP, 0, 0).released);
        assertEquals(0, buttons.update(1, MotionEvent.ACTION_BUTTON_RELEASE, L, 0).released);
    }

    @Test public void releasingRightPreservesTheHeldLeftButton() {
        HardwareMouseButtons buttons = new HardwareMouseButtons();
        buttons.update(1, MotionEvent.ACTION_DOWN, 0, L);
        assertEquals(R, buttons.update(1, MotionEvent.ACTION_BUTTON_PRESS, R, L | R).pressed);
        assertEquals(R, buttons.update(1, MotionEvent.ACTION_BUTTON_RELEASE, R, L).released);
        assertEquals(L, buttons.state());
        assertEquals(L, buttons.update(1, MotionEvent.ACTION_UP, 0, 0).released);
    }

    @Test public void aMouseBackKeyAndItsMotionDuplicateShareRightButtonOwnership() {
        HardwareMouseButtons buttons = new HardwareMouseButtons();
        assertEquals(R, buttons.updateKey(1, R, true).pressed);
        assertEquals(0, buttons.update(1, MotionEvent.ACTION_DOWN, 0, R).pressed);
        assertEquals(0, buttons.update(1, MotionEvent.ACTION_UP, 0, 0).released);
        assertEquals(R, buttons.updateKey(1, R, false).released);
    }

    @Test public void oneMouseCannotReleaseAnotherMousesHeldButton() {
        HardwareMouseButtons buttons = new HardwareMouseButtons();
        buttons.update(1, MotionEvent.ACTION_DOWN, 0, L);
        assertEquals(0, buttons.update(2, MotionEvent.ACTION_DOWN, 0, L).pressed);
        assertEquals(0, buttons.update(1, MotionEvent.ACTION_UP, 0, 0).released);
        assertEquals(L, buttons.state());
        assertEquals(L, buttons.update(2, MotionEvent.ACTION_UP, 0, 0).released);
    }

    @Test public void rightButtonReportedOnlyAsAKeyStaysHeldWhileTheMouseMoves() {
        HardwareMouseButtons buttons = new HardwareMouseButtons();
        buttons.updateKey(1, R, true);
        assertEquals(0, buttons.update(1, MotionEvent.ACTION_MOVE, 0, 0).released);
        assertEquals(R, buttons.state());
        assertEquals(R, buttons.updateKey(1, R, false).released);
    }

    @Test public void legacyPrimaryWithoutButtonFlagsStillWorks() {
        HardwareMouseButtons buttons = new HardwareMouseButtons();
        assertEquals(L, buttons.update(1, MotionEvent.ACTION_DOWN, 0, 0).pressed);
        assertEquals(L, buttons.update(1, MotionEvent.ACTION_CANCEL, 0, 0).released);
        assertEquals(0, buttons.reset());
    }

    @Test public void backOrForwardButtonsDoNotBecomeLeftClicks() {
        HardwareMouseButtons buttons = new HardwareMouseButtons();
        assertEquals(0, buttons.update(1, MotionEvent.ACTION_DOWN, 0, MotionEvent.BUTTON_BACK).pressed);
        assertEquals(0, buttons.update(1, MotionEvent.ACTION_DOWN, 0, MotionEvent.BUTTON_FORWARD).pressed);
    }

    @Test public void missedReleaseIsReconciledWithoutConsumingTheNextMotion() {
        HardwareMouseButtons buttons = new HardwareMouseButtons();
        buttons.update(1, MotionEvent.ACTION_DOWN, 0, R);
        HardwareMouseButtons.Change moved = buttons.update(1, MotionEvent.ACTION_MOVE, 0, 0);
        assertEquals(R, moved.released); assertFalse(moved.handled);
    }

    @Test public void focusLossReleasesAllHeldButtonsOnlyOnce() {
        HardwareMouseButtons buttons = new HardwareMouseButtons();
        buttons.update(1, MotionEvent.ACTION_DOWN, 0, L | R);
        buttons.update(2, MotionEvent.ACTION_DOWN, 0, M);
        assertEquals(L | R | M, buttons.reset());
        assertEquals(0, buttons.reset());
    }

    @Test public void capturedMotionUsesScaleAndRotationWithoutScreenTranslation() {
        float[] transform = XForm.set(XForm.getInstance(), 500, -900, 0.5f, 2);
        assertArrayEquals(new float[]{1, 8}, XForm.transformVector(transform, 2, 4), 0f);
        assertArrayEquals(new float[]{501, -892}, XForm.transformPoint(transform, 2, 4), 0f);
        XForm.set(transform, 0, 1, -1, 0, 500, -900);
        assertArrayEquals(new float[]{-4, 2}, XForm.transformVector(transform, 2, 4), 0f);
    }

    @Test public void smallScaledMouseMovementsAreAccumulatedInsteadOfLost() {
        MouseMotionAccumulator movement = new MouseMotionAccumulator();
        for (int i = 0; i < 3; i++) assertArrayEquals(new int[]{0, 0}, movement.add(0.25f, -0.25f));
        assertArrayEquals(new int[]{1, -1}, movement.add(0.25f, -0.25f));
    }

    @Test public void directionChangesAndCaptureResetDoNotLeaveSubpixelDrift() {
        MouseMotionAccumulator movement = new MouseMotionAccumulator();
        movement.add(0.75f, 0.75f);
        assertArrayEquals(new int[]{0, 0}, movement.add(-0.75f, -0.75f));
        movement.add(0.75f, 0); movement.reset();
        assertArrayEquals(new int[]{0, 0}, movement.add(0.5f, 0));
        assertArrayEquals(new int[]{0, 0}, movement.add(Float.NaN, 0));
        assertArrayEquals(new int[]{0, 0}, movement.add(0.5f, 0));
    }
}
