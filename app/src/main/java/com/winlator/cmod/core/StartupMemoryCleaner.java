package com.winlator.cmod.core;

import android.app.ActivityManager;
import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import java.util.function.BooleanSupplier;

/** Optional, bounded adaptation of Winlator Mali's prelaunch allocation pass. */
public final class StartupMemoryCleaner {
    private StartupMemoryCleaner() {}

    public static void run(Context context, BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) return;
        try {
            ActivityManager manager = (ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return;
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(info);
            long budget = StartupMemoryPolicy.budget(info.totalMem, info.availMem, info.lowMemory,
                    !ProcessHelper.listRunningWineProcesses().isEmpty());
            if (budget == 0 || cancelled.getAsBoolean()) return;
            long started = SystemClock.elapsedRealtime();
            long touched = nativePrepare(budget, StartupMemoryPolicy.reserve(info.totalMem),
                    StartupMemoryPolicy.MAX_MILLIS, cancelled);
            // These bytes were allocated and released by this pass, not a claim
            // that this much RAM was reclaimed from Wine or other applications.
            Log.i("StartupMemory", "Mali preparation touched " + touched + " bytes in "
                    + (SystemClock.elapsedRealtime() - started) + " ms");
        } catch (RuntimeException | LinkageError failure) {
            Log.w("StartupMemory", "Optional memory preparation skipped", failure);
        }
    }

    static { System.loadLibrary("winlator"); }
    private static native long nativePrepare(long budget, long reserve, int maxMillis,
                                             BooleanSupplier cancelled);
}
