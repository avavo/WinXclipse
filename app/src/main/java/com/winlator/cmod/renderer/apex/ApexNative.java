package com.winlator.cmod.renderer.apex;

import android.util.Log;

/**
 * JNI bridge for the experimental Apex GLES frame-interpolation engine.
 *
 * <p>libapex.so is a prebuilt binary shipped under app/src/main/jniLibs (no
 * source in this repo; it is intentionally tracked in git). Loading is
 * best-effort: when the library is missing or rejects a frame, callers fall
 * back to the GLES compute path (see LSFGEffect).
 */
public final class ApexNative {
    private static final String TAG = "ApexNative";
    private static final boolean AVAILABLE;

    static {
        boolean loaded = false;
        try {
            System.loadLibrary("apex");
            loaded = true;
        }
        catch (Throwable error) {
            Log.w(TAG, "Native Apex backend unavailable; using the GLES compute fallback", error);
        }
        AVAILABLE = loaded;
    }

    private ApexNative() {}

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    public static native long nativeCreateEngineGLES(int width, int height);
    public static native long nativeCreateEngineVulkan(long instance, long physicalDevice,
            long device, long queue, int queueFamily, int width, int height);
    public static native void nativeDestroyEngine(long engine);
    public static native void nativeGetStats(long engine, float[] timing, int[] frames,
            boolean[] enabled, int[] quality);
    public static native boolean nativeProcessFrameGLES(long engine, int currentTexture,
            int previousTexture, int[] motionVectorTexture);
    public static native boolean nativeProcessFrameVulkan(long engine, long currentImage,
            long previousImage, long outputImage, float[] interpolationFactor);
    public static native void nativeSetEnabled(long engine, boolean enabled);
    public static native void nativeSetQuality(long engine, int quality);
    public static native void nativeSetSharpenAmount(long engine, float amount);
    public static native void nativeSetTargetFPS(long engine, int targetFPS);
}
