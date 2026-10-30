package com.winlator.cmod.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class StorageUsageTest {
    @Test public void usedAndAvailableAccountForTheWholeVolume() {
        StorageUsage usage = new StorageUsage(256_000_000_000L, 64_000_000_000L);
        assertEquals(192_000_000_000L, usage.used);
        assertEquals(75.0, usage.usedPercent(), 0.00001);
        assertEquals(25.0, usage.availablePercent(), 0.00001);
        assertEquals(750, usage.progress());
    }
    @Test public void missingVolumeHasNoInvalidDivision() {
        StorageUsage usage = new StorageUsage(0, 0);
        assertEquals(0.0, usage.usedPercent(), 0.0);
        assertEquals(0, usage.progress());
    }
    @Test public void inconsistentSensorValuesStayWithinBounds() {
        assertEquals(100L, new StorageUsage(100, 500).available);
        assertEquals(100L, new StorageUsage(100, -1).used);
        assertEquals(0L, new StorageUsage(-5, 10).total);
    }
    @Test public void gigabytesUseDecimalStorageUnits() {
        assertEquals(1.5, StorageUsage.gigabytes(1_500_000_000L), 0.0);
    }
}
