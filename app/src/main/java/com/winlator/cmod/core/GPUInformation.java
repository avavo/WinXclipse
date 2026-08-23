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

    /**
     * Unified-memory VRAM ceiling for the detected Xclipse, in MB, following
     * the per-model tuning table: the 1-2 WGP mid-rangers (530/540), the
     * ~3 WGP 920 and the RDNA3 550 stay at 2048 MB regardless of device RAM;
     * the flagship 940/950 ship with varying RAM so they follow the RAM-based
     * split (2048 MB on 8 GB-class, 4092 MB on 12 GB-class); the 8 WGP 960
     * always gets the full 4092 MB. Returns 0 when no model override exists.
     */
    public static int getModelVramCapMB() {
        switch (getXclipseModel()) {
            case 530:
            case 540:
            case 550:
            case 920:
                return 2048;
            case 960:
                return 4092;
            default:
                return 0;
        }
    }

    /**
     * Whether the detected SoC has dedicated LITTLE efficiency cores. All
     * supported Exynos models do except the Exynos 2600, whose deca-core
     * layout is one prime plus nine performance mid-cores; excluding its
     * "slowest" cluster from emulation pinning would only waste throughput.
     */
    public static boolean hasLittleCores() {
        return getXclipseModel() != 960;
    }

    /**
     * Default BCn backend for every detected model: the compute path wins on
     * the whole Xclipse family (the wrapper transcode kernels stay cheap even
     * on the small WGPs, and hardware ASTC decode remains selectable
     * separately). Kept as a method so generation-specific exceptions can be
     * reintroduced in one place if ever needed.
     */
    public static String defaultBcnEmulationType() {
        return "compute";
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
