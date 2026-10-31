package com.winlator.cmod.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;

/** Unit conversions and counter deltas. Missing measurements remain missing. */
public final class TelemetryMath {
    private TelemetryMath() {}

    public static final class CpuTimes {
        final long[] ticks;
        CpuTimes(long[] ticks) { this.ticks = ticks; }
    }

    public static Map<Integer, CpuTimes> cpuTimes(String stat) {
        Map<Integer, CpuTimes> result = new LinkedHashMap<>();
        for (String line : stat.split("\n")) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length < 5 || !fields[0].matches("cpu[0-9]*")) continue;
            try {
                int id = fields[0].equals("cpu") ? -1 : Integer.parseInt(fields[0].substring(3));
                long[] ticks = new long[8]; // guest counters are already included in user/nice.
                for (int i = 1; i < fields.length && i <= 8; i++) {
                    ticks[i - 1] = Long.parseLong(fields[i]);
                    if (ticks[i - 1] < 0) throw new NumberFormatException();
                }
                result.put(id, new CpuTimes(ticks));
            } catch (NumberFormatException ignored) {}
        }
        return result;
    }

    public static int cpuUsage(CpuTimes before, CpuTimes after) {
        if (before == null || after == null) return -1;
        long total = 0, idle = 0;
        for (int i = 0; i < 8; i++) {
            long delta = after.ticks[i] - before.ticks[i];
            if (delta < 0) return -1; // reset/hotplug or decreasing iowait, not a usable interval.
            total += delta;
            if (i == 3 || i == 4) idle += delta;
        }
        return total > 0 ? (int)Math.round((total - idle) * 100d / total) : -1;
    }

    public static int percent(String text) {
        try {
            String value = text.trim();
            if (!value.matches("[0-9]+(?:\\.[0-9]+)?\\s*%?")) return -1;
            double number = Double.parseDouble(value.replace("%", "").trim());
            return number >= 0 && number <= 100 ? (int)Math.round(number) : -1;
        } catch (RuntimeException ignored) { return -1; }
    }

    public static int batteryPercent(int level, int scale) {
        return level >= 0 && scale > 0 && level <= scale ? (int)Math.round(level * 100d / scale) : -1;
    }

    public static double watts(long currentMicroamps, int voltageMillivolts) {
        if (currentMicroamps == Long.MIN_VALUE || currentMicroamps == Integer.MIN_VALUE || voltageMillivolts <= 0)
            return Double.NaN;
        double result = Math.abs(currentMicroamps / 1_000_000d) * voltageMillivolts / 1000d;
        return result <= 200 ? result : Double.NaN;
    }

    public static double chargeMah(long microampHours) {
        return microampHours >= 0 && microampHours <= 100_000_000L ? microampHours / 1000d : Double.NaN;
    }

    public static String ram(long total, long available, int format) {
        if (total <= 0 || available < 0 || available > total) return "N/A";
        boolean free = format == 1 || format == 3;
        long bytes = free ? available : total - available;
        return format >= 2 ? String.format(Locale.getDefault(), "%.1f GB", bytes / 1_000_000_000d)
                : Math.round(bytes * 100d / total) + "%";
    }

    public static String cortexName(long midr) {
        if (((midr >>> 24) & 255) != 0x41) return "";
        switch ((int)((midr >>> 4) & 0xfff)) {
            case 0xd03: return "Cortex-A53";
            case 0xd04: return "Cortex-A35";
            case 0xd05: return "Cortex-A55";
            case 0xd07: return "Cortex-A57";
            case 0xd08: return "Cortex-A72";
            case 0xd09: return "Cortex-A73";
            case 0xd0a: return "Cortex-A75";
            case 0xd0b: return "Cortex-A76";
            case 0xd0c: return "Neoverse-N1";
            case 0xd0d: return "Cortex-A77";
            case 0xd41: return "Cortex-A78";
            case 0xd44: return "Cortex-X1";
            case 0xd46: return "Cortex-A510";
            case 0xd47: return "Cortex-A710";
            case 0xd48: return "Cortex-X2";
            case 0xd4d: return "Cortex-A715";
            case 0xd4e: return "Cortex-X3";
            case 0xd80: return "Cortex-A520";
            case 0xd81: return "Cortex-A720";
            case 0xd82: return "Cortex-X4";
            case 0xd85: return "Cortex-X925";
            case 0xd87: return "Cortex-A725";
            default: return "";
        }
    }

    public static Map<Integer, String> cpuNames(String cpuinfo) {
        Map<Integer, String> result = new LinkedHashMap<>();
        for (String block : cpuinfo.split("\\n\\s*\\n")) {
            int id = -1;
            long implementer = -1, part = -1;
            for (String line : block.split("\n")) {
                String[] fields = line.split(":", 2);
                if (fields.length != 2) continue;
                try {
                    String key = fields[0].trim(), value = fields[1].trim();
                    if (key.equals("processor")) id = Integer.parseInt(value);
                    else if (key.equals("CPU implementer")) implementer = Long.decode(value);
                    else if (key.equals("CPU part")) part = Long.decode(value);
                } catch (NumberFormatException ignored) {}
            }
            if (id >= 0 && implementer >= 0 && part >= 0)
                result.put(id, cortexName((implementer << 24) | (part << 4)));
        }
        return result;
    }
}
