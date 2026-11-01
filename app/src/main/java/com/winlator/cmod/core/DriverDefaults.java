package com.winlator.cmod.core;

import java.util.Locale;

/** Defaults follow reported capabilities; driver names are used only for the BCn policy. */
public final class DriverDefaults {
    private DriverDefaults() {}
    public static boolean nativeBcn(String name) {
        String value = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return value.contains("quasar") || value.contains("radv") || value.contains("phoenix");
    }
    public static String vulkanVersion(int api) {
        return VulkanCapabilities.supports14(api) ? "1.4" : "1.3";
    }
}
