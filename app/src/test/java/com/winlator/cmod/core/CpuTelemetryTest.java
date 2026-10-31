package com.winlator.cmod.core;

import java.io.File;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class CpuTelemetryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private void write(File file, String text) throws Exception {
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test public void readsSparseTopologyMhzAndActualMidr() throws Exception {
        File root = temporary.newFolder("cpu"), stat = temporary.newFile("stat");
        write(new File(root, "cpu0/cpufreq/scaling_cur_freq"), "1500000");
        write(new File(root, "cpu3/cpufreq/scaling_cur_freq"), "3200000");
        write(new File(root, "cpu3/regs/identification/midr_el1"), "0x410fd820");
        write(stat, "cpu 10 0 0 90\ncpu0 10 0 0 90\ncpu3 0 0 0 100");
        CpuTelemetry source = new CpuTelemetry(root, stat);
        CpuTelemetry.Sample first = source.poll();
        assertEquals(2, first.cores.length);
        assertEquals(-1, first.usage);
        assertEquals(3, first.cores[1].id);
        assertEquals(3200, first.cores[1].mhz);
        assertEquals("Cortex-X4", first.cores[1].name);
        assertEquals("", first.cores[0].name);
        write(stat, "cpu 60 0 0 140\ncpu0 30 0 0 170\ncpu3 80 0 0 120");
        CpuTelemetry.Sample next = source.poll();
        assertEquals(50, next.usage);
        assertEquals(20, next.cores[0].usage);
        assertEquals(80, next.cores[1].usage);
    }
    @Test public void missingPermissionsDoNotBecomeZeroUsageOrClock() throws Exception {
        File root = temporary.newFolder("cpu"), stat = new File(root, "unreadable");
        new File(root, "cpu0").mkdir();
        var sample = new CpuTelemetry(root, stat).poll();
        assertEquals(-1, sample.usage);
        assertEquals(-1, sample.cores[0].mhz);
        assertEquals(-1, sample.cores[0].usage);
    }
}
