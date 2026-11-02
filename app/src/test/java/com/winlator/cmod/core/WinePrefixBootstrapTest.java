package com.winlator.cmod.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.winlator.cmod.core.WinePrefixStateTest.*;
import static org.junit.Assert.*;

public class WinePrefixBootstrapTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private class Fixture {
        final File prefix = temp.newFolder("prefix");
        final File runtime = temp.newFolder("runtime with spaces");
        final File bin = new File(runtime, "bin");
        final File inf = new File(runtime, "share/wine/wine.inf");
        final List<String> phases = new ArrayList<>();
        final List<String[]> commands = new ArrayList<>();
        int bootStatus, killStatus, waitStatus;
        boolean installCore = true, timeout;

        Fixture() throws Exception {
            write(runtime, "bin/wine", "launcher");
            write(runtime, "bin/wineserver", "server");
            write(runtime, "share/wine/wine.inf", "setup");
            write(prefix, ".update-timestamp", "disable\n");
            write(prefix, "drive_c/Game/save.dat", "do not touch");
        }

        int run(String[] command, long timeoutMs) throws IOException {
            commands.add(command);
            String last = command[command.length - 1];
            phases.add(last);
            if ("-u".equals(last)) {
                assertEquals(180_000L, timeoutMs);
                if (timeout) throw new IOException("timeout");
                if (installCore) core(prefix);
                return bootStatus;
            }
            assertEquals(15_000L, timeoutMs);
            return "-k".equals(last) ? killStatus : waitStatus;
        }

        boolean prepare(String id, File box64, WinePrefixBootstrap.AfterUpdate after) throws Exception {
            return WinePrefixBootstrap.prepare(prefix, true, id, bin, inf, box64, this::run, after);
        }

        String fingerprint(String id) throws Exception {
            return WinePrefixState.fingerprint(id, new File(bin, "wine"), inf);
        }
    }

    @Test public void catalogAliasUsesOnlyTheMatchingBakedInTemplate() {
        assertEquals("proton-9.0-arm64ec_container_pattern.tzst",
                WinePrefixBootstrap.templateAsset("Proton-9.0-arm64ec-0", true));
        assertEquals("Proton-10.0-arm64ec-2_container_pattern.tzst",
                WinePrefixBootstrap.templateAsset("Proton-10.0-arm64ec-2", false));
    }

    private File preparedTemplate(Fixture f) throws Exception {
        WinePrefixState.ensureRegistries(f.prefix, true);
        for (String name : new String[]{"system.reg", "user.reg", "userdef.reg"})
            write(f.prefix, name, "WINE REGISTRY Version 2\n#arch=win64\n[Software\\\\Wine]\n\"Prepared\"=\"9\"\n");
        File libraries = new File(f.runtime, "lib");
        for (String[] arch : new String[][]{{"aarch64-windows", "system32"}, {"i386-windows", "syswow64"}})
            for (String name : new String[]{"ntdll.dll", "kernel32.dll", "wineboot.exe"}) {
                write(libraries, "wine/" + arch[0] + "/" + name, "selected-" + arch[0] + "-" + name);
                write(f.prefix, "drive_c/windows/" + arch[1] + "/" + name, "selected-" + arch[0] + "-" + name);
            }
        return libraries;
    }

    @Test public void verifiedPreparedApkTemplateSkipsForcedWinebootAndRetainsRuntimeUpgradeChecks() throws Exception {
        Fixture f = new Fixture();
        File libraries = preparedTemplate(f);
        assertTrue(WinePrefixBootstrap.adoptBundledTemplate(f.prefix, "proton9", f.bin, libraries, f.inf));
        assertFalse(f.prepare("proton9", null, () -> fail("No redundant component installation")));
        assertTrue(f.phases.isEmpty());
        assertTrue(f.prepare("proton10", null, () -> {}));
        assertEquals(Arrays.asList("-u", "-k", "-w"), f.phases);
        assertEquals("do not touch", read(new File(f.prefix, "drive_c/Game/save.dat")));
    }

    @Test public void mismatchedOrIncompleteTemplateStillRequiresRealWineboot() throws Exception {
        Fixture f = new Fixture();
        File libraries = preparedTemplate(f);
        write(f.prefix, "drive_c/windows/syswow64/kernel32.dll", "another runtime");
        assertFalse(WinePrefixBootstrap.adoptBundledTemplate(f.prefix, "proton9", f.bin, libraries, f.inf));
        assertFalse(WinePrefixState.isPrepared(f.prefix, f.fingerprint("proton9")));
        assertTrue(f.prepare("proton9", null, () -> {}));
        WinePrefixState.invalidate(f.prefix);
        write(f.prefix, ".update-timestamp", "");
        assertFalse(WinePrefixBootstrap.adoptBundledTemplate(f.prefix, "proton9", f.bin, libraries, f.inf));
    }

    @Test public void emptyRegistrySkeletonIsNeverMistakenForPreparedTemplate() throws Exception {
        Fixture f = new Fixture();
        File libraries = preparedTemplate(f);
        write(f.prefix, "system.reg", "WINE REGISTRY Version 2\n#arch=win64\n");
        assertFalse(WinePrefixBootstrap.adoptBundledTemplate(f.prefix, "proton9", f.bin, libraries, f.inf));
        assertFalse(WinePrefixState.isPrepared(f.prefix, f.fingerprint("proton9")));
        assertTrue(new File(f.prefix, "user.reg").delete());
        assertFalse(WinePrefixBootstrap.adoptBundledTemplate(f.prefix, "proton9", f.bin, libraries, f.inf));
        assertFalse(new File(f.prefix, "user.reg").exists());
    }

    @Test public void bootFinishesAndServerStopsBeforeInstallingComponentsAndRecordingSuccess() throws Exception {
        Fixture f = new Fixture();
        assertTrue(f.prepare("proton10", null, () -> f.phases.add("components")));
        assertEquals(Arrays.asList("-u", "-k", "-w", "components"), f.phases);
        assertEquals(new File(f.bin, "wine").getAbsolutePath(), f.commands.get(0)[0]);
        assertEquals("wineboot", f.commands.get(0)[1]);
        assertTrue(WinePrefixState.isPrepared(f.prefix, f.fingerprint("proton10")));
        assertEquals("do not touch", read(new File(f.prefix, "drive_c/Game/save.dat")));
    }

    @Test public void matchingRuntimeDoesNotRunWinebootOrReinstallComponentsOnEveryLaunch() throws Exception {
        Fixture f = new Fixture();
        f.prepare("proton10", null, () -> {});
        f.phases.clear();
        assertFalse(f.prepare("proton10", null, () -> fail("unexpected reinstallation")));
        assertTrue(f.phases.isEmpty());
    }

    @Test public void runtimeChangeUpdatesTheSamePrefixWithoutCopyingAnotherContainersData() throws Exception {
        Fixture f = new Fixture();
        f.prepare("proton9", null, () -> {});
        f.phases.clear();
        assertTrue(f.prepare("proton10", null, () -> f.phases.add("components")));
        assertEquals(Arrays.asList("-u", "-k", "-w", "components"), f.phases);
        assertEquals("do not touch", read(new File(f.prefix, "drive_c/Game/save.dat")));
    }

    @Test public void box64ReceivesAbsolutePathsAsSeparateArgumentsEvenWithSpaces() throws Exception {
        Fixture f = new Fixture();
        File box = new File(f.runtime, "emulator dir/box64");
        f.prepare("wine64", box, () -> {});
        for (String[] command : f.commands) {
            assertEquals(box.getAbsolutePath(), command[0]);
            assertTrue(command[1].startsWith(f.bin.getAbsolutePath()));
        }
    }

    @Test public void failedBootCleansUpButNeverCommitsOrInstallsComponents() throws Exception {
        Fixture f = new Fixture();
        f.bootStatus = 53;
        IOException failure = assertThrows(IOException.class, () -> f.prepare("bad", null, () -> fail("must not install")));
        assertTrue(failure.getMessage().contains("53"));
        assertEquals(Arrays.asList("-u", "-k", "-w"), f.phases);
        assertFalse(WinePrefixState.isPrepared(f.prefix, f.fingerprint("bad")));
    }

    @Test public void timedOutBootAttemptsScopedServerCleanupAndRetriesNextTime() throws Exception {
        Fixture f = new Fixture();
        f.timeout = true;
        assertThrows(IOException.class, () -> f.prepare("slow", null, () -> fail("must not install")));
        assertEquals(Arrays.asList("-u", "-k", "-w"), f.phases);
        f.timeout = false;
        assertTrue(f.prepare("slow", null, () -> {}));
    }

    @Test public void aStuckServerPreventsRegistryEditsAndAReadyReceipt() throws Exception {
        Fixture f = new Fixture();
        f.waitStatus = 1;
        assertThrows(IOException.class, () -> f.prepare("busy", null, () -> fail("Wine is still using the registry")));
        assertFalse(WinePrefixState.isPrepared(f.prefix, f.fingerprint("busy")));
    }

    @Test public void alreadyExitedServerIsAcceptedAfterSuccessfulWait() throws Exception {
        Fixture f = new Fixture();
        f.killStatus = 1;
        assertTrue(f.prepare("exited", null, () -> {}));
    }

    @Test public void zeroExitWithoutSystemModulesIsNotACompletedInstallation() throws Exception {
        Fixture f = new Fixture();
        f.installCore = false;
        assertThrows(IOException.class, () -> f.prepare("empty", null, () -> fail("no system modules")));
        assertFalse(WinePrefixState.isPrepared(f.prefix, f.fingerprint("empty")));
    }

    @Test public void missingSetupDataDoesNotRunTheGameOrTouchUserFiles() throws Exception {
        Fixture f = new Fixture();
        assertTrue(f.inf.delete());
        assertThrows(IOException.class, () -> f.prepare("missing", null, () -> fail("no setup data")));
        assertTrue(f.phases.isEmpty());
        assertEquals("do not touch", read(new File(f.prefix, "drive_c/Game/save.dat")));
    }

    @Test public void componentInstallationFailureNeverCreatesAReadyReceipt() throws Exception {
        Fixture f = new Fixture();
        assertThrows(IOException.class, () -> f.prepare("components", null, () -> { throw new IOException("install failed"); }));
        assertFalse(WinePrefixState.isPrepared(f.prefix, f.fingerprint("components")));
    }

    @Test public void interruptedPreparationCleansUpAndDoesNotInstallComponents() throws Exception {
        Fixture f = new Fixture();
        assertThrows(InterruptedException.class, () -> WinePrefixBootstrap.prepare(
                f.prefix, true, "cancelled", f.bin, f.inf, null, (args, timeout) -> {
                    if ("-u".equals(args[args.length - 1])) throw new InterruptedException("cancel");
                    return f.run(args, timeout);
                }, () -> fail("cancelled")));
        assertEquals(Arrays.asList("-k", "-w"), f.phases);
        assertFalse(WinePrefixState.isPrepared(f.prefix, f.fingerprint("cancelled")));
    }
}
