package com.winlator.cmod.core;
import org.junit.Test;
import static org.junit.Assert.*;
public class HudModeTest {
    @Test public void keepsOldMangoAsSecondStyle() {
        assertEquals(2, HudMode.nativeStyle("mangohud"));
        assertEquals("mangohud", HudMode.normalize("mangohud2"));
        assertEquals(1, HudMode.nativeStyle("mangohud1"));
    }
    @Test public void onlyExplicitCombinationsEnableDxvk() {
        assertFalse(HudMode.dxvk("mangohud1"));
        assertFalse(HudMode.dxvk("mangohud"));
        assertTrue(HudMode.dxvk("winlator_dxvk"));
        assertEquals(-1, HudMode.nativeStyle("dxvk"));
    }
    @Test public void selectingNativeAppearancePreservesTheSecondDxvkHud() {
        assertEquals("mangohud1_dxvk", HudMode.withStyle("dxvk", 1));
        assertEquals("mangohud2_dxvk", HudMode.withStyle("winlator_dxvk", 2));
        assertEquals("winlator_dxvk", HudMode.withStyle("mangohud2_dxvk", 0));
        assertEquals("mangohud1", HudMode.withStyle("winlator", 1));
    }
    @Test public void selectorIdsRoundTripAndUnknownValuesAreSafe() {
        for (String mode : HudMode.IDS) assertEquals(mode, HudMode.at(HudMode.index(mode)));
        assertEquals("winlator", HudMode.normalize("DXVK fake"));
        assertEquals("winlator", HudMode.normalize(null));
    }
}
