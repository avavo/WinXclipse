package com.winlator.cmod.core;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;
import java.util.Collections;
import java.util.TreeSet;

/** Background-reader for the actual online/possible topology; no guessed Cortex names. */
public final class CpuTelemetry {
    public static final class Core {
        public final int id, mhz, maxMhz, usage;
        public final String name;
        Core(int id, int mhz, int maxMhz, int usage, String name) {
            this.id = id; this.mhz = mhz; this.maxMhz = maxMhz; this.usage = usage; this.name = name;
        }
        public String label(boolean named) { return "CPU " + id + (named && !name.isEmpty() ? " " + name : ""); }
    }
    public static final class Sample {
        public final Core[] cores;
        public final int usage;
        Sample(Core[] cores, int usage) { this.cores = cores; this.usage = usage; }
    }
    public static final Sample EMPTY = new Sample(new Core[0], -1);
    private Map<Integer, TelemetryMath.CpuTimes> previous = Collections.emptyMap();
    private final File root;
    private final File stat;
    private final File cpuinfo;
    private final Map<Integer, String> names = new java.util.HashMap<>();

    public CpuTelemetry() { this(new File("/sys/devices/system/cpu"), new File("/proc/stat"), new File("/proc/cpuinfo")); }
    public CpuTelemetry(File root, File stat) { this(root, stat, new File(root, "cpuinfo")); }
    public CpuTelemetry(File root, File stat, File cpuinfo) { this.root = root; this.stat = stat; this.cpuinfo = cpuinfo; }

    public Sample poll() {
        Map<Integer, TelemetryMath.CpuTimes> current = TelemetryMath.cpuTimes(read(stat));
        TreeSet<Integer> ids = new TreeSet<>();
        File[] dirs = root.listFiles();
        if (dirs != null) for (File dir : dirs) if (dir.getName().matches("cpu[0-9]+")) {
            try { ids.add(Integer.parseInt(dir.getName().substring(3))); } catch (NumberFormatException ignored) {}
        }
        for (int id : current.keySet()) if (id >= 0) ids.add(id);
        if (ids.isEmpty()) for (int i = 0; i < Runtime.getRuntime().availableProcessors(); i++) ids.add(i);
        Core[] cores = new Core[ids.size()];
        Map<Integer, String> procNames = null;
        int index = 0;
        for (int id : ids) {
            File cpu = new File(root, "cpu" + id);
            int frequency = frequency(new File(cpu, "cpufreq/scaling_cur_freq"));
            if (frequency < 0) frequency = frequency(new File(cpu, "cpufreq/cpuinfo_cur_freq"));
            if (!names.containsKey(id) || names.get(id).isEmpty()) {
                String name = "";
                try { name = TelemetryMath.cortexName(Long.decode(read(new File(cpu, "regs/identification/midr_el1")).trim())); }
                catch (RuntimeException ignored) {}
                if (name.isEmpty()) {
                    if (procNames == null) procNames = TelemetryMath.cpuNames(read(cpuinfo));
                    name = procNames.getOrDefault(id, "");
                }
                names.put(id, name);
            }
            cores[index++] = new Core(id, frequency, frequency(new File(cpu, "cpufreq/cpuinfo_max_freq")),
                    TelemetryMath.cpuUsage(previous.get(id), current.get(id)), names.get(id));
        }
        int usage = TelemetryMath.cpuUsage(previous.get(-1), current.get(-1));
        previous = current;
        return new Sample(cores, usage);
    }
    private static int frequency(File file) {
        try { long khz = Long.parseLong(read(file).trim()); return khz > 0 && khz <= 20_000_000 ? (int)(khz / 1000) : -1; }
        catch (RuntimeException ignored) { return -1; }
    }
    private static String read(File file) {
        try { return new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8); }
        catch (Exception ignored) { return ""; }
    }
}
