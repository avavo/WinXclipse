package com.winlator.cmod.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** Validates staged Windows wrappers and replaces a whole set while Wine is stopped. */
public final class GraphicsWrapperFiles {
    private GraphicsWrapperFiles() {}
    interface Move { void run(Path source, Path destination) throws IOException; }

    /** Reject truncated DLLs, wrong bitness and raw sections extending past EOF. */
    public static boolean validDll(File file, boolean is64) {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            long size = in.length();
            if (size < 64 || u16(in) != 0x5a4d) return false;
            in.seek(60); long pe = u32(in);
            if (pe < 64 || pe > size - 24) return false;
            in.seek(pe);
            if (u32(in) != 0x4550) return false;
            int machine = u16(in), sections = u16(in);
            boolean machine64 = machine == 0x8664 || machine == 0xaa64 || machine == 0xa641 || machine == 0xa64e;
            if (is64 ? !machine64 : machine != 0x14c) return false;
            in.seek(pe + 20); int optional = u16(in), flags = u16(in);
            if ((flags & 0x2000) == 0 || sections < 1 || sections > 96 || optional < (is64 ? 112 : 96)) return false;
            long table = pe + 24 + optional, tableEnd = table + sections * 40L;
            if (tableEnd > size || u16(in) != (is64 ? 0x20b : 0x10b)) return false;
            in.seek(pe + 24 + 60); long headers = u32(in);
            if (headers < tableEnd || headers > size) return false;
            for (int i = 0; i < sections; i++) {
                in.seek(table + i * 40L + 16); long bytes = u32(in), offset = u32(in);
                if (bytes > 0 && (offset < headers || offset > size || bytes > size - offset)) return false;
            }
            return true;
        } catch (IOException | SecurityException failure) { return false; }
    }

    private static int u16(RandomAccessFile in) throws IOException { return in.readUnsignedByte() | in.readUnsignedByte() << 8; }
    private static long u32(RandomAccessFile in) throws IOException { return (long)u16(in) | (long)u16(in) << 16; }

    /** Mirrors bundled archive routing for a pure 32-bit prefix. */
    public static String profileTarget(String template, boolean win64) throws IOException {
        String relative;
        if (template != null && template.startsWith("${system32}/")) {
            if (!win64) return null;
            relative = "system32/" + template.substring("${system32}/".length());
        } else if (template != null && template.startsWith("${syswow64}/")) {
            relative = (win64 ? "syswow64/" : "system32/") + template.substring("${syswow64}/".length());
        } else throw new IOException("Unexpected graphics package target");
        checkRelative(relative);
        return relative;
    }

    public static void requireDxvk(File windows, boolean win64) throws IOException {
        require(windows, "system32", win64, "d3d9.dll", "d3d11.dll", "dxgi.dll");
        if (win64) require(windows, "syswow64", false, "d3d9.dll", "d3d11.dll", "dxgi.dll");
    }

    public static void require(File windows, String directory, boolean is64, String... names) throws IOException {
        for (String name : names) if (!validDll(new File(windows, directory + "/" + name), is64))
            throw new IOException("Missing, incomplete or incompatible DLL: " + directory + "/" + name);
    }

    public static void install(File staged, File windows, Set<String> removals, boolean win64) throws IOException {
        install(staged, windows, removals, win64, GraphicsWrapperFiles::move);
    }

    static synchronized void install(File staged, File windows, Set<String> removals, boolean win64, Move mover) throws IOException {
        Path source = staged.toPath().toRealPath();
        if (!windows.isDirectory() && !windows.mkdirs()) throw new IOException("Cannot prepare Windows directory");
        Path root = windows.toPath().toRealPath();
        if (source.startsWith(root) || root.startsWith(source)) throw new IOException("Staging must be outside Windows");
        Set<String> targets = new LinkedHashSet<>();
        Set<String> replacements = new LinkedHashSet<>();
        try (Stream<Path> tree = Files.walk(source)) {
            for (Path entry : (Iterable<Path>)tree::iterator) {
                if (Files.isSymbolicLink(entry)) throw new IOException("Graphics package contains a symlink");
                if (Files.isDirectory(entry)) continue;
                String relative = source.relativize(entry).toString().replace(File.separatorChar, '/');
                checkRelative(relative);
                boolean is64 = win64 && relative.startsWith("system32/");
                if (!Files.isRegularFile(entry) || Files.size(entry) == 0
                        || relative.toLowerCase(java.util.Locale.ROOT).endsWith(".dll") && !validDll(entry.toFile(), is64))
                    throw new IOException("Incomplete graphics payload: " + relative);
                targets.add(relative); replacements.add(relative);
            }
        }
        if (removals != null) for (String relative : removals) { checkRelative(relative); targets.add(relative); }
        // Validate all destinations before touching any installed DLL. Follow
        // parents, but not the final DLL: an old Wine symlink must be replaced,
        // never written through into the shared runtime.
        for (String relative : targets) {
            Path target = root.resolve(relative), parent = target.getParent();
            if (!parent.toFile().getCanonicalFile().toPath().startsWith(root)) throw new IOException("Target escapes Windows");
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("DLL target is a directory: " + relative);
        }
        Path backup = Files.createTempDirectory(root.getParent(), ".graphics-backup-");
        List<String> saved = new ArrayList<>(), installed = new ArrayList<>();
        boolean cleanBackup = true;
        try {
            for (String relative : targets) {
                Path target = root.resolve(relative), old = backup.resolve(relative);
                Files.createDirectories(target.getParent());
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(old.getParent()); mover.run(target, old); saved.add(relative);
                }
                if (replacements.contains(relative)) {
                    mover.run(source.resolve(relative), target); installed.add(relative);
                }
            }
        } catch (IOException | RuntimeException failure) {
            // Remove only files installed by this transaction, then restore
            // every old file/link. Keep the backup if a restoration fails.
            for (int i = installed.size() - 1; i >= 0; i--)
                try { Files.deleteIfExists(root.resolve(installed.get(i))); }
                catch (IOException rollback) { failure.addSuppressed(rollback); cleanBackup = false; }
            for (int i = saved.size() - 1; i >= 0; i--)
                try { mover.run(backup.resolve(saved.get(i)), root.resolve(saved.get(i))); }
                catch (IOException rollback) { failure.addSuppressed(rollback); cleanBackup = false; }
            if (!cleanBackup) throw new IOException("Graphics rollback incomplete; preserved backup: " + backup, failure);
            throw failure;
        } finally {
            if (cleanBackup) deleteTree(backup.toFile());
        }
    }

    private static void checkRelative(String path) throws IOException {
        if (path == null || !path.matches("(?:system32|syswow64)/[^\\\\:]+") || path.endsWith("/"))
            throw new IOException("Unexpected graphics path");
        for (String segment : path.split("/")) if (segment.isEmpty() || segment.equals(".") || segment.equals(".."))
            throw new IOException("Unsafe graphics path");
    }

    private static void move(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException unsupported) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
    }

    public static void deleteTree(File directory) throws IOException {
        if (!directory.exists()) return;
        try (Stream<Path> paths = Files.walk(directory.toPath())) {
            for (Path path : (Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
        }
    }
}
