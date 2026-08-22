package com.winlator.cmod.renderer.effects;

import com.winlator.cmod.renderer.material.ScreenMaterial;
import com.winlator.cmod.renderer.material.ShaderMaterial;

/** Lightweight fake-HDR post-processing effect ported from Winlator Mali. */
public class HDREffect extends Effect {
    @Override
    protected ShaderMaterial createMaterial() {
        return new HDRMaterial();
    }

    private static class HDRMaterial extends ScreenMaterial {
        @Override
        protected String getFragmentShader() {
            return String.join("\n", new CharSequence[]{
                    "precision mediump float;",
                    "uniform sampler2D screenTexture;",
                    "uniform vec2 resolution;",
                    "const float HDRPower = 1.30;",
                    "const float radius1 = 0.793;",
                    "const float radius2 = 0.870;",
                    "void main() {",
                    "    vec2 texcoord = gl_FragCoord.xy / resolution;",
                    "    vec2 px = 1.0 / resolution;",
                    "    vec3 color = texture2D(screenTexture, texcoord).rgb;",
                    "    vec3 bloom_sum1 = texture2D(screenTexture, texcoord + vec2(1.5, -1.5) * radius1 * px).rgb;",
                    "    bloom_sum1 += texture2D(screenTexture, texcoord + vec2(-1.5, -1.5) * radius1 * px).rgb;",
                    "    bloom_sum1 += texture2D(screenTexture, texcoord + vec2( 1.5,  1.5) * radius1 * px).rgb;",
                    "    bloom_sum1 += texture2D(screenTexture, texcoord + vec2(-1.5,  1.5) * radius1 * px).rgb;",
                    "    bloom_sum1 += texture2D(screenTexture, texcoord + vec2( 0.0, -2.5) * radius1 * px).rgb;",
                    "    bloom_sum1 += texture2D(screenTexture, texcoord + vec2( 0.0,  2.5) * radius1 * px).rgb;",
                    "    bloom_sum1 += texture2D(screenTexture, texcoord + vec2(-2.5,  0.0) * radius1 * px).rgb;",
                    "    bloom_sum1 += texture2D(screenTexture, texcoord + vec2( 2.5,  0.0) * radius1 * px).rgb;",
                    "    bloom_sum1 *= 0.005;",
                    "    vec3 bloom_sum2 = texture2D(screenTexture, texcoord + vec2(1.5, -1.5) * radius2 * px).rgb;",
                    "    bloom_sum2 += texture2D(screenTexture, texcoord + vec2(-1.5, -1.5) * radius2 * px).rgb;",
                    "    bloom_sum2 += texture2D(screenTexture, texcoord + vec2( 1.5,  1.5) * radius2 * px).rgb;",
                    "    bloom_sum2 += texture2D(screenTexture, texcoord + vec2(-1.5,  1.5) * radius2 * px).rgb;",
                    "    bloom_sum2 += texture2D(screenTexture, texcoord + vec2( 0.0, -2.5) * radius2 * px).rgb;",
                    "    bloom_sum2 += texture2D(screenTexture, texcoord + vec2( 0.0,  2.5) * radius2 * px).rgb;",
                    "    bloom_sum2 += texture2D(screenTexture, texcoord + vec2(-2.5,  0.0) * radius2 * px).rgb;",
                    "    bloom_sum2 += texture2D(screenTexture, texcoord + vec2( 2.5,  0.0) * radius2 * px).rgb;",
                    "    bloom_sum2 *= 0.010;",
                    "    float dist = radius2 - radius1;",
                    "    vec3 hdr = (color + (bloom_sum2 - bloom_sum1)) * dist;",
                    "    vec3 blend = hdr + color;",
                    "    color = pow(abs(blend), vec3(abs(HDRPower))) + hdr;",
                    "    gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);",
                    "}"
            });
        }
    }
}
