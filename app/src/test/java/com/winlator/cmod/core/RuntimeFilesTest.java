package com.winlator.cmod.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class RuntimeFilesTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void metadataAloneDoesNotMakeARuntimeUsable() throws Exception {
        File root = temp.newFolder();
        Files.write(new File(root, "profile.json").toPath(), "{}".getBytes());
        assertFalse(RuntimeFiles.hasWine(root, "bin"));
    }

    @Test public void emptyOrDirectoryExecutableIsNotWine() throws Exception {
        File root = temp.newFolder();
        File bin = new File(root, "bin");
        assertTrue(bin.mkdir());
        assertTrue(new File(bin, "wine").createNewFile());
        assertFalse(RuntimeFiles.hasWine(root, "bin"));
        assertTrue(new File(bin, "wine64").mkdir());
        Files.write(new File(bin, "wine64/nested").toPath(), new byte[]{1});
        assertFalse(RuntimeFiles.hasWine(root, "bin"));
    }

    @Test public void acceptsBothWineExecutableNames() throws Exception {
        for (String name : new String[]{"wine", "wine64"}) {
            File root = temp.newFolder();
            assertTrue(new File(root, "bin").mkdir());
            Files.write(new File(root, "bin/" + name).toPath(), new byte[]{1});
            assertTrue(RuntimeFiles.hasWine(root, "bin"));
        }
    }

    @Test public void emptyBridgeDllIsNotInstalled() throws Exception {
        File root = temp.newFolder();
        File dll = new File(root, "libwow64fex.dll");
        assertTrue(dll.createNewFile());
        assertFalse(RuntimeFiles.hasPayload(root, dll.getName()));
        Files.write(dll.toPath(), new byte[]{1, 2});
        assertTrue(RuntimeFiles.hasPayload(root, dll.getName()));
    }

    @Test public void refusesPayloadOutsideThePackage() throws Exception {
        File root = temp.newFolder();
        File outside = temp.newFile("outside");
        Files.write(outside.toPath(), new byte[]{1});
        assertFalse(RuntimeFiles.hasPayload(root, "../outside"));
        assertFalse(RuntimeFiles.hasPayload(root, ""));
        assertFalse(RuntimeFiles.hasPayload(root, "."));
    }
}
