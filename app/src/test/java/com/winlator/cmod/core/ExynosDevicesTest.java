package com.winlator.cmod.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class ExynosDevicesTest {
    @Test public void s26AcceptsOnlyBAndNVariants() {
        for (String code : new String[]{"942", "947", "741"}) {
            assertNotNull(ExynosDevices.find("SM-S" + code + "B"));
            assertNotNull(ExynosDevices.find("SM-S" + code + "N"));
            for (String suffix : new String[]{"U", "U1", "W", "0", "E", "Q", ""})
                assertNull(ExynosDevices.find("SM-S" + code + suffix));
        }
    }
    @Test public void s26FeHasItsOwnNameAndChip() {
        ExynosDevices.Profile profile = ExynosDevices.find("SM-S741N");
        assertEquals("Galaxy S26 FE", profile.device);
        assertEquals("Exynos 2500", profile.soc);
        assertEquals("Xclipse 950", profile.gpu);
    }
    @Test public void excludesSnapdragonS22IncludingKoreanVariant() {
        assertNotNull(ExynosDevices.find("SM-S908B"));
        for (String model : new String[]{"SM-S901N", "SM-S906U", "SM-S908W", "SM-S9010"})
            assertNull(ExynosDevices.find(model));
    }
    @Test public void mixedS23AndS24FamiliesNeedTheCorrectRegionalCode() {
        assertNotNull(ExynosDevices.find("SM-S711N"));
        assertNotNull(ExynosDevices.find("SM-S926N"));
        assertNull(ExynosDevices.find("SM-S711U1"));
        assertNull(ExynosDevices.find("SM-S921E"));
        assertNull(ExynosDevices.find("SM-S926U"));
        assertNull(ExynosDevices.find("SM-S928B"));
    }
    @Test public void dualSimAndCaseDoNotAffectMatching() {
        assertEquals("S24+", ExynosDevices.find("Samsung sm-s926b/DS").shortLabel());
        assertEquals("S26+", ExynosDevices.find("SM-S947BZKDEUB").shortLabel());
    }
    @Test public void shortenedLegacyModelsStillRespectRegionalSuffix() {
        assertEquals("S24+", ExynosDevices.find("SM-926B").shortLabel());
        assertNull(ExynosDevices.find("SM-926U"));
        assertNull(ExynosDevices.find("SM-947E"));
    }
    @Test public void s25FeDoesNotInheritTheS26FeChip() {
        assertEquals("Exynos 2400", ExynosDevices.find("SM-S731B").soc);
        assertEquals("Xclipse 940", ExynosDevices.find("SM-S731U").gpu);
    }
    @Test public void globalExynosAndWifiTabletsDoNotNeedRestrictedSuffixes() {
        assertEquals("A55", ExynosDevices.find("SM-A556E").shortLabel());
        assertEquals("Xclipse 540", ExynosDevices.find("SM-X520").gpu);
        assertNotNull(ExynosDevices.find("SM-X626N"));
    }
    @Test public void unrelatedAndAmbiguousModelsStayUnknown() {
        for (String model : new String[]{null, "", "SM-S947", "SM-S926", "other phone", "SM-G991B"})
            assertNull(ExynosDevices.find(model));
    }
}
