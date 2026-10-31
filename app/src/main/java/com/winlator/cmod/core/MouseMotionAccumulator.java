package com.winlator.cmod.core;

/** Retains subpixel motion when physical deltas are scaled to guest coordinates. */
public final class MouseMotionAccumulator {
    private float x, y;
    public int[] add(float dx, float dy) {
        if (!Float.isFinite(dx) || !Float.isFinite(dy)) { reset(); return new int[]{0, 0}; }
        x += dx; y += dy;
        int wholeX = (int)x, wholeY = (int)y;
        x -= wholeX; y -= wholeY;
        return new int[]{wholeX, wholeY};
    }
    public void reset() { x = y = 0; }
}
