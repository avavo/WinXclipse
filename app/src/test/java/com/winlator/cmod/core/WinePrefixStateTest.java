package com.winlator.cmod.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class WinePrefixStateTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    static void write(File root, String path, String text) throws IOException {
        File target = new File(root, path);
        Files.createDirectories(target.getParentFile().toPath());
        Files.write(target.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    static void core(File root) throws IOException {
        for (String file : new String[]{"ntdll.dll", "kernel32.dll", "wineboot.exe"})
            write(root, "drive_c/windows/system32/" + file, "runtime payload");
    }

    @Test public void seedsAnActuallyWin32RegistryWithoutRelabellingAnotherPrefix() throws Exception {
        File prefix = temp.newFolder();
        WinePrefixState.ensureRegistries(prefix, false);
        for (String name : new String[]{"system.reg", "user.reg", "userdef.reg"})
            assertEquals("WINE REGISTRY Version 2\n#arch=win32\n", read(new File(prefix, name)));
    }

    @Test public void rejectsAnArchitectureChangeBeforeTouchingAnyFile() throws Exception {
        File prefix = temp.newFolder();
        write(prefix, "user.reg", "WINE REGISTRY Version 2\n#arch=win64\n[Saved Game]\n\"Progress\"=\"keep\"\n");
        byte[] original = Files.readAllBytes(new File(prefix, "user.reg").toPath());
        assertThrows(IOException.class, () -> WinePrefixState.ensureRegistries(prefix, false));
        assertArrayEquals(original, Files.readAllBytes(new File(prefix, "user.reg").toPath()));
        assertFalse(new File(prefix, "system.reg").exists());
    }

    @Test public void doesNotEraseNonemptyRegistriesWithAnInvalidHeader() throws Exception {
        File prefix = temp.newFolder();
        write(prefix, "system.reg", "broken header\n[User Settings]\n\"Saved\"=\"yes\"\n");
        byte[] original = Files.readAllBytes(new File(prefix, "system.reg").toPath());
        assertThrows(IOException.class, () -> WinePrefixState.ensureRegistries(prefix, true));
        assertArrayEquals(original, Files.readAllBytes(new File(prefix, "system.reg").toPath()));
        assertFalse(new File(prefix, "user.reg").exists());
    }

    @Test public void leavesValidCrlfRegistriesByteIdentical() throws Exception {
        File prefix = temp.newFolder();
        String registry = "WINE REGISTRY Version 2\r\n;; Comment\r\n#arch=win64\r\n[Setting]\r\n\"Value\"=dword:00000001\r\n";
        for (String name : new String[]{"system.reg", "user.reg", "userdef.reg"}) write(prefix, name, registry);
        WinePrefixState.ensureRegistries(prefix, true);
        assertEquals(registry, read(new File(prefix, "user.reg")));
    }

    @Test public void addsAMissingMarkerWithoutDroppingAnyKeys() throws Exception {
        File prefix = temp.newFolder();
        write(prefix, "system.reg", "WINE REGISTRY Version 2\n[Important]\n\"Save\"=\"yes\"\n");
        WinePrefixState.ensureRegistries(prefix, true);
        assertEquals("WINE REGISTRY Version 2\n#arch=win64\n[Important]\n\"Save\"=\"yes\"\n", read(new File(prefix, "system.reg")));
    }

    @Test public void repairingAnEmptyRegistryInvalidatesThePreviousSuccessReceipt() throws Exception {
        File prefix = temp.newFolder();
        WinePrefixState.ensureRegistries(prefix, true);
        core(prefix);
        WinePrefixState.markPrepared(prefix, "runtime");
        assertTrue(WinePrefixState.isPrepared(prefix, "runtime"));
        write(prefix, "user.reg", "");
        WinePrefixState.ensureRegistries(prefix, true);
        assertFalse(WinePrefixState.isPrepared(prefix, "runtime"));
    }

    @Test public void excludesOnlyDriveMappingsFromBothPrefixPackLayouts() throws Exception {
        File stage = temp.newFolder();
        assertTrue(WinePrefixState.isDriveMapping(stage, new File(stage, ".wine/dosdevices/drive-link")));
        assertTrue(WinePrefixState.isDriveMapping(stage, new File(stage, "dosdevices/drive-link")));
        assertFalse(WinePrefixState.isDriveMapping(stage, new File(stage, ".wine/dosdevices")));
        assertFalse(WinePrefixState.isDriveMapping(stage, new File(stage, ".wine/system.reg")));
        assertFalse(WinePrefixState.isDriveMapping(stage, new File(stage, ".wine/drive_c/game.exe")));
    }

    @Test public void aZeroByteSystemModuleInvalidatesAReceipt() throws Exception {
        File prefix = temp.newFolder();
        core(prefix);
        WinePrefixState.markPrepared(prefix, "runtime");
        write(prefix, "drive_c/windows/system32/ntdll.dll", "");
        assertFalse(WinePrefixState.isPrepared(prefix, "runtime"));
        assertThrows(IOException.class, () -> WinePrefixState.markPrepared(prefix, "runtime"));
    }

    @Test public void executableSelectionSupportsWine64OnlyAndRejectsEmptyWine() throws Exception {
        File bin = temp.newFolder();
        write(bin, "wine", "");
        write(bin, "wine64", "launcher");
        assertEquals(new File(bin, "wine64"), WinePrefixState.wineExecutable(bin));
        write(bin, "wine64", "");
        assertThrows(IOException.class, () -> WinePrefixState.wineExecutable(bin));
    }

    @Test public void packageIdentityAndReinstalledBinaryChangeTheFingerprint() throws Exception {
        File runtime = temp.newFolder();
        write(runtime, "bin/wine", "launcher");
        write(runtime, "share/wine/wine.inf", "setup");
        File wine = new File(runtime, "bin/wine"), inf = new File(runtime, "share/wine/wine.inf");
        String first = WinePrefixState.fingerprint("proton-10-arm64ec-1", wine, inf);
        assertNotEquals(first, WinePrefixState.fingerprint("proton-10-arm64ec-2", wine, inf));
        write(runtime, "bin/wine", "another launcher");
        assertNotEquals(first, WinePrefixState.fingerprint("proton-10-arm64ec-1", wine, inf));
    }

    @Test public void sameSizeReinstallationWithPreservedMtimeStillChangesTheFingerprint() throws Exception {
        File runtime = temp.newFolder();
        write(runtime, "wine", "version1");
        write(runtime, "wine.inf", "setup");
        File wine = new File(runtime, "wine"), inf = new File(runtime, "wine.inf");
        long timestamp = wine.lastModified();
        String first = WinePrefixState.fingerprint("same-id", wine, inf);
        write(runtime, "wine", "version2");
        assertTrue(wine.setLastModified(timestamp));
        assertNotEquals(first, WinePrefixState.fingerprint("same-id", wine, inf));
    }
}
