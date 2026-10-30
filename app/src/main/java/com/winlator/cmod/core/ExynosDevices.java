package com.winlator.cmod.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared model database for export metadata, toolbar labels and HUDs. */
public final class ExynosDevices {
    private static final Pattern MODEL = Pattern.compile("(?<![A-Z0-9])SM-([A-Z]?)(\\d{3})([A-Z0-9]*)");

    public static final class Profile {
        public final String device, model, soc, gpu;
        private final String variants;
        private Profile(String device, String model, String soc, String gpu, String variants) {
            this.device = device;
            this.model = model;
            this.soc = soc;
            this.gpu = gpu;
            this.variants = variants;
        }
        public String shortLabel() { return device.replaceFirst("^Galaxy\\s+", "").replace(" 5G", ""); }
    }

    // An empty variants field is used only for families with the same Exynos SoC globally.
    // Mixed Snapdragon/Exynos families require an explicit regional variant.
    private static final Profile[] DEVICES = {
            new Profile("Galaxy S22", "SM-S901", "Exynos 2200", "Xclipse 920", "B"),
            new Profile("Galaxy S22+", "SM-S906", "Exynos 2200", "Xclipse 920", "B"),
            new Profile("Galaxy S22 Ultra", "SM-S908", "Exynos 2200", "Xclipse 920", "B"),
            new Profile("Galaxy S23 FE", "SM-S711", "Exynos 2200", "Xclipse 920", "BN"),
            new Profile("Galaxy S24", "SM-S921", "Exynos 2400", "Xclipse 940", "BN"),
            new Profile("Galaxy S24+", "SM-S926", "Exynos 2400", "Xclipse 940", "BN"),
            new Profile("Galaxy S24 FE", "SM-S721", "Exynos 2400e", "Xclipse 940", ""),
            new Profile("Galaxy S25 FE", "SM-S731", "Exynos 2400", "Xclipse 940", ""),
            new Profile("Galaxy S26 FE", "SM-S741", "Exynos 2500", "Xclipse 950", "BN"),
            new Profile("Galaxy Z Flip7", "SM-F766", "Exynos 2500", "Xclipse 950", ""),
            new Profile("Galaxy Z Flip7 FE", "SM-F761", "Exynos 2400", "Xclipse 940", ""),
            new Profile("Galaxy Z Flip8", "SM-F776", "Exynos 2600", "Xclipse 960", "B"),
            new Profile("Galaxy S26", "SM-S942", "Exynos 2600", "Xclipse 960", "BN"),
            new Profile("Galaxy S26+", "SM-S947", "Exynos 2600", "Xclipse 960", "BN"),
            new Profile("Galaxy A55 5G", "SM-A556", "Exynos 1480", "Xclipse 530", ""),
            new Profile("Galaxy A37 5G", "SM-A376", "Exynos 1480", "Xclipse 530", ""),
            new Profile("Galaxy A56 5G", "SM-A566", "Exynos 1580", "Xclipse 540", ""),
            new Profile("Galaxy Tab S10 FE (Wi-Fi)", "SM-X520", "Exynos 1580", "Xclipse 540", ""),
            new Profile("Galaxy Tab S10 FE (5G)", "SM-X526", "Exynos 1580", "Xclipse 540", ""),
            new Profile("Galaxy Tab S10 FE+ (Wi-Fi)", "SM-X620", "Exynos 1580", "Xclipse 540", ""),
            new Profile("Galaxy Tab S10 FE+ (5G)", "SM-X626", "Exynos 1580", "Xclipse 540", ""),
            new Profile("Galaxy A57 5G", "SM-A576", "Exynos 1680", "Xclipse 550", "")
    };

    private ExynosDevices() {}

    public static Profile find(String reported) {
        if (reported == null) return null;
        Matcher matcher = MODEL.matcher(reported.toUpperCase(Locale.ROOT));
        if (!matcher.find()) return null;
        String family = matcher.group(1), digits = matcher.group(2), suffix = matcher.group(3);
        for (Profile profile : DEVICES) {
            if (!profile.model.endsWith(digits) || !family.isEmpty() && profile.model.charAt(3) != family.charAt(0)) continue;
            if (!profile.variants.isEmpty() && (suffix.isEmpty() || profile.variants.indexOf(suffix.charAt(0)) < 0)) return null;
            return profile;
        }
        return null;
    }
}
