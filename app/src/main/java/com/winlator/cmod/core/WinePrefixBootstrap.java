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

    public static boolean prepare(File prefix, boolean win64, String identity, File wineBin,
                                  File wineInf, File box64, Runner runner, AfterUpdate afterUpdate)
            throws IOException, InterruptedException {
        WinePrefixState.ensureRegistries(prefix, win64);
        File wine = WinePrefixState.wineExecutable(wineBin);
        File server = new File(wineBin, "wineserver");
        if (!server.isFile() || server.length() == 0) throw new IOException("Wine server is missing or empty: " + server);
        if (!wineInf.isFile() || wineInf.length() == 0) throw new IOException("Wine setup data is missing or empty: " + wineInf);
        String fingerprint = WinePrefixState.fingerprint(identity, wine, wineInf);
        if (WinePrefixState.isPrepared(prefix, fingerprint)) return false;
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
