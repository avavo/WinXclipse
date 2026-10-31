package com.winlator.cmod.core;

import android.view.InputDevice;

/** Source IDs include shared class bits: testing any overlapping bit is insufficient. */
public final class InputSources {
    private InputSources() {}
    public static boolean includes(int sources, int source) { return source != 0 && (sources & source) == source; }
    public static boolean controller(int sources) {
        return includes(sources, InputDevice.SOURCE_GAMEPAD) || includes(sources, InputDevice.SOURCE_JOYSTICK);
    }
    public static boolean controllerKey(int source, boolean gamepadButton) {
        return gamepadButton || source == InputDevice.SOURCE_UNKNOWN || controller(source) || includes(source, InputDevice.SOURCE_DPAD);
    }
}
