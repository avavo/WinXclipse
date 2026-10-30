package com.winlator.cmod.core;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import java.util.function.Consumer;

public final class DriverCapabilities {
    public final int apiVersion;
    public final String[] extensions;

    private DriverCapabilities(int apiVersion, String[] extensions) {
        this.apiVersion = apiVersion;
        this.extensions = extensions;
    }

    /** Call on the UI thread; close the request when its dialog closes. A crash/timeout returns null. */
    public static Request query(Context context, String driver, Consumer<DriverCapabilities> callback) {
        Request request = new Request(context.getApplicationContext(), driver, callback);
        request.start();
        return request;
    }

    public static final class Request implements AutoCloseable, ServiceConnection {
        private final Context context;
        private final String driver;
        private final Consumer<DriverCapabilities> callback;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable timeout = () -> finish(null);
        private final Messenger reply = new Messenger(new Handler(Looper.getMainLooper(), message -> {
            Bundle data = message.getData();
            String[] extensions = data.getStringArray("extensions");
            finish(extensions == null ? null : new DriverCapabilities(data.getInt("apiVersion"), extensions));
            return true;
        }));
        private boolean bound;
        private boolean closed;

        private Request(Context context, String driver, Consumer<DriverCapabilities> callback) {
            this.context = context;
            this.driver = driver;
            this.callback = callback;
        }

        private void start() {
            try {
                bound = context.bindService(new Intent(context, DriverProbeService.class), this, Context.BIND_AUTO_CREATE);
            }
            catch (RuntimeException ignored) {}
            if (!bound) handler.post(timeout);
            else handler.postDelayed(timeout, 10000);
        }

        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (closed) return;
            Message message = Message.obtain(null, 1);
            Bundle data = new Bundle();
            data.putString("driver", driver);
            message.setData(data);
            message.replyTo = reply;
            try { new Messenger(binder).send(message); }
            catch (RemoteException error) { finish(null); }
        }

        @Override public void onServiceDisconnected(ComponentName name) { finish(null); }
        @Override public void onBindingDied(ComponentName name) { finish(null); }
        @Override public void onNullBinding(ComponentName name) { finish(null); }

        private void finish(DriverCapabilities value) {
            if (closed) return;
            close();
            callback.accept(value);
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            handler.removeCallbacks(timeout);
            if (bound) {
                try { context.unbindService(this); }
                catch (IllegalArgumentException ignored) {}
                bound = false;
            }
        }
    }
}
