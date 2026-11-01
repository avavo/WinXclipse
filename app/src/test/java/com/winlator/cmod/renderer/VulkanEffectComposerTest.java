package com.winlator.cmod.renderer;

import com.winlator.cmod.renderer.effects.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class VulkanEffectComposerTest {
    private static final class Composer extends VulkanEffectComposer {
        Composer(){super(null);}
        @Override protected void requestRender(){}
    }

    @Test public void defaultPlanNeedsNeitherAnEffectPassNorReducedResolution() {
        Composer c=new Composer();
        VulkanEffectComposer.Plan p=c.plan(2340,1080,false,1280,720,0);
        assertEquals(2340,p.sceneWidth);assertEquals(1080,p.sceneHeight);assertEquals(0,p.ids.length);
        assertTrue(p.pushes.isDirect());
    }

    @Test public void easuAndRcasHaveSeparatePassesAndAlignedPushConstants() {
        Composer c=new Composer();c.setSceneScale(2f);
        c.addEffect(new FSREasuEffect());c.addEffect(new FSREffect(.25f));
        VulkanEffectComposer.Plan p=c.plan(1920,1080,true,1280,720,0);
        assertArrayEquals(new int[]{3,4},p.ids);
        assertEquals(960,p.sceneWidth);assertEquals(540,p.sceneHeight);
        assertEquals(.5f,p.pushes.getFloat(0),0);
        assertEquals(.5f,p.pushes.getFloat(4),0);
        assertEquals(1920f,p.pushes.getFloat(24),0);
        assertEquals(1080f,p.pushes.getFloat(28),0);
        assertEquals(1f/960,p.pushes.getFloat(40),0);
        assertEquals(1f/540,p.pushes.getFloat(44),0);
        assertEquals(1920f,p.pushes.getFloat(64),0);
        assertEquals(.25f,p.pushes.getFloat(128),0);
        assertEquals(0,p.pushes.getInt(132)); // vec2 padding
        assertEquals(1920f,p.pushes.getFloat(136),0);
        assertEquals(1919.5f,p.pushes.getFloat(152),0);
    }

    @Test public void ultrawideLetterboxIsExcludedFromFsrNeighborhood() {
        Composer c=new Composer();c.setSceneScale(2);
        c.addEffect(new FSREasuEffect());c.addEffect(new FSREffect(.4f));
        VulkanEffectComposer.Plan p=c.plan(2340,1080,false,1280,720,0);
        assertEquals(104.75f,p.pushes.getFloat(8),.01f); // source offset + half-texel mapping
        assertEquals(210f,p.pushes.getFloat(16),0); // destination pillarbox
        assertEquals(105.5f,p.pushes.getFloat(48),0); // bounded source tap
        assertEquals(210.5f,p.pushes.getFloat(144),0); // bounded RCAS tap
    }

    @Test public void everyExistingOptionalFilterHasItsOwnVulkanPass() {
        Composer c=new Composer();ColorEffect color=new ColorEffect();
        color.setBrightness(.2f);color.setContrast(.3f);color.setGamma(1.2f);color.setSaturation(.8f);
        c.addEffect(color);c.addEffect(new CRTEffect());c.addEffect(new FXAAEffect());
        c.addEffect(new HDREffect());c.addEffect(new NTSCCombinedEffect());c.addEffect(new ToonEffect());
        VulkanEffectComposer.Plan p=c.plan(1920,1080,false,1280,720,7);
        assertArrayEquals(new int[]{1,2,5,6,7,8},p.ids);
        assertEquals(.2f,p.pushes.getFloat(0),0);assertEquals(1.2f,p.pushes.getFloat(8),0);
        assertEquals(1920f,p.pushes.getFloat(2*128),0);
        assertEquals(1080f,p.pushes.getFloat(2*128+12),0);
        assertEquals(7,p.pushes.getInt(4*128));
    }

    @Test public void ntscTimeDoesNotDiscardFrameGenerationHistoryButSettingsDo() {
        Composer c=new Composer();ColorEffect color=new ColorEffect();
        c.addEffect(color);c.addEffect(new NTSCCombinedEffect());
        VulkanEffectComposer.Plan a=c.plan(1920,1080,true,1280,720,1);
        int firstFrame=a.pushes.getInt(128);
        VulkanEffectComposer.Plan b=c.plan(1920,1080,true,1280,720,2);
        assertEquals(a.configurationHash,b.configurationHash);
        assertNotEquals(firstFrame,b.pushes.getInt(128));
        color.setGamma(1.4f);
        assertNotEquals(a.configurationHash,c.plan(1920,1080,true,1280,720,2).configurationHash);
    }

    @Test public void zeroSurfaceCannotEmitNonfiniteShaderParameters() {
        Composer c=new Composer();c.setSceneScale(2);c.addEffect(new FSREasuEffect());
        VulkanEffectComposer.Plan p=c.plan(0,0,false,0,0,0);
        assertEquals(1,p.sceneWidth);assertEquals(1,p.sceneHeight);
        for(int i=0;i<72;i+=4)assertTrue(Float.isFinite(p.pushes.getFloat(i)));
    }

    @Test(expected=IllegalArgumentException.class) public void missingPortIsReportedInsteadOfRunningAnEffectThroughGles() {
        Composer c=new Composer();c.addEffect(new Effect(){});
        c.plan(1920,1080,false,1280,720,0);
    }
}
