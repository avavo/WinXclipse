package com.winlator.cmod.core;

import android.app.ActivityManager;
import android.content.Context;

/** One immutable Android RAM reading, shared by the HUD and task manager. */
public final class SystemMemoryUsage {
    public static final SystemMemoryUsage UNKNOWN = new SystemMemoryUsage(-1, -1);
    public final long total, available, used;
    public final int usedPercent;

    public SystemMemoryUsage(long total, long available) {
        boolean valid = total > 0 && available >= 0 && available <= total;
        this.total = valid ? total : -1;
        this.available = valid ? available : -1;
        used = valid ? total - available : -1;
        usedPercent = valid ? (int)Math.round(used * 100d / total) : -1;
    }

    public static SystemMemoryUsage read(Context context) {
        try {
            ActivityManager manager = (ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return UNKNOWN;
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(info);
            return new SystemMemoryUsage(info.totalMem, info.availMem);
        } catch (RuntimeException unavailable) { return UNKNOWN; }
    }
}
