package com.winlator.cmod.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

/** Serializes base-image updates and repairs only the missing bundled runtime. */
public final class ImageFsMaintenance {
    public enum Result { IMAGE_UPDATED, WINE_REPAIRED, DEPENDENCIES_READY }

    public interface Backend {
        boolean extractImage(File root) throws IOException;
        boolean extractWine(String version, File staging) throws IOException;
        boolean hasReplacementWine();
        void prepareDependencies() throws IOException;
        void imageUpdated() throws IOException;
        boolean delete(File path);
    }

    private ImageFsMaintenance() {}

    /** Re-evaluates receipts inside the lock, including after Activity recreation. */
    public static synchronized Result prepare(File root, int version, String[] wineVersions,
                                               boolean forceImage, Backend backend) throws IOException {
        clearAbandonedStaging(root, backend);
        File receipt = new File(root, ".winlator/.img_version");
        boolean update = forceImage || !root.isDirectory() || readVersion(receipt) < version;
        if (update) {
            // Invalidate before changing the base. An interrupted update is retried.
            Files.deleteIfExists(receipt.toPath());
            clearBase(root, backend);
            if (!backend.extractImage(root)) throw new IOException("ImageFS extraction failed");
            for (String wine : wineVersions) installWine(root, wine, backend);
            backend.prepareDependencies();
            backend.imageUpdated();
            if (!FileUtils.writeStringAtomic(receipt, Integer.toString(version))) {
                throw new IOException("Could not commit the ImageFS receipt");
            }
            return Result.IMAGE_UPDATED;
        }

        boolean missing = false;
        for (String wine : wineVersions) missing |= !hasBundledWine(new File(root, "opt/" + wine));
        boolean repair = missing && !backend.hasReplacementWine();
        if (repair) {
            for (String wine : wineVersions) {
                if (!hasBundledWine(new File(root, "opt/" + wine))) installWine(root, wine, backend);
            }
        }
        backend.prepareDependencies();
        return repair ? Result.WINE_REPAIRED : Result.DEPENDENCIES_READY;
    }

    /** Core files of the APK's ARM64EC Proton, including its 32-bit Windows side. */
    public static boolean hasBundledWine(File runtime) {
        if (!hasFile(runtime, "bin/wine")
                || !hasFile(runtime, "bin/wine-preloader")
                || !hasFile(runtime, "bin/wineserver")
                || !hasFile(runtime, "share/wine/wine.inf")
                || !hasFile(runtime, "lib/wine/aarch64-unix/ntdll.so")) return false;
        for (String arch : new String[]{"aarch64-windows", "i386-windows"}) {
            String path = "lib/wine/" + arch + "/";
            if (!hasFile(runtime, path + "ntdll.dll")
                    || !hasFile(runtime, path + "kernel32.dll")) return false;
        }
        return true;
    }

    private static boolean hasFile(File root, String path) {
        return new File(root, path).isFile() && RuntimeFiles.hasPayload(root, path);
    }

    private static int readVersion(File receipt) {
        try {
            if (!receipt.isFile() || receipt.length() > 64) return 0;
            return Math.max(0, Integer.parseInt(new String(Files.readAllBytes(receipt.toPath()),
                    StandardCharsets.UTF_8).trim()));
        } catch (IOException | NumberFormatException error) { return 0; }
    }

    private static void clearBase(File root, Backend backend) throws IOException {
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create ImageFS");
        File[] children = root.listFiles();
        if (children == null) throw new IOException("Cannot list ImageFS");
        for (File child : children) {
            // Containers/saves and legacy runtime trees are user data. ImageFS only
            // overlays opt/winetricks; bundled Wine is replaced separately below.
            if (child.getName().equals("home") || child.getName().equals("opt")) continue;
            if (!backend.delete(child)) throw new IOException("Cannot clear ImageFS path: " + child);
        }
    }

    private static void installWine(File root, String version, Backend backend) throws IOException {
        File staging = new File(root.getParentFile(), stagingPrefix(root) + UUID.randomUUID());
        if (!staging.mkdir()) throw new IOException("Cannot create Wine staging directory");
        try {
            if (!backend.extractWine(version, staging) || !hasBundledWine(staging)) {
                throw new IOException("Incomplete bundled runtime: " + version);
            }
            File previous = DirectoryInstall.commit(staging, new File(root, "opt/" + version));
            if (previous != null) backend.delete(previous);
        } finally {
            if (staging.exists()) backend.delete(staging);
        }
    }

    private static String stagingPrefix(File root) {
        return ".imagefs-wine-" + root.getName() + "-";
    }

    private static void clearAbandonedStaging(File root, Backend backend) {
        File parent = root.getAbsoluteFile().getParentFile();
        File[] files = parent == null ? null : parent.listFiles();
        if (files == null) return;
        String prefix = stagingPrefix(root);
        for (File file : files) {
            if (!file.getName().startsWith(prefix)) continue;
            try {
                String suffix = file.getName().substring(prefix.length());
                if (UUID.fromString(suffix).toString().equals(suffix)) backend.delete(file);
            } catch (IllegalArgumentException ignored) {}
        }
    }
}
