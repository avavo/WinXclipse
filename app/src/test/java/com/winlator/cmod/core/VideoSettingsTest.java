package com.winlator.cmod.core;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class VideoSettingsTest {
    @Test public void independentSurfaceDefaultsAndNativeDriverRestoreDoNotSpoofGpu() {
        Map<String,String> values=new HashMap<>();
        assertEquals("bgra",VideoSettings.surfaceFormat(values,false));assertEquals("rgba",VideoSettings.surfaceFormat(values,true));
        EnvVars env=new EnvVars();VideoSettings.guestEnvironment(values,"vulkan",env);assertEquals("zink",env.get("GALLIUM_DRIVER"));
        values.put("surfaceFormatDisplayX","bgra");values.put("openGlDriver","native");
        VideoSettings.guestEnvironment(values,"egl",env);assertTrue(env.get("GALLIUM_DRIVER").isEmpty());
        assertEquals("0",env.get("WINE_X11FORCEGLX"));assertEquals("bgra",env.get("WINXCLIPSE_DISPLAYX_FORMAT"));
        assertEquals("gl",VideoSettings.wineRegistryRenderer("EGL"));assertEquals("vulkan",VideoSettings.wineRegistryRenderer("Vulkan"));
        assertTrue(env.get("WRAPPER_DEVICE_NAME").isEmpty());
    }
}
