package com.winlator.cmod.core;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Finish the selected runtime's prefix update before starting the application's launch chain. */
public final class WinePrefixBootstrap {
    public interface Runner {
        int run(String[] command, long timeoutMs) throws IOException, InterruptedException;
    }
    public interface AfterUpdate { void run() throws IOException; }

    private WinePrefixBootstrap() {}

    /** Catalog aliases of the APK runtime use the same bundled prepared prefix. */
    public static String templateAsset(String selection, boolean bakedInRuntime) {
        return (bakedInRuntime ? "proton-9.0-arm64ec" : selection) + "_container_pattern.tzst";
    }

    /** Accept only a freshly extracted prepared template for this installed runtime.
     * Downloaded packs must be checked BEFORE seeding any missing core modules. */
    public static boolean adoptPreparedTemplate(File prefix, String architecture, String identity,
                                                File wineBin, File wineLib, File wineInf) throws IOException {
        if (!WineRuntimeLayout.supportsArchitecture(architecture)
                || !wineInf.isFile() || wineInf.length() == 0) return false;
        File timestamp = new File(prefix, ".update-timestamp");
        if (!timestamp.isFile() || timestamp.length() > 32
                || !"disable".equals(new String(java.nio.file.Files.readAllBytes(timestamp.toPath()),
                java.nio.charset.StandardCharsets.UTF_8).trim())) return false;
        for (String name : new String[]{"system.reg", "user.reg", "userdef.reg"}) {
            File hive = new File(prefix, name);
            if (!hive.isFile()) return false;
            boolean hasKeys = false;
            try (java.io.BufferedReader reader = java.nio.file.Files.newBufferedReader(hive.toPath(),
                    java.nio.charset.StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("[") && line.contains("]")) { hasKeys = true; break; }
                }
            }
            if (!hasKeys) return false;
        }
        WinePrefixState.ensureRegistries(prefix, !"x86".equals(architecture));
        // Confirm that the archive already contains the selected runtime's actual
        // core for every Windows side. Header-only/incomplete packs still migrate.
        String primary = WineRuntimeLayout.isNativeArm64(architecture) ? "aarch64-windows" : "x86_64-windows";
        String[][] modules = "x86".equals(architecture)
                ? new String[][]{{"i386-windows", "system32"}}
                : new String[][]{{primary, "system32"}, {"i386-windows", "syswow64"}};
        for (String[] arch : modules) {
            for (String name : new String[]{"ntdll.dll", "kernel32.dll", "wineboot.exe"}) {
                File source = new File(WineRuntimeLayout.modules(wineLib, arch[0]), name);
                File target = new File(prefix, "drive_c/windows/" + arch[1] + "/" + name);
                if (!RuntimeFiles.samePayload(source, target)) return false;
            }
        }
        WinePrefixState.markPrepared(prefix, WinePrefixState.fingerprint(identity,
                WinePrefixState.wineExecutable(wineBin), wineInf));
        return true;
    }

    public static boolean prepare(File prefix, boolean win64, String identity, File wineBin,
                                  File wineInf, File box64, Runner runner, AfterUpdate afterUpdate)
            throws IOException, InterruptedException {
        WinePrefixState.ensureRegistries(prefix, win64);
        File wine = WinePrefixState.wineExecutable(wineBin);
        File server = new File(wineBin, "wineserver");
        if (!server.isFile() || server.length() == 0) throw new IOException("Wine server is missing or empty: " + server);
        if (!wineInf.isFile() || wineInf.length() == 0) throw new IOException("Wine setup data is missing or empty: " + wineInf);
        String fingerprint = WinePrefixState.fingerprint(identity, wine, wineInf);
        if (WinePrefixState.isPrepared(prefix, fingerprint)
                || WinePrefixState.migrateLegacyReceipt(prefix, identity, wine, wineInf, fingerprint)) return false;
        WinePrefixState.invalidate(prefix);

        // -u forces Wine's real prefix migration even when an old template's
        // .update-timestamp contains "disable". -h would only request help.
        int bootStatus;
        try {
            bootStatus = runner.run(command(box64, wine, "wineboot", "-u"), 180_000L);
        } catch (IOException | InterruptedException failure) {
            try { stopServer(box64, server, runner); }
            catch (IOException | InterruptedException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        // Registry files must be flushed and unlocked before Java installs the
        // configured components. Scope both commands to this prefix via its env.
        stopServer(box64, server, runner);
        if (bootStatus != 0) throw new IOException("Wine prefix update failed (exit " + bootStatus + ")");
        WinePrefixState.ensureRegistries(prefix, win64);
        if (!WinePrefixState.hasCoreFiles(prefix)) throw new IOException("Wine prefix update left missing or empty system modules");
        afterUpdate.run();
        WinePrefixState.markPrepared(prefix, fingerprint);
        return true;
    }

    private static void stopServer(File box64, File server, Runner runner) throws IOException, InterruptedException {
        int killStatus = runner.run(command(box64, server, "-k"), 15_000L);
        // Wine returns 1 if its server already exited. The wait still has to finish.
        int waitStatus = runner.run(command(box64, server, "-w"), 15_000L);
        if (killStatus != 0 && killStatus != 1 || waitStatus != 0)
            throw new IOException("Wine server did not finish prefix preparation (" + killStatus + "/" + waitStatus + ")");
    }

    private static String[] command(File box64, File executable, String... args) {
        List<String> result = new ArrayList<>();
        if (box64 != null) result.add(box64.getAbsolutePath());
        result.add(executable.getAbsolutePath());
        result.addAll(Arrays.asList(args));
        return result.toArray(new String[0]);
    }
}
