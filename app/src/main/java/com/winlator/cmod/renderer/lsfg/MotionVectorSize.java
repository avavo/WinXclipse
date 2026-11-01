package com.winlator.cmod.renderer.lsfg;

/** The same motion-estimation workload for the GLES and Vulkan compositors. */
public final class MotionVectorSize {
    private MotionVectorSize(){}
    public static int[] forQuality(int width,int height,int quality) {
        int scale=quality<=0?8:quality==1?4:2;
        return new int[]{Math.max(1,width/scale),Math.max(1,height/scale)};
    }
}
