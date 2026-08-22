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

    public static String getPerformanceCPUList() {
        String result = cachedPerformanceCPUList;
        if (result == null) {
            result = resolvePerformanceCPUList();
            cachedPerformanceCPUList = result;
        }
        return result;
    }

    private static String resolvePerformanceCPUList() {
        int numProcessors = Runtime.getRuntime().availableProcessors();
        if (numProcessors <= 1) return "0";

        Map<Long, List<Integer>> clustersByFreq = new LinkedHashMap<>();
        for (int cpu = 0; cpu < numProcessors; cpu++) {
            long maxFreq = readMaxFrequencyKHz(cpu);
            if (maxFreq < 0) return fallbackCPUList(numProcessors);
            long bucket = Math.round(maxFreq / 100000000.0);
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
