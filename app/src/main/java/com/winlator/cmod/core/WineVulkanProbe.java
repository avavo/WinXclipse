package com.winlator.cmod.core;

import java.util.*;
import java.util.regex.*;

/** Evidence from the original vulkaninfo: AppGpu constructs a real VkDevice before printing. */
public final class WineVulkanProbe {
    private static final Pattern DEVICE = Pattern.compile("(?m)^\\s*deviceName\\s*=\\s*(\\S[^\\r\\n]*)$");
    private WineVulkanProbe() {}

    public static String signature(int status, String output, boolean nativeLoader) {
        if (status != 0 || output == null || output.length() > 1024 * 1024) return "";
        boolean loader = false, bridge = false;
        for (String line : output.toLowerCase(Locale.ROOT).split("\\r?\\n")) {
            if (!line.contains("loaddll") || !line.contains("loaded")) continue;
            if (line.contains("vulkan-1.dll") && line.matches(".*\\b" + (nativeLoader ? "native" : "builtin") + "\\b.*")) loader = true;
            if (line.contains("winevulkan.dll") && line.matches(".*\\bbuiltin\\b.*")) bridge = true;
        }
        if (!loader || !bridge || output.contains("PHYSICAL_DEVICE_TYPE_CPU")) return "";
        List<String> devices = new ArrayList<>();
        Matcher matcher = DEVICE.matcher(output);
        while (matcher.find()) devices.add(matcher.group(1).trim());
        if (devices.isEmpty()) return "";
        // Include driver properties too: the same GPU with a fallback driver
        // must not pass as the originally selected Android driver.
        for (String key : new String[]{"vendorID", "deviceID", "driverVersion", "driverName", "driverInfo"}) {
            Matcher property = Pattern.compile("(?m)^\\s*" + key + "\\s*=\\s*([^\\r\\n]+)$").matcher(output);
            while (property.find()) devices.add(key + "=" + property.group(1).trim());
        }
        Collections.sort(devices);
        return String.join("\n", devices);
    }
}
