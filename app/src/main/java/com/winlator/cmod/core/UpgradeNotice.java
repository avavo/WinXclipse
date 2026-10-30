package com.winlator.cmod.core;

public final class UpgradeNotice {
    private UpgradeNotice() {}

    public static boolean shouldShow096(String version, boolean alreadyShown, long firstInstall, long lastUpdate) {
        return "0.9.6".equals(version) && !alreadyShown && firstInstall > 0 && lastUpdate > firstInstall + 1000;
    }
}
