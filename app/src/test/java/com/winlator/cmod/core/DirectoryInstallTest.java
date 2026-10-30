package com.winlator.cmod.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class DirectoryInstallTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void installsByMovingStagingWithoutACopy() throws Exception {
        File staging = temp.newFolder();
        Files.write(new File(staging, "payload").toPath(), new byte[]{1});
        File destination = new File(temp.getRoot(), "installed/new");
        assertNull(DirectoryInstall.commit(staging, destination));
        assertFalse(staging.exists());
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(new File(destination, "payload").toPath()));
    }

    @Test public void retainsAnIncompletePreviousPackageUntilCommitSucceeds() throws Exception {
        File staging = temp.newFolder();
        File destination = temp.newFolder();
        Files.write(new File(staging, "new").toPath(), new byte[]{2});
        Files.write(new File(destination, "old").toPath(), new byte[]{1});
        File previous = DirectoryInstall.commit(staging, destination);
        assertTrue(new File(previous, "old").isFile());
        assertTrue(new File(destination, "new").isFile());
        assertFalse(new File(destination, "old").exists());
    }

    @Test public void restoresPreviousPackageWhenTheFinalMoveFails() throws Exception {
        File staging = temp.newFolder();
        File destination = temp.newFolder();
        Files.write(new File(destination, "old").toPath(), new byte[]{1});
        File failing = new File(staging.getAbsolutePath()) {
            @Override public File getCanonicalFile() { return this; }
            @Override public boolean renameTo(File target) { return false; }
        };
        assertThrows(IOException.class, () -> DirectoryInstall.commit(failing, destination));
        assertTrue(new File(destination, "old").isFile());
        assertTrue(staging.isDirectory());
    }

    @Test public void missingStagingDoesNotMoveThePreviousPackage() throws Exception {
        File destination = temp.newFolder();
        Files.write(new File(destination, "old").toPath(), new byte[]{1});
        assertThrows(IOException.class, () -> DirectoryInstall.commit(null, destination));
        assertTrue(new File(destination, "old").isFile());
    }

    @Test public void refusesOverlappingPaths() throws Exception {
        File staging = temp.newFolder();
        assertThrows(IOException.class, () -> DirectoryInstall.commit(staging, staging));
        assertThrows(IOException.class, () -> DirectoryInstall.commit(staging, new File(staging, "child")));
        assertThrows(IOException.class, () -> DirectoryInstall.commit(staging, staging.getParentFile()));
    }
}
