package com.winlator.cmod.core;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Per-item transfer: publish a complete staged item before removing a move's source. */
public final class FileTransfer {
    public interface Progress {
        void copied(String name, long bytes);
        default void published(String name) {}
    }
    interface Rename { boolean move(Path source, Path destination); }
    public static final class Result {
        public final boolean sourceRemoved;
        public final File retainedBackup;
        public final File retainedSource;
        private Result(boolean sourceRemoved, Path backup, Path source) {
            this.sourceRemoved = sourceRemoved;
            this.retainedBackup = backup == null ? null : backup.toFile();
            this.retainedSource = source == null ? null : source.toFile();
        }
    }

    private final AtomicBoolean cancelled;
    private final Progress progress;
    private final Rename rename;
    private final ByteBuffer buffer = ByteBuffer.allocateDirect(256 * 1024);
    private long copiedBytes;

    public FileTransfer(AtomicBoolean cancelled, Progress progress) {
        this(cancelled, progress, (source, destination) -> source.toFile().renameTo(destination.toFile()));
    }

    FileTransfer(AtomicBoolean cancelled, Progress progress, Rename rename) {
        this.cancelled = cancelled;
        this.progress = progress;
        this.rename = rename;
    }

    public Result transfer(File source, File destination, boolean move, boolean replace) throws IOException {
        Path src = entryPath(source.toPath());
        Path dst = entryPath(destination.toPath());
        if (src.equals(dst)) return new Result(false, null, null);
        if (src.startsWith(dst) || dst.startsWith(src)) throw new IOException("Overlapping transfer paths");
        checkCancelled();
        if (!exists(src)) throw new IOException("Source is missing: " + src);
        if (!replace && exists(dst)) throw new FileAlreadyExistsException(dst.toString());
        Path work = Files.createTempDirectory(dst.getParent(), ".wx-transfer-");
        Path staged = work.resolve("payload");
        Path backup = work.resolve("previous");
        boolean renamedSource = false;
        boolean committed = false;
        Map<Path, Snapshot> snapshots = new LinkedHashMap<>();
        try {
            // renameTo never implements a recursive cross-filesystem copy. A whole
            // directory on the same mount moves without a preliminary size scan.
            renamedSource = move && rename.move(src, staged);
            if (!renamedSource) copyTree(src, staged, snapshots);
            checkCancelled();
            publish(staged, dst, backup, replace);
            committed = true;
            if (progress != null) progress.published(src.getFileName().toString());
            boolean removed = renamedSource;
            Path retainedSource = null;
            if (move && !renamedSource && !cancelled.get()) {
                Removal removal = removeCopiedSource(src, snapshots);
                removed = removal.removed;
                retainedSource = removal.recovery;
            }
            if (exists(backup)) {
                try { deleteTree(backup); }
                catch (IOException ignored) { /* Retain the previous item for recovery. */ }
            }
            return new Result(removed, exists(backup) ? backup : null, retainedSource);
        } finally {
            if (!committed && renamedSource && exists(staged)) {
                // Never delete the only remaining source after cancellation or a failed commit.
                try { Files.move(staged, src); }
                catch (IOException error) {
                    throw new IOException("Source retained for recovery at " + staged, error);
                }
            } else if (!committed && exists(staged)) {
                deleteTree(staged);
            }
            // A failed rollback or backup cleanup must remain recoverable.
            if (!exists(staged) && !exists(backup)) Files.deleteIfExists(work);
        }
    }

    private static Path entryPath(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (absolute.getParent() == null) throw new IOException("Cannot transfer a filesystem root");
        // Resolve parent aliases, but never follow the selected leaf symlink.
        return absolute.getParent().toRealPath().resolve(absolute.getFileName());
    }

    private static boolean exists(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    private void checkCancelled() throws InterruptedIOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Transfer cancelled");
        }
    }

    private static synchronized void publish(Path staged, Path destination, Path backup,
                                              boolean replace) throws IOException {
        if (exists(destination)) {
            if (!replace) throw new FileAlreadyExistsException(destination.toString());
            Files.move(destination, backup);
        }
        try { Files.move(staged, destination); }
        catch (IOException error) {
            if (exists(backup)) {
                try { Files.move(backup, destination); }
                catch (IOException rollback) {
                    error.addSuppressed(new IOException("Previous item retained at " + backup, rollback));
                }
            }
            throw error;
        }
    }

    private void copyTree(Path source, Path destination, Map<Path, Snapshot> snapshots) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            private Path target(Path path) { return destination.resolve(source.relativize(path)); }

            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                checkCancelled();
                // Directory enumerators may return cached/incomplete file keys.
                // Capture and later compare metadata using the same API.
                snapshots.put(dir, new Snapshot(dir, Files.readAttributes(dir,
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)));
                if (!snapshots.get(dir).directory) throw new IOException("Source type changed: " + dir);
                Files.createDirectory(target(dir));
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                checkCancelled();
                Snapshot snapshot = new Snapshot(file, Files.readAttributes(file,
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
                snapshots.put(file, snapshot);
                if (snapshot.symbolic) {
                    Files.createSymbolicLink(target(file), snapshot.link);
                } else if (snapshot.regular) {
                    copyFile(file, target(file), snapshot.size);
                    if (!snapshot.matches(file)) throw new IOException("Source changed during copy: " + file);
                    preserveMetadata(file, target(file), snapshot.modified);
                } else throw new IOException("Unsupported file type: " + file);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                checkCancelled();
                if (!snapshots.get(dir).matches(dir)) throw new IOException("Folder changed during copy: " + dir);
                preserveMetadata(dir, target(dir), snapshots.get(dir).modified);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void copyFile(Path source, Path destination, long size) throws IOException {
        try (FileChannel in = FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             FileChannel out = FileChannel.open(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            long position = 0;
            while (position < size) {
                checkCancelled();
                long count = in.transferTo(position, Math.min(8L * 1024 * 1024, size - position), out);
                if (count == 0) {
                    buffer.clear();
                    buffer.limit((int) Math.min(buffer.capacity(), size - position));
                    int read = in.read(buffer, position);
                    if (read <= 0) throw new IOException("Incomplete source: " + source);
                    buffer.flip();
                    while (buffer.hasRemaining()) {
                        checkCancelled();
                        if (out.write(buffer) <= 0) throw new IOException("Destination stopped accepting data");
                    }
                    count = read;
                }
                position += count;
                copiedBytes += count;
                if (progress != null) progress.copied(source.getFileName().toString(), copiedBytes);
            }
            if (in.size() != size || out.size() != size) throw new IOException("File size changed during copy");
        }
    }

    private static void preserveMetadata(Path source, Path destination, FileTime modified) {
        try { Files.setLastModifiedTime(destination, modified); }
        catch (IOException ignored) {}
        try { Files.setPosixFilePermissions(destination, Files.getPosixFilePermissions(source)); }
        catch (IOException | UnsupportedOperationException ignored) {}
    }

    private static final class Removal {
        final boolean removed;
        final Path recovery;
        Removal(boolean removed, Path recovery) { this.removed = removed; this.recovery = recovery; }
    }

    private Removal removeCopiedSource(Path source, Map<Path, Snapshot> snapshots) {
        int[] verified = {0};
        try {
            // A directory mtime can have coarse granularity. Enumerate names as
            // well so a newly created save is never mistaken for copied data.
            Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
                private void verify(Path path) throws IOException {
                    Snapshot snapshot = snapshots.get(path);
                    if (snapshot == null || !snapshot.matches(path)) throw new IOException("Source changed");
                    verified[0]++;
                }
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    checkCancelled(); verify(dir); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    checkCancelled(); verify(file); return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException error) { return new Removal(false, null); }
        if (cancelled.get() || verified[0] != snapshots.size()) return new Removal(false, null);
        Path recovery = source.resolveSibling(".wx-moved-" + UUID.randomUUID());
        try { Files.move(source, recovery); }
        catch (IOException error) { return new Removal(false, null); }
        // Never leave a half-deleted tree at the clipboard's original path. If
        // cleanup fails or is cancelled, the complete destination stays authoritative
        // and the remaining source data has a separate, reported recovery path.
        ArrayList<Path> paths = new ArrayList<>(snapshots.keySet());
        Collections.reverse(paths);
        for (Path path : paths) {
            if (cancelled.get()) return new Removal(true, recovery);
            Snapshot snapshot = snapshots.get(path);
            Path moved = recovery.resolve(source.relativize(path));
            try {
                // Removing children changes directory timestamps; delete itself
                // refuses nonempty directories/new children that weren't copied.
                if (!snapshot.matchesIdentity(moved, snapshot.directory)) return new Removal(true, recovery);
                Files.delete(moved);
            } catch (IOException error) { return new Removal(true, recovery); }
        }
        return new Removal(true, null);
    }

    private static final class Snapshot {
        final long size;
        final FileTime modified;
        final Object key;
        final boolean directory;
        final boolean symbolic;
        final boolean regular;
        final Path link;

        Snapshot(Path path, BasicFileAttributes attrs) throws IOException {
            size = attrs.size(); modified = attrs.lastModifiedTime(); key = attrs.fileKey();
            directory = attrs.isDirectory(); symbolic = attrs.isSymbolicLink();
            regular = attrs.isRegularFile();
            link = symbolic ? Files.readSymbolicLink(path) : null;
        }

        boolean matches(Path path) throws IOException { return matchesIdentity(path, false); }
        boolean matchesIdentity(Path path, boolean ignoreDirectoryTime) throws IOException {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attrs.isDirectory() == directory && attrs.isSymbolicLink() == symbolic
                    && attrs.isRegularFile() == regular
                    && Objects.equals(key, attrs.fileKey())
                    && (ignoreDirectoryTime || size == attrs.size() && modified.equals(attrs.lastModifiedTime()))
                    && (!symbolic || link.equals(Files.readSymbolicLink(path)));
        }
    }

    private static void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }
}
