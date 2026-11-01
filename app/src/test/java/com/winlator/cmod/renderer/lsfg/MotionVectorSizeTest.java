package com.winlator.cmod.renderer.lsfg;

import org.junit.Test;
import static org.junit.Assert.*;

public class MotionVectorSizeTest {
    @Test public void profilesPreserveTheReferenceFrameGenerationWorkload() {
        assertArrayEquals(new int[]{240,135},MotionVectorSize.forQuality(1920,1080,0));
        assertArrayEquals(new int[]{480,270},MotionVectorSize.forQuality(1920,1080,1));
        assertArrayEquals(new int[]{960,540},MotionVectorSize.forQuality(1920,1080,2));
    }
    @Test public void oddSizesUseIntegerResolutionInsteadOfRoundingBeyondTheFrame() {
        assertArrayEquals(new int[]{292,135},MotionVectorSize.forQuality(2341,1081,0));
        assertArrayEquals(new int[]{585,270},MotionVectorSize.forQuality(2341,1081,1));
    }
    @Test public void minimizedAndTinySurfacesCannotAllocateAZeroSizedMotionImage() {
        assertArrayEquals(new int[]{1,1},MotionVectorSize.forQuality(0,0,2));
        assertArrayEquals(new int[]{1,1},MotionVectorSize.forQuality(1,3,0));
    }
}
