package com.winlator.cmod.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class UpgradeNoticeTest {
    @Test public void freshInstallNeverGetsAnUpdateNotice() {
        assertFalse(UpgradeNotice.shouldShow096("0.9.6", false, 5000, 5000));
        assertFalse(UpgradeNotice.shouldShow096("0.9.6", false, 5000, 5500));
    }
    @Test public void legacyUpgradeNeedsNoOldPreferenceToBeRecognized() {
        assertTrue(UpgradeNotice.shouldShow096("0.9.6", false, 5000, 20000));
    }
    @Test public void noticeDoesNotRepeatAfterItWasShown() {
        assertFalse(UpgradeNotice.shouldShow096("0.9.6", true, 5000, 20000));
    }
    @Test public void futureReleasesDoNotInheritOldReleaseNotes() {
        assertFalse(UpgradeNotice.shouldShow096("1.0.0", false, 5000, 20000));
    }
    @Test public void missingOrInvertedInstallTimesDoNotLookLikeAnUpgrade() {
        assertFalse(UpgradeNotice.shouldShow096("0.9.6", false, 0, 20000));
        assertFalse(UpgradeNotice.shouldShow096("0.9.6", false, 20000, 5000));
    }
}
