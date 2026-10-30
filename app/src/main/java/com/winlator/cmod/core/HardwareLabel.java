package com.winlator.cmod.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HardwareLabel {
    public static final int FULL = 0, MODEL = 1, BRAND = 2;
    private static final Pattern SAMSUNG = Pattern.compile("(?i)^(Exynos|Xclipse)\\s*([0-9]+[a-z]?)\\b");
    private HardwareLabel() {}

    public static String format(String source, int mode) {
        String value = source == null ? "" : source.trim().replaceFirst("(?i)^Samsung\\s+", "");
        Matcher matcher = SAMSUNG.matcher(value);
        if (!matcher.find() || mode == FULL) return value;
        if (mode == MODEL) return matcher.group(2);
        if (mode == BRAND) return matcher.group(1);
        return value;
    }

    public static String family(String device) {
        return device.replaceFirst("^(Galaxy\\s+(?:Tab|[A-Z])).*$", "$1");
    }
}
