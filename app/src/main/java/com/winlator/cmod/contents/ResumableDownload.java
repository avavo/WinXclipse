package com.winlator.cmod.contents;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A durable download workspace. Only a complete response is published as the output file. */
public final class ResumableDownload {
    public enum Result { COMPLETE, PAUSED, CANCELLED, FAILED }

    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
    private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<>();

    public static final class Control {
        private volatile boolean paused;
        private volatile boolean cancelled;
        private HttpURLConnection connection;

        public boolean isStopping() { return paused; }
        public boolean isCancelled() { return cancelled; }

        public synchronized void pause() {
            paused = true;
            if (connection != null) connection.disconnect();
        }

        public synchronized void cancel() {
            cancelled = true;
            pause();
        }

        private synchronized void attach(HttpURLConnection value) {
            connection = value;
            if (paused && value != null) value.disconnect();
        }
    }

    private ResumableDownload() {}

    /** Stable across process restarts; different URLs cannot share a partial file. */
    public static File outputFile(File directory, String address, String name) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(address.getBytes(StandardCharsets.UTF_8));
            StringBuilder id = new StringBuilder();
            for (byte value : hash) id.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            String safeName = name == null ? "package.wcp" : name.replaceAll("[^A-Za-z0-9._-]", "_");
            if (safeName.length() > 100) safeName = safeName.substring(safeName.length() - 100);
            return new File(directory, id + "-" + safeName);
        }
        catch (Exception error) { throw new IllegalArgumentException("Invalid download address", error); }
    }

    public static boolean hasSavedDownload(String address, File output) {
        Properties meta = readMeta(output);
        return address.equals(meta.getProperty("url"))
                && (partial(output).length() > 0 || isComplete(output, meta));
    }

    public static int savedProgress(File output) {
        Properties meta = readMeta(output);
        if (isComplete(output, meta)) return 99;
        long total = number(meta.getProperty("total"));
        return total > 0 ? percent(partial(output).length(), total) : 0;
    }

    /** Call after the worker has stopped, or use Control.cancel() while it is running. */
    public static void discard(File output) {
        output.delete();
        partial(output).delete();
        metadata(output).delete();
        new File(output.getPath() + ".meta.tmp").delete();
    }

    public static Result download(String address, File output, Control control,
                                  Downloader.ProgressCallback callback) {
        if (control == null) throw new IllegalArgumentException("A download control is required");
        Object lock = LOCKS.computeIfAbsent(output.getAbsolutePath(), ignored -> new Object());
        synchronized (lock) {
            try {
                if (control.paused) return stopped(control);
                File parent = output.getAbsoluteFile().getParentFile();
                if (parent == null || !parent.isDirectory() && !parent.mkdirs()) return Result.FAILED;
                Properties meta = readMeta(output);
                if (!address.equals(meta.getProperty("url"))) {
                    partial(output).delete();
                    meta.clear();
                }
                if (isComplete(output, meta)) {
                    if (callback != null) callback.onProgress(100);
                    return Result.COMPLETE;
                }
                // A strong validator is necessary to avoid appending a different release.
                String validator = validator(meta);
                long offset = partial(output).length();
                long oldTotal = number(meta.getProperty("total"));
                if (validator.isEmpty() || offset <= 0 || oldTotal > 0 && offset >= oldTotal) offset = 0;
                for (int attempt = 0; attempt < 2; attempt++) {
                    HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(30000);
                    connection.setInstanceFollowRedirects(true);
                    connection.setRequestProperty("Accept-Encoding", "identity");
                    connection.setRequestProperty("User-Agent", "WinXclipse-Android");
                    if (offset > 0) {
                        connection.setRequestProperty("Range", "bytes=" + offset + "-");
                        connection.setRequestProperty("If-Range", validator);
                    }
                    control.attach(connection);
                    try {
                        if (control.paused) return stopped(control);
                        int response = connection.getResponseCode();
                        long length = connection.getContentLengthLong();
                        long total;
                        if (response == HttpURLConnection.HTTP_PARTIAL && offset > 0) {
                            Matcher range = CONTENT_RANGE.matcher(value(connection.getHeaderField("Content-Range")));
                            if (!range.matches()) return Result.FAILED;
                            long start = number(range.group(1)), end = number(range.group(2));
                            total = number(range.group(3));
                            if (start != offset || end < start || total <= end
                                    || length >= 0 && length != end - start + 1) return Result.FAILED;
                            if (oldTotal > 0 && oldTotal != total || changedValidator(meta, connection)) {
                                offset = 0;
                                continue;
                            }
                        }
                        else if (response == HttpURLConnection.HTTP_OK) {
                            // If Range is unsupported or If-Range did not match, replace the partial.
                            offset = 0;
                            total = length;
                        }
                        else if (response == 416 && offset > 0) {
                            offset = 0;
                            continue;
                        }
                        else return Result.FAILED;

                        meta.clear();
                        meta.setProperty("url", address);
                        meta.setProperty("etag", value(connection.getHeaderField("ETag")));
                        meta.setProperty("lastModified", value(connection.getHeaderField("Last-Modified")));
                        meta.setProperty("total", Long.toString(total));
                        // Truncate before saving a new identity, so a crash cannot pair old bytes
                        // with the new validator. Open the append stream only after that receipt.
                        if (offset == 0) try (FileOutputStream reset = new FileOutputStream(partial(output))) {}
                        writeMeta(output, meta);
                        long downloaded = offset;
                        int lastPercent = -1;
                        try (InputStream input = connection.getInputStream();
                             FileOutputStream sink = new FileOutputStream(partial(output), true)) {
                            byte[] buffer = new byte[64 * 1024];
                            while (!control.paused) {
                                int count = input.read(buffer);
                                if (count == -1) break;
                                sink.write(buffer, 0, count);
                                downloaded += count;
                                if (total > 0 && downloaded > total) return Result.FAILED;
                                int progress = total > 0 ? percent(downloaded, total) : 0;
                                if (callback != null && progress != lastPercent) {
                                    lastPercent = progress;
                                    callback.onProgress(progress);
                                }
                            }
                            sink.flush();
                        }
                        if (control.paused) return stopped(control);
                        if (downloaded == 0 || total >= 0 && downloaded != total) return Result.FAILED;
                        move(partial(output), output);
                        meta.setProperty("total", Long.toString(downloaded));
                        meta.setProperty("complete", "true");
                        writeMeta(output, meta);
                        if (callback != null) callback.onProgress(100);
                        return Result.COMPLETE;
                    }
                    finally {
                        control.attach(null);
                        connection.disconnect();
                    }
                }
                return Result.FAILED;
            }
            catch (Exception error) { return control.paused ? stopped(control) : Result.FAILED; }
            finally { if (control.cancelled) discard(output); }
        }
    }

    private static Result stopped(Control control) {
        return control.cancelled ? Result.CANCELLED : Result.PAUSED;
    }

    private static boolean changedValidator(Properties meta, HttpURLConnection connection) {
        String etag = meta.getProperty("etag", "");
        if (!etag.isEmpty() && !etag.startsWith("W/")) {
            String current = value(connection.getHeaderField("ETag"));
            return !current.isEmpty() && !current.equals(etag);
        }
        String current = value(connection.getHeaderField("Last-Modified"));
        return !current.isEmpty() && !current.equals(meta.getProperty("lastModified", ""));
    }

    private static String validator(Properties meta) {
        String etag = meta.getProperty("etag", "");
        return !etag.isEmpty() && !etag.startsWith("W/") ? etag : meta.getProperty("lastModified", "");
    }

    private static boolean isComplete(File output, Properties meta) {
        return "true".equals(meta.getProperty("complete")) && output.isFile()
                && output.length() > 0 && output.length() == number(meta.getProperty("total"));
    }

    private static int percent(long bytes, long total) { return (int) Math.min(99, bytes * 100.0 / total); }
    private static String value(String value) { return value == null ? "" : value; }
    private static long number(String value) {
        try { return Long.parseLong(value); }
        catch (Exception error) { return -1; }
    }
    private static File partial(File output) { return new File(output.getPath() + ".part"); }
    private static File metadata(File output) { return new File(output.getPath() + ".meta"); }

    private static Properties readMeta(File output) {
        Properties meta = new Properties();
        try (FileInputStream input = new FileInputStream(metadata(output))) { meta.load(input); }
        catch (IOException | IllegalArgumentException ignored) {}
        return meta;
    }

    private static void writeMeta(File output, Properties meta) throws IOException {
        File temporary = new File(output.getPath() + ".meta.tmp");
        try (FileOutputStream sink = new FileOutputStream(temporary)) {
            meta.store(sink, "WinXclipse content download");
            sink.getFD().sync();
        }
        move(temporary, metadata(output));
    }

    private static void move(File source, File destination) throws IOException {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        }
        catch (AtomicMoveNotSupportedException error) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
