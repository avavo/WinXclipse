package com.winlator.cmod.renderer;

import org.junit.Test;
import static org.junit.Assert.*;

public class FsrViewportTest {
    private float[] bounds(int w,int h,float x,float y,float vw,float vh) {
        float[] out=new float[4];FsrViewport.sampleBounds(out,w,h,x,y,vw,vh);return out;
    }
    @Test public void fullSurfaceUsesFirstAndLastTexelCenters() {
        assertArrayEquals(new float[]{0.5f,0.5f,1919.5f,1079.5f},bounds(1920,1080,0,0,1920,1080),0);
    }
    @Test public void letterboxRowsAreExcludedFromTheFilterNeighborhood() {
        assertArrayEquals(new float[]{0.5f,180.5f,1919.5f,899.5f},bounds(1920,1080,0,180,1920,720),0);
    }
    @Test public void pillarboxColumnsAreExcluded() {
        assertArrayEquals(new float[]{480.5f,0.5f,1439.5f,1079.5f},bounds(1920,1080,480,0,960,1080),0);
    }
    @Test public void singlePixelContentAlwaysSamplesItsOwnCenter() {
        assertArrayEquals(new float[]{2.5f,3.5f,2.5f,3.5f},bounds(16,16,2,3,1,1),0);
    }
    @Test public void clippedContentCannotReadBeyondTheTexture() {
        assertArrayEquals(new float[]{0.5f,0.5f,7.5f,7.5f},bounds(8,8,-4,-2,16,16),0);
        assertArrayEquals(new float[]{7.5f,7.5f,7.5f,7.5f},bounds(8,8,50,50,16,16),0);
    }
    @Test public void rotationAndDegenerateViewportBoundsStayFiniteAndOrdered() {
        float[] out=bounds(0,0,Float.NaN,Float.POSITIVE_INFINITY,0,-1);
        assertArrayEquals(new float[]{0.5f,0.5f,0.5f,0.5f},out,0);
        out=bounds(1080,1920,0,420,1080,1080);
        assertEquals(420.5f,out[1],0);assertEquals(1499.5f,out[3],0);
    }
}
