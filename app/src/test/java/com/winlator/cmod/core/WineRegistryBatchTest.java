package com.winlator.cmod.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class WineRegistryBatchTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private File registry(String text) throws Exception {
        File file = temp.newFile();
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return file;
    }
    private String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test public void changesOnlyRequestedValuesAndPreservesMetadataAndUnrelatedMultilineData() throws Exception {
        File file = registry("WINE REGISTRY Version 2\n#arch=win64\n;; comment\n"
                + "[Settings] 123\n#time=01234567\n\"Start\"=dword:00000002\n"
                + "\"Keep\"=hex:01,\\\n  02,03\n\"Label\"=\"ação\"\n\n[Other]\n\"Save\"=\"yes\"\n");
        try (WineRegistryBatch batch = new WineRegistryBatch(file)) {
            batch.setDwordValue("Settings", "Start", 4);
            batch.setStringValue("Settings", "Added", "ready");
        }
        String output = read(file);
        assertTrue(output.contains("[Settings] 123\n#time=01234567"));
        assertTrue(output.contains("\"Start\"=dword:00000004"));
        assertTrue(output.contains("\"Added\"=\"ready\""));
        assertTrue(output.contains("\"Keep\"=hex:01,\\\n  02,03\n\"Label\"=\"ação\""));
        assertTrue(output.contains("[Other]\n\"Save\"=\"yes\""));
    }

    @Test public void replacesAndRemovesEntireContinuedValuesWithoutEatingTheNextValue() throws Exception {
        File file = registry("WINE REGISTRY Version 2\n[Data]\n\"Replace\"=hex:01,\\\n  02,\\\n  03\n"
                + "\"Delete\"=hex:04,\\\n  05\n\"Keep\"=\"value\"\n");
        try (WineRegistryBatch batch = new WineRegistryBatch(file)) {
            batch.setHexValue("Data", "Replace", new byte[]{0, (byte)255});
            batch.removeValue("Data", "Delete");
        }
        assertEquals("WINE REGISTRY Version 2\n[Data]\n\"Replace\"=hex:00,ff\n\"Keep\"=\"value\"\n", read(file));
    }

    @Test public void unchangedSettingsKeepCrlfBytesAndTimestamp() throws Exception {
        String original = "WINE REGISTRY Version 2\r\n#arch=win64\r\n[Settings]\r\n\"Start\"=dword:00000004\r\n";
        File file = registry(original);
        assertTrue(file.setLastModified(1_600_000_000_000L));
        long stamp = file.lastModified();
        try (WineRegistryBatch batch = new WineRegistryBatch(file)) {
            batch.setDwordValue("Settings", "Start", 4);
            batch.removeValue("Absent", "Missing");
        }
        assertEquals(original, read(file));
        assertEquals(stamp, file.lastModified());
    }

    @Test public void servicePolicyNeverCreatesNonexistentControlSetsOrServices() throws Exception {
        File file = registry("WINE REGISTRY Version 2\n[System\\\\CurrentControlSet\\\\Services\\\\winebus]\n"
                + "\"Start\"=dword:00000004\n");
        try (WineRegistryBatch batch = new WineRegistryBatch(file)) {
            batch.setCreateKeyIfNotExist(false);
            for (String control : new String[]{"CurrentControlSet", "ControlSet001", "ControlSet002"})
                batch.setDwordValue("System\\" + control + "\\Services\\winebus", "Start", 2);
        }
        assertTrue(read(file).contains("\"Start\"=dword:00000002"));
        assertFalse(read(file).contains("ControlSet001"));
        assertFalse(read(file).contains("ControlSet002"));
    }

    @Test public void escapedKeyAndValueNamesAndDefaultValuesRoundTripThroughExistingReader() throws Exception {
        File file = registry("WINE REGISTRY Version 2\n#arch=win64\n");
        String key = "Software\\A \"quoted\" key";
        try (WineRegistryBatch batch = new WineRegistryBatch(file)) {
            batch.setStringValue(key, null, "C:\\Games\\ação.exe \"%1\"");
            batch.setStringValue(key, "A \"name\"", "old");
            batch.setStringValue(key, "A \"name\"", "new");
            batch.setDwordValue(key, "Dword", -1);
        }
        try (WineRegistryEditor reader = new WineRegistryEditor(file)) {
            assertEquals("C:\\Games\\ação.exe \"%1\"", reader.getStringValue(key, null));
            assertEquals("new", reader.getStringValue(key, "A \"name\""));
        }
        assertTrue(read(file).contains("\"Dword\"=dword:ffffffff"));
    }

    @Test public void malformedOrMissingRegistryIsNeverReplacedByPartialSettings() throws Exception {
        File file = registry("broken\n[Important]\n\"Saved\"=\"yes\"\n");
        String original = read(file);
        assertThrows(UncheckedIOException.class, () -> {
            try (WineRegistryBatch batch = new WineRegistryBatch(file)) {
                batch.setStringValue("Settings", "New", "value");
            }
        });
        assertEquals(original, read(file));
        assertEquals(1, temp.getRoot().listFiles().length);
        File missing = new File(temp.getRoot(), "missing.reg");
        assertThrows(UncheckedIOException.class, () -> {
            try (WineRegistryBatch batch = new WineRegistryBatch(missing)) {
                batch.setStringValue("Settings", "New", "value");
            }
        });
        assertFalse(missing.exists());
    }
}
