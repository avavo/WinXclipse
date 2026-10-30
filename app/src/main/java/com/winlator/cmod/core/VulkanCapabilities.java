package com.winlator.cmod.core;

import java.util.Arrays;
import java.util.LinkedHashSet;

public final class VulkanCapabilities {
    private VulkanCapabilities() {}

    public static boolean supports14(int apiVersion) {
        int major = (apiVersion >>> 22) & 0x7f;
        int minor = (apiVersion >>> 12) & 0x3ff;
        return major > 1 || major == 1 && minor >= 4;
    }

    public static int parseVersion(String value) {
        try { return Integer.parseInt(value); }
        catch (Exception error) { return 0; }
    }

    /** Unknown names can belong to a wrapper, or an unavailable driver; do not erase them on save. */
    public static String mergeBlacklist(String saved, String[] supported, String disabled) {
        if (supported == null) return saved == null ? "" : saved;
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (saved != null) for (String name : saved.split(",")) if (!name.trim().isEmpty()) result.add(name.trim());
        result.removeAll(Arrays.asList(supported));
        if (disabled != null) for (String name : disabled.split(","))
            if (!name.trim().isEmpty()) result.add(name.trim());
        return String.join(",", result);
    }
}
