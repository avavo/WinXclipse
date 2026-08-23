package com.winlator.cmod.core;

/**
 * Reads the MdiEx device classification and per-Xclipse policy that also
 * feeds the guest-side driver stack through the MDIEX_PROFILE environment.
 */
public final class MdiExBridge {
    static {
        System.loadLibrary("winlator");
    }

    private MdiExBridge() {}

    public static native String nativeProfileName();
    public static native String nativeSocName();
    public static native int nativeRamGB();
    public static native String nativePolicySummary();
}
