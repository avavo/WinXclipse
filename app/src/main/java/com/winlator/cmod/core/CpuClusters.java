package com.winlator.cmod.core;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

public abstract class CpuClusters {
    private static volatile String cachedPerformanceCPUList;
    private static volatile boolean performancePinningEnabled = true;

    /** Session-level override from the Experimental Performance tuning
     * dialog: when disabled, 64-bit guests may use every core instead of
     * the performance-cluster default. */
    public static void setPerformancePinningEnabled(boolean enabled) {
        performancePinningEnabled = enabled;
    }

    public static String getPerformanceCPUList() {
        String result = cachedPerformanceCPUList;
        if (result == null) {
            result = resolvePerformanceCPUList();
            cachedPerformanceCPUList = result;
        }
        return performancePinningEnabled ? result : allCPUList(Runtime.getRuntime().availableProcessors());
    }

    private static String resolvePerformanceCPUList() {
        int numProcessors = Runtime.getRuntime().availableProcessors();
        if (numProcessors <= 1) return "0";

        // The Exynos 2600 has no LITTLE cluster at all: its deca layout is one
        // prime plus nine performance mids, so excluding the slowest cluster
        // would only throw away real throughput for 32-bit guests.
        if (!GPUInformation.hasLittleCores()) return allCPUList(numProcessors);

        Map<Long, List<Integer>> clustersByFreq = new LinkedHashMap<>();
        for (int cpu = 0; cpu < numProcessors; cpu++) {
            long maxFreq = readMaxFrequencyKHz(cpu);
            if (maxFreq < 0) return fallbackCPUList(numProcessors);
            // cpuinfo_max_freq is in kHz; bucket by ~100 MHz steps so cores of the
            // same cluster group together while LITTLE/performance clusters differ.
            long bucket = Math.round(maxFreq / 100000.0);
            List<Integer> cluster = clustersByFreq.get(bucket);
            if (cluster == null) {
                cluster = new ArrayList<>();
                clustersByFreq.put(bucket, cluster);
            }
            cluster.add(cpu);
        }

        if (clustersByFreq.size() <= 1) return allCPUList(numProcessors);

        Long slowestCluster = Collections.min(clustersByFreq.keySet());
        StringBuilder cpuList = new StringBuilder();
        for (Map.Entry<Long, List<Integer>> cluster : clustersByFreq.entrySet()) {
            if (cluster.getKey().equals(slowestCluster)) continue;
            for (int cpu : cluster.getValue()) appendCore(cpuList, cpu);
        }
        return cpuList.length() > 0 ? cpuList.toString() : allCPUList(numProcessors);
    }

    private static long readMaxFrequencyKHz(int cpu) {
        File file = new File("/sys/devices/system/cpu/cpu" + cpu + "/cpufreq/cpuinfo_max_freq");
        try (Scanner scanner = new Scanner(new FileInputStream(file))) {
            if (!scanner.hasNextLong()) return -1;
            return scanner.nextLong();
        }
        catch (Exception e) {
            return -1;
        }
    }

    private static String fallbackCPUList(int numProcessors) {
        StringBuilder cpuList = new StringBuilder();
        for (int i = numProcessors / 2; i < numProcessors; i++) appendCore(cpuList, i);
        return cpuList.toString();
    }

    private static String allCPUList(int numProcessors) {
        StringBuilder cpuList = new StringBuilder();
        for (int i = 0; i < numProcessors; i++) appendCore(cpuList, i);
        return cpuList.toString();
    }

    private static void appendCore(StringBuilder cpuList, int cpu) {
        if (cpuList.length() > 0) cpuList.append(',');
        cpuList.append(cpu);
    }
}
