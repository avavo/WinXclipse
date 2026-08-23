package com.winlator.cmod.core;

/**
 * Reads the MdiEx per-Xclipse policy profile that also feeds the
 * guest-side driver stack through the MDIEX_PROFILE environment.
 */
public final class WinXclipsePolicy {
    static {
        System.loadLibrary("winlator");
    }

    private WinXclipsePolicy() {}

    public static native String nativeProfileName();
}

