package com.winlator.cmod.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class HardwareLabelTest {
    @Test public void s24FeModelKeepsTheESuffixInBothFormats() {
        String soc = ExynosDevices.find("SM-S721B").soc;
        assertEquals("Exynos 2400e", HardwareLabel.format(soc, HardwareLabel.FULL));
        assertEquals("2400e", HardwareLabel.format(soc, HardwareLabel.MODEL));
    }
    @Test public void brandModeDoesNotKeepTheModelNumber() {
        assertEquals("Exynos", HardwareLabel.format("Exynos 2500", HardwareLabel.BRAND));
        assertEquals("Xclipse", HardwareLabel.format("Samsung Xclipse 940", HardwareLabel.BRAND));
    }
    @Test public void gpuModelModeDoesNotKeepTheBrand() {
        assertEquals("940", HardwareLabel.format("Samsung Xclipse 940", HardwareLabel.MODEL));
    }
    @Test public void changingTheFormatDoesNotDestroyTheOriginalValue() {
        String soc = "Exynos 2400e";
        HardwareLabel.format(soc, HardwareLabel.BRAND);
        assertEquals(soc, HardwareLabel.format(soc, HardwareLabel.FULL));
    }
    @Test public void unknownHardwareIsNotInventedOrBlanked() {
        assertEquals("Unknown GPU", HardwareLabel.format("Unknown GPU", HardwareLabel.MODEL));
        assertEquals("", HardwareLabel.format(null, HardwareLabel.BRAND));
    }
    @Test public void familyAndModelLabelsAreDistinct() {
        assertEquals("Galaxy S", HardwareLabel.family("Galaxy S24 FE"));
        assertEquals("Galaxy A", HardwareLabel.family("Galaxy A55 5G"));
        assertEquals("Galaxy Tab", HardwareLabel.family("Galaxy Tab S10 FE"));
    }
}
