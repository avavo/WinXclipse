package com.winlator.cmod.core;
import org.junit.Test;
import static org.junit.Assert.*;
public class DriverDefaultsTest {
    @Test public void apiDefaultFollowsReportedVersionInsteadOfMarketingName() {
        assertEquals("1.3",DriverDefaults.vulkanVersion(0));
        assertEquals("1.3",DriverDefaults.vulkanVersion((1<<22)|(3<<12)|290));
        assertEquals("1.4",DriverDefaults.vulkanVersion((1<<22)|(4<<12)|1));
    }
    @Test public void nativeBcnNamesAreCaseInsensitiveAndOnlyMatchRequestedFamilies() {
        for(String name:new String[]{"QUASAR 67","Mesa RADV","phoenix-1"})assertTrue(DriverDefaults.nativeBcn(name));
        assertFalse(DriverDefaults.nativeBcn("Turnip 26"));assertFalse(DriverDefaults.nativeBcn(null));
    }
}
