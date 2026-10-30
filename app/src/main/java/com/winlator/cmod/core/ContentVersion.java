package com.winlator.cmod.core;

import java.util.Locale;

/** Exact package matching: separators are cosmetic; builds and ABIs are not. */
public final class ContentVersion {
    private ContentVersion() {}

    public static String canonical(String value) {
        return (value == null ? "" : value.trim()).toLowerCase(Locale.ENGLISH)
                .replaceFirst("\\.(?:wcp(?:\\.(?:xz|zst))?|tzst|tar\\.(?:xz|zst)|zip)$", "")
                .replaceFirst("^(?:wine|proton|dxvk|vkd3d|box64|fexcore)[\\s._-]+", "")
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }

    public static String architecture(String value) {
        String name = (value == null ? "" : value).toLowerCase(Locale.ENGLISH)
                .replace('-', '_').replace(' ', '_');
        if (name.contains("arm64ec")) return "arm64ec";
        if (name.contains("arm64") || name.contains("aarch64")) return "arm64";
        if (name.contains("x86_64") || name.contains("amd64") || name.contains("x64")) return "x86_64";
        if (name.matches(".*(?:^|_)x86(?:$|_).*")) return "x86";
        return "";
    }

    public static boolean matches(String actualName, int actualCode, String requestedName,
                                  String requestedEntry, Integer requestedCode, boolean remote) {
        String requested = canonical(requestedName);
        String actual = canonical(actualName);
        if (actual.isEmpty()) return false;
        if (!requested.isEmpty()) {
            return actual.equals(requested)
                    && (remote || requestedCode == null || actualCode == requestedCode);
        }
        String entry = canonical(requestedEntry);
        return !entry.isEmpty() && (entry.equals(actual)
                || entry.equals(canonical(actualName + "-" + actualCode)));
    }
}
