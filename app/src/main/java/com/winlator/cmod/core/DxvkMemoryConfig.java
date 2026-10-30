package com.winlator.cmod.core;

/** Reported D3D memory, separate from the wrapper's Vulkan heap limit. */
public final class DxvkMemoryConfig {
    public static final int RAGE_POOL_MB = 512;

    private DxvkMemoryConfig() {}

    public static int effectiveCapMb(int driverCapMb, int experimentalCapMb) {
        int driver = Math.max(0, driverCapMb);
        int experimental = Math.max(0, experimentalCapMb);
        return driver > 0 && experimental > 0
                ? Math.min(driver, experimental) : Math.max(driver, experimental);
    }

    public static int normalizePoolMb(int poolMb) {
        return poolMb <= 0 ? 0 : Math.min(Math.max(poolMb, 256), 12288);
    }

    /**
     * RAM Fix applies conservative reported-memory budgets to every game.
     * RAGE has an additional 512 MB Auto pool; it is not the only supported
     * game. Reference budgets are used only when RAM Fix is disabled.
     */
    public static String build(long totalMemMb, int explicitCapMb, int poolMb,
                               boolean ramFixOn, boolean rageSmallPool) {
        int cap = Math.max(0, explicitCapMb);
        int pool = ramFixOn ? normalizePoolMb(poolMb) : 0;
        if (ramFixOn && pool == 0 && rageSmallPool) pool = RAGE_POOL_MB;

        int device;
        int shared;
        int d3d9;
        if (pool > 0) {
            int reported = cap > 0 ? Math.min(pool, cap) : pool;
            device = shared = d3d9 = reported;
        } else if (cap > 0) {
            device = shared = cap;
            d3d9 = Math.min(cap, 4096);
        } else if (ramFixOn) {
            device = totalMemMb <= 6144 ? 1024 : 1536;
            shared = 1024;
            d3d9 = 2048;
        } else {
            device = totalMemMb <= 6144 ? 2048 : 3072;
            shared = 2048;
            d3d9 = 4096;
        }

        // d3d9.maxDeviceMemory is not a DXVK option. Only generate keys
        // understood by both the bundled DXVK 1.x and newer DXVK releases.
        return "dxgi.maxDeviceMemory = " + device
                + "; dxgi.maxSharedMemory = " + shared
                + "; d3d9.maxAvailableMemory = " + d3d9;
    }
}
