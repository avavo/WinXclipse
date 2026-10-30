package com.winlator.cmod.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class VulkanCapabilitiesTest {
    @Test public void readsTheDeviceApiVersionIncludingPatchAndVariantBits() {
        assertTrue(VulkanCapabilities.supports14((1 << 22) | (4 << 12) | 300));
        assertTrue(VulkanCapabilities.supports14((1 << 29) | (1 << 22) | (4 << 12)));
        assertFalse(VulkanCapabilities.supports14((1 << 22) | (3 << 12) | 4095));
        assertFalse(VulkanCapabilities.supports14(0));
    }
    @Test public void failedDriverQueryPreservesBlacklist() {
        assertEquals("VK_EXT_foo,VK_EXT_bar", VulkanCapabilities.mergeBlacklist("VK_EXT_foo,VK_EXT_bar", null, ""));
    }
    @Test public void enablingAnExtensionOnlyRemovesThatExtensionFromBlacklist() {
        assertEquals("VK_WRAPPER_extra,VK_EXT_new", VulkanCapabilities.mergeBlacklist("VK_EXT_old,VK_WRAPPER_extra",
                new String[]{"VK_EXT_old", "VK_EXT_new"}, "VK_EXT_new"));
    }
    @Test public void blacklistDoesNotAccumulateEmptyOrDuplicateEntries() {
        assertEquals("VK_EXT_a", VulkanCapabilities.mergeBlacklist("VK_EXT_a,, VK_EXT_a", new String[0], ""));
    }
    @Test public void invalidImportedApiVersionIsUnknown() {
        assertEquals(0, VulkanCapabilities.parseVersion("1.4"));
        assertEquals(0, VulkanCapabilities.parseVersion(null));
    }
}
