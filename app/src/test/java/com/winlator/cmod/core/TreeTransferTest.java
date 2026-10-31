package com.winlator.cmod.core;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class TreeTransferTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Provider provider = new Provider();
    private TreeTransfer transfer() { return new TreeTransfer(cancelled, null); }
    private File file(String name, byte[] bytes) throws Exception {
        File file = new File(temp.getRoot(), name); Files.write(file.toPath(), bytes); return file;
    }
    private byte[] bytes(String value) { return value.getBytes(java.nio.charset.StandardCharsets.UTF_8); }
    private void noStaging(MemoryNode parent) {
        for (String name : parent.children.keySet()) assertFalse(name, name.startsWith(".wx-"));
    }

    @Test public void localFolderToProviderKeepsNamesHiddenFilesAndNestedContents() throws Exception {
        File source = temp.newFolder("Jogo é 中文"); Files.createDirectories(new File(source, "saves").toPath());
        Files.write(new File(source, "saves/slot.dat").toPath(), bytes("save")); Files.write(new File(source, ".hidden").toPath(), bytes("hidden"));
        TreeTransfer.Result result = transfer().transfer(StorageAccess.file(source), provider.root, false, false);
        assertFalse(result.sourceRemoved); assertTrue(source.isDirectory());
        MemoryNode copied = provider.root.children.get(source.getName());
        assertArrayEquals(bytes("save"), copied.children.get("saves").children.get("slot.dat").data);
        assertArrayEquals(bytes("hidden"), copied.children.get(".hidden").data); noStaging(provider.root);
    }

    @Test public void providerMoveDeletesSourceOnlyAfterVerifiedLocalPublication() throws Exception {
        MemoryNode source = provider.file("game.exe", bytes("program")); File destination = temp.newFolder();
        source.beforeDelete = () -> {
            assertArrayEquals(bytes("program"), Files.readAllBytes(new File(destination, "game.exe").toPath()));
            assertTrue(provider.reads >= 1);
        };
        assertTrue(transfer().transfer(source, StorageAccess.file(destination), true, false).sourceRemoved);
        assertFalse(provider.root.children.containsKey("game.exe"));
        assertArrayEquals(bytes("program"), Files.readAllBytes(new File(destination, "game.exe").toPath()));
    }

    @Test public void readOnlySourceCanBeCopiedButIsNotRemovedByMove() throws Exception {
        MemoryNode source = provider.file("read-only", bytes("data")); source.deletable = false;
        File destination = temp.newFolder();
        assertFalse(transfer().transfer(source, StorageAccess.file(destination), true, false).sourceRemoved);
        assertArrayEquals(bytes("data"), Files.readAllBytes(new File(destination, source.name).toPath()));
        assertTrue(provider.root.children.containsKey(source.name));
    }

    @Test public void existingDestinationNeedsExplicitReplacement() throws Exception {
        File source = file("save", bytes("new")); provider.file("save", bytes("old"));
        assertThrows(java.nio.file.FileAlreadyExistsException.class,
                () -> transfer().transfer(StorageAccess.file(source), provider.root, true, false));
        assertArrayEquals(bytes("old"), provider.root.children.get("save").data); assertTrue(source.isFile()); noStaging(provider.root);
    }

    @Test public void successfulReplacementHandlesFileToFolderAndChangedRenameIds() throws Exception {
        File source = temp.newFolder("game"); Files.write(new File(source, "save").toPath(), bytes("new"));
        MemoryNode previous = provider.file("game", bytes("old")); String oldId = previous.id;
        transfer().transfer(StorageAccess.file(source), provider.root, false, true);
        MemoryNode target = provider.root.children.get("game"); assertTrue(target.directory); assertNotEquals(oldId, target.id);
        assertArrayEquals(bytes("new"), target.children.get("save").data); noStaging(provider.root);
    }

    @Test public void failedSourceReadKeepsPreviousDestinationAndSource() throws Exception {
        MemoryNode source = provider.file("input", new byte[600000]); source.readFailure = true;
        File destination = temp.newFolder(); File previous = new File(destination, "input"); Files.write(previous.toPath(), bytes("old"));
        assertThrows(IOException.class, () -> transfer().transfer(source, StorageAccess.file(destination), true, true));
        assertArrayEquals(bytes("old"), Files.readAllBytes(previous.toPath())); assertTrue(provider.root.children.containsKey("input"));
        assertEquals(1, destination.list().length);
    }

    @Test public void corruptedDestinationReadbackCannotDeleteSourceOrReplacePreviousItem() throws Exception {
        File source = file("save", bytes("complete")); provider.file("save", bytes("old")); provider.corruptWrites = true;
        assertThrows(IOException.class, () -> transfer().transfer(StorageAccess.file(source), provider.root, true, true));
        assertTrue(source.isFile()); assertArrayEquals(bytes("old"), provider.root.children.get("save").data); noStaging(provider.root);
    }

    @Test public void cancellationDuringCopyRemovesStagingAndKeepsOldTarget() throws Exception {
        File source = file("large", new byte[600000]); provider.file("large", bytes("previous"));
        TreeTransfer transfer = new TreeTransfer(cancelled, (name, count) -> cancelled.set(true));
        assertThrows(InterruptedIOException.class, () -> transfer.transfer(StorageAccess.file(source), provider.root, true, true));
        assertTrue(source.isFile()); assertArrayEquals(bytes("previous"), provider.root.children.get("large").data); noStaging(provider.root);
    }

    @Test public void changedSourceIsKeptAndNotPublished() throws Exception {
        File source = file("changing", bytes("before"));
        TreeTransfer transfer = new TreeTransfer(cancelled, (name, count) -> {
            try { Files.write(source.toPath(), bytes("changed and longer")); } catch (IOException error) { throw new RuntimeException(error); }
        });
        assertThrows(IOException.class, () -> transfer.transfer(StorageAccess.file(source), provider.root, true, false));
        assertArrayEquals(bytes("changed and longer"), Files.readAllBytes(source.toPath())); assertTrue(provider.root.children.isEmpty());
    }

    @Test public void cancellationAfterPublishKeepsCompleteTargetAndOriginalSource() throws Exception {
        File source = file("save", bytes("content")); provider.afterPublish = () -> cancelled.set(true);
        TreeTransfer.Result result = transfer().transfer(StorageAccess.file(source), provider.root, true, false);
        assertFalse(result.sourceRemoved); assertTrue(source.isFile()); assertArrayEquals(bytes("content"), provider.root.children.get("save").data);
        noStaging(provider.root);
    }

    @Test public void failedFinalRenameRestoresPreviousItem() throws Exception {
        File source = file("game", bytes("new")); provider.file("game", bytes("old")); provider.failPublish = true;
        assertThrows(IOException.class, () -> transfer().transfer(StorageAccess.file(source), provider.root, true, true));
        assertArrayEquals(bytes("old"), provider.root.children.get("game").data); assertTrue(source.isFile()); noStaging(provider.root);
    }

    @Test public void failedRollbackRetainsRecoveryBackupInsteadOfDeletingIt() throws Exception {
        File source = file("game", bytes("new")); provider.file("game", bytes("old")); provider.failPublish = provider.failRollback = true;
        TreeTransfer.RecoveryException error = assertThrows(TreeTransfer.RecoveryException.class,
                () -> transfer().transfer(StorageAccess.file(source), provider.root, true, true));
        assertEquals(1, error.recovery.size()); MemoryNode retained = provider.root.children.get(error.recovery.get(0));
        assertNotNull(retained); assertArrayEquals(bytes("old"), retained.data); assertTrue(source.isFile());
    }

    @Test public void providerWithoutRenameStillSupportsNewNames() throws Exception {
        File source = file("new", bytes("data")); provider.renameNew = false;
        transfer().transfer(StorageAccess.file(source), provider.root, false, false);
        assertArrayEquals(bytes("data"), provider.root.children.get("new").data); noStaging(provider.root);
    }

    @Test public void providerWithoutRenameCannotDropChildrenWhileItsNewFolderIndexIsStale() throws Exception {
        File source = temp.newFolder("game"); Files.write(new File(source, "save.dat").toPath(), bytes("keep save"));
        provider.renameNew = false; provider.hideStagingChildren = true;
        assertTrue(transfer().transfer(StorageAccess.file(source), provider.root, true, false).sourceRemoved);
        assertFalse(source.exists()); assertArrayEquals(bytes("keep save"), provider.root.children.get("game").children.get("save.dat").data);
        noStaging(provider.root);
    }

    @Test public void providerWithoutSafeReplacementKeepsExistingBytesWithoutCopying() throws Exception {
        File source = file("save", bytes("new")); MemoryNode old = provider.file("save", bytes("old")); old.renamable = false;
        assertThrows(TreeTransfer.ReplaceNotSupportedException.class,
                () -> transfer().transfer(StorageAccess.file(source), provider.root, true, true));
        assertEquals(0, provider.created); assertArrayEquals(bytes("old"), old.data); assertTrue(source.isFile());
    }

    @Test public void unknownSizeAndEmptyFilesAreVerifiedByContent() throws Exception {
        MemoryNode source = provider.file("unknown", bytes("stream")); source.unknownSize = true;
        File target = temp.newFolder(); transfer().transfer(source, StorageAccess.file(target), false, false);
        assertArrayEquals(bytes("stream"), Files.readAllBytes(new File(target, "unknown").toPath()));
        transfer().transfer(provider.file("empty", new byte[0]), StorageAccess.file(target), false, false);
        assertEquals(0, new File(target, "empty").length());
    }

    @Test public void cyclesAndDescendantDestinationsAreRejectedBeforeCreation() throws Exception {
        MemoryNode source = provider.folder("source"); MemoryNode inside = provider.folder(source, "inside");
        assertThrows(IOException.class, () -> transfer().transfer(source, inside, false, false));
        source.children.put("cycle", source);
        assertThrows(IOException.class, () -> transfer().transfer(source, StorageAccess.file(temp.newFolder()), false, false));
        assertEquals(0, provider.created);
    }

    @Test public void invalidNamesCannotEscapeTheDestination() throws Exception {
        for (String name : new String[]{"..", ".", "../outside", "a/b", "a\\b", "", "bad\0name"}) {
            MemoryNode source = provider.file("original", bytes("x")); source.name = name;
            assertThrows(IOException.class, () -> transfer().transfer(source, StorageAccess.file(temp.newFolder()), false, false));
        }
    }

    @Test public void newSourceChildrenDuringCopyDoNotGetDeletedOrSilentlySkipped() throws Exception {
        MemoryNode source = provider.folder("folder"); provider.file(source, "first", bytes("old"));
        TreeTransfer transfer = new TreeTransfer(cancelled, (name, count) -> provider.file(source, "new-save", bytes("new")));
        File destination = temp.newFolder();
        assertThrows(IOException.class, () -> transfer.transfer(source, StorageAccess.file(destination), true, false));
        assertTrue(source.children.containsKey("new-save")); assertEquals(0, destination.list().length);
    }

    @Test public void largeFoldersAreNotRelistedForEachCreatedFile() throws Exception {
        File source = temp.newFolder("many"); for (int i = 0; i < 300; i++) Files.write(new File(source, "f" + i).toPath(), new byte[]{42});
        transfer().transfer(StorageAccess.file(source), provider.root, false, false);
        assertEquals(300, provider.root.children.get("many").children.size()); assertTrue("Listing calls=" + provider.listings, provider.listings < 15);
    }

    @Test public void cancellationClosesBlockedProviderInputAndPreservesTheSource() throws Exception {
        java.util.concurrent.CountDownLatch reading = new java.util.concurrent.CountDownLatch(1), closed = new java.util.concurrent.CountDownLatch(1);
        MemoryNode source = new MemoryNode(provider, provider.root, "blocked", false) {
            @Override public InputStream read() {
                return new InputStream() {
                    @Override public int read() throws IOException {
                        reading.countDown();
                        try { if (!closed.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("Timed out"); }
                        catch (InterruptedException error) { throw new IOException(error); }
                        throw new IOException("Input was closed");
                    }
                    @Override public void close() { closed.countDown(); }
                };
            }
        };
        provider.root.children.put(source.name, source); File destination = temp.newFolder();
        java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        TreeTransfer transfer = transfer();
        try {
            java.util.concurrent.Future<?> operation = worker.submit(() -> {
                try { transfer.transfer(source, StorageAccess.file(destination), true, false); throw new AssertionError("Unexpected success"); }
                catch (IOException expected) {}
            });
            assertTrue(reading.await(5, java.util.concurrent.TimeUnit.SECONDS)); transfer.cancel(); operation.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(provider.root.children.containsKey(source.name)); assertEquals(0, destination.list().length);
        } finally { closed.countDown(); worker.shutdownNow(); }
    }

    private interface Checked { void run() throws Exception; }
    private class Provider {
        final MemoryNode root = new MemoryNode(this, null, "root", true);
        int created, reads, listings;
        boolean corruptWrites, renameNew = true, failPublish, failRollback, hideStagingChildren;
        Runnable afterPublish;
        MemoryNode file(String name, byte[] data) { return file(root, name, data); }
        MemoryNode file(MemoryNode parent, String name, byte[] data) { MemoryNode n = new MemoryNode(this, parent, name, false); n.data = data.clone(); parent.children.put(name, n); parent.modified++; return n; }
        MemoryNode folder(String name) { return folder(root, name); }
        MemoryNode folder(MemoryNode parent, String name) { MemoryNode n = new MemoryNode(this, parent, name, true); parent.children.put(name, n); parent.modified++; return n; }
    }
    private class MemoryNode implements TreeTransfer.Node {
        final Provider provider;
        MemoryNode parent;
        String name, id = UUID.randomUUID().toString();
        boolean directory, renamable = true, deletable = true, unknownSize, readFailure;
        long modified = 1;
        byte[] data = new byte[0];
        Map<String, MemoryNode> children = new LinkedHashMap<>();
        Checked beforeDelete;
        MemoryNode(Provider provider, MemoryNode parent, String name, boolean directory) { this.provider = provider; this.parent = parent; this.name = name; this.directory = directory; }
        @Override public String key() { return id; }
        @Override public TreeTransfer.Info info() { return new TreeTransfer.Info(name, directory ? "folder" : "binary", directory, unknownSize ? -1 : data.length, modified, renamable, deletable, directory); }
        @Override public List<TreeTransfer.Node> children() {
            provider.listings++; return provider.hideStagingChildren && name.startsWith(".wx-transfer-") ? Collections.emptyList() : new ArrayList<>(children.values());
        }
        @Override public TreeTransfer.Node create(String name, TreeTransfer.Info source) throws IOException {
            if (children.containsKey(name)) throw new IOException("Exists"); provider.created++;
            MemoryNode n = source.directory ? provider.folder(this, name) : provider.file(this, name, new byte[0]); n.renamable = provider.renameNew; return n;
        }
        @Override public TreeTransfer.Node rename(String next) throws IOException {
            if (!renamable || provider.failPublish && name.startsWith(".wx-transfer-") && !next.startsWith(".wx-")) throw new IOException("Rename failed");
            if (provider.failRollback && name.startsWith(".wx-previous-")) throw new IOException("Rollback failed");
            if (parent.children.containsKey(next)) throw new IOException("Exists");
            parent.children.remove(name); name = next; id = UUID.randomUUID().toString(); parent.children.put(name, this); parent.modified++;
            if (!next.startsWith(".wx-") && provider.afterPublish != null) provider.afterPublish.run(); return this;
        }
        @Override public void delete() throws IOException {
            if (!deletable) throw new IOException("Read only");
            try { if (beforeDelete != null) beforeDelete.run(); } catch (Exception error) { throw new IOException(error); }
            parent.children.remove(name); parent.modified++;
        }
        @Override public InputStream read() {
            provider.reads++;
            if (!readFailure) return new ByteArrayInputStream(data);
            return new InputStream() { int calls;
                @Override public int read() throws IOException { throw new IOException("Disconnected"); }
                @Override public int read(byte[] b, int off, int len) throws IOException { if (++calls > 1) throw new IOException("Disconnected"); Arrays.fill(b, off, off + Math.min(len, 17), (byte)0); return Math.min(len, 17); }
            };
        }
        @Override public OutputStream write() {
            return new ByteArrayOutputStream() {
                @Override public void close() { data = toByteArray(); if (provider.corruptWrites && data.length > 0) data[0] ^= 1; modified++; }
            };
        }
    }
}
