package com.winlator.cmod.container;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Publishes a complete config ZIP, without exposing a partially written export. */
public final class CommunityConfigArchive {
    public static final int MAX_CONFIG_BYTES = 5 * 1024 * 1024;
    public static final int MAX_COVER_BYTES = 16 * 1024 * 1024;
    private CommunityConfigArchive() {}

    public static File write(File directory, String title, byte[] manifest, byte[] cover) throws IOException {
        if (manifest == null || manifest.length == 0 || manifest.length > MAX_CONFIG_BYTES)
            throw new IOException("Invalid configuration size");
        if (cover != null && (cover.length == 0 || cover.length > MAX_COVER_BYTES))
            throw new IOException("Invalid cover size");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create export directory");
        String base = title == null ? "" : title.trim().replaceAll("[^\\p{L}\\p{N}._-]+", "-").replaceAll("^[.-]+|[.-]+$", "");
        if (base.isEmpty()) base = "WinXclipse-config";
        if (base.length() > 100) base = base.substring(0, 100);
        File temporary = File.createTempFile(".config-export-", ".tmp", directory);
        try {
            try (FileOutputStream file = new FileOutputStream(temporary);
                 ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(file))) {
                entry(zip, "config.txt", manifest);
                if (cover != null) entry(zip, "cover.png", cover);
                zip.finish();
                zip.flush();
                file.getFD().sync();
            }
            synchronized (CommunityConfigArchive.class) {
                File output = new File(directory, base + ".zip");
                int suffix = 2;
                while (output.exists()) output = new File(directory, base + "-" + suffix++ + ".zip");
                // Same-directory rename; never request replacement of an existing export.
                Files.move(temporary.toPath(), output.toPath());
                return output;
            }
        } finally { Files.deleteIfExists(temporary.toPath()); }
    }

    private static void entry(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }
}
