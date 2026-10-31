package com.winlator.cmod.core;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/** Persistent recovery of the Wine loaders temporarily touched by the Windows installer. */
final class WineVulkanBackup {
    private static final String[] PATHS = {"drive_c/windows/system32/vulkan-1.dll", "drive_c/windows/syswow64/vulkan-1.dll"};
    private final File prefix, backup;
    WineVulkanBackup(File prefix) {
        this.prefix = prefix;
        backup = new File(prefix, ".winxclipse-vulkanrt-recovery");
    }
    boolean exists() { return backup.exists(); }

    void create() throws IOException {
        if (exists()) throw new IOException("Unrecovered Vulkan loader backup");
        File staging = Files.createTempDirectory(prefix.toPath(), ".vulkanrt-backup-").toFile();
        try {
            Properties manifest = new Properties();
            for (int i = 0; i < PATHS.length; i++) {
                Path original = new File(prefix, PATHS[i]).toPath();
                boolean exists = Files.exists(original, LinkOption.NOFOLLOW_LINKS);
                manifest.setProperty("type" + i, exists ? Files.isSymbolicLink(original) ? "link" : "file" : "missing");
                if (exists) {
                    if (!Files.isRegularFile(original)) throw new IOException("Unexpected Vulkan loader: " + original);
                    File saved = new File(staging, "loader" + i);
                    Files.copy(original, saved.toPath());
                    manifest.setProperty("sha" + i, VulkanRuntimePayload.sha256(saved));
                    if (Files.isSymbolicLink(original)) manifest.setProperty("link" + i, Files.readSymbolicLink(original).toString());
                }
            }
            try (OutputStream output = new FileOutputStream(new File(staging, "manifest"))) { manifest.store(output, null); }
            DirectoryInstall.commit(staging, backup);
            // Wine prefixes may link their builtins to the shared runtime.
            // Detach every present loader before NSIS can truncate it in place.
            for (int i = 0; i < PATHS.length; i++) {
                if ("missing".equals(manifest.getProperty("type" + i))) continue;
                Path target = new File(prefix, PATHS[i]).toPath();
                Path detached = Files.createTempFile(target.getParent(), ".vulkan-detach-", ".dll");
                try {
                    Files.copy(new File(backup, "loader" + i).toPath(), detached, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(detached, target, StandardCopyOption.REPLACE_EXISTING);
                } finally { Files.deleteIfExists(detached); }
            }
        } finally { if (staging.exists()) VulkanRuntimePayload.removeTree(staging); }
    }

    void restore() throws IOException {
        Properties manifest = new Properties();
        try (InputStream input = new FileInputStream(new File(backup, "manifest"))) { manifest.load(input); }
        // Validate every backup before modifying either architecture.
        for (int i = 0; i < PATHS.length; i++) {
            String type = manifest.getProperty("type" + i, "");
            if ("missing".equals(type)) continue;
            File saved = new File(backup, "loader" + i);
            if ((!"file".equals(type) && !"link".equals(type))
                    || !VulkanRuntimePayload.sha256(saved).equals(manifest.getProperty("sha" + i))
                    || "link".equals(type) && !manifest.containsKey("link" + i))
                throw new IOException("Damaged Vulkan recovery data; retained at " + backup);
        }
        for (int i = 0; i < PATHS.length; i++) {
            Path target = new File(prefix, PATHS[i]).toPath();
            String type = manifest.getProperty("type" + i);
            if ("missing".equals(type)) { Files.deleteIfExists(target); continue; }
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), ".vulkan-restore-", ".dll");
            try {
                if ("link".equals(type)) {
                    Files.delete(temporary);
                    Files.createSymbolicLink(temporary, Paths.get(manifest.getProperty("link" + i)));
                } else Files.copy(new File(backup, "loader" + i).toPath(), temporary, StandardCopyOption.REPLACE_EXISTING);
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
        }
        // Retire atomically before cleanup: an app kill while deleting an already
        // restored backup must not look like damaged, still-needed recovery data.
        Path retired = new File(prefix, ".vulkanrt-restored-" + java.util.UUID.randomUUID()).toPath();
        Files.move(backup.toPath(), retired);
        try { VulkanRuntimePayload.removeTree(retired.toFile()); }
        catch (IOException ignored) { /* Original loaders are already restored. */ }
    }
}
