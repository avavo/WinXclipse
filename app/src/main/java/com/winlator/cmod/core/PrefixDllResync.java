package com.winlator.cmod.core;

import android.util.Log;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;

/**
 * One-time compatibility repair for prefixes touched by the old runtime resync.
 *
 * <p>The previous implementation copied or replaced hundreds of Wine modules on
 * every runtime change. That is not equivalent to Wine's prefix upgrade and can
 * mix Proton 9/10/11 modules in an existing prefix. Current prefixes are seeded
 * only by {@code ContainerManager}; this class now performs a narrow, reversible
 * cleanup of the two runtime files deliberately skipped by the reference
 * Winlator ARM64EC implementations.</p>
 */
public final class PrefixDllResync {
    private static final String TAG = "PrefixDllResync";
    private static final String EXTRA_REPAIR_VERSION = "prefixCompatibilityRepairVersion";
    private static final String REPAIR_VERSION = "1";
    private static final String OLD_RUNTIME_MARKER = "lastPrefixRuntimeId";
    private static final String[] EXCLUDED_RUNTIME_FILES = {"icu.dll", "tabtip.exe"};

    private PrefixDllResync() {}

    public static void resync(Container container, WineInfo wineInfo, ImageFs imageFs) {
        try {
            repairLegacyRuntimeCopies(container, wineInfo, imageFs);
        }
        catch (Throwable error) {
            Log.w(TAG, "Prefix compatibility repair skipped; boot continues", error);
        }
    }

    private static void repairLegacyRuntimeCopies(Container container, WineInfo wineInfo,
                                                   ImageFs imageFs) {
        if (container == null || wineInfo == null || imageFs == null
                || REPAIR_VERSION.equals(container.getExtra(EXTRA_REPAIR_VERSION))) return;

        File containerRoot = container.getRootDir();
        String winePath = imageFs.getWinePath();
        File wineRoot = winePath != null ? new File(winePath) : null;
        if (containerRoot == null || wineRoot == null || !wineRoot.isDirectory()) return;

        File prefixWindows = new File(containerRoot, ".wine/drive_c/windows");
        File system32Dir = new File(prefixWindows, "system32");
        if (!system32Dir.isDirectory()) return;

        File wineLibDir = new File(wineRoot, "lib/wine");
        File nested = new File(wineLibDir, "wine");
        if (!new File(wineLibDir, "i386-windows").isDirectory()
                && new File(nested, "i386-windows").isDirectory()) wineLibDir = nested;

        String systemArch;
        String wowArch = null;
        if (wineInfo.isArm64EC()) {
            systemArch = "aarch64-windows";
            wowArch = "i386-windows";
        }
        else if (wineInfo.isWin64()) {
            systemArch = "x86_64-windows";
            wowArch = "i386-windows";
        }
        else systemArch = "i386-windows";

        File systemSourceDir = new File(wineLibDir, systemArch);
        File wowSourceDir = wowArch != null ? new File(wineLibDir, wowArch) : null;
        if (!systemSourceDir.isDirectory()
                || (wowSourceDir != null && !wowSourceDir.isDirectory())) return;

        int removed = removeExactRuntimeCopies(systemSourceDir, system32Dir);
        if (wowArch != null) {
            removed += removeExactRuntimeCopies(wowSourceDir,
                    new File(prefixWindows, "syswow64"));
        }

        // Retire the marker used by the broad resync so it cannot be mistaken
        // for an active runtime migration mechanism by later code.
        container.putExtra(OLD_RUNTIME_MARKER, null);
        container.putExtra(EXTRA_REPAIR_VERSION, REPAIR_VERSION);
        container.saveData();
        Log.i(TAG, "Prefix compatibility repair complete; removed=" + removed);
    }

    private static int removeExactRuntimeCopies(File sourceDir, File destinationDir) {
        if (!sourceDir.isDirectory() || !destinationDir.isDirectory()) return 0;
        int removed = 0;
        for (String name : EXCLUDED_RUNTIME_FILES) {
            File source = new File(sourceDir, name);
            File destination = new File(destinationDir, name);
            // Delete only byte-identical copies made from this runtime. A user
            // supplied/native replacement is preserved even when names match.
            if (source.isFile() && destination.isFile()
                    && FileUtils.contentEquals(source, destination)
                    && FileUtils.delete(destination)) {
                removed++;
            }
        }
        return removed;
    }
}
