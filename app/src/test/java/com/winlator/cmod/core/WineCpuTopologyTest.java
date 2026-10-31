package com.winlator.cmod.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class WineCpuTopologyTest {
    @Test public void mapsSparseSelectedCoresAndDeduplicatesRanges() {
        assertEquals("4:0,4,5,7", WineCpuTopology.fromCpuList("7,4-5,0,4"));
    }

    @Test public void disabledSyncPreservesExplicitEnvironmentOverride() {
        assertEquals("8:0,1,2,3,4,5,6,7", WineCpuTopology.resolve(false, "4,5", "8:0,1,2,3,4,5,6,7"));
        assertEquals("2:4,5", WineCpuTopology.resolve(true, "4,5", "8:0,1,2,3,4,5,6,7"));
    }

    @Test public void emptySelectionDoesNotAdvertiseZeroCpus() {
        assertEquals("", WineCpuTopology.fromCpuList(""));
        assertEquals("", WineCpuTopology.fromCpuList(null));
    }

    @Test public void rejectsWholeInvalidListInsteadOfPartiallyChangingTopology() {
        for (String invalid : new String[]{"4,", "4,x", "-1", "2-0", "1024", "0-9999", "0,,1", "1;PATH=x"}) {
            try { WineCpuTopology.fromCpuList(invalid); fail(invalid); }
            catch (IllegalArgumentException expected) { }
        }
    }
}
