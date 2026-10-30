package com.winlator.cmod.core;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class FileTransferTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final AtomicBoolean cancelled = new AtomicBoolean();

    private File file(String name, byte... bytes) throws IOException {
        File file = new File(temp.getRoot(), name);
        Files.createDirectories(file.toPath().getParent());
        Files.write(file.toPath(), bytes);
        return file;
    }
    private File destination(String name) { return new File(temp.getRoot(), name); }
    private void assertNoWorkDirectories() {
        assertEquals(0, temp.getRoot().listFiles(f -> f.getName().startsWith(".wx-transfer-")).length);
    }

    @Test public void copiedBytesAndSourceSurviveLargeFileCopy() throws Exception {
        byte[] bytes = new byte[9 * 1024 * 1024 + 13];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 31);
        File source = file("game.exe", bytes);
        File target = destination("copied.exe");
        long[] progress = {0};
        FileTransfer.Result result = new FileTransfer(cancelled, (name, copied) -> progress[0] = copied)
                .transfer(source, target, false, false);
        assertArrayEquals(bytes, Files.readAllBytes(target.toPath()));
        assertArrayEquals(bytes, Files.readAllBytes(source.toPath()));
        assertEquals(bytes.length, progress[0]);
        assertFalse(result.sourceRemoved);
        assertNull(result.retainedBackup);
        assertNoWorkDirectories();
    }

    @Test public void completeFolderReplacesOldFolderWithoutMerging() throws Exception {
        File source = temp.newFolder("new-tree");
        file("new-tree/sub/game.exe", (byte) 3);
        File target = temp.newFolder("installed");
        file("installed/old-save", (byte) 8);
        new FileTransfer(cancelled, null).transfer(source, target, false, true);
        assertFalse(new File(target, "old-save").exists());
        assertArrayEquals(new byte[]{3}, Files.readAllBytes(new File(target, "sub/game.exe").toPath()));
        assertTrue(new File(source, "sub/game.exe").exists());
        assertNoWorkDirectories();
    }

    @Test public void sameMountMoveNeedsNoCopyOrFolderSizeScan() throws Exception {
        File source = temp.newFolder("source");
        file("source/a/b.bin", (byte) 7);
        File target = destination("moved");
        FileTransfer.Result result = new FileTransfer(cancelled, (name, bytes) -> fail("Rename must not copy bytes"))
                .transfer(source, target, true, false);
        assertTrue(result.sourceRemoved);
        assertFalse(source.exists());
        assertTrue(new File(target, "a/b.bin").exists());
        assertNoWorkDirectories();
    }

    @Test public void fallbackMoveRemovesSourceOnlyAfterPublishingCopy() throws Exception {
        File source = temp.newFolder("source");
        file("source/sub/file", (byte) 4);
        File target = destination("moved");
        FileTransfer.Result result = new FileTransfer(cancelled, (name, bytes) -> {
            assertTrue(source.exists());
            assertFalse(target.exists());
        }, (a, b) -> false).transfer(source, target, true, false);
        assertTrue(result.sourceRemoved);
        assertFalse(source.exists());
        assertArrayEquals(new byte[]{4}, Files.readAllBytes(new File(target, "sub/file").toPath()));
        assertNoWorkDirectories();
    }

    @Test public void unconfirmedConflictPreservesBothFiles() throws Exception {
        File source = file("new", (byte) 1);
        File target = file("old", (byte) 2);
        assertThrows(FileAlreadyExistsException.class,
                () -> new FileTransfer(cancelled, null).transfer(source, target, false, false));
        assertArrayEquals(new byte[]{2}, Files.readAllBytes(target.toPath()));
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(source.toPath()));
        assertNoWorkDirectories();
    }

    @Test public void cancellationAfterPublicationKeepsACompleteRetryableSource() throws Exception {
        File source = temp.newFolder("source");
        file("source/a", (byte) 1);
        file("source/b", (byte) 2);
        File target = destination("target");
        FileTransfer.Result result = new FileTransfer(cancelled, new FileTransfer.Progress() {
            @Override public void copied(String name, long bytes) {}
            @Override public void published(String name) { cancelled.set(true); }
        }, (a, b) -> false).transfer(source, target, true, false);
        assertFalse(result.sourceRemoved);
        for (String name : new String[]{"a", "b"}) {
            assertArrayEquals(Files.readAllBytes(new File(source, name).toPath()),
                    Files.readAllBytes(new File(target, name).toPath()));
        }
        assertNoWorkDirectories();
    }

    @Test public void newSourceDataAfterPublicationIsPreservedForTheNextAttempt() throws Exception {
        File source = temp.newFolder("source");
        file("source/original", (byte) 1);
        File target = destination("target");
        FileTransfer.Result result = new FileTransfer(cancelled, new FileTransfer.Progress() {
            @Override public void copied(String name, long bytes) {}
            @Override public void published(String name) {
                try { file("source/new-save", (byte) 2); }
                catch (IOException error) { throw new RuntimeException(error); }
            }
        }, (a, b) -> false).transfer(source, target, true, false);
        assertFalse(result.sourceRemoved);
        assertTrue(new File(source, "original").exists());
        assertTrue(new File(source, "new-save").exists());
        assertTrue(new File(target, "original").exists());
        assertFalse(new File(target, "new-save").exists());
        assertNoWorkDirectories();
    }

    @Test public void cancelDuringReplacementKeepsOldDestinationAndEntireSource() throws Exception {
        File source = file("new", new byte[9 * 1024 * 1024]);
        File target = file("old", (byte) 9);
        assertThrows(InterruptedIOException.class, () -> new FileTransfer(cancelled, (name, bytes) -> cancelled.set(true))
                .transfer(source, target, false, true));
        assertEquals(9 * 1024 * 1024, source.length());
        assertArrayEquals(new byte[]{9}, Files.readAllBytes(target.toPath()));
        assertNoWorkDirectories();
    }

    @Test public void cancelAfterFastRenameRestoresSource() throws Exception {
        File source = file("source", (byte) 6);
        File target = file("old", (byte) 5);
        assertThrows(InterruptedIOException.class, () -> new FileTransfer(cancelled, null, (a, b) -> {
            boolean renamed = a.toFile().renameTo(b.toFile());
            cancelled.set(true);
            return renamed;
        }).transfer(source, target, true, true));
        assertArrayEquals(new byte[]{6}, Files.readAllBytes(source.toPath()));
        assertArrayEquals(new byte[]{5}, Files.readAllBytes(target.toPath()));
        assertNoWorkDirectories();
    }

    @Test public void lateConflictRestoresRenamedSourceAndPreservesNewDestination() throws Exception {
        File source = file("source", (byte) 6);
        File target = destination("target");
        assertThrows(FileAlreadyExistsException.class, () -> new FileTransfer(cancelled, null, (a, b) -> {
            boolean renamed = a.toFile().renameTo(b.toFile());
            try { Files.write(target.toPath(), new byte[]{5}); }
            catch (IOException error) { throw new RuntimeException(error); }
            return renamed;
        }).transfer(source, target, true, false));
        assertArrayEquals(new byte[]{6}, Files.readAllBytes(source.toPath()));
        assertArrayEquals(new byte[]{5}, Files.readAllBytes(target.toPath()));
        assertNoWorkDirectories();
    }

    @Test public void changedSourceNeverReplacesPreviousDestination() throws Exception {
        File source = file("source", new byte[9 * 1024 * 1024]);
        File target = file("old", (byte) 5);
        assertThrows(IOException.class, () -> new FileTransfer(cancelled, (name, bytes) -> {
            try { Files.write(source.toPath(), new byte[]{1}); }
            catch (IOException error) { throw new RuntimeException(error); }
        }).transfer(source, target, false, true));
        assertArrayEquals(new byte[]{5}, Files.readAllBytes(target.toPath()));
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(source.toPath()));
        assertNoWorkDirectories();
    }

    @Test public void refusesCopyIntoItselfAndReplacementOfAncestor() throws Exception {
        File source = temp.newFolder("source");
        File nested = new File(source, "nested");
        assertThrows(IOException.class, () -> new FileTransfer(cancelled, null).transfer(source, nested, false, false));
        File inside = file("source/game.exe", (byte) 1);
        assertThrows(IOException.class, () -> new FileTransfer(cancelled, null).transfer(inside, source, true, true));
        assertTrue(inside.exists());
        assertNoWorkDirectories();
    }

    @Test public void emptyFilesAndFoldersRemainValid() throws Exception {
        File emptyFile = file("empty");
        new FileTransfer(cancelled, null).transfer(emptyFile, destination("empty-copy"), false, false);
        assertEquals(0, destination("empty-copy").length());
        File emptyDir = temp.newFolder("empty-dir");
        new FileTransfer(cancelled, null).transfer(emptyDir, destination("dir-copy"), false, false);
        assertTrue(destination("dir-copy").isDirectory());
        assertNoWorkDirectories();
    }

    @Test public void symlinksAreCopiedWithoutTraversingTheirTargets() throws Exception {
        File source = temp.newFolder("source");
        File outside = file("outside/keep", (byte) 8);
        Path link = new File(source, "link").toPath();
        try { Files.createSymbolicLink(link, outside.toPath().getParent()); }
        catch (IOException | UnsupportedOperationException error) { Assume.assumeNoException(error); }
        File target = destination("copy");
        new FileTransfer(cancelled, null).transfer(source, target, false, false);
        assertTrue(Files.isSymbolicLink(new File(target, "link").toPath()));
        assertEquals(outside.toPath().getParent(), Files.readSymbolicLink(new File(target, "link").toPath()));
        assertArrayEquals(new byte[]{8}, Files.readAllBytes(outside.toPath()));
        // TemporaryFolder's Windows cleanup may follow directory symlinks.
        Files.delete(new File(target, "link").toPath());
        Files.delete(link);
        assertNoWorkDirectories();
    }
}
