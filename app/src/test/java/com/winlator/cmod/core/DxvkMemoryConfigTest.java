package com.winlator.cmod.core;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DxvkMemoryConfigTest {
    private static Map<String, Integer> options(long total, int cap, int pool, boolean rage) {
        return options(total, cap, pool, true, rage);
    }

    private static Map<String, Integer> options(long total, int cap, int pool,
                                                boolean ramFixOn, boolean rage) {
        Map<String, Integer> result = new HashMap<>();
        for (String entry : DxvkMemoryConfig.build(total, cap, pool, ramFixOn, rage).split(";")) {
            String[] assignment = entry.trim().split("\\s*=\\s*");
            result.put(assignment[0], Integer.parseInt(assignment[1]));
        }
        return result;
    }

    private static void assertBudget(Map<String, Integer> config, int device, int shared, int d3d9) {
        assertEquals(Integer.valueOf(device), config.get("dxgi.maxDeviceMemory"));
        assertEquals(Integer.valueOf(shared), config.get("dxgi.maxSharedMemory"));
        assertEquals(Integer.valueOf(d3d9), config.get("d3d9.maxAvailableMemory"));
    }

    @Test public void ramFixAppliesToOrdinaryGamesAcrossRamTiers() {
        assertBudget(options(5500, 0, 0, false), 1024, 1024, 2048);
        assertBudget(options(6144, 0, 0, false), 1024, 1024, 2048);
        assertBudget(options(7500, 0, 0, false), 1536, 1024, 2048);
        assertBudget(options(11500, 0, 0, false), 1536, 1024, 2048);
    }

    @Test public void disabledRamFixUsesReferenceBudgets() {
        assertBudget(options(5500, 0, 0, false, false), 2048, 2048, 4096);
        assertBudget(options(7500, 0, 0, false, false), 3072, 2048, 4096);
    }

    @Test public void disabledRamFixIgnoresItsPoolAndRageOverride() {
        assertBudget(options(7500, 0, 512, false, true), 3072, 2048, 4096);
        assertBudget(options(7500, 2048, 512, false, true), 2048, 2048, 2048);
    }

    @Test public void unknownRamUsesTheSmallerRamFixTier() {
        assertBudget(options(0, 0, 0, false), 1024, 1024, 2048);
    }

    @Test public void rageSmallPoolDoesNotBecomeTheGlobalDefault() {
        assertBudget(options(7500, 0, 0, true), 512, 512, 512);
        assertBudget(options(7500, 0, 0, false), 1536, 1024, 2048);
    }

    @Test public void explicitPoolOverridesRageAutoAndRamTier() {
        assertBudget(options(7500, 0, 2048, true), 2048, 2048, 2048);
        assertBudget(options(5500, 0, 1536, false), 1536, 1536, 1536);
    }

    @Test public void reportedPoolNeverExceedsTheSelectedHeapCap() {
        for (boolean rage : new boolean[]{false, true}) {
            for (int pool : new int[]{0, 512, 1024, 2048, 4096, 12288}) {
                for (int cap : new int[]{256, 512, 1024, 2048, 4096}) {
                    for (int value : options(7500, cap, pool, rage).values()) {
                        assertTrue(value > 0 && value <= cap);
                    }
                }
            }
        }
    }

    @Test public void explicitWrapperCapIsKeptWithAutomaticPool() {
        assertBudget(options(7500, 2048, 0, false), 2048, 2048, 2048);
        assertBudget(options(7500, 6144, 0, false), 6144, 6144, 4096);
    }

    @Test public void stricterExplicitHeapLimitWins() {
        assertEquals(2048, DxvkMemoryConfig.effectiveCapMb(4096, 2048));
        assertEquals(2048, DxvkMemoryConfig.effectiveCapMb(2048, 4096));
        assertEquals(4096, DxvkMemoryConfig.effectiveCapMb(4096, 0));
        assertEquals(2048, DxvkMemoryConfig.effectiveCapMb(0, 2048));
        assertEquals(0, DxvkMemoryConfig.effectiveCapMb(0, 0));
        assertEquals(0, DxvkMemoryConfig.effectiveCapMb(-1, -1));
    }

    @Test public void reportedRagePoolDoesNotInventAHardHeapLimit() {
        assertBudget(options(7500, 0, 0, true), 512, 512, 512);
        assertEquals(0, DxvkMemoryConfig.effectiveCapMb(0, 0));
    }

    @Test public void invalidPoolValuesAreBounded() {
        assertEquals(0, DxvkMemoryConfig.normalizePoolMb(-1));
        assertEquals(0, DxvkMemoryConfig.normalizePoolMb(0));
        assertBudget(options(7500, 0, 1, false), 256, 256, 256);
        assertBudget(options(7500, 0, Integer.MAX_VALUE, false), 12288, 12288, 12288);
    }

    @Test public void generatedKeysAreSupportedByBundledDxvk() {
        assertEquals(Set.of("dxgi.maxDeviceMemory", "dxgi.maxSharedMemory",
                        "d3d9.maxAvailableMemory"),
                options(7500, 0, 0, false).keySet());
    }
}
