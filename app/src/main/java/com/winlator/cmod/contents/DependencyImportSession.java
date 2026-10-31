package com.winlator.cmod.contents;

import com.winlator.cmod.core.ContentOperationRegistry;

import java.io.File;
import java.io.IOException;
import java.util.function.Supplier;
import java.util.HashMap;
import java.util.Map;

/** Pause is a control-flow outcome, never evidence that an exact dependency is unavailable. */
public final class DependencyImportSession {
    public enum Stage { CATALOG, LOCAL, QUEUED, DOWNLOADING, INSTALLING }
    public enum Failure { UNAVAILABLE, CATALOG, DOWNLOAD, PACKAGE }
    public interface Listener { void update(Stage stage, String name, int percent); }
    public static final class Stopped extends IOException {
        public final boolean cancelled;
        private Stopped(boolean cancelled) { super(cancelled ? "Import cancelled" : "Import paused"); this.cancelled = cancelled; }
    }

    private final File directory;
    private final Listener listener;
    private boolean paused, cancelled;
    private ResumableDownload.Control control;
    private final Map<String, Failure> failures = new HashMap<>();

    public void failed(String role, Failure failure) {
        if (failure.ordinal() > failure(role).ordinal()) failures.put(role, failure);
    }
    public Failure failure(String role) { return failures.getOrDefault(role, Failure.UNAVAILABLE); }

    public DependencyImportSession(File directory, Listener listener) { this.directory = directory; this.listener = listener; }

    public synchronized void pause() { paused = true; if (control != null) control.pause(); }
    public synchronized void cancel() { cancelled = true; paused = true; if (control != null) control.cancel(); }
    public synchronized void checkRunning() throws Stopped { if (paused) throw new Stopped(cancelled); }

    public void update(Stage stage, String name, int percent) throws Stopped {
        checkRunning();
        if (listener != null) listener.update(stage, name, percent);
    }

    public File output(String address) {
        String path;
        try { path = new java.net.URI(address).getPath(); }
        catch (Exception invalid) { throw new IllegalArgumentException("Invalid download URL", invalid); }
        String name = path == null ? "package.wcp" : path.substring(path.lastIndexOf('/') + 1);
        return ResumableDownload.outputFile(directory, address, name.isEmpty() ? "package.wcp" : name);
    }

    /** Exact dependency installers use a private archive that survives another consumer's cleanup. */
    public ContentDownloadQueue.Artifact acquire(String address, String name) throws Stopped {
        ResumableDownload.Control current;
        synchronized (this) { checkRunning(); control = current = new ResumableDownload.Control(); }
        ContentDownloadQueue.Artifact artifact = null;
        try {
            artifact = ContentDownloadQueue.acquire(address, output(address), current,
                    percent -> { if (listener != null) listener.update(Stage.DOWNLOADING, name, percent); },
                    () -> { if (listener != null) listener.update(Stage.QUEUED, name, 0); });
            checkRunning();
            if (artifact.result == ResumableDownload.Result.PAUSED || artifact.result == ResumableDownload.Result.CANCELLED) {
                synchronized (this) { paused = true; cancelled |= artifact.result == ResumableDownload.Result.CANCELLED; }
                checkRunning();
            }
            return artifact;
        } catch (Stopped stopped) {
            if (artifact != null) artifact.close();
            throw stopped;
        } finally { synchronized (this) { if (control == current) control = null; } }
    }

    public <T> T install(String name, Supplier<T> install) throws Stopped {
        return install(name, install, () -> {});
    }

    public <T> T install(String name, Supplier<T> install, Runnable afterAttempt) throws Stopped {
        update(Stage.INSTALLING, name, 0);
        try (ContentOperationRegistry.Token operation = ContentOperationRegistry.begin(ContentOperationRegistry.Kind.INSTALL)) {
            T result;
            try { result = install.get(); }
            finally { afterAttempt.run(); }
            checkRunning();
            return result;
        }
    }
}
