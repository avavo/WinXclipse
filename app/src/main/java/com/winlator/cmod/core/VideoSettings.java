package com.winlator.cmod.core;

import java.util.Map;
import java.util.Locale;

/** Canonical video values shared by editors, guest environment and compositor. */
public final class VideoSettings {
    private VideoSettings() {}
    public static boolean enabled(Map<String,String> values,String key,boolean fallback) {
        return "1".equals(values.getOrDefault(key,fallback?"1":"0"));
    }
    public static String surfaceFormat(Map<String,String> values,boolean displayX) {
        String value=values.getOrDefault(displayX?"surfaceFormatDisplayX":"surfaceFormatVulkan",displayX?"rgba":"bgra");
        return "rgba".equalsIgnoreCase(value)?"rgba":"bgra";
    }
    public static String wineRenderer(String value) {
        String name=value==null?"":value.toLowerCase(Locale.ROOT);
        return name.equals("gl")||name.equals("egl")?name:"vulkan";
    }
    public static String wineRegistryRenderer(String value) { return wineRenderer(value).equals("egl")?"gl":wineRenderer(value); }
    public static void guestEnvironment(Map<String,String> values,String renderer,EnvVars env) {
        env.put("WINE_X11FORCEGLX",wineRenderer(renderer).equals("egl")?"0":"1");
        boolean zink=!"native".equals(values.getOrDefault("openGlDriver","zink"));
        if(zink) { env.put("GALLIUM_DRIVER","zink");env.put("LIBGL_KOPPER_DISABLE","true"); }
        else { env.remove("GALLIUM_DRIVER");env.remove("LIBGL_KOPPER_DISABLE"); }
        env.put("WINXCLIPSE_DISPLAYX_FORMAT",surfaceFormat(values,true));
    }
}
