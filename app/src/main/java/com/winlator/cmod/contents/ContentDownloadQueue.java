package com.winlator.cmod.contents;

import com.winlator.cmod.core.ContentOperationRegistry;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** One fair network queue shared by the catalog, manual URLs and config imports. */
public final class ContentDownloadQueue {
    private static final Semaphore NETWORK = new Semaphore(1, true);
    private static final ConcurrentHashMap<String, Boolean> WRITING = new ConcurrentHashMap<>();
    private static final Object FILE_STATE = new Object();
    private static final java.util.Set<String> DISCARD_PENDING = new java.util.HashSet<>();
    private static final java.util.Set<String> LIVE_ARTIFACTS = ConcurrentHashMap.newKeySet();

    /** An installer owns this immutable archive path until close, independently of cache eviction. */
    public static final class Artifact implements AutoCloseable {
        public final ResumableDownload.Result result;
        public final File file;
        private Artifact(ResumableDownload.Result result, File file) { this.result = result; this.file = file; }
        @Override public void close() {
            if (file != null) {
                file.delete();
                LIVE_ARTIFACTS.remove(file.getAbsolutePath());
            }
        }
    }

    private ContentDownloadQueue() {}

    public static boolean hasSavedDownload(String address, File output) {
        if (ResumableDownload.hasSavedDownload(address, output)) return true;
        try {
            return address.equals(new String(Files.readAllBytes(request(output).toPath()), StandardCharsets.UTF_8));
        } catch (IOException ignored) { return false; }
    }

    public static void discard(File output) {
        synchronized (FILE_STATE) {
            String key = output.getAbsolutePath();
            if (WRITING.containsKey(key)) { DISCARD_PENDING.add(key); return; }
            discardIdle(output);
        }
    }

    private static void discardIdle(File output) {
        ResumableDownload.discard(output);
        request(output).delete();
    }

    public static ResumableDownload.Result download(String address, File output,
            ResumableDownload.Control control, Downloader.ProgressCallback progress, Runnable queued) {
        return download(address, output, control, progress, queued, null);
    }

    public static Artifact acquire(String address, File output, ResumableDownload.Control control,
            Downloader.ProgressCallback progress, Runnable queued) {
        File[] archive = new File[1];
        ResumableDownload.Result result = download(address, output, control, progress, queued, archive);
        return new Artifact(result, archive[0]);
    }

    private static ResumableDownload.Result download(String address, File output,
            ResumableDownload.Control control, Downloader.ProgressCallback progress, Runnable queued, File[] archive) {
        boolean acquired = false;
        String key = output.getAbsolutePath();
        try {
            File parent = output.getAbsoluteFile().getParentFile();
            if (parent == null || !parent.isDirectory() && !parent.mkdirs()) return ResumableDownload.Result.FAILED;
            File temporary = File.createTempFile(".download-request-", ".tmp", parent);
            try {
                Files.write(temporary.toPath(), address.getBytes(StandardCharsets.UTF_8));
                Files.move(temporary.toPath(), request(output).toPath(), StandardCopyOption.REPLACE_EXISTING);
            } finally { temporary.delete(); }
            if (queued != null) queued.run();
            while (!control.isStopping()) {
                if (!NETWORK.tryAcquire(150, TimeUnit.MILLISECONDS)) continue;
                acquired = true;
                break;
            }
            if (!acquired || control.isStopping()) {
                if (control.isCancelled() && !WRITING.containsKey(key)) discard(output);
                return control.isCancelled() ? ResumableDownload.Result.CANCELLED : ResumableDownload.Result.PAUSED;
            }
            synchronized (FILE_STATE) { WRITING.put(key, true); }
            try (ContentOperationRegistry.Token operation = ContentOperationRegistry.begin(ContentOperationRegistry.Kind.DOWNLOAD)) {
                ResumableDownload.Result result = ResumableDownload.download(address, output, control, progress);
                if (result == ResumableDownload.Result.COMPLETE) {
                    if (archive != null) archive[0] = ownArchive(output, control);
                    request(output).delete();
                }
                else if (result == ResumableDownload.Result.CANCELLED) discard(output);
                return result;
            } finally {
                synchronized (FILE_STATE) {
                    WRITING.remove(key);
                    if (DISCARD_PENDING.remove(key)) discardIdle(output);
                }
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            control.pause();
            return ResumableDownload.Result.PAUSED;
        } catch (IOException failure) {
            if (control.isCancelled()) discard(output);
            return control.isStopping() ? control.isCancelled() ? ResumableDownload.Result.CANCELLED : ResumableDownload.Result.PAUSED
                    : ResumableDownload.Result.FAILED;
        }
        finally { if (acquired) NETWORK.release(); }
    }

    private static File ownArchive(File output, ResumableDownload.Control control) throws IOException {
        if (control.isStopping()) throw new IOException("Archive acquisition stopped");
        File parent = output.getAbsoluteFile().getParentFile();
        File[] abandoned = parent.listFiles((dir, name) -> name.startsWith(".in-use-"));
        if (abandoned != null) for (File file : abandoned) {
            if (!LIVE_ARTIFACTS.contains(file.getAbsolutePath())
                    && Files.isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) file.delete();
        }
        File owned = new File(parent, ".in-use-" + java.util.UUID.randomUUID() + "-" + output.getName());
        boolean ready = false;
        try {
            // Both paths are private app files on the same volume. A hardlink avoids
            // duplicating multi-GB runtimes; installers only read these archives.
            try { Files.createLink(owned.toPath(), output.toPath()); }
            catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
                // Portable fallback, cancellable and outside the UI/file-state lock.
                try (java.io.InputStream in = new java.io.FileInputStream(output);
                     java.io.OutputStream out = new java.io.FileOutputStream(owned)) {
                    byte[] buffer = new byte[256 * 1024];
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        if (control.isStopping()) throw new IOException("Archive acquisition stopped");
                        out.write(buffer, 0, count);
                    }
                }
            }
            if (control.isStopping() || owned.length() != output.length() || owned.length() == 0)
                throw new IOException("Incomplete installer archive");
            LIVE_ARTIFACTS.add(owned.getAbsolutePath()); ready = true;
            return owned;
        } finally { if (!ready) owned.delete(); }
    }

    private static File request(File output) { return new File(output.getPath() + ".request"); }
}
