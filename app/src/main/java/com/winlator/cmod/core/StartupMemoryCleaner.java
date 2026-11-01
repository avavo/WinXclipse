package com.winlator.cmod.core;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import java.util.function.BooleanSupplier;

/** Optional one-shot release of unused app allocator pages before launching Wine. */
public final class StartupMemoryCleaner {
    private StartupMemoryCleaner() {}
    public static void run(Context context, BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) return;
        try {
            long started = SystemClock.elapsedRealtime();
            // Do not allocate/touch anonymous memory to pressure other apps.
            // Java objects left by preparation can become collectible before the purge.
            Runtime.getRuntime().gc();
            if (cancelled.getAsBoolean()) return;
            int accepted = nativeTrim();
            Log.i("StartupMemory", "Unused app heap purge accepted=" + accepted + " elapsedMs="
                    + (SystemClock.elapsedRealtime()-started));
        } catch (RuntimeException | LinkageError failure) {
            Log.w("StartupMemory", "Optional memory preparation skipped", failure);
        }
    }
    static { System.loadLibrary("winlator"); }
    private static native int nativeTrim();
}
