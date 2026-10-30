package com.winlator.cmod.renderer;

import org.junit.Test;
import static org.junit.Assert.*;

public class FsrSettingsTest {
    @Test public void oldQualityPresetEnablesActualUpscaling() {
        assertEquals("quality", FsrSettings.resolve(0, false, "Quality (1.5x)",
                null, "0", null, null));
    }

    @Test public void explicitTextureFilterOverridesLegacyQuality() {
        assertEquals("off", FsrSettings.resolve(0, true, "quality", null, "1", null, null));
    }

    @Test public void explicitUpscalingOffIsPreservedDuringMigration() {
        assertEquals("on", FsrSettings.resolve(2, false, "quality", "0", "1", null, null));
    }

    @Test public void missingOrInvalidFlagDoesNotEnableUpscaling() {
        for (String flag : new String[]{null, "", "no", "2", "quality"})
            assertEquals("on", FsrSettings.resolve(2, true, "off", flag, null, null, null));
    }

    @Test public void invalidQualityUsesBalancedInsteadOfDisablingFsr() {
        assertEquals("balanced", FsrSettings.resolve(2, true, "off", "1", null, "invalid", null));
        assertEquals("performance", FsrSettings.resolve(2, true, "off", "1", null,
                "Performance (2.0x)", null));
    }

    @Test public void qualityRenderScaleIsAppliedOnlyOnce() {
        assertArrayEquals(new int[]{1280, 720}, FsrSettings.guestSize(1920, 1080, "quality"));
        assertArrayEquals(new int[]{1280, 720}, FsrSettings.sceneSize(1280, 720, 2340, 1080));
    }

    @Test public void sceneClampingKeepsAspectWhenOutputIsSmallerOrRotated() {
        assertArrayEquals(new int[]{960, 540}, FsrSettings.sceneSize(1920, 1080, 960, 1080));
        assertArrayEquals(new int[]{1080, 608}, FsrSettings.sceneSize(1920, 1080, 1080, 2340));
    }

    @Test public void temporaryZeroSurfaceCannotProduceZeroAllocationOrDivision() {
        assertArrayEquals(new int[]{1, 1}, FsrSettings.sceneSize(1920, 1080, 0, 0));
    }
}
