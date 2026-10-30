package com.winlator.cmod.renderer;

import java.util.Locale;

/** Canonical FSR1 state and render sizes shared by startup and live changes. */
public final class FsrSettings {
    private FsrSettings() {}

    public static String normalize(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("ultra")) return "ultraperformance";
        if (value.startsWith("fidelity")) return "fidelity";
        if (value.startsWith("quality")) return "quality";
        if (value.startsWith("balanced")) return "balanced";
        if (value.startsWith("performance")) return "performance";
        if (value.equals("on") || value.equals("1") || value.equals("true")) return "on";
        return "off";
    }

    public static String resolve(int filter, boolean filterExplicit, String legacy,
                                 String upscale, String driverUpscale,
                                 String quality, String driverQuality) {
        String oldMode = normalize(legacy);
        boolean enabled = filter == 2;
        if (!filterExplicit && !oldMode.equals("off")) {
            enabled = true;
            if (upscale == null) upscale = oldMode.equals("on") ? "0" : "1";
            if (quality == null && !oldMode.equals("on")) quality = oldMode;
        }
        if (!enabled) return "off";
        String upscaleValue = upscale != null ? upscale : driverUpscale;
        if (!"on".equals(normalize(upscaleValue))) return "on";
        String mode = normalize(quality != null ? quality : driverQuality);
        return mode.equals("off") || mode.equals("on") ? "balanced" : mode;
    }

    public static float factor(String mode) {
        switch (normalize(mode)) {
            case "fidelity": return 1.3f;
            case "quality": return 1.5f;
            case "performance": return 2.0f;
            case "ultraperformance": return 2.5f;
            default: return 1.7f;
        }
    }

    public static int[] guestSize(int width, int height, String mode) {
        float factor = factor(mode);
        return new int[]{Math.max(64, Math.round(width / factor) & ~1),
                Math.max(64, Math.round(height / factor) & ~1)};
    }

    /** Uniformly clamp a guest scene to the output while preserving its aspect ratio. */
    public static int[] sceneSize(int guestWidth, int guestHeight, int outputWidth, int outputHeight) {
        int width = Math.max(1, guestWidth);
        int height = Math.max(1, guestHeight);
        double scale = Math.min(1.0, Math.min((double)Math.max(1, outputWidth) / width,
                (double)Math.max(1, outputHeight) / height));
        return new int[]{Math.max(1, (int)Math.round(width * scale)),
                Math.max(1, (int)Math.round(height * scale))};
    }
}
