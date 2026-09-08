package com.winlator.cmod.renderer.lsfg;

import android.opengl.GLES20;

import com.winlator.cmod.renderer.material.ScreenMaterial;

/** Warps the two captured real frames using Apex/compute motion vectors. */
final class LSFGMaterial extends ScreenMaterial {
    private final LSFGEffect effect;

    LSFGMaterial(LSFGEffect effect) {
        this.effect = effect;
        setUniformNames("previousCapturedTexture",
                "currentCapturedTexture", "motionVectorTexture", "interpolationFactor",
                "stability", "lowLatencyMode");
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
                + "precision highp float;\n"
                + "uniform sampler2D previousCapturedTexture,currentCapturedTexture,motionVectorTexture;\n"
                + "uniform float interpolationFactor,qualityMode,stability,lowLatencyMode;\n"
                + "in vec2 vUV; out vec4 outColor;\n"
                + "void main(){\n"
                + " vec3 prev=texture(previousCapturedTexture,vUV).rgb;\n"
                + " vec3 curr=texture(currentCapturedTexture,vUV).rgb;\n"
                + " if(lowLatencyMode>0.5){\n"
                + "  if(interpolationFactor<1.01){outColor=vec4(curr,1);return;}\n"
                + "  vec4 md=texture(motionVectorTexture,vUV); vec2 mv=(md.rg-0.5)*0.25; float confidence=md.b;\n"
                + "  float ahead=clamp(interpolationFactor-1.0,0.0,0.99);\n"
                + "  vec3 projected=texture(currentCapturedTexture,vUV+mv*ahead).rgb;\n"
                + "  float uncertain=clamp((mix(0.04,0.16,stability)-confidence)*4.0,0.0,1.0);\n"
                + "  outColor=vec4(mix(projected,curr,uncertain),1);return;}\n"
                + " if(interpolationFactor<0.01){outColor=vec4(prev,1);return;}\n"
                + " if(interpolationFactor>0.99){outColor=vec4(curr,1);return;}\n"
                + " vec4 md=texture(motionVectorTexture,vUV); vec2 mv=(md.rg-0.5)*0.25; float confidence=md.b;\n"
                + " vec2 up=vUV+mv*interpolationFactor;\n"
                + " vec2 uc=vUV-mv*(1.0-interpolationFactor);\n"
                + " vec3 wp=texture(previousCapturedTexture,clamp(up,0.0,1.0)).rgb;\n"
                + " vec3 wc=texture(currentCapturedTexture,clamp(uc,0.0,1.0)).rgb;\n"
                + " vec3 result=mix(wp,wc,interpolationFactor);\n"
                + " float diff=distance(wp,wc);\n"
                + " float threshold=mix(0.22,0.07,stability);\n"
                + " float disocclusion=smoothstep(threshold,threshold+0.16,diff);\n"
                + " float floor=mix(0.32,0.62,stability);\n"
                + " float uncertain=1.0-smoothstep(floor,min(0.98,floor+0.20),confidence);\n"
                + " vec2 in0=step(vec2(0.0),up)*step(up,vec2(1.0));\n"
                + " vec2 in1=step(vec2(0.0),uc)*step(uc,vec2(1.0));\n"
                + " float outOfBounds=1.0-in0.x*in0.y*in1.x*in1.y;\n"
                + " float fallback=max(max(uncertain,disocclusion),outOfBounds);\n"
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
        setUniformFloat("stability", effect.getStability());
        setUniformFloat("lowLatencyMode", effect.getManager().isLowLatencyMode() ? 1.0f : 0.0f);
    }
}
