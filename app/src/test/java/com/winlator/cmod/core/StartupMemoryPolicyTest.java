package com.winlator.cmod.core;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.winlator.cmod.core.StartupMemoryPolicy.*;

public class StartupMemoryPolicyTest {
    @Test public void neverPressuresAnActiveWineOrLowMemorySystem() {
        assertEquals(0, budget(8*1024*MIB, 4*1024*MIB, false, true));
        assertEquals(0, budget(8*1024*MIB, 4*1024*MIB, true, false));
        assertEquals(0, budget(8*1024*MIB, 900*MIB, false, false));
        assertEquals(0, budget(0, 4*1024*MIB, false, false));
        assertEquals(0, budget(8*1024*MIB, 9*1024*MIB, false, false));
    }

    @Test public void capsAllocationAndPreservesLargeReserve() {
        assertEquals(256*MIB, budget(16*1024*MIB, 8*1024*MIB, false, false));
        assertEquals(64*MIB, budget(8*1024*MIB, 1280*MIB, false, false));
        assertEquals(2*1024*MIB, reserve(16*1024*MIB));
    }

    @Test public void skipsTinyPassesNearReserve() {
        assertEquals(0, budget(8*1024*MIB, 1050*MIB, false, false));
    }
}
