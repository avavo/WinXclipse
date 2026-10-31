package com.winlator.cmod.container;

import android.content.Context;

import com.winlator.cmod.contents.DependencyImportSession;
import com.winlator.cmod.core.FileUtils;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Retains a pending config independently of a Fragment/Activity and of process restarts. */
public final class PendingConfigImport {
    public enum State { IDLE, RUNNING, STOPPING, PAUSED, READY, APPLYING, FAILED, CANCELLED }
    public interface Observer { void changed(); }
    interface Resolver {
        List<CommunityConfigManager.ContentResolution> resolve(JSONObject manifest, DependencyImportSession session) throws Exception;
    }
    private static PendingConfigImport instance;
    private final File receipt;
    private final ExecutorService worker;
    private final Resolver resolver;
    private Observer observer;
    private boolean running;
    private long generation;
    private DependencyImportSession session;
    private State state;
    private DependencyImportSession.Stage stage = DependencyImportSession.Stage.LOCAL;
    private String name = "", error = "";
    private int percent;
    private JSONObject manifest;
    private List<CommunityConfigManager.ContentResolution> resolutions;

    private PendingConfigImport(Context context) {
        this(new File(context.getApplicationContext().getFilesDir(), "pending-config-import.json"),
                Executors.newSingleThreadExecutor(), (manifest, session) ->
                        CommunityConfigManager.resolveContents(context.getApplicationContext(), manifest, session));
    }

    PendingConfigImport(File receipt, ExecutorService worker, Resolver resolver) {
        this.receipt = receipt;
        this.worker = worker;
        this.resolver = resolver;
        state = receipt.isFile() ? State.PAUSED : State.IDLE;
    }

    public static synchronized PendingConfigImport get(Context context) {
        if (instance == null) instance = new PendingConfigImport(context);
        return instance;
    }

    public synchronized void observe(Observer observer) { this.observer = observer; }
    public synchronized void detach(Observer observer) { if (this.observer == observer) this.observer = null; }
    public synchronized State state() { return state; }
    public synchronized DependencyImportSession.Stage stage() { return stage; }
    public synchronized String name() { return name; }
    public synchronized String error() { return error; }
    public synchronized int percent() { return percent; }
    public synchronized boolean isRunning() { return running; }
    public synchronized JSONObject manifest() { return manifest; }
    public synchronized List<CommunityConfigManager.ContentResolution> resolutions() { return resolutions; }

    public synchronized void offer(JSONObject config) throws IOException {
        if (running) throw new IOException("Another configuration import is still running");
        try { config = new JSONObject(config.toString()); }
        catch (org.json.JSONException invalid) { throw new IOException("Invalid pending configuration", invalid); }
        if (!FileUtils.writeStringAtomic(receipt, config.toString())) throw new IOException("Could not save pending configuration");
        manifest = config;
        resolutions = null;
        state = State.PAUSED;
        error = "";
    }

    public void start() {
        final long attempt;
        final DependencyImportSession active;
        synchronized (this) {
            if (running || !receipt.isFile()) return;
            running = true;
            state = State.RUNNING;
            stage = DependencyImportSession.Stage.LOCAL;
            error = "";
            percent = 0;
            attempt = ++generation;
            session = active = new DependencyImportSession(new File(receipt.getParentFile(), "content-downloads"),
                    (phase, label, progress) -> {
                        synchronized (PendingConfigImport.this) {
                            if (attempt != generation || state != State.RUNNING) return;
                            stage = phase; name = label; percent = progress;
                        }
                        notifyObserver();
                    });
        }
        notifyObserver();
        worker.execute(() -> {
            try {
                JSONObject config;
                synchronized (this) { config = manifest; }
                if (config == null) config = new JSONObject(FileUtils.readString(receipt));
                List<CommunityConfigManager.ContentResolution> resolved = resolver.resolve(config, active);
                synchronized (this) {
                    if (attempt != generation) return;
                    active.checkRunning();
                    manifest = config;
                    resolutions = resolved;
                    running = false;
                    session = null;
                    state = State.READY;
                }
            } catch (DependencyImportSession.Stopped stopped) {
                synchronized (this) {
                    if (attempt == generation) state = stopped.cancelled ? State.CANCELLED : State.PAUSED;
                }
            } catch (Exception failure) {
                synchronized (this) {
                    if (attempt == generation) { state = State.FAILED; error = failure.getMessage(); }
                }
            } finally {
                synchronized (this) { if (session == active) { running = false; session = null; } }
                notifyObserver();
            }
        });
    }

    public void pause() {
        synchronized (this) {
            if (!running || state == State.APPLYING) return;
            state = State.STOPPING;
            if (session != null) session.pause();
        }
        notifyObserver();
    }

    public void cancel() {
        synchronized (this) {
            if (state == State.APPLYING) return;
            generation++;
            if (session != null) session.cancel();
            receipt.delete();
            state = State.CANCELLED;
            manifest = null;
            resolutions = null;
        }
        notifyObserver();
    }

    public void complete() {
        synchronized (this) { receipt.delete(); running = false; state = State.IDLE; manifest = null; resolutions = null; }
        notifyObserver();
    }

    /** Exactly one creation can consume a ready import, including after Activity recreation. */
    public boolean beginApply() {
        synchronized (this) {
            if (running || state != State.READY) return false;
            running = true;
            state = State.APPLYING;
            stage = DependencyImportSession.Stage.INSTALLING;
            name = "";
        }
        notifyObserver();
        return true;
    }

    public void applyFailed() {
        synchronized (this) {
            if (state != State.APPLYING) return;
            running = false;
            state = State.READY;
        }
        notifyObserver();
    }

    private void notifyObserver() {
        Observer listener;
        synchronized (this) { listener = observer; }
        if (listener != null) listener.changed();
    }
}
