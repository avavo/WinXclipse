package com.winlator.cmod.core;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Verified original Windows installer and diagnostics, prepared without executing either. */
public final class VulkanRuntimePayload {
    public static final String VERSION = "1.3.283.0";
    public static final String ASSET = "dependencies/vulkanrt-" + VERSION + ".zip";
    private static final Map<String, String> HASHES = new LinkedHashMap<>();
    static {
        HASHES.put("installer.exe", "a00e79a390b08565129ee81aaa8bf48e1f87e2144ef5d2601e376c9eb7140f8a");
        HASHES.put("x64/vulkaninfo.exe", "a526dab98b6ca185561ab0f3a091f441dc071b15fde089f5e4bf7a9623688cf6");
        HASHES.put("x86/vulkaninfo.exe", "e714787d5c46ee56302cb125486a82390f1f591669a2b698e42426f675855694");
        HASHES.put("x64/vulkan-1.dll", "f413d235d314b2901b20984a21b5506242203bba2d20c728f313ff1bb55bf5a4");
        HASHES.put("x86/vulkan-1.dll", "4a0a3cac32b5f88f7b9d46c097fd5ba60809e601cd5b8c9cca624aad4dcb9d4d");
        HASHES.put("LICENSE.txt", "cd980c984ab93374eae195d533990d6af57b81f4dffbb3f602f4390977e8dd54");
    }
    public interface Source { InputStream open() throws IOException; }
    private VulkanRuntimePayload() {}

    public static synchronized File prepare(File parent, Source source) throws IOException {
        File target = new File(parent, "vulkanrt-" + VERSION);
        if (isReady(target)) return target;
        Files.createDirectories(parent.toPath());
        File staging = Files.createTempDirectory(parent.toPath(), ".vulkanrt-").toFile();
        try {
            Set<String> seen = new HashSet<>();
            long total = 0;
            try (ZipInputStream zip = new ZipInputStream(source.open())) {
                ZipEntry entry;
                byte[] buffer = new byte[64 * 1024];
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (entry.isDirectory() || !HASHES.containsKey(name) || !seen.add(name))
                        throw new IOException("Unexpected Vulkan runtime payload entry: " + name);
                    File file = new File(staging, name);
                    Files.createDirectories(file.getParentFile().toPath());
                    try (OutputStream output = new FileOutputStream(file)) {
                        int count;
                        while ((count = zip.read(buffer)) != -1) {
                            total += count;
                            if (total > 8 * 1024 * 1024) throw new IOException("Vulkan runtime payload too large");
                            output.write(buffer, 0, count);
                        }
                    }
                }
            }
            if (!isReady(staging)) throw new IOException("Vulkan runtime payload verification failed");
            File previous = DirectoryInstall.commit(staging, target);
            if (previous != null) removeTree(previous);
            return target;
        } finally { if (staging.exists()) removeTree(staging); }
    }

    public static boolean isReady(File directory) {
        try {
            for (Map.Entry<String, String> entry : HASHES.entrySet()) {
                File file = new File(directory, entry.getKey());
                if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
                        || !file.getCanonicalFile().toPath().startsWith(directory.getCanonicalFile().toPath())
                        || !entry.getValue().equals(sha256(file))) return false;
            }
            return true;
        } catch (IOException failure) { return false; }
    }

    public static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
    }

    public static boolean matches(File file, String name) {
        try { return HASHES.containsKey(name) && HASHES.get(name).equals(sha256(file)); }
        catch (IOException failure) { return false; }
    }

    static void removeTree(File root) throws IOException {
        try (java.util.stream.Stream<Path> paths = Files.walk(root.toPath())) {
            for (Path path : (Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)
                Files.deleteIfExists(path);
        }
    }
}
