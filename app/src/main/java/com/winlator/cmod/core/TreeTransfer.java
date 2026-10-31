package com.winlator.cmod.core;

import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cross-provider copy/move. Local-to-local transfers retain FileTransfer's rename path. */
public final class TreeTransfer {
    public static final class Info {
        public final String name, mime;
        public final boolean directory, rename, delete, create;
        public final long size, modified;
        public Info(String name, String mime, boolean directory, long size, long modified,
                    boolean rename, boolean delete, boolean create) {
            this.name = name; this.mime = mime; this.directory = directory; this.size = size; this.modified = modified;
            this.rename = rename; this.delete = delete; this.create = create;
        }
    }
    public interface Node {
        String key() throws IOException;
        Info info() throws IOException;
        default Info listedInfo() throws IOException { return info(); }
        default String recoveryName() throws IOException { return info().name; }
        List<Node> children() throws IOException;
        Node create(String name, Info source) throws IOException;
        Node rename(String name) throws IOException;
        void delete() throws IOException;
        InputStream read() throws IOException;
        OutputStream write() throws IOException;
    }
    public interface Progress {
        void copied(String name, long bytes);
        default void verifying(String name) {}
    }
    public static final class Result {
        public final boolean sourceRemoved;
        public final List<String> recovery;
        Result(boolean removed, List<String> recovery) {
            sourceRemoved = removed; this.recovery = Collections.unmodifiableList(new ArrayList<>(recovery));
        }
    }
    public static final class RecoveryException extends IOException {
        public final List<String> recovery;
        RecoveryException(IOException cause, List<String> recovery) {
            super(cause); this.recovery = new ArrayList<>(recovery);
        }
    }
    public static final class ReplaceNotSupportedException extends IOException {
        ReplaceNotSupportedException() { super("Provider cannot safely replace this item"); }
    }
    private static final class Plan {
        final Node node; final Info info; final List<Plan> children = new ArrayList<>();
        Plan(Node node, Info info) { this.node = node; this.info = info; }
    }
    private final AtomicBoolean cancelled;
    private final Progress progress;
    private final byte[] buffer = new byte[256 * 1024];
    private long bytes;
    private final Set<Closeable> streams = Collections.newSetFromMap(new IdentityHashMap<>());
    public TreeTransfer(AtomicBoolean cancelled, Progress progress) { this.cancelled = cancelled; this.progress = progress; }
    /** May block while a provider closes its descriptor; call off Android's UI thread. */
    public void cancel() {
        cancelled.set(true); List<Closeable> opened;
        synchronized (streams) { opened = new ArrayList<>(streams); }
        for (Closeable stream : opened) try { stream.close(); } catch (IOException ignored) {}
    }

    public Result transfer(Node source, Node parent, boolean move, boolean replace) throws IOException {
        check();
        Info parentInfo = parent.info();
        if (!parentInfo.directory || !parentInfo.create) throw new IOException("Destination is not writable");
        Plan plan = plan(source, parent.key(), new HashSet<>(), new int[]{0}, 0);
        Node existing = find(parent, plan.info.name);
        if (existing != null && existing.key().equals(source.key())) return new Result(false, Collections.emptyList());
        if (existing != null && !replace) throw new java.nio.file.FileAlreadyExistsException(plan.info.name);
        if (existing != null && !existing.info().rename) throw new ReplaceNotSupportedException();
        Node staged = null, backup = null, direct = null;
        boolean published = false;
        List<String> recovery = new ArrayList<>();
        try {
            staged = copy(plan, parent, ".wx-transfer-" + UUID.randomUUID());
            verify(plan);
            check();
            if (staged.info().rename) {
                if (existing != null) {
                    if (!existing.info().rename) throw new ReplaceNotSupportedException();
                    backup = existing.rename(".wx-previous-" + UUID.randomUUID());
                }
                // Recheck the destination immediately before publishing: no silent collision suffix.
                if (find(parent, plan.info.name) != null) throw new java.nio.file.FileAlreadyExistsException(plan.info.name);
                staged = staged.rename(plan.info.name);
                if (!staged.info().name.equals(plan.info.name)) throw new IOException("Provider changed the destination name");
                published = true;
            } else {
                // Some providers cannot rename documents. New names still work,
                // but replacement requires preserving the old item, so refuse it.
                if (existing != null) throw new ReplaceNotSupportedException();
                // Reuse the original inventory: a provider may not immediately
                // enumerate freshly created staging children. Never mistake that
                // delayed index for a genuinely empty source folder.
                direct = copy(plan, parent, plan.info.name);
                verify(plan);
                check();
                published = true;
                cleanup(staged, recovery); staged = null;
            }
            cleanup(backup, recovery); backup = null;
            boolean removed = false;
            if (move && !cancelled.get() && plan.info.delete) {
                try { verify(plan); check(); source.delete(); removed = true; }
                catch (IOException | RuntimeException keepSource) { /* Complete destination remains available. */ }
            }
            return new Result(removed, recovery);
        } catch (IOException | RuntimeException error) {
            IOException failure = error instanceof IOException ? (IOException)error : new IOException(error);
            if (!published) {
                cleanup(direct, recovery);
                cleanup(staged, recovery);
                if (backup != null) {
                    try {
                        if (find(parent, plan.info.name) != null) throw new IOException("Destination appeared during rollback");
                        backup.rename(plan.info.name);
                    } catch (IOException | RuntimeException retained) { recovery.add(recoveryName(backup)); }
                }
            }
            if (!recovery.isEmpty()) throw new RecoveryException(failure, recovery);
            throw failure;
        }
    }

    public static Node find(Node parent, String name) throws IOException {
        Node found = null;
        for (Node node : parent.children()) if (name.equals(node.listedInfo().name)) {
            if (found != null) throw new IOException("Ambiguous duplicate names");
            found = node;
        }
        return found;
    }
    public static void validName(String name) throws IOException {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0)
            throw new IOException("Invalid file name");
    }
    private Plan plan(Node node, String destination, Set<String> visited, int[] count, int depth) throws IOException {
        check();
        String key = node.key();
        if (key.equals(destination)) throw new IOException("Destination is inside the source");
        if (!visited.add(key) || depth > 128 || ++count[0] > 100000) throw new IOException("Cyclic or oversized source tree");
        Info info = node.listedInfo(); validName(info.name);
        Plan result = new Plan(node, info);
        if (info.directory) {
            Set<String> names = new HashSet<>();
            for (Node child : node.children()) {
                Plan item = plan(child, destination, visited, count, depth + 1);
                if (!names.add(item.info.name)) throw new IOException("Ambiguous duplicate names");
                result.children.add(item);
            }
        }
        return result;
    }
    private Node copy(Plan plan, Node parent, String name) throws IOException {
        return copy(plan, parent, name, true);
    }
    private Node copy(Plan plan, Node parent, String name, boolean checkName) throws IOException {
        check(); validName(name);
        if (checkName && find(parent, name) != null) throw new java.nio.file.FileAlreadyExistsException(name);
        Node output = parent.create(name, plan.info);
        try {
            Info created = output.info();
            if (!created.name.equals(name) || created.directory != plan.info.directory)
                throw new IOException("Provider changed the requested name/type");
            if (plan.info.directory) {
                for (Plan child : plan.children) copy(child, output, child.info.name, false);
            } else {
                MessageDigest expected = digest(); long count = 0;
                try (InputStream input = track(plan.node.read()); OutputStream stream = track(output.write())) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        check();
                        if (read == 0) { int value = input.read(); if (value < 0) break; buffer[0] = (byte)value; read = 1; }
                        stream.write(buffer, 0, read); expected.update(buffer, 0, read);
                        count += read; bytes += read;
                        if (progress != null) progress.copied(plan.info.name, bytes);
                    }
                }
                if (plan.info.size >= 0 && count != plan.info.size) throw new IOException("Incomplete source");
                if (progress != null) progress.verifying(plan.info.name);
                MessageDigest actual = digest(); long verified = 0;
                try (InputStream input = track(output.read())) {
                    int read; while ((read = input.read(buffer)) != -1) {
                        check();
                        if (read == 0) { int value = input.read(); if (value < 0) break; buffer[0] = (byte)value; read = 1; }
                        actual.update(buffer, 0, read); verified += read;
                    }
                }
                if (verified != count || !Arrays.equals(actual.digest(), expected.digest())) throw new IOException("Destination verification failed");
            }
            return output;
        } catch (IOException | RuntimeException error) {
            List<String> retained = new ArrayList<>(); cleanup(output, retained);
            if (!retained.isEmpty()) throw new RecoveryException(error instanceof IOException ? (IOException)error : new IOException(error), retained);
            throw error;
        }
    }
    private void verify(Plan plan) throws IOException {
        check(); Info now = plan.node.info();
        if (!now.name.equals(plan.info.name) || now.directory != plan.info.directory
                || !now.directory && plan.info.size >= 0 && now.size != plan.info.size
                || plan.info.modified > 0 && now.modified != plan.info.modified) throw new IOException("Source changed during copy");
        if (now.directory) {
            Map<String, Node> current = new HashMap<>();
            for (Node child : plan.node.children()) if (current.put(child.key(), child) != null) throw new IOException("Duplicate document");
            if (current.size() != plan.children.size()) throw new IOException("Source folder changed during copy");
            for (Plan child : plan.children) { if (!current.containsKey(child.node.key())) throw new IOException("Source folder changed during copy"); verify(child); }
        }
    }
    private void cleanup(Node node, List<String> retained) {
        if (node == null) return;
        try { node.delete(); }
        catch (IOException | RuntimeException error) {
            retained.add(recoveryName(node));
        }
    }
    private String recoveryName(Node node) {
        try { return node.recoveryName(); } catch (IOException | RuntimeException ignored) { return ".wx-transfer"; }
    }
    private void check() throws InterruptedIOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Transfer cancelled");
    }
    private InputStream track(InputStream input) throws IOException {
        InputStream tracked = new FilterInputStream(input) {
            @Override public void close() throws IOException { try { super.close(); } finally { synchronized (streams) { streams.remove(this); } } }
        };
        register(tracked);
        return tracked;
    }
    private OutputStream track(OutputStream output) throws IOException {
        OutputStream tracked = new FilterOutputStream(output) {
            @Override public void write(byte[] bytes, int offset, int length) throws IOException { out.write(bytes, offset, length); }
            @Override public void close() throws IOException { try { super.close(); } finally { synchronized (streams) { streams.remove(this); } } }
        };
        register(tracked);
        return tracked;
    }
    private void register(Closeable stream) throws IOException {
        IOException stopped = null;
        synchronized (streams) {
            try { check(); streams.add(stream); } catch (IOException error) { stopped = error; }
        }
        if (stopped != null) { try { stream.close(); } catch (IOException error) { stopped.addSuppressed(error); } throw stopped; }
    }
    private static MessageDigest digest() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); } }
}
