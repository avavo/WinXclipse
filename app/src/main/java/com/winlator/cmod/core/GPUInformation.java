package com.winlator.cmod.core;

import java.util.Locale;

public abstract class GPUInformation {

    public static String getRendererName() {
        String renderer = getRenderer();
        return renderer == null || renderer.trim().isEmpty() ? "Unknown GPU" : renderer.trim();
    }

    public static boolean isXclipse() {
        return getRendererName().toLowerCase(Locale.ENGLISH).contains("xclipse");
    }

    public static boolean isXclipse920() {
        return getRendererName().toLowerCase(Locale.ENGLISH).matches(".*xclipse[ _-]?920.*");
    }

    public static boolean isXclipse940() {
        return getRendererName().toLowerCase(Locale.ENGLISH).matches(".*xclipse[ _-]?940.*");
    }

    public static boolean isXclipse950() {
        return getRendererName().toLowerCase(Locale.ENGLISH).matches(".*xclipse[ _-]?950.*");
    }

    public native static String getVersion();
    public native static String getRenderer();
    public native static long getMemorySize();
    public native static String[] enumerateExtensions();

    static {
        System.loadLibrary("winlator");
    }
}
