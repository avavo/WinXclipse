package com.winlator.cmod.core;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import com.winlator.cmod.contents.XclipseDriverManager;
import java.io.File;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Driver code executes only in :driver_probe, never inside the UI or a running Wine session. */
public final class DriverProbeService extends Service {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler lifecycle = new Handler(Looper.getMainLooper());
    private boolean hasClients;
    private final Messenger endpoint = new Messenger(new Handler(Looper.getMainLooper(), message -> {
        if (message.what != 1 || message.replyTo == null) return false;
        String id = message.getData().getString("driver", DefaultVersion.WRAPPER);
        Messenger recipient = message.replyTo;
        worker.execute(() -> probe(id, recipient));
        return true;
    }));

    @Override public IBinder onBind(Intent intent) {
        hasClients = true;
        return endpoint.getBinder();
    }

    @Override public void onRebind(Intent intent) { hasClients = true; }

    private void probe(String id, Messenger recipient) {
        Bundle result = new Bundle();
        try {
            File library = DefaultVersion.WRAPPER.equalsIgnoreCase(id) ? null
                    : new XclipseDriverManager(this).getDriverLibrary(id);
            if (library == null && !DefaultVersion.WRAPPER.equalsIgnoreCase(id))
                throw new IllegalStateException("Selected driver library is missing: " + id);
            if (library != null) library = library.getCanonicalFile();
            System.loadLibrary("winlator");
            File temporary = new File(getCacheDir(), "driver-probe");
            if (!temporary.isDirectory() && !temporary.mkdirs()) throw new IllegalStateException("Missing probe directory");
            String[] values = nativeProbe(getApplicationInfo().nativeLibraryDir, temporary.getAbsolutePath(),
                    library == null ? "" : library.getParent() + "/", library == null ? "" : library.getName());
            if (values == null || values.length < 1) throw new IllegalStateException("Vulkan driver query failed: " + id);
            result.putInt("apiVersion", Integer.parseInt(values[0]));
            result.putStringArray("extensions", Arrays.copyOfRange(values, 1, values.length));
        }
        catch (Exception | LinkageError error) { Log.e("DriverProbe", "Unable to query " + id, error); }
        Message response = Message.obtain(null, 1);
        response.setData(result);
        try { recipient.send(response); }
        catch (RemoteException ignored) {}
    }

    @Override public boolean onUnbind(Intent intent) {
        // Some drivers retain global namespaces/threads after vkDestroyInstance.
        // The last client unbinding ends only this disposable process.
        hasClients = false;
        lifecycle.postDelayed(() -> {
            if (hasClients) return;
            worker.shutdownNow();
            android.os.Process.killProcess(android.os.Process.myPid());
        }, 200);
        return true;
    }

    private static native String[] nativeProbe(String hooks, String temporary, String directory, String library);
}
