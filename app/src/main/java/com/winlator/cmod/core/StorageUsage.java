package com.winlator.cmod.core;

/** Storage available to the user, including filesystem-reserved space in used. */
public final class StorageUsage {
    public static final String PREFERENCE = "file_manager_storage_units";
    public final long total;
    public final long available;
    public final long used;

    public StorageUsage(long total, long available) {
        this.total = Math.max(0, total);
        this.available = Math.max(0, Math.min(this.total, available));
        this.used = this.total - this.available;
    }

    public double usedPercent() { return total == 0 ? 0 : used * 100.0 / total; }
    public double availablePercent() { return total == 0 ? 0 : 100.0 - usedPercent(); }
    public int progress() { return (int) Math.round(usedPercent() * 10); }
    public static double gigabytes(long bytes) { return bytes / 1_000_000_000.0; }
}
