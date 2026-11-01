package com.winlator.cmod.renderer;

import com.winlator.cmod.renderer.effects.*;
import com.winlator.cmod.renderer.lsfg.LSFGManager;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Arrays;

/** Encodes settings for SPIR-V passes. It never instantiates GLES materials. */
public class VulkanEffectComposer extends EffectComposer {
    private final DisplayRenderer renderer;
    private ByteBuffer pushBuffer=ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());
    public VulkanEffectComposer(DisplayRenderer renderer) { super(renderer); this.renderer = renderer; }

    @Override public synchronized boolean setLSFGEnabled(boolean enabled, int quality,
            float multiplier, int targetFPS, float stability, int backend, boolean lowLatency) {
        LSFGManager manager = renderer.getLSFGManager();
        manager.setMultiplier(multiplier);
        manager.setTargetFPS(targetFPS);
        manager.setLowLatencyMode(false);
        manager.setEnabled(enabled);
        if (enabled) manager.reportBackendReady("Vulkan");
        renderer.xServerView.requestRender();
        return true;
    }

    @Override public synchronized void render() { renderer.drawFrame(); }

    public static final class Plan {
        public final int sceneWidth, sceneHeight;
        public final int[] ids;
        public final ByteBuffer pushes;
        public final int configurationHash;
        Plan(int w, int h, int[] ids, ByteBuffer pushes, int hash) {
            sceneWidth=w; sceneHeight=h; this.ids=ids; this.pushes=pushes; configurationHash=hash;
        }
    }

    public synchronized Plan plan(int width, int height, boolean full, int guestW, int guestH, int frame) {
        width=Math.max(1,width);height=Math.max(1,height);
        guestW=Math.max(1,guestW);guestH=Math.max(1,guestH);
        int[] size = sceneSize(width, height);
        List<Effect> effects = snapshotEffects();
        int[] ids = new int[effects.size()];
        int bytes=Math.max(1,effects.size())*128;
        if(pushBuffer.capacity()<bytes)pushBuffer=ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        ByteBuffer pushes=pushBuffer;
        pushes.clear();pushes.limit(bytes);
        for(int i=0;i<bytes;i+=8)pushes.putLong(i,0);
        ViewTransformation source = new ViewTransformation(), target = new ViewTransformation();
        source.update(size[0], size[1], guestW, guestH);
        target.update(width, height, guestW, guestH);
        int n = 0;
        for (Effect effect : effects) {
            int base = n * 128;
            if (effect instanceof ColorEffect) {
                ColorEffect color = (ColorEffect)effect;
                ids[n] = 1;
                floats(pushes,base,color.getBrightness(),color.getContrast(),color.getGamma(),color.getSaturation());
            } else if (effect instanceof CRTEffect) ids[n] = 2;
            else if (effect instanceof FSREasuEffect) {
                ids[n] = 3;
                FSREasuEffect easu = (FSREasuEffect)effect;
                easu.setMapping(size[0],size[1],full?0:source.viewOffsetX,
                        full?0:size[1]-source.viewOffsetY-source.viewHeight,
                        full?size[0]:source.viewWidth,full?size[1]:source.viewHeight,
                        full?0:target.viewOffsetX,full?0:height-target.viewOffsetY-target.viewHeight,
                        full?width:target.viewWidth,full?height:target.viewHeight,height);
                floats(pushes,base,easu.getCon0());floats(pushes,base+16,easu.getDstRect());
                floats(pushes,base+32,easu.getOutHeight());floats(pushes,base+40,1f/size[0],1f/size[1]);
                floats(pushes,base+48,easu.getSourceBounds());floats(pushes,base+64,width,height);
            } else if (effect instanceof FSREffect) {
                ids[n] = 4;
                FSREffect rcas = (FSREffect)effect;
                rcas.setSampleBounds(width,height,full?0:target.viewOffsetX,full?0:target.viewOffsetY,
                        full?width:target.viewWidth,full?height:target.viewHeight);
                floats(pushes,base,rcas.getStops());floats(pushes,base+8,width,height);
                floats(pushes,base+16,rcas.getSampleBounds());floats(pushes,base+32,width,height);
            } else if (effect instanceof FXAAEffect) { ids[n]=5;floats(pushes,base,width,height,width,height); }
            else if (effect instanceof HDREffect) { ids[n]=6;floats(pushes,base,width,height,width,height); }
            else if (effect instanceof NTSCCombinedEffect) {
                ids[n]=7;floats(pushes,base+8,width,height,width,height);
            } else if (effect instanceof ToonEffect) { ids[n]=8;floats(pushes,base,width,height,width,height); }
            else throw new IllegalArgumentException("Effect has no Vulkan implementation: " + effect.getClass().getName());
            n++;
        }
        // NTSC's time changes every real frame; it must not reset temporal history.
        int hash = 31*(31*Arrays.hashCode(ids)+pushes.hashCode())+Arrays.hashCode(size);
        for (int i=0;i<ids.length;i++)if(ids[i]==7)pushes.putInt(i*128,frame);
        return new Plan(size[0],size[1],ids,pushes,hash);
    }

    static void floats(ByteBuffer buffer, int offset, float... values) {
        for (float value : values) { buffer.putFloat(offset,value); offset+=4; }
    }
}
