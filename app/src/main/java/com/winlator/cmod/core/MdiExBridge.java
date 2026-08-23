package com.winlator.cmod.core;

/**
 * Reads the MdiEx per-Xclipse policy profile that also feeds the
 * guest-side driver stack through the MDIEX_PROFILE environment.
 */
public final class MdiExBridge {
    static {
        System.loadLibrary("winlator");
    }

    private MdiExBridge() {}

    public static native String nativeProfileName();
}
