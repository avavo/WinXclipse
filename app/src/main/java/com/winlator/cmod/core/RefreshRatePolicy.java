package com.winlator.cmod.core;

public final class RefreshRatePolicy {
    private RefreshRatePolicy() {}

    public static final class Mode {
        public final int id, width, height;
        public final float rate;
        public Mode(int id, int width, int height, float rate) {
            this.id = id;
            this.width = width;
            this.height = height;
            this.rate = rate;
        }
    }

    /** Preserve the current physical resolution. Auto chooses its fastest supported mode. */
    public static Mode choose(Mode current, Mode[] modes, String preference) {
        float requested;
        try { requested = Float.parseFloat(preference); }
        catch (Exception ignored) { requested = 0; }
        if (!Float.isFinite(requested) || requested < 0) requested = 0;
        Mode best = null;
        for (Mode mode : modes) {
            if (mode.width != current.width || mode.height != current.height
                    || !Float.isFinite(mode.rate) || mode.rate <= 0) continue;
            if (best == null || requested == 0 && mode.rate > best.rate
                    || requested > 0 && (Math.abs(mode.rate - requested) < Math.abs(best.rate - requested)
                    || Math.abs(mode.rate - requested) == Math.abs(best.rate - requested) && mode.rate > best.rate))
                best = mode;
        }
        return best == null ? current : best;
    }
}
