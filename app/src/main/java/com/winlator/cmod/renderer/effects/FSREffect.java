package com.winlator.cmod.renderer.effects;

import com.winlator.cmod.renderer.material.ScreenMaterial;
import com.winlator.cmod.renderer.material.ShaderMaterial;

/**
 * AMD FSR-style edge-adaptive sharpening (RCAS core) applied over the
 * composed screen texture. Runs as a single screen-space pass so it stays
 * cheap on Xclipse's small WGPs while restoring detail lost to bilinear
 * filtering of upscaled content.
 */
public class FSREffect extends Effect {
    public FSREffect() {
        super();
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
                    "uniform sampler2D screenTexture;",
                    "uniform vec2 resolution;",
                    "const float FSR_SHARPENING = 0.87;", // RCAS default stop cutoff
                    "void main() {",
                    "    vec2 invResolution = 1.0 / resolution;",
                    "    vec3 rgbNW = texture2D(screenTexture, (gl_FragCoord.xy + vec2(-1.0,  0.0)) * invResolution).rgb;",
                    "    vec3 rgbNE = texture2D(screenTexture, (gl_FragCoord.xy + vec2( 1.0,  0.0)) * invResolution).rgb;",
                    "    vec3 rgbSW = texture2D(screenTexture, (gl_FragCoord.xy + vec2( 0.0, -1.0)) * invResolution).rgb;",
                    "    vec3 rgbSE = texture2D(screenTexture, (gl_FragCoord.xy + vec2( 0.0,  1.0)) * invResolution).rgb;",
                    "    vec3 rgbM  = texture2D(screenTexture,  gl_FragCoord.xy * invResolution).rgb;",
                    "    vec3 lumaNW = vec3(rgbNW * (1.0 / (0.265 + rgbNW)));",
                    "    vec3 lumaNE = vec3(rgbNE * (1.0 / (0.265 + rgbNE)));",
                    "    vec3 lumaSW = vec3(rgbSW * (1.0 / (0.265 + rgbSW)));",
                    "    vec3 lumaSE = vec3(rgbSE * (1.0 / (0.265 + rgbSE)));",
                    "    vec3 lumaM  = vec3(rgbM  * (1.0 / (0.265 + rgbM)));",
                    "    vec3 dir = -min(lumaM, min(min(lumaNW, lumaNE), min(lumaSW, lumaSE)))",
                    "             + max(lumaM, max(max(lumaNW, lumaNE), max(lumaSW, lumaSE)));",
                    "    dir *= clamp((min(lumaM, min(min(lumaNW, lumaNE), min(lumaSW, lumaSE)))",
                    "             + max(lumaM, max(max(lumaNW, lumaNE), max(lumaSW, lumaSE)))) ",
                    "             * FSR_SHARPENING / dot(dir, dir) , 0.0, 1.0);",
                    "    vec3 outColor = lumaM.rgb;",
                    "    outColor += (lumaNW + lumaNE + lumaSW + lumaSE) * 0.25 * dir;",
                    "    outColor = outColor / (1.0 + 0.25 * dir);",
                    "    gl_FragColor = vec4(clamp(outColor, 0.0, 1.0), 1.0);",
                    "}"
            });
        }
    }
}
