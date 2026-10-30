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

    private ContentDownloadQueue() {}

    public static boolean hasSavedDownload(String address, File output) {
        if (ResumableDownload.hasSavedDownload(address, output)) return true;
        try {
            return address.equals(new String(Files.readAllBytes(request(output).toPath()), StandardCharsets.UTF_8));
        } catch (IOException ignored) { return false; }
    }

    public static void discard(File output) {
        ResumableDownload.discard(output);
        request(output).delete();
    }

    public static ResumableDownload.Result download(String address, File output,
            ResumableDownload.Control control, Downloader.ProgressCallback progress, Runnable queued) {
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
            WRITING.put(key, true);
            try (ContentOperationRegistry.Token operation = ContentOperationRegistry.begin(ContentOperationRegistry.Kind.DOWNLOAD)) {
                ResumableDownload.Result result = ResumableDownload.download(address, output, control, progress);
                if (result == ResumableDownload.Result.COMPLETE) request(output).delete();
                else if (result == ResumableDownload.Result.CANCELLED) discard(output);
                return result;
            } finally { WRITING.remove(key); }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            control.pause();
            return ResumableDownload.Result.PAUSED;
        } catch (IOException failure) { return ResumableDownload.Result.FAILED; }
        finally { if (acquired) NETWORK.release(); }
    }

    private static File request(File output) { return new File(output.getPath() + ".request"); }
}
