package com.winlator.cmod.core;

import android.content.Context;
import android.util.Log;

import java.io.File;

public final class EvshimPatcher {
    private EvshimPatcher() {}

    /**
     * Copy the pre-patched winebus.so from imagefs into a Wine / Proton tree
     * the first time we see it.
     */
    public static void patchWineTree(Context ctx, File wineRoot, boolean arm64ec) {
        if (!arm64ec) {
            Log.i("Evshim", "Skipping winebus patch for non-ARM64EC runtime");
            return;
        }
        File dst = new File(wineRoot,
                "lib/wine/aarch64-unix/winebus.so");

        // Already patched?
        if (dst.exists() && dst.length() > 0) return;

        File src = new File(ctx.getFilesDir(),
                "imagefs/opt/proton-9.0-arm64ec/lib/wine/aarch64-unix/winebus.so");

        if (!src.exists()) {
            Log.w("Evshim", "patch source missing: " + src);
            return;
        }

        dst.getParentFile().mkdirs();
        FileUtils.copy(src, dst);          // helper already in project
        FileUtils.chmod(dst, 0644);
        Log.i("Evshim", "Patched winebus into " + dst);
    }
}
