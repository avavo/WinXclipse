package com.winlator.cmod.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class DeviceHudReadingsTest {
    @Test public void samsungS24PlusMilliampReadingProducesWattsInsteadOfRoundedZero() {
        long current = TelemetryMath.batteryCurrentMicroamps(-832, "samsung", "SM-S926B");
        assertEquals(-832_000, current);
        assertEquals(3.133312, TelemetryMath.watts(current, 3766), .000001);
        assertEquals(-832_000, TelemetryMath.batteryCurrentMicroamps(-832_000, "samsung", "SM-S926B"));
        assertEquals(-832, TelemetryMath.batteryCurrentMicroamps(-832, "other", "device"));
        assertEquals(Long.MIN_VALUE, TelemetryMath.batteryCurrentMicroamps(Long.MIN_VALUE, "samsung", "SM-S926B"));
        assertFalse(TelemetryMath.validCurrent(0));
        assertFalse(TelemetryMath.validCurrent(Integer.MIN_VALUE));
    }
    @Test public void samsungReadableGpuAndApSensorsKeepCorrectScales() {
        assertEquals(53, TelemetryMath.temperatureC("53.0  \n"));
        assertEquals(52, TelemetryMath.temperatureC("524"));
        assertEquals(47, TelemetryMath.temperatureC("47200"));
        assertEquals(-1, TelemetryMath.temperatureC("Permission denied"));
        assertEquals(-1, TelemetryMath.temperatureC("NaN"));
        assertEquals(-1, TelemetryMath.temperatureC("0"));
    }
    @Test public void swapIsUsedOverTotalAndMissingIsNotReportedAsZero() {
        java.util.Locale old = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.US);
            assertEquals("Swap 3.09 / 17.18 GB", TelemetryMath.swap(16_777_212L*1024, 13_760_984L*1024));
            assertEquals("Swap 0.00 / 0.00 GB", TelemetryMath.swap(0, 0));
            assertEquals("Swap N/A", TelemetryMath.swap(-1, -1));
            assertEquals("Swap N/A", TelemetryMath.swap(1, 2));
        } finally { java.util.Locale.setDefault(old); }
    }
}
