package com.winlator.cmod.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;

/** Installs the opt-in policy in the guest, preserving other preload libraries. */
public final class GuestMemoryPolicy {
    public static final String KEY = "WINXCLIPSE_RAM_AGGRESSIVE";
    private GuestMemoryPolicy() {}
    public static void apply(EnvVars env, File library) {
        String path = library.getAbsolutePath();
        ArrayList<String> libraries = new ArrayList<>(Arrays.asList(env.get("LD_PRELOAD").split("[:\\s]+")));
        libraries.removeIf(value -> value.isEmpty() || value.endsWith("/libwinx_memory.so"));
        if ("1".equals(env.get(KEY)) && library.isFile()) libraries.add(path);
        if (libraries.isEmpty()) env.remove("LD_PRELOAD");
        else env.put("LD_PRELOAD", String.join(":", libraries));
    }
}
