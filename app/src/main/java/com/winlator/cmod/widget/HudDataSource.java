package com.winlator.cmod.widget;

// Ported from Winlator Mali:
// https://github.com/GunaCharanTeja/WinlatorMali

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;

import com.winlator.cmod.core.CPUStatus;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.concurrent.atomic.AtomicInteger;

public class HudDataSource {
    public final AtomicInteger gpuLoad       = new AtomicInteger(-1);
    public final AtomicInteger cpuLoad       = new AtomicInteger(-1);
    public final AtomicInteger batteryMw     = new AtomicInteger(-1);
    public final AtomicInteger batteryTempC  = new AtomicInteger(-1);
    public final AtomicInteger cpuTempC      = new AtomicInteger(-1);
    public final AtomicInteger gpuTempC      = new AtomicInteger(-1);
    public final AtomicInteger batteryPct    = new AtomicInteger(-1);
    public final AtomicInteger ramUsagePct  = new AtomicInteger(-1);

    private HandlerThread thread;
    private Handler handler;
    private final Context context;
    private final BatteryManager batteryManager;

    private String gpuPath = null;
    private boolean gpuFailed = false;
    private long lastMaliGpuInfoMs = -1;
    private long lastMaliGpuInfoWallMs = -1;
    private java.util.List<String> discoveredCpuTempPaths = null;
    private java.util.List<String> discoveredGpuTempPaths = null;

    private static final boolean USE_BATTERY_AS_CPU_FALLBACK = false;

    private static final String[] GPU_PATHS = {
        "/sys/class/misc/mali0/device/utilisation",
        "/sys/class/misc/mali0/device/gpuinfo",
        "/sys/devices/platform/mali/utilization",
        "/sys/devices/platform/13000000.mali/utilization",
        "/sys/module/mali_kbase/parameters/mali_gpu_utilization",
        "/sys/module/mali/parameters/mali_gpu_utilization",
        "/proc/mali/utilization",
        "/sys/kernel/gpu/gpu_busy",
        "/sys/module/ged/parameters/gpu_loading",
        "/sys/class/devfreq/mtk-dvfs-gpu/gpu_loading",
        "/sys/class/devfreq/gpufreq/gpu_load",
        "/sys/class/devfreq/gpu/load",
        "/sys/class/misc/pvrsrvkm/device/utilisation",
        "/proc/gpufreq/gpufreq_power_dump",
    };

    private static final String[] CPU_TEMP_PATHS = {
        "/sys/class/thermal/thermal_zone7/temp", // SD8 Gen 2/3
        "/sys/class/thermal/thermal_zone0/temp", // Common MTK / Generic
        "/sys/class/thermal/thermal_zone1/temp",
        "/proc/mtktscpu/mtktscpu",               // Legacy MediaTek
        "/sys/class/thermal/cpu-thermal/temp"
    };

    private static final String[] CURRENT_PATHS = {
        "/sys/class/power_supply/battery/current_now",
        "/sys/class/power_supply/bms/current_now",
        "/sys/class/power_supply/maxfg/current_now",
        "/sys/class/power_supply/maxfg/ibat_now"
    };

    private static final String[] VOLTAGE_PATHS = {
        "/sys/class/power_supply/battery/voltage_now",
        "/sys/class/power_supply/bms/voltage_now",
        "/sys/class/power_supply/maxfg/voltage_now",
        "/sys/class/power_supply/maxfg/vbat_now"
    };

    public HudDataSource(Context context) {
        this.context = context.getApplicationContext();
        this.batteryManager = (BatteryManager) this.context.getSystemService(Context.BATTERY_SERVICE);
        thread = new HandlerThread("WinlatorHUD", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    public void start() {
        if (!thread.isAlive()) {
            thread = new HandlerThread("WinlatorHUD", android.os.Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            handler = new Handler(thread.getLooper());
            gpuFailed = false;
            gpuPath = null;
        }
        handler.removeCallbacksAndMessages(null);
        handler.post(this::poll);
    }

    public void stop() {
        handler.removeCallbacksAndMessages(null);
        thread.quitSafely();
    }

    private void poll() {
        pollGpu();
        pollCpu();
        pollBattery();
        pollCpuTemp();
        pollGpuTemp();
        pollRam();
        handler.postDelayed(this::poll, 1500);
    }

    private void pollGpu() {
        if (gpuFailed) return;

        if (gpuPath != null) {
            int v = gpuPath.endsWith("gpuinfo") ? readGpuInfo(gpuPath) : readPercent(gpuPath);
            if (v >= 0) { gpuLoad.set(v); return; }
            gpuPath = null;
        }

        String path = findGpuPath();
        if (path != null) {
            gpuPath = path;
            int v = path.endsWith("gpuinfo") ? readGpuInfo(path) : readPercent(path);
            gpuLoad.set(v >= 0 ? v : 0);
            return;
        }

        gpuFailed = true;
        gpuLoad.set(-1);
    }

    private String findGpuPath() {
        for (String p : GPU_PATHS) {
            File f = new File(p);
            if (f.exists() && f.canRead()) {
                if (p.endsWith("gpuinfo")) return p;
                if (readPercent(p) >= 0) return p;
            }
        }

        try {
            File devfreqDir = new File("/sys/class/devfreq");
            if (devfreqDir.isDirectory()) {
                File[] subdirs = devfreqDir.listFiles();
                if (subdirs != null) {
                    for (File dir : subdirs) {
                        if (dir.isDirectory()) {
                            String[] candidates = {"gpu_load", "gpu_loading", "load", "percent", "utilisation", "utilization", "gpuinfo"};
                            for (String name : candidates) {
                                File f = new File(dir, name);
                                if (f.isFile() && f.canRead()) {
                                    if (name.equals("gpuinfo")) return f.getPath();
                                    if (readPercent(f.getPath()) >= 0) return f.getPath();
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        try {
            File platformDir = new File("/sys/devices/platform");
            if (platformDir.isDirectory()) {
                File[] subdirs = platformDir.listFiles();
                if (subdirs != null) {
                    for (File dir : subdirs) {
                        String nameLower = dir.getName().toLowerCase();
                        if (dir.isDirectory() && (nameLower.contains("mali") || nameLower.contains("gpu"))) {
                            String[] candidates = {"utilization", "utilisation", "gpu_load", "gpu_loading", "load", "percent", "gpuinfo"};
                            for (String name : candidates) {
                                File f = new File(dir, name);
                                if (f.isFile() && f.canRead()) {
                                    if (name.equals("gpuinfo")) return f.getPath();
                                    if (readPercent(f.getPath()) >= 0) return f.getPath();
                                }
                            }
                            
                            File[] subFiles = dir.listFiles();
                            if (subFiles != null) {
                                for (File sub : subFiles) {
                                    if (sub.isDirectory()) {
                                        for (String sname : candidates) {
                                            File f = new File(sub, sname);
                                            if (f.isFile() && f.canRead()) {
                                                if (sname.equals("gpuinfo")) return f.getPath();
                                                if (readPercent(f.getPath()) >= 0) return f.getPath();
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    private int readInt(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String l = r.readLine();
            if (l == null) return -1;
            return Integer.parseInt(l.trim().replaceAll("[^0-9]", ""));
        } catch (Exception e) { return -1; }
    }

    private int readPercent(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String l = r.readLine();
            if (l == null) return -1;
            int val = Integer.parseInt(l.trim().replaceAll("[^0-9]", ""));
            return (val >= 0 && val <= 100) ? val : -1;
        } catch (Exception e) { return -1; }
    }

    private void pollCpu() {
        try {
            short[] clocks = CPUStatus.getCurrentClockSpeeds();
            long cur = 0, max = 0;
            for (int i = 0; i < clocks.length; i++) {
                cur += clocks[i];
                max += CPUStatus.getMaxClockSpeed(i);
            }
            cpuLoad.set(max > 0 ? (int) Math.min(100, cur * 100L / max) : -1);
        } catch (Exception e) {
            cpuLoad.set(-1);
        }
    }

    private void pollCpuTemp() {
        for (String p : discoverCpuTempPaths()) {
            int v = readInt(p);
            if (v > 0) {
                if (v > 5000) v /= 1000;
                else if (v > 200) v /= 10;
                
                if (v > 0 && v < 120) {
                    cpuTempC.set(v);
                    return;
                }
            }
        }

        for (String p : CPU_TEMP_PATHS) {
            int v = readInt(p);
            if (v > 0) {
                // Scaling detection:
                // Values like 45000 are millidegrees (divide by 1000)
                // Values like 450 are degrees * 10 (divide by 10)
                // Values like 45 are just degrees
                if (v > 5000) v /= 1000;
                else if (v > 200) v /= 10;
                
                if (v > 0 && v < 120) { // Basic sanity check for valid CPU temp
                    cpuTempC.set(v);
                    return;
                }
            }
        }

        int batt = batteryTempC.get();
        if (batt > 0 && batt < 120 && USE_BATTERY_AS_CPU_FALLBACK) {
            // Off by default: mirrors the battery sensor (already shown as TMP),
            // which confused users into thinking it was a real CPU reading.
            // Samsung's SELinux policy blocks /sys/class/thermal on recent
            // firmware, so without root there is no true CPU temp source.
            cpuTempC.set(batt);
            return;
        }
        cpuTempC.set(-1);
    }

    private void pollGpuTemp() {
        for (String p : discoverGpuTempPaths()) {
            int v = readInt(p);
            if (v > 0) {
                if (v > 5000) v /= 1000;
                else if (v > 200) v /= 10;

                if (v > 0 && v < 120) {
                    gpuTempC.set(v);
                    return;
                }
            }
        }
        gpuTempC.set(-1);
    }

    private void pollBattery() {
        try {
            Intent batt = context.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batt == null) return;

            int temp = batt.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
            batteryTempC.set(temp > 0 ? temp / 10 : -1);

            int pct = batt.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            batteryPct.set(pct);

            int status = batt.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                            || status == BatteryManager.BATTERY_STATUS_FULL;
            int mv = batt.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
            if (mv <= 0) {
                long uv = firstNonZero(VOLTAGE_PATHS);
                if (uv > 0) mv = (int) (uv / 1000L);
            }

            long rawCurrent = 0;
            if (batteryManager != null)
                rawCurrent = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            if (rawCurrent == Long.MIN_VALUE) rawCurrent = 0;
            if (rawCurrent == 0) rawCurrent = firstNonZero(CURRENT_PATHS);

            float amps = normalizeCurrentAmps(rawCurrent);
            if (mv > 0 && amps > 0f) {
                // Unlike the old CHG-only branch, keep showing real watts while
                // charging. Samsung devices expose a signed current; abs() in
                // Normalize the different current units exposed by Android kernels.
                batteryMw.set(Math.round((mv / 1000f) * amps * 1000f));
            } else {
                batteryMw.set(charging ? -2 : -1);
            }
        } catch (Exception ignored) {}
    }

    /**
     * BatteryManager normally reports microamps, while a number of vendor
     * sysfs nodes (including Samsung fuel gauges) expose milliamps.  Match
     * Detect the kernel current unit before calculating voltage x current.
     */
    private float normalizeCurrentAmps(long rawCurrent) {
        if (rawCurrent == 0 || rawCurrent == Long.MIN_VALUE) return -1f;
        long magnitude = Math.abs(rawCurrent);
        return magnitude < 20_000L ? magnitude / 1_000f : magnitude / 1_000_000f;
    }

    private void pollRam() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.FileReader("/proc/meminfo"))) {
            long memTotal = -1, memAvail = -1;
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MemTotal:"))     memTotal = parseMeminfoKb(line);
                else if (line.startsWith("MemAvailable:")) { memAvail = parseMeminfoKb(line); break; }
            }
            if (memTotal > 0 && memAvail >= 0)
                ramUsagePct.set((int)(100L * (memTotal - memAvail) / memTotal));
            else ramUsagePct.set(-1);
        } catch (Exception e) { ramUsagePct.set(-1); }
    }

    private long parseMeminfoKb(String line) {
        try {
            String[] parts = line.trim().split("\\s+");
            return Long.parseLong(parts[1]);
        } catch (Exception e) { return -1; }
    }


    private long firstNonZero(String[] paths) {
        for (String path : paths) {
            long v = readSysFsLong(path);
            if (v != 0) return v;
        }
        return 0;
    }

    private long readSysFsLong(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String l = r.readLine();
            return l != null ? Long.parseLong(l.trim()) : 0;
        } catch (Exception e) { return 0; }
    }

    private String readSysFsString(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String l = r.readLine();
            return l != null ? l : "";
        } catch (Exception e) { return ""; }
    }

    private int readGpuInfo(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            r.readLine(); // skip line 0
            String line = r.readLine(); // line 1
            if (line == null) return -1;
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 0) return -1;
            long gpuMs = Long.parseLong(parts[parts.length - 1]);
            long now = android.os.SystemClock.elapsedRealtime();
            long prevMs = lastMaliGpuInfoMs;
            long prevWall = lastMaliGpuInfoWallMs;
            lastMaliGpuInfoMs = gpuMs;
            lastMaliGpuInfoWallMs = now;
            if (prevMs < 0 || prevWall <= 0) return -1;
            long wallDelta = now - prevWall;
            if (wallDelta <= 0) return -1;
            long gpuDelta = gpuMs - prevMs;
            if (gpuDelta < 0) gpuDelta = 0;
            return (int) Math.min(100, (gpuDelta * 100L) / wallDelta);
        } catch (Exception e) { return -1; }
    }

    private java.util.List<String> discoverCpuTempPaths() {
        if (discoveredCpuTempPaths != null) return discoveredCpuTempPaths;
        
        java.util.List<CpuTempCandidate> candidates = new java.util.ArrayList<>();
        String[] roots = {"/sys/class/thermal", "/sys/devices/virtual/thermal"};
        
        for (String root : roots) {
            File dir = new File(root);
            if (!dir.exists() || !dir.isDirectory()) continue;
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File file : files) {
                if (file.isDirectory() && file.getName().startsWith("thermal_zone")) {
                    File typeFile = new File(file, "type");
                    File tempFile = new File(file, "temp");
                    if (typeFile.exists() && tempFile.exists() && tempFile.canRead()) {
                        String type = readSysFsString(typeFile.getPath()).trim().toLowerCase(java.util.Locale.US);
                        int rank = getCpuTempRank(type);
                        if (rank >= 0) {
                            candidates.add(new CpuTempCandidate(tempFile.getPath(), rank));
                        }
                    }
                }
            }
        }
        
        java.util.Collections.sort(candidates, (c1, c2) -> Integer.compare(c1.rank, c2.rank));
        
        java.util.List<String> paths = new java.util.ArrayList<>();
        for (CpuTempCandidate c : candidates) {
            if (!paths.contains(c.path)) paths.add(c.path);
        }
        
        discoveredCpuTempPaths = paths;
        return paths;
    }
    
    private java.util.List<String> discoverGpuTempPaths() {
        if (discoveredGpuTempPaths != null) return discoveredGpuTempPaths;

        java.util.List<CpuTempCandidate> candidates = new java.util.ArrayList<>();
        String[] roots = {"/sys/class/thermal", "/sys/devices/virtual/thermal"};

        for (String root : roots) {
            File dir = new File(root);
            if (!dir.exists() || !dir.isDirectory()) continue;
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File file : files) {
                if (file.isDirectory() && file.getName().startsWith("thermal_zone")) {
                    File typeFile = new File(file, "type");
                    File tempFile = new File(file, "temp");
                    if (typeFile.exists() && tempFile.exists() && tempFile.canRead()) {
                        String type = readSysFsString(typeFile.getPath()).trim().toLowerCase(java.util.Locale.US);
                        int rank = getGpuTempRank(type);
                        if (rank >= 0) {
                            candidates.add(new CpuTempCandidate(tempFile.getPath(), rank));
                        }
                    }
                }
            }
        }

        java.util.Collections.sort(candidates, (c1, c2) -> Integer.compare(c1.rank, c2.rank));

        java.util.List<String> paths = new java.util.ArrayList<>();
        for (CpuTempCandidate c : candidates) {
            if (!paths.contains(c.path)) paths.add(c.path);
        }

        discoveredGpuTempPaths = paths;
        return paths;
    }

    private int getGpuTempRank(String type) {
        if (type.contains("g3d")) return 0;
        if (type.contains("gpu")) return 1;
        if (type.contains("mali")) return 2;
        if (type.contains("gpufreq")) return 3;
        return -1;
    }

    private int getCpuTempRank(String type) {
        if (type.contains("gpu") || type.contains("g3d")) return -1;
        if (type.contains("cpu-silicon")) return 0;
        if (type.contains("cpu-0")) return 1;
        if (type.contains("cputop")) return 2;
        if (type.contains("cpu")) return 3;
        if (type.contains("soc")) return 4;
        if (type.contains("s5p-tmu") || type.contains("exynos-tmu")) return 5;
        if (type.contains("acpu") || type.contains("apc0") || type.contains("apc1")) return 6;
        if (type.contains("tsens")) return 7;
        if (type.contains("cluster")) return 8;
        if (type.contains("big") || type.contains("little") || type.contains("mid")) return 9;
        if (type.contains("acpm")) return 10;
        if (isExcludedZone(type)) return -1;
        return 90;
    }

    private static boolean isExcludedZone(String type) {
        return type.contains("battery") || type.contains("batt")
                || type.contains("usb") || type.contains("charger")
                || type.contains("lcd") || type.contains("display")
                || type.contains("cam") || type.contains("isp")
                || type.contains("wifi") || type.contains("modem")
                || type.contains("pa_thermal");
    }
    
    private static class CpuTempCandidate {
        final String path;
        final int rank;
        CpuTempCandidate(String path, int rank) {
            this.path = path;
            this.rank = rank;
        }
    }
}
