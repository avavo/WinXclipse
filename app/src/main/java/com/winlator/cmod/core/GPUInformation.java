package com.winlator.cmod.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class GPUInformation {

    public static final int MODEL_NONE = 0;
    private static final Pattern XCLIPSE_MODEL_PATTERN = Pattern.compile("xclipse[ _-]?(\\d{3})");

    private static volatile RendererInfo cachedInfo;

    private static RendererInfo getInfo() {
        RendererInfo info = cachedInfo;
        if (info == null) {
            synchronized (GPUInformation.class) {
                info = cachedInfo;
                if (info == null) {
                    String renderer = getRenderer();
                    info = new RendererInfo(renderer == null || renderer.trim().isEmpty()
                            ? "Unknown GPU" : renderer.trim());
                    cachedInfo = info;
                }
            }
        }
        return info;
    }

    public static String getRendererName() {
        return getInfo().name;
    }

    public static boolean isXclipse() {
        return getInfo().xclipse;
    }

    public static int getXclipseModel() {
        return getInfo().model;
    }

    public static int getRDNAVersion() {
        return getInfo().rdna;
    }

    public static boolean isRDNA2() {
        return getRDNAVersion() == 2;
    }

    public static boolean isRDNA3() {
        return getRDNAVersion() == 3;
    }

    public static boolean isRDNA4() {
        return getRDNAVersion() == 4;
    }

    public static boolean isXclipse530() {
        return getXclipseModel() == 530;
    }

    public static boolean isXclipse540() {
        return getXclipseModel() == 540;
    }

    public static boolean isXclipse550() {
        return getXclipseModel() == 550;
    }

    public static boolean isXclipse920() {
        return getXclipseModel() == 920;
    }

    public static boolean isXclipse940() {
        return getXclipseModel() == 940;
    }

    public static boolean isXclipse950() {
        return getXclipseModel() == 950;
    }

    public static boolean isXclipse960() {
        return getXclipseModel() == 960;
    }

    public static String getExynosModel() {
        switch (getXclipseModel()) {
            case 530: return "Exynos 1480";
            case 540: return "Exynos 1580";
            case 550: return "Exynos 1680";
            case 920: return "Exynos 2200";
            case 940: return "Exynos 2400/2400e";
            case 950: return "Exynos 2500";
            case 960: return "Exynos 2600";
            default: return "";
        }
    }

    /**
     * Typical device RAM for a detected SoC, in GB. Returns 0 when unknown.
     * Every 1480/1580/1680/2200 device ships with 8 GB and every 2600
     * device with 12 GB. Models that ship with varying RAM amounts
     * (Xclipse 940/950 flagships) stay unknown so no wrong fallback is used.
     */
    public static int getTypicalRamGB() {
        switch (getXclipseModel()) {
            case 530:
            case 540:
            case 550:
            case 920:
                return 8;
            case 960:
                return 12;
            default:
                return 0;
        }
    }

    /** Relative GPU size classes used by session tuning. */
    public static final int GPU_TINY = 0;   // 1-2 WGP  (Xclipse 530/540)
    public static final int GPU_MID = 1;    // ~3 WGP   (Xclipse 920, 550)
    public static final int GPU_BIG = 2;    // 6-8 WGP  (Xclipse 940/950/960)

    /**
     * Size class of the detected Xclipse, from public die information:
     * 530=1 WGP, 540=2 WGP, 920~3 WGP (384 SP), 940=6 WGP, 950/960=8 WGP.
     * Unknown models map to MID.
     */
    public static int getGpuTier() {
        switch (getXclipseModel()) {
            case 530:
            case 540:
                return GPU_TINY;
            case 940:
            case 950:
            case 960:
                return GPU_BIG;
            default:
                return GPU_MID;
        }
    }

    /**
     * Unified-memory VRAM ceiling for the detected GPU, in MB. A tiny GPU can
     * never consume what the RAM-based formula would grant, and granting a
     * big GPU less than its share wastes it. Returns 0 when unknown.
     */
    public static int getModelVramCapMB() {
        switch (getGpuTier()) {
            case GPU_TINY:
                return 2048;
            case GPU_MID:
                return 3072;
            case GPU_BIG:
                return 4092;
            default:
                return 0;
        }
    }

    /**
     * Default BCn compute-emulation backend for the detected GPU generation,
     * used only when the user never picked one explicitly. The RDNA ISA docs
     * define the VOPD dual-issue VALU encoding from RDNA3 onwards (wave32),
     * which roughly doubles per-SIMD ALU throughput for independent ops like
     * the BCn transcode kernels; RDNA2 has no dual-issue and the Xclipse
     * 530/540 also expose very few WGPs, so there the lighter software path
     * is preferred by default.
     */
    public static String defaultBcnEmulationType() {
        return getRDNAVersion() == 2 ? "software" : "compute";
    }

    public native static String getVersion();
    public native static String getRenderer();
    public native static long getMemorySize();
    public native static String[] enumerateExtensions();

    static {
        System.loadLibrary("winlator");
    }

    private static final class RendererInfo {
        final String name;
        final boolean xclipse;
        final int model;
        final int rdna;

        RendererInfo(String name) {
            this.name = name;
            String lower = name.toLowerCase(Locale.ENGLISH);
            this.xclipse = lower.contains("xclipse");
            int parsedModel = MODEL_NONE;
            if (this.xclipse) {
                Matcher matcher = XCLIPSE_MODEL_PATTERN.matcher(lower);
                if (matcher.find()) parsedModel = Integer.parseInt(matcher.group(1));
            }
            this.model = parsedModel;
            int rdnaVersion = MODEL_NONE;
            switch (parsedModel) {
                case 530:
                case 540:
                case 920:
                    rdnaVersion = 2;
                    break;
                case 550:
                case 940:
                case 950:
                    rdnaVersion = 3;
                    break;
                case 960:
                    rdnaVersion = 4;
                    break;
            }
            this.rdna = rdnaVersion;
        }
    }
}
