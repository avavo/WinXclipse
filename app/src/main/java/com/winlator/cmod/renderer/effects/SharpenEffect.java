package com.winlator.cmod.renderer.effects;

import android.opengl.GLES20;

import com.winlator.cmod.renderer.material.ScreenMaterial;
import com.winlator.cmod.renderer.material.ShaderMaterial;

public class SharpenEffect extends Effect {
    private float amount = 0.5f;
    private int radius = 10;
    private int threshold = 5;

    public SharpenEffect() {
        super();
    }

    @Override
    protected ShaderMaterial createMaterial() {
        return new SharpenMaterial();
    }

    public void setAmount(float amount) {
        this.amount = amount;
        ShaderMaterial mat = getMaterial();
        if (mat != null && mat instanceof SharpenMaterial) {
            ((SharpenMaterial) mat).updateAmount(amount);
        }
    }

    public void setRadius(int radius) {
        this.radius = radius;
        ShaderMaterial mat = getMaterial();
        if (mat != null && mat instanceof SharpenMaterial) {
            ((SharpenMaterial) mat).updateRadius(radius);
        }
    }

    public void setThreshold(int threshold) {
        this.threshold = threshold;
        ShaderMaterial mat = getMaterial();
        if (mat != null && mat instanceof SharpenMaterial) {
            ((SharpenMaterial) mat).updateThreshold(threshold);
        }
    }

    public float getAmount() {
        return amount;
    }

    public int getRadius() {
        return radius;
    }

    public int getThreshold() {
        return threshold;
    }

    private class SharpenMaterial extends ScreenMaterial {
        private int uAmount;
        private int uRadius;
        private int uThreshold;
        private int uTexelSize;

        @Override
        protected String getFragmentShader() {
            return String.join("\n", new CharSequence[]{
                    "precision mediump float;",
                    "uniform sampler2D screenTexture;",
                    "uniform vec2 resolution;",
                    "uniform vec2 texelSize;",
                    "uniform float amount;",
                    "uniform int radius;",
                    "uniform int threshold;",
                    "void main() {",
                    "    vec2 texcoord = gl_FragCoord.xy / resolution;",
                    "    vec3 color = texture2D(screenTexture, texcoord).rgb;",
                    "    vec3 sum = vec3(0.0);",
                    "    float weightSum = 0.0;",
                    "    for (int x = -5; x <= 5; x++) {",
                    "        for (int y = -5; y <= 5; y++) {",
                    "            if (abs(x) + abs(y) > radius) continue;",
                    "            vec2 offset = vec2(float(x), float(y)) * texelSize;",
                    "            vec3 sample = texture2D(screenTexture, texcoord + offset).rgb;",
                    "            float dist = length(vec2(float(x), float(y)));",
                    "            float weight = exp(-dist * dist / 2.0);",
                    "            sum += sample * weight;",
                    "            weightSum += weight;",
                    "        }",
                    "    }",
                    "    vec3 blurred = sum / weightSum;",
                    "    vec3 diff = color - blurred;",
                    "    float lumaDiff = dot(diff, vec3(0.299, 0.587, 0.114));",
                    "    float mask = step(float(threshold) / 255.0, abs(lumaDiff)) ? 1.0 : 0.0;",
                    "    vec3 sharpened = color + diff * amount * mask;",
                    "    gl_FragColor = vec4(clamp(sharpened, 0.0, 1.0), 1.0);",
                    "}"
            });
        }

        @Override
        public void use() {
            super.use();
            uAmount = getUniformLocation("amount");
            uRadius = getUniformLocation("radius");
            uThreshold = getUniformLocation("threshold");
            uTexelSize = getUniformLocation("texelSize");
        }

        public void updateAmount(float amount) {
            if (uAmount >= 0) {
                GLES20.glUniform1f(uAmount, amount);
            }
        }

        public void updateRadius(int radius) {
            if (uRadius >= 0) {
                GLES20.glUniform1i(uRadius, radius);
            }
        }

        public void updateThreshold(int threshold) {
            if (uThreshold >= 0) {
                GLES20.glUniform1i(uThreshold, threshold);
            }
        }
    }
}