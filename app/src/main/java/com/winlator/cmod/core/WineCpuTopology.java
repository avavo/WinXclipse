package com.winlator.cmod.core;

import java.util.TreeSet;

/** Wine's logical CPU count and host CPU mapping, derived from the selected affinity. */
public final class WineCpuTopology {
    private WineCpuTopology() {}

    public static String fromCpuList(String cpuList) {
        if (cpuList == null || cpuList.trim().isEmpty()) return "";
        if (cpuList.length() > 8192) throw new IllegalArgumentException("CPU list too long");
        TreeSet<Integer> cores = new TreeSet<>();
        for (String value : cpuList.split(",", -1)) {
            String token = value.trim();
            if (!token.matches("[0-9]{1,4}(-[0-9]{1,4})?"))
                throw new IllegalArgumentException("Invalid CPU list");
            String[] range = token.split("-");
            int first = Integer.parseInt(range[0]);
            int last = range.length == 1 ? first : Integer.parseInt(range[1]);
            if (first > last || last > 1023) throw new IllegalArgumentException("Invalid CPU range");
            for (int core = first; core <= last; core++) cores.add(core);
        }
        StringBuilder result = new StringBuilder().append(cores.size()).append(':');
        for (int core : cores) {
            if (result.charAt(result.length() - 1) != ':') result.append(',');
            result.append(core);
        }
        return result.toString();
    }

    public static String resolve(boolean enabled, String cpuList, String explicitValue) {
        return enabled ? fromCpuList(cpuList) : explicitValue;
    }
}
