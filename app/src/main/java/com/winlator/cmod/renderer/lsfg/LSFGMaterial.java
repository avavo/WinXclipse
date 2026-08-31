package com.winlator.cmod.renderer.lsfg;

import android.opengl.GLES20;

import com.winlator.cmod.renderer.material.ScreenMaterial;

/** Warps the two captured real frames using Apex/compute motion vectors. */
final class LSFGMaterial extends ScreenMaterial {
    private final LSFGEffect effect;

    LSFGMaterial(LSFGEffect effect) {
        this.effect = effect;
        setUniformNames("resolution", "screenTexture", "previousCapturedTexture",
                "currentCapturedTexture", "motionVectorTexture", "interpolationFactor",
                "qualityMode", "stability");
    }

    @Override
    protected String getVertexShader() {
        return "#version 300 es\n"
                + "in vec2 position; out vec2 vUV;\n"
                + "void main() { vUV=position; gl_Position=vec4(position*2.0-1.0,0.0,1.0); }";
    }

    @Override
    protected String getFragmentShader() {
        return "#version 300 es\n"
                + "precision mediump float;\n"
                + "uniform sampler2D previousCapturedTexture,currentCapturedTexture,motionVectorTexture;\n"
                + "uniform float interpolationFactor,qualityMode,stability;\n"
                + "in vec2 vUV; out vec4 outColor;\n"
                + "void main(){\n"
                + " vec3 prev=texture(previousCapturedTexture,vUV).rgb;\n"
                + " if(interpolationFactor<0.01){outColor=vec4(prev,1);return;}\n"
                + " vec3 curr=texture(currentCapturedTexture,vUV).rgb;\n"
                + " if(interpolationFactor>0.99){outColor=vec4(curr,1);return;}\n"
                + " vec4 md=texture(motionVectorTexture,vUV); vec2 mv=md.rg; float confidence=md.b;\n"
                + " vec3 wp=texture(previousCapturedTexture,vUV+mv*interpolationFactor).rgb;\n"
                + " vec3 wc=texture(currentCapturedTexture,vUV-mv*(1.0-interpolationFactor)).rgb;\n"
                + " vec3 result=mix(wp,wc,interpolationFactor);\n"
                + " float diff=distance(wp,wc);\n"
                + " float threshold=mix(0.28,0.10,stability);\n"
                + " float disocclusion=smoothstep(threshold,threshold+0.25,diff);\n"
                + " float uncertain=clamp((mix(0.04,0.16,stability)-confidence)*4.0,0.0,1.0);\n"
                + " float fallback=max(uncertain,disocclusion);\n"
                + " result=mix(result,mix(prev,curr,interpolationFactor),fallback);\n"
                + " outColor=vec4(result,1);}\n";
    }

    @Override
    public void use() {
        super.use();
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, effect.getPreviousTextureId());
        setUniformInt("previousCapturedTexture", 1);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, effect.getCurrentTextureId());
        setUniformInt("currentCapturedTexture", 2);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, effect.getMotionVectorTexture());
        setUniformInt("motionVectorTexture", 3);
        setUniformFloat("interpolationFactor", effect.getManager().getInterpolationFactor());
        setUniformFloat("qualityMode", effect.getQuality());
        setUniformFloat("stability", effect.getStability());
    }
}
