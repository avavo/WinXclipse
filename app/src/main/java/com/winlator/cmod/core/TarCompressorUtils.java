package com.winlator.cmod.core;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public abstract class TarCompressorUtils {
    public enum Type {XZ, ZSTD, NONE}

    private static final byte[] XZ_MAGIC = {(byte) 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00};
    private static final byte[] ZSTD_MAGIC = {0x28, (byte) 0xB5, 0x2F, (byte) 0xFD};
    private static final String TAG = "TarCompressorUtils";

    // Interface to define the exclusion filter
    public interface ExclusionFilter {
        boolean shouldInclude(File file);
    }

    public interface OnReadProgressListener {
        void onProgress(long bytesRead, long totalBytes);
    }


    private static void addFile(ArchiveOutputStream tar, File file, String entryName) {
        try {
            tar.putArchiveEntry(tar.createArchiveEntry(file, entryName));
            try (BufferedInputStream inStream = new BufferedInputStream(new FileInputStream(file), StreamUtils.BUFFER_SIZE)) {
                StreamUtils.copy(inStream, tar);
            }
            tar.closeArchiveEntry();
        }
        catch (Exception e) {}
    }

    private static void addLinkFile(ArchiveOutputStream tar, File file, String entryName) {
        try {
            TarArchiveEntry entry = new TarArchiveEntry(entryName, TarConstants.LF_SYMLINK);
            entry.setLinkName(FileUtils.readSymlink(file));
            tar.putArchiveEntry(entry);
            tar.closeArchiveEntry();
        }
        catch (Exception e) {}
    }

    private static void addDirectory(ArchiveOutputStream tar, File folder, String basePath, ExclusionFilter filter) throws IOException {
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (filter != null && !filter.shouldInclude(file)) {
                continue; // Skip files that should be excluded
            }
            if (FileUtils.isSymlink(file)) {
                addLinkFile(tar, file, basePath + file.getName());
            } else if (file.isDirectory()) {
                String entryName = basePath + file.getName() + "/";
                tar.putArchiveEntry(tar.createArchiveEntry(folder, entryName));
                tar.closeArchiveEntry();
                addDirectory(tar, file, entryName, filter);
            } else {
                addFile(tar, file, basePath + file.getName());
            }
        }
    }
    public static void compress(Type type, File file, File destination, int level) {
        compress(type, new File[]{file}, destination, level, null);
    }

    public static void compress(Type type, File file, File destination, int level, ExclusionFilter filter) {
        compress(type, new File[]{file}, destination, level, filter);
    }

    public static void compress(Type type, File[] files, File destination, int level, ExclusionFilter filter) {
        try (OutputStream outStream = getCompressorOutputStream(type, destination, level);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(outStream)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
            for (File file : files) {
                if (filter != null && !filter.shouldInclude(file)) {
                    continue; // Skip files that should be excluded
                }
                if (FileUtils.isSymlink(file)) {
                    addLinkFile(tar, file, file.getName());
                } else if (file.isDirectory()) {
                    String basePath = file.getName() + "/";
                    tar.putArchiveEntry(tar.createArchiveEntry(file, basePath));
                    tar.closeArchiveEntry();
                    addDirectory(tar, file, basePath, filter);
                } else {
                    addFile(tar, file, file.getName());
                }
            }
            tar.finish();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    public static boolean extract(Type type, Context context, String assetFile, File destination) {
        return extract(type, context, assetFile, destination, null);
    }

    public static boolean extract(Type type, Context context, String assetFile, File destination, OnExtractFileListener onExtractFileListener) {
        try {
            return extract(type, context.getAssets().open(assetFile), destination, onExtractFileListener);
        }
        catch (IOException e) {
            return false;
        }
    }

    public static boolean extract(Type type, Context context, Uri source, File destination) {
        return extract(type, context, source, destination, null);
    }

    public static boolean extract(Type type, Context context, Uri source, File destination, OnExtractFileListener onExtractFileListener) {
        return extract(type, context, source, destination, onExtractFileListener, null);
    }

    public static boolean extract(Type type, Context context, Uri source, File destination,
                                  OnExtractFileListener onExtractFileListener,
                                  OnReadProgressListener progressListener) {
        if (source == null) return false;
        try {
            long totalBytes = getSourceSize(context, source);
            InputStream input;
            if (source.toString().startsWith("/")) {
                input = new FileInputStream(source.toString());
            } else {
                input = context.getContentResolver().openInputStream(source);
            }
            return extract(type, new ProgressInputStream(input, totalBytes, progressListener),
                    destination, onExtractFileListener);
        }
        catch (FileNotFoundException e) {
            return false;
        }
    }

    public static boolean extract(Type type, File source, File destination) {
        return extract(type, source, destination, null);
    }

    public static boolean extract(Type type, InputStream source, File destination) {
        return extract(type, source, destination, null);
    }

    public static boolean extract(Type type, File source, File destination, OnExtractFileListener onExtractFileListener) {
        if (source == null || !source.isFile()) return false;
        try {
            return extract(type, new BufferedInputStream(new FileInputStream(source), StreamUtils.BUFFER_SIZE), destination, onExtractFileListener);
        }
        catch (FileNotFoundException e) {
            return false;
        }
    }

    private static boolean extract(Type type, InputStream source, File destination, OnExtractFileListener onExtractFileListener) {
        if (source == null) return false;
        try (InputStream inStream = getCompressorInputStream(type, source);
             ArchiveInputStream tar = new TarArchiveInputStream(inStream)) {
            File destinationRoot = destination.getCanonicalFile();
            if (!destinationRoot.isDirectory() && !destinationRoot.mkdirs()) return false;
            TarArchiveEntry entry;
            while ((entry = (TarArchiveEntry)tar.getNextEntry()) != null) {
                if (!tar.canReadEntryData(entry)) continue;
                File file = getSafeArchivePath(destinationRoot, entry.getName());
                if (file == null) return false;

                if (onExtractFileListener != null) {
                    file = onExtractFileListener.onExtractFile(file, entry.getSize());
                    if (file == null) continue;
                    file = file.getCanonicalFile();
                    if (!isInside(destinationRoot, file)) return false;
                }

if (entry.isDirectory()) {
                    if (!file.isDirectory()) file.mkdirs();
                }
                else {
                    if (entry.isSymbolicLink()) {
                        String linkName = entry.getLinkName();
                        File linkTarget = new File(file.getParentFile(), linkName);
                        try {
                            linkTarget = linkTarget.getCanonicalFile();
                            if (!isInside(destinationRoot, linkTarget)) {
                                Log.e(TAG, "Symlink target outside destination root: " + linkName);
                                return false;
                            }
                        } catch (IOException e) {
                            Log.e(TAG, "Failed to resolve symlink target: " + linkName, e);
                            return false;
                        }
                        FileUtils.symlink(linkName, file.getAbsolutePath());
                    }
                    else {
                        File parent = file.getParentFile();
                        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
                        try (BufferedOutputStream outStream = new BufferedOutputStream(new FileOutputStream(file), StreamUtils.BUFFER_SIZE)) {
                            if (!StreamUtils.copy(tar, outStream)) return false;
                        }
                    }
                }

                FileUtils.chmod(file, 0771);
            }
            return true;
        }
        catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static boolean extractZip(File source, File destination) {
        if (source == null || !source.isFile()) return false;
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
                new BufferedInputStream(new FileInputStream(source)))) {
            File destinationRoot = destination.getCanonicalFile();
            if (!destinationRoot.isDirectory() && !destinationRoot.mkdirs()) return false;
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File file = getSafeArchivePath(destinationRoot, entry.getName());
                if (file == null) return false;
                if (entry.isDirectory()) {
                    if (!file.isDirectory() && !file.mkdirs()) return false;
                }
                else {
                    File parent = file.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
                    try (BufferedOutputStream output = new BufferedOutputStream(
                            new FileOutputStream(file), StreamUtils.BUFFER_SIZE)) {
                        if (!StreamUtils.copy(zip, output)) return false;
                    }
                }
                zip.closeEntry();
                FileUtils.chmod(file, 0771);
            }
            return true;
        }
        catch (IOException e) {
            return false;
        }
    }

    private static long getSourceSize(Context context, Uri source) {
        if (source.toString().startsWith("/")) return new File(source.toString()).length();
        try (Cursor cursor = context.getContentResolver().query(source,
                new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index);
            }
        }
        catch (Exception ignored) {}
        return -1;
    }

    private static final class ProgressInputStream extends FilterInputStream {
        private final long totalBytes;
        private final OnReadProgressListener listener;
        private long bytesRead;
        private int lastPercent = -1;

        ProgressInputStream(InputStream input, long totalBytes, OnReadProgressListener listener) {
            super(input);
            this.totalBytes = totalBytes;
            this.listener = listener;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) report(1);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) report(count);
            return count;
        }

        private void report(int count) {
            bytesRead += count;
            if (listener == null || totalBytes <= 0) return;
            int percent = (int)Math.min(100, bytesRead * 100L / totalBytes);
            if (percent != lastPercent) {
                lastPercent = percent;
                listener.onProgress(bytesRead, totalBytes);
            }
        }
    }

    /**
     * Determines the real compression from the stream header. Content packages
     * are intentionally not classified by their extension: .wcp, .wcp.xz and
     * .xz files found in the wild do not consistently match their suffix.
     */
    public static Type detectType(Context context, Uri source) {
        if (source == null) return null;
        try (InputStream input = source.toString().startsWith("/")
                ? new FileInputStream(source.toString())
                : context.getContentResolver().openInputStream(source)) {
            return detectType(input);
        }
        catch (IOException e) {
            return null;
        }
    }

    public static Type detectType(File source) {
        if (source == null || !source.isFile()) return null;
        try (InputStream input = new FileInputStream(source)) {
            return detectType(input);
        }
        catch (IOException e) {
            return null;
        }
    }

    private static Type detectType(InputStream source) throws IOException {
        if (source == null) return null;
        byte[] header = new byte[6];
        int read = source.read(header);
        if (startsWith(header, read, XZ_MAGIC)) return Type.XZ;
        if (startsWith(header, read, ZSTD_MAGIC)) return Type.ZSTD;
        // Uncompressed tar archives have no leading magic. TarArchiveInputStream
        // validates the header during extraction, so NONE is safe as a fallback.
        return Type.NONE;
    }

    private static boolean startsWith(byte[] value, int length, byte[] prefix) {
        if (length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (value[i] != prefix[i]) return false;
        }
        return true;
    }

    private static File getSafeArchivePath(File destinationRoot, String entryName) throws IOException {
        File file = new File(destinationRoot, entryName).getCanonicalFile();
        return isInside(destinationRoot, file) ? file : null;
    }

    private static boolean isInside(File destinationRoot, File file) throws IOException {
        String rootPath = destinationRoot.getCanonicalPath();
        String filePath = file.getCanonicalPath();
        return filePath.equals(rootPath) || filePath.startsWith(rootPath + File.separator);
    }

    private static InputStream getCompressorInputStream(Type type, InputStream source) throws IOException {
        if (type == Type.XZ) {
            return new XZCompressorInputStream(source);
        }
        else if (type == Type.ZSTD) {
            return new ZstdCompressorInputStream(source);
        }
        return source;
    }

    private static OutputStream getCompressorOutputStream(Type type, File destination, int level) throws IOException {
        if (type == Type.XZ) {
            return new XZCompressorOutputStream(new BufferedOutputStream(new FileOutputStream(destination), StreamUtils.BUFFER_SIZE), level);
        }
        else if (type == Type.ZSTD) {
            return new ZstdCompressorOutputStream(new BufferedOutputStream(new FileOutputStream(destination), StreamUtils.BUFFER_SIZE), level);
        }
        return new BufferedOutputStream(new FileOutputStream(destination), StreamUtils.BUFFER_SIZE);
    }

    public static void archive(File[] files, File destination, ExclusionFilter filter) {
        try (OutputStream outStream = new BufferedOutputStream(new FileOutputStream(destination), StreamUtils.BUFFER_SIZE);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(outStream)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
            for (File file : files) {
                if (filter != null && !filter.shouldInclude(file)) {
                    continue; // Skip files that should be excluded
                }
                if (FileUtils.isSymlink(file)) {
                    addLinkFile(tar, file, file.getName());
                } else if (file.isDirectory()) {
                    String basePath = file.getName() + "/";
                    tar.putArchiveEntry(tar.createArchiveEntry(file, basePath));
                    tar.closeArchiveEntry();
                    addDirectory(tar, file, basePath, filter);
                } else {
                    addFile(tar, file, file.getName());
                }
            }
            tar.finish();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static boolean extractTar(File source, File destination, OnExtractFileListener onExtractFileListener) {
        if (source == null || !source.isFile()) return false;
        try (InputStream inStream = new BufferedInputStream(new FileInputStream(source), StreamUtils.BUFFER_SIZE);
             TarArchiveInputStream tar = new TarArchiveInputStream(inStream)) {
            File destinationRoot = destination.getCanonicalFile();
            if (!destinationRoot.isDirectory() && !destinationRoot.mkdirs()) return false;
            TarArchiveEntry entry;
            String topLevelDirectory = null;
            while ((entry = (TarArchiveEntry) tar.getNextEntry()) != null) {
                if (!tar.canReadEntryData(entry)) continue;

                // Get the top-level directory name
                String entryName = entry.getName();
                if (topLevelDirectory == null) {
                    if (entry.isDirectory()) {
                        topLevelDirectory = entryName;
                        continue; // Skip creating the top-level directory
                    }
                }

                // Skip the entire tmp directory
                if (entryName.contains("/tmp/")) {
                    Log.d("RestoreOp", "Skipping tmp directory: " + entryName);
                    continue;
                }

                // Adjust the extraction path to remove the top-level directory
                String adjustedName = topLevelDirectory != null
                        ? entryName.replaceFirst("^" + java.util.regex.Pattern.quote(topLevelDirectory), "")
                        : entryName;
                File file = getSafeArchivePath(destinationRoot, adjustedName);
                if (file == null) return false;

                if (onExtractFileListener != null) {
                    file = onExtractFileListener.onExtractFile(file, entry.getSize());
                    if (file == null) continue;
                    file = file.getCanonicalFile();
                    if (!isInside(destinationRoot, file)) return false;
                }

                if (entry.isDirectory()) {
                    if (!file.isDirectory()) file.mkdirs();
                } else {
                    if (entry.isSymbolicLink()) {
                        // Only follow links whose target resolves inside the destination root.
                        File linkTarget = new File(file.getParentFile(), entry.getLinkName()).getCanonicalFile();
                        if (!isInside(destinationRoot, linkTarget)) {
                            Log.w("RestoreOp", "Skipping symlink pointing outside destination: " + entryName + " -> " + entry.getLinkName());
                            continue;
                        }
                        FileUtils.symlink(entry.getLinkName(), file.getAbsolutePath());
                    } else {
                        File parent = file.getParentFile();
                        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
                        try (BufferedOutputStream outStream = new BufferedOutputStream(new FileOutputStream(file), StreamUtils.BUFFER_SIZE)) {
                            if (!StreamUtils.copy(tar, outStream)) return false;
                        }
                    }
                }

                FileUtils.chmod(file, 0771);
            }
            return true;
        } catch (IOException e) {
            Log.e("RestoreOp", "Failed to extract tar file", e);
            return false;
        }
    }


}







