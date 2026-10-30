package com.winlator.cmod.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class RefreshRatePolicyTest {
    private final RefreshRatePolicy.Mode base = new RefreshRatePolicy.Mode(1, 1440, 3120, 60);
    private final RefreshRatePolicy.Mode native120 = new RefreshRatePolicy.Mode(2, 1440, 3120, 120);
    private final RefreshRatePolicy.Mode lower144 = new RefreshRatePolicy.Mode(3, 1080, 2340, 144);
    private final RefreshRatePolicy.Mode[] modes = {base, native120, lower144};

    @Test public void autoRequests120WithoutChangingResolution() {
        assertSame(native120, RefreshRatePolicy.choose(base, modes, "auto"));
    }
    @Test public void explicit60Remains60() {
        assertSame(base, RefreshRatePolicy.choose(base, modes, "60"));
    }
    @Test public void unsupportedTargetUsesClosestRealMode() {
        assertSame(native120, RefreshRatePolicy.choose(base, modes, "144"));
    }
    @Test public void treatsFractionalRatesAsTheSameRefreshFamily() {
        RefreshRatePolicy.Mode fractional = new RefreshRatePolicy.Mode(4, 1440, 3120, 119.88f);
        assertSame(fractional, RefreshRatePolicy.choose(base, new RefreshRatePolicy.Mode[]{base, fractional}, "120"));
    }
    @Test public void invalidSettingsUseAuto() {
        for (String value : new String[]{"", "NaN", "Infinity", "-1", "broken"})
            assertSame(native120, RefreshRatePolicy.choose(base, modes, value));
    }
    @Test public void missingOrUnrelatedModesKeepTheCurrentMode() {
        assertSame(base, RefreshRatePolicy.choose(base, new RefreshRatePolicy.Mode[]{lower144}, "auto"));
        assertSame(base, RefreshRatePolicy.choose(base, new RefreshRatePolicy.Mode[0], "120"));
    }
}
