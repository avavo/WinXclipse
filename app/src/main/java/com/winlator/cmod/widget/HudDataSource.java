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
import com.winlator.cmod.core.CpuTelemetry;
import com.winlator.cmod.core.TelemetryMath;
import com.winlator.cmod.core.GameplayMeasurement;

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
    public final AtomicInteger gpuMhz = new AtomicInteger(-1);
    public volatile CpuTelemetry.Sample cpu = CpuTelemetry.EMPTY;
    public volatile com.winlator.cmod.core.SystemMemoryUsage memory = com.winlator.cmod.core.SystemMemoryUsage.UNKNOWN;
    public volatile long swapTotalBytes = -1, swapFreeBytes = -1;
    public volatile boolean cpuApThermistor;
    public volatile double batteryAvailableMah = Double.NaN;
    public volatile boolean batteryChargeEstimated, charging;
    public volatile long batteryCurrentMicroamps = Long.MIN_VALUE;
    public volatile GameplayMeasurement.Battery battery = new GameplayMeasurement.Battery(0,
            Double.NaN, Double.NaN, Double.NaN, false, false);
    private final CpuTelemetry cpuTelemetry = new CpuTelemetry();
    private boolean running;
    private long generation;
    private String gpuFrequencyPath;

    private HandlerThread thread;
    private Handler handler;
    private final Context context;
    private final BatteryManager batteryManager;

    private String gpuPath = null;
    private long lastMaliGpuInfoMs = -1;
    private long lastMaliGpuInfoWallMs = -1;
    private java.util.List<String> discoveredCpuTempPaths = null;
    private java.util.List<String> discoveredGpuTempPaths = null;

    private static final String[] GPU_PATHS = {
        "/sys/kernel/gpu/gpu_busy", // SGPU/Xclipse; do not prefer legacy Mali counters on Exynos.
        "/sys/class/misc/mali0/device/utilisation",
        "/sys/class/misc/mali0/device/gpuinfo",
        "/sys/devices/platform/mali/utilization",
        "/sys/devices/platform/13000000.mali/utilization",
        "/sys/module/mali_kbase/parameters/mali_gpu_utilization",
        "/sys/module/mali/parameters/mali_gpu_utilization",
        "/proc/mali/utilization",
        "/sys/module/ged/parameters/gpu_loading",
        "/sys/class/devfreq/mtk-dvfs-gpu/gpu_loading",
        "/sys/class/devfreq/gpufreq/gpu_load",
        "/sys/class/devfreq/gpu/load",
        "/sys/class/misc/pvrsrvkm/device/utilisation",
        "/proc/gpufreq/gpufreq_power_dump",
    };

    private static final String[] CPU_TEMP_PATHS = {
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
    }

    public synchronized void start() {
        if (running) return;
        if (thread == null || !thread.isAlive()) {
            thread = new HandlerThread("WinlatorHUD", android.os.Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            handler = new Handler(thread.getLooper());
            gpuPath = null;
        }
        running = true;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        long current = ++generation;
        handler.post(() -> {
            cpuTelemetry.reset();
            lastMaliGpuInfoMs = lastMaliGpuInfoWallMs = -1;
            poll(current);
        });
    }

    public synchronized void stop() {
        running = false;
        generation++;
        if (handler != null) handler.removeCallbacksAndMessages(null);
    }

    public synchronized void close() {
        stop();
        if (thread != null) thread.quitSafely();
        thread = null;
    }

    private void poll(long current) {
        synchronized (this) { if (!running || current != generation) return; }
        pollGpu();
        pollGpuFrequency();
        pollCpu();
        pollBattery();
        pollCpuTemp();
        pollGpuTemp();
        pollRam();
        synchronized (this) { if (running && current == generation) handler.postDelayed(() -> poll(current), 1500); }
    }

    private void pollGpu() {
        // Permissions and GPU runtime power state can change after the session starts.

        if (gpuPath != null) {
            int v = gpuPath.endsWith("gpuinfo") ? readGpuInfo(gpuPath) : readPercent(gpuPath);
            if (v >= 0) { gpuLoad.set(v); return; }
            gpuPath = null;
        }

        String path = findGpuPath();
        if (path != null) {
            gpuPath = path;
            int v = path.endsWith("gpuinfo") ? readGpuInfo(path) : readPercent(path);
            gpuLoad.set(v);
            return;
        }

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
                        if (dir.isDirectory() && isGpuNode(dir.getName())) {
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
            return TelemetryMath.percent(l);
        } catch (Exception e) { return -1; }
    }

    private void pollCpu() {
        cpu = cpuTelemetry.poll();
        cpuLoad.set(cpu.usage);
    }

    private static boolean isGpuNode(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("gpu") || lower.contains("g3d") || lower.contains("mali")
                || lower.contains("kgsl") || lower.contains("xclipse");
    }

    private void pollGpuFrequency() {
        if (gpuFrequencyPath != null) {
            int mhz = readGpuMhz(gpuFrequencyPath);
            if (mhz >= 0) { gpuMhz.set(mhz); return; }
            gpuFrequencyPath = null;
        }
        java.util.List<String> paths = new java.util.ArrayList<>();
        paths.add("/sys/kernel/gpu/gpu_clock"); // Samsung SGPU reports kHz.
        paths.add("/sys/class/kgsl/kgsl-3d0/gpuclk"); // Hz, like devfreq cur_freq.
        File[] nodes = new File("/sys/class/devfreq").listFiles();
        if (nodes != null) for (File node : nodes) if (isGpuNode(node.getName()))
            paths.add(new File(node, "cur_freq").getPath());
        for (String path : paths) {
            int mhz = readGpuMhz(path);
            if (mhz >= 0) { gpuFrequencyPath = path; gpuMhz.set(mhz); return; }
        }
        gpuMhz.set(-1);
    }

    private int readGpuMhz(String path) {
        long hz = readSysFsLong(path);
        if (path.equals("/sys/kernel/gpu/gpu_clock")) return TelemetryMath.samsungGpuMhz(hz);
        return hz > 0 && hz <= 10_000_000_000L ? (int)(hz / 1_000_000) : -1;
    }

    private int firstTemperature(java.util.List<String> paths) {
        for (String path : paths) {
            int value = TelemetryMath.temperatureC(readSysFsString(path));
            if (value >= 0) return value;
        }
        return -1;
    }

    private void pollCpuTemp() {
        cpuApThermistor = false;
        int value = firstTemperature(discoverCpuTempPaths());
        if (value < 0) value = firstTemperature(java.util.Arrays.asList(CPU_TEMP_PATHS));
        if (value < 0) {
            // Samsung's AP board thermistor is readable on the S24+; die TMU
            // nodes are denied. Keep its AP identity instead of claiming a die reading.
            value = firstTemperature(java.util.Arrays.asList(
                    "/sys/devices/virtual/sec/sec-ap-thermistor/temperature",
                    "/sys/class/sec/sec-ap-thermistor/temperature"));
            cpuApThermistor = value >= 0;
        }
        cpuTempC.set(value);
    }

    private void pollGpuTemp() {
        int value = firstTemperature(java.util.Arrays.asList("/sys/kernel/gpu/gpu_tmu"));
        if (value < 0) value = firstTemperature(discoverGpuTempPaths());
        gpuTempC.set(value);
    }

    private void pollBattery() {
        batteryTempC.set(-1);
        batteryMw.set(-1);
        batteryPct.set(-1);
        batteryAvailableMah = Double.NaN;
        batteryChargeEstimated = false;
        batteryCurrentMicroamps = Long.MIN_VALUE;
        charging = false;
        try {
            Intent batt = context.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batt == null) return;

            int temp = batt.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
            batteryTempC.set(temp > 0 ? temp / 10 : -1);

            int pct = TelemetryMath.batteryPercent(batt.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                    batt.getIntExtra(BatteryManager.EXTRA_SCALE, -1));
            batteryPct.set(pct);

            int status = batt.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                            || status == BatteryManager.BATTERY_STATUS_FULL;
            int mv = batt.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
            if (mv <= 0) {
                long uv = firstNonZero(new String[]{VOLTAGE_PATHS[0], VOLTAGE_PATHS[1], VOLTAGE_PATHS[2]});
                if (uv > 0) mv = (int) (uv / 1000L);
            }

            long rawCurrent = Long.MIN_VALUE;
            if (batteryManager != null)
                rawCurrent = TelemetryMath.batteryCurrentMicroamps(
                        batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
                        android.os.Build.MANUFACTURER, android.os.Build.MODEL);
            if (!TelemetryMath.validCurrent(rawCurrent) && batteryManager != null)
                rawCurrent = TelemetryMath.batteryCurrentMicroamps(
                        batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE),
                        android.os.Build.MANUFACTURER, android.os.Build.MODEL);
            if (!TelemetryMath.validCurrent(rawCurrent)) {
                // Standard power_supply current_now is µA. Vendor ibat_now has
                // unspecified units, so it is deliberately not used here.
                rawCurrent = firstNonZero(new String[]{CURRENT_PATHS[0], CURRENT_PATHS[1], CURRENT_PATHS[2]});
                if (rawCurrent == 0) rawCurrent = Long.MIN_VALUE;
                else rawCurrent = charging ? Math.abs(rawCurrent) : -Math.abs(rawCurrent);
            }
            if (!TelemetryMath.validCurrent(rawCurrent)
                    && "samsung".equalsIgnoreCase(android.os.Build.MANUFACTURER)) {
                // Samsung broadcasts these vendor extras in mA, not the µA of
                // BatteryManager/current_now. Never guess units of arbitrary sysfs nodes.
                for (String key : new String[]{"current_now", "current_avg"}) {
                    int milliamps = batt.getIntExtra(key, Integer.MIN_VALUE);
                    if (milliamps != Integer.MIN_VALUE && TelemetryMath.validCurrent((long)milliamps * 1000)) {
                        rawCurrent = (long)milliamps * 1000; break;
                    }
                }
            }
            if (!TelemetryMath.validCurrent(rawCurrent)) rawCurrent = Long.MIN_VALUE;
            batteryCurrentMicroamps = rawCurrent;
            double watts = TelemetryMath.watts(rawCurrent, mv);
            batteryMw.set(Double.isNaN(watts) ? -1 : (int)Math.round(watts * 1000));
            long charge = batteryManager == null ? Long.MIN_VALUE
                    : batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
            batteryAvailableMah = TelemetryMath.chargeMah(charge);
            if (charge == 0 && pct != 0) batteryAvailableMah = Double.NaN;
            if (Double.isNaN(batteryAvailableMah)) {
                long full = firstNonZero(new String[]{"/sys/class/power_supply/battery/charge_full",
                        "/sys/class/power_supply/battery/charge_full_design", "/sys/class/power_supply/bms/charge_full"});
                double fullMah = TelemetryMath.chargeMah(full);
                if (full > 0 && pct >= 0 && !Double.isNaN(fullMah)) {
                    batteryAvailableMah = fullMah * pct / 100d;
                    batteryChargeEstimated = true;
                }
            }
        } catch (Exception ignored) {
        } finally {
            battery = new GameplayMeasurement.Battery(System.nanoTime(),
                    batteryMw.get() >= 0 ? batteryMw.get() / 1000d : Double.NaN,
                    batteryPct.get() >= 0 ? batteryPct.get() : Double.NaN,
                    batteryAvailableMah, charging, batteryChargeEstimated);
        }
    }

    private void pollRam() {
        com.winlator.cmod.core.SystemMemoryUsage reading = com.winlator.cmod.core.SystemMemoryUsage.read(context);
        memory = reading;
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.FileReader("/proc/meminfo"))) {
            long swapTotal = -1, swapFree = -1;
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("SwapTotal:")) swapTotal = parseMeminfoKb(line);
                else if (line.startsWith("SwapFree:")) swapFree = parseMeminfoKb(line);
            }
            swapTotalBytes = swapTotal >= 0 ? swapTotal * 1024 : -1;
            swapFreeBytes = swapFree >= 0 && swapFree <= swapTotal ? swapFree * 1024 : -1;

        } catch (Exception e) {
            // A denied swap read must not erase the independent Android RAM reading.
            swapTotalBytes = swapFreeBytes = -1;
        }
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
        return -1;
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
