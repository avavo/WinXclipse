package com.winlator.cmod.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class TelemetryMathTest {
    @Test public void usesCounterDeltasNotFrequencyAndDoesNotCountGuestTwice() {
        var first = TelemetryMath.cpuTimes("cpu 10 0 10 80 0 0 0 0 4 0\ncpu0 10 0 10 80 0 0 0 0 4 0");
        var next = TelemetryMath.cpuTimes("cpu 20 0 20 160 0 0 0 0 14 0\ncpu0 20 0 20 160 0 0 0 0 14 0");
        assertEquals(20, TelemetryMath.cpuUsage(first.get(-1), next.get(-1)));
        assertEquals(20, TelemetryMath.cpuUsage(first.get(0), next.get(0)));
    }
    @Test public void rejectsResetMissingAndUnchangedCounters() {
        var a = TelemetryMath.cpuTimes("cpu0 10 2 5 80 3").get(0);
        assertEquals(-1, TelemetryMath.cpuUsage(null, a));
        assertEquals(-1, TelemetryMath.cpuUsage(a, a));
        assertEquals(-1, TelemetryMath.cpuUsage(a, TelemetryMath.cpuTimes("cpu0 11 2 6 90 2").get(0)));
        assertTrue(TelemetryMath.cpuTimes("cpu0 3 -1 2 4\ncpuB nonsense").isEmpty());
    }
    @Test public void parsesOnlyActualPercentNotConcatenatedOrNegativeNumbers() {
        assertEquals(45, TelemetryMath.percent("45 %"));
        assertEquals(46, TelemetryMath.percent("45.6"));
        assertEquals(-1, TelemetryMath.percent("-5"));
        assertEquals(-1, TelemetryMath.percent("load 4 busy 5"));
        assertEquals(-1, TelemetryMath.percent("101"));
    }
    @Test public void batteryUsesDocumentedUnitsEvenAtSmallCurrents() {
        assertEquals(.04, TelemetryMath.watts(-10_000, 4000), .000001);
        assertEquals(4.8, TelemetryMath.watts(1_200_000, 4000), .000001);
        assertEquals(0, TelemetryMath.watts(0, 4000), 0);
        assertTrue(Double.isNaN(TelemetryMath.watts(Long.MIN_VALUE, 4000)));
        assertTrue(Double.isNaN(TelemetryMath.watts(Integer.MIN_VALUE, 4000)));
        assertEquals(3750, TelemetryMath.chargeMah(3_750_000), 0);
        assertTrue(Double.isNaN(TelemetryMath.chargeMah(Long.MIN_VALUE)));
    }
    @Test public void normalizesBatteryScaleAndRejectsMissingScale() {
        assertEquals(50, TelemetryMath.batteryPercent(125, 250));
        assertEquals(-1, TelemetryMath.batteryPercent(125, 100));
        assertEquals(-1, TelemetryMath.batteryPercent(50, -1));
    }
    @Test public void ramFormatsFreeAndUsedFromSameMeasuredBytes() {
        assertEquals("75%", TelemetryMath.ram(8_000_000_000L, 2_000_000_000L, 0));
        assertEquals("25%", TelemetryMath.ram(8_000_000_000L, 2_000_000_000L, 1));
        assertTrue(TelemetryMath.ram(8_000_000_000L, 2_000_000_000L, 2).matches("6[.,]0 GB"));
        assertTrue(TelemetryMath.ram(8_000_000_000L, 2_000_000_000L, 3).matches("2[.,]0 GB"));
        assertEquals("N/A", TelemetryMath.ram(8, 9, 0));
    }
    @Test public void cortexNameRequiresActualArmImplementerAndKnownPart() {
        assertEquals("Cortex-X4", TelemetryMath.cortexName(0x410fd820L));
        assertEquals("Cortex-A75", TelemetryMath.cortexName(0x410fd0a0L));
        assertEquals("", TelemetryMath.cortexName(0x530fd820L));
        assertEquals("", TelemetryMath.cortexName(0x410ffff0L));
    }
    @Test public void cpuinfoDoesNotSpreadOneCoreNameToOtherCores() {
        var names = TelemetryMath.cpuNames("processor : 0\nCPU implementer : 0x41\nCPU part : 0xd05\n\n"
                + "processor : 4\nCPU implementer : 0x41\nCPU part : 0xd82\n\nprocessor : 5\nCPU part : 0xd82");
        assertEquals("Cortex-A55", names.get(0));
        assertEquals("Cortex-X4", names.get(4));
        assertFalse(names.containsKey(5));
    }
}
