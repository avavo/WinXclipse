package com.winlator.cmod.renderer.effects;

import com.winlator.cmod.renderer.material.ScreenMaterial;
import com.winlator.cmod.renderer.material.ShaderMaterial;

/**
 * AMD FidelityFX Super Resolution 1.0 - [RCAS] Robust Contrast Adaptive
 * Sharpening, faithfully ported from ffx_fsr1.h v1.20210629 (MIT).
 *
 * Runs as a screen-space pass at display resolution. Sharpness follows the
 * spec's stop scale: exp2(-stops).
 */
public class FSREffect extends Effect {
    /** Sharpness in FSR stops: exp2(-stops). Lower internal resolution
     * (Performance) pairs with stronger sharpening. */
    private final float stops;
    public FSREffect(float stops) {
        super();
        this.stops = stops;
    }

    @Override
    protected ShaderMaterial createMaterial() {
        return new FSRCASMaterial();
    }

    private class FSRCASMaterial extends ScreenMaterial {
        FSRCASMaterial() {
            super();
        }

        @Override
        protected String getFragmentShader() {
            return String.join("\n", new CharSequence[]{
                    "precision highp float;",
                    "// AMD FidelityFX SUPER RESOLUTION [FSR1] ::: RCAS - MIT License",
                    "#define FSR_RCAS_LIMIT (0.25-(1.0/16.0))",
                    "uniform sampler2D screenTexture;",
                    "uniform vec2 resolution;",
                    "float rcpF1(float x){ return 1.0 / (abs(x)<1.0e-6 ? (x<0.0 ? -1.0e-6 : 1.0e-6) : x); }",
                    "float satF1(float x){ return clamp(x, 0.0, 1.0); }",
                    "float max3F1(float x,float y,float z){ return max(x, max(y, z)); }",
                    "float min3F1(float x,float y,float z){ return min(x, min(y, z)); }",
                    "vec3 fetch(vec2 pixelOffset){",
                    "    vec2 invResolution = 1.0 / max(resolution, vec2(1.0));",
                    "    return texture2D(screenTexture, (gl_FragCoord.xy + pixelOffset) * invResolution).rgb;",
                    "}",
                    "void main(){",
                    "    // Algorithm uses minimal 3x3 pixel neighborhood.",
                    "    //    b ",
                    "    //  d e f",
                    "    //    h",
                    "    vec3 b = fetch(vec2( 0.0,-1.0));",
                    "    vec3 d = fetch(vec2(-1.0, 0.0));",
                    "    vec3 e = fetch(vec2( 0.0, 0.0));",
                    "    vec3 f = fetch(vec2( 1.0, 0.0));",
                    "    vec3 h = fetch(vec2( 0.0, 1.0));",
                    "    // Luma times 2.",
                    "    float bL=b.b*0.5+(b.r*0.5+b.g);",
                    "    float dL=d.b*0.5+(d.r*0.5+d.g);",
                    "    float eL=e.b*0.5+(e.r*0.5+e.g);",
                    "    float fL=f.b*0.5+(f.r*0.5+f.g);",
                    "    float hL=h.b*0.5+(h.r*0.5+h.g);",
                    "    // Noise detection (FSR_RCAS_DENOISE).",
                    "    float nz=0.25*bL+0.25*dL+0.25*fL+0.25*hL-eL;",
                    "    nz=satF1(abs(nz)*rcpF1(max3F1(max3F1(bL,dL,eL),fL,hL)-min3F1(min3F1(bL,dL,eL),fL,hL)));",
                    "    nz=-0.5*nz+1.0;",
                    "    // Min and max of ring.",
                    "    float mn4R=min(min3F1(b.r,d.r,f.r),h.r);",
                    "    float mn4G=min(min3F1(b.g,d.g,f.g),h.g);",
                    "    float mn4B=min(min3F1(b.b,d.b,f.b),h.b);",
                    "    float mx4R=max(max3F1(b.r,d.r,f.r),h.r);",
                    "    float mx4G=max(max3F1(b.g,d.g,f.g),h.g);",
                    "    float mx4B=max(max3F1(b.b,d.b,f.b),h.b);",
                    "    // Immediate constants for peak range.",
                    "    vec2 peakC=vec2(1.0,-1.0*4.0);",
                    "    // Limiters, these need to be high precision RCPs.",
                    "    float hitMinR=min(mn4R,e.r)*rcpF1(4.0*mx4R);",
                    "    float hitMinG=min(mn4G,e.g)*rcpF1(4.0*mx4G);",
                    "    float hitMinB=min(mn4B,e.b)*rcpF1(4.0*mx4B);",
                    "    float hitMaxR=(peakC.x-max(mx4R,e.r))*rcpF1(4.0*mn4R+peakC.y);",
                    "    float hitMaxG=(peakC.x-max(mx4G,e.g))*rcpF1(4.0*mn4G+peakC.y);",
                    "    float hitMaxB=(peakC.x-max(mx4B,e.b))*rcpF1(4.0*mn4B+peakC.y);",
                    "    float lobeR=max(-hitMinR,hitMaxR);",
                    "    float lobeG=max(-hitMinG,hitMaxG);",
                    "    float lobeB=max(-hitMinB,hitMaxB);",
                    "    float lobe=max(-FSR_RCAS_LIMIT,min(max3F1(lobeR,lobeG,lobeB),0.0));",
                    "    // Transform from stops to linear value: exp2(-sharpness), as in FsrRcasCon().",
                    "    lobe*=exp2(-" + stops + ");",
                    "    // Apply noise removal.",
                    "    lobe*=nz;",
                    "    // Resolve, which needs the medium precision rcp approximation to",
                    "    // avoid visible tonality changes.",
                    "    float rcpL=rcpF1(4.0*lobe+1.0);",
                    "    vec3 pix=vec3(",
                    "        (lobe*b.r+lobe*d.r+lobe*h.r+lobe*f.r+e.r)*rcpL,",
                    "        (lobe*b.g+lobe*d.g+lobe*h.g+lobe*f.g+e.g)*rcpL,",
                    "        (lobe*b.b+lobe*d.b+lobe*h.b+lobe*f.b+e.b)*rcpL);",
                    "    gl_FragColor=vec4(clamp(pix,0.0,1.0),1.0);",
                    "}"
            });
        }
    }
}
