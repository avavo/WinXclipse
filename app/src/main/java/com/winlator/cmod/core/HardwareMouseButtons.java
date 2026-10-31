package com.winlator.cmod.core;

import android.view.MotionEvent;
import java.util.HashMap;
import java.util.Map;

/** Deduplicates BUTTON_* and DOWN/UP mouse events, preserving chords and device ownership. */
public final class HardwareMouseButtons {
    public static final int SUPPORTED = MotionEvent.BUTTON_PRIMARY | MotionEvent.BUTTON_SECONDARY | MotionEvent.BUTTON_TERTIARY;
    public static final class Change {
        public final int pressed, released;
        public final boolean handled;
        private Change(int pressed, int released, boolean handled) { this.pressed = pressed; this.released = released; this.handled = handled; }
    }
    private static final Change NONE = new Change(0, 0, false), HANDLED = new Change(0, 0, true);
    private final Map<Integer, Integer> devices = new HashMap<>();
    private final Map<Integer, Integer> keyDevices = new HashMap<>();

    public int state() {
        int result = 0;
        for (int buttons : devices.values()) result |= buttons;
        for (int buttons : keyDevices.values()) result |= buttons;
        return result;
    }

    public Change updateKey(int device, int button, boolean down) {
        button &= SUPPORTED;
        if (button == 0) return NONE;
        int before = state(), previous = keyDevices.getOrDefault(device, 0);
        int next = down ? previous | button : previous & ~button;
        if (next == 0) keyDevices.remove(device); else keyDevices.put(device, next);
        int after = state();
        return before == after ? HANDLED : new Change(after & ~before, before & ~after, true);
    }

    public Change update(int device, int action, int actionButton, int buttonState) {
        int before = state(), previous = devices.getOrDefault(device, 0);
        int next = buttonState & SUPPORTED, changed = actionButton & SUPPORTED;
        boolean handled = true;
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                // Legacy primary DOWN may omit flags. A supplied secondary bit
                // always takes precedence and must never invent a left click.
                if (next == 0) {
                    if (changed != 0) next = changed;
                    else if (buttonState == 0 && actionButton == 0) next = MotionEvent.BUTTON_PRIMARY;
                    else return NONE;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                next = 0;
                break;
            case MotionEvent.ACTION_BUTTON_PRESS:
                if (changed == 0) return NONE;
                next = (next != 0 ? next : previous) | changed;
                break;
            case MotionEvent.ACTION_BUTTON_RELEASE:
                if (changed == 0) return NONE;
                next = (next != 0 ? next : previous) & ~changed;
                break;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_HOVER_MOVE:
            case MotionEvent.ACTION_SCROLL:
                handled = false; // Reconcile a missed release, then still move/scroll.
                break;
            default: return NONE;
        }
        if (next == 0) devices.remove(device); else devices.put(device, next);
        int after = state(), pressed = after & ~before, released = before & ~after;
        return pressed == 0 && released == 0 ? handled ? HANDLED : NONE : new Change(pressed, released, handled);
    }

    public int reset() { int released = state(); devices.clear(); keyDevices.clear(); return released; }
}
