package com.winlator.cmod.renderer;

/** Inclusive texel-center bounds; exclude letterbox pixels from FSR neighborhoods. */
public final class FsrViewport {
    private FsrViewport() {}
    public static void sampleBounds(float[] output, int width, int height,
                                    float x, float y, float viewWidth, float viewHeight) {
        float lastX = Math.max(1, width) - 1, lastY = Math.max(1, height) - 1;
        x = finite(x, 0); y = finite(y, 0);
        viewWidth = Math.max(1, finite(viewWidth, 1)); viewHeight = Math.max(1, finite(viewHeight, 1));
        float firstX = Math.max(0, Math.min(lastX, x)), firstY = Math.max(0, Math.min(lastY, y));
        output[0] = firstX + 0.5f; output[1] = firstY + 0.5f;
        output[2] = Math.max(firstX, Math.min(lastX, x + viewWidth - 1)) + 0.5f;
        output[3] = Math.max(firstY, Math.min(lastY, y + viewHeight - 1)) + 0.5f;
    }
    private static float finite(float value, float fallback) { return Float.isNaN(value) || Float.isInfinite(value) ? fallback : value; }
}
