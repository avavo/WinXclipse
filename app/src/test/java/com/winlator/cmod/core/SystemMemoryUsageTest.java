package com.winlator.cmod.core;

import org.junit.Test;
import java.util.Locale;
import static org.junit.Assert.*;

public class SystemMemoryUsageTest {
    @Test public void screenshotValuesUseOneUnitAndOneRoundingRule() {
        SystemMemoryUsage sample = new SystemMemoryUsage(11_747_356_672L, 4_424_437_760L);
        assertEquals(sample.total, sample.used + sample.available);
        assertEquals(62, sample.usedPercent);
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            assertEquals("6.82 GiB", TelemetryMath.ram(sample.total, sample.available, 2));
            assertEquals("6.82 / 10.94 GiB", TelemetryMath.ramPair(sample.total, sample.available));
            assertEquals(sample.usedPercent + "%", TelemetryMath.ram(sample.total, sample.available, 0));
            assertEquals("4.12 GiB", TelemetryMath.ram(sample.total, sample.available, 3));
        } finally { Locale.setDefault(previous); }
    }

    @Test public void unavailableMemoryNeverLooksLikeZeroPercentUsage() {
        for (SystemMemoryUsage sample : new SystemMemoryUsage[]{SystemMemoryUsage.UNKNOWN,
                new SystemMemoryUsage(0, 0), new SystemMemoryUsage(8, 9), new SystemMemoryUsage(8, -1)}) {
            assertEquals(-1, sample.usedPercent);
            assertEquals(-1, sample.used);
            assertEquals("N/A", TelemetryMath.ramPair(sample.total, sample.available));
        }
        assertEquals(0, new SystemMemoryUsage(8, 8).usedPercent);
        assertEquals(100, new SystemMemoryUsage(8, 0).usedPercent);
    }
}
