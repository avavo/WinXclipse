package com.winlator.cmod.core;

import java.net.URI;

/** Public API identity policy; upload credentials must never follow redirects. */
public final class CommunityEndpoint {
    public static final String ORIGIN = "https://winxclipse-community.silky-moss-5631.chatgpt.site";
    public static final String LEGACY = "https://github.com/avavo/WinXclipse/releases/download/community-configs/";
    public static final long MAX_ZIP = 20L * 1024 * 1024;
    private CommunityEndpoint() {}

    public static String file(String id, int revision, boolean cover) {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") || revision < 1)
            throw new IllegalArgumentException("Invalid community revision");
        return ORIGIN + "/api/v1/configs/" + id + (cover ? "/cover?r=" : "/archive?r=") + revision;
    }

    public static boolean trustedFile(String url, String id, int revision, boolean cover) {
        try { return file(id, revision, cover).equals(url); }
        catch (IllegalArgumentException ignored) { return false; }
    }

    public static boolean legacySource(String url) {
        if (url == null || !url.startsWith(LEGACY) || url.length() <= LEGACY.length()) return false;
        try {
            URI parsed = new URI(url);
            return parsed.getRawUserInfo() == null && parsed.getRawQuery() == null && parsed.getRawFragment() == null
                    && "github.com".equals(parsed.getHost()) && parsed.getPort() == -1;
        } catch (Exception ignored) { return false; }
    }

    public static boolean validArchive(String hash, long size) {
        return hash != null && hash.matches("[a-f0-9]{64}") && size >= 22 && size <= MAX_ZIP;
    }
}
