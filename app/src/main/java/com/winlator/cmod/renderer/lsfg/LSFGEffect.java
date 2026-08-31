package com.winlator.cmod.renderer.lsfg;

import android.opengl.GLES20;
import android.opengl.GLES31;
import android.util.Log;

import com.winlator.cmod.renderer.GLRenderer;
import com.winlator.cmod.renderer.RenderTarget;
import com.winlator.cmod.renderer.apex.ApexNative;
import com.winlator.cmod.renderer.effects.Effect;
import com.winlator.cmod.renderer.material.ShaderMaterial;

/** Final compositor pass for the experimental Apex frame generator. */
public final class LSFGEffect extends Effect {
    private static final String TAG = "LSFGEffect";
    // The imported native engine can terminate the entire process inside the
    // vendor GL driver (SIGSEGV cannot be caught in Java). Keep the portable
    // GLES 3.1 compute backend as the safe default on Xclipse.
    private static final boolean ENABLE_NATIVE_APEX = false;

    private final LSFGManager manager;
    private final GLRenderer renderer;
    private final LSFGComputeMaterial computeMaterial = new LSFGComputeMaterial();
    private final RenderTarget[] frameBuffers = {new RenderTarget(), new RenderTarget()};

    private int motionVectorTexture;
    private int motionVectorHistoryTexture;
    private int motionVectorWidth;
    private int motionVectorHeight;
    private int currentFrameIndex;
    private int quality = 1;
    private float stability = 0.6f;
    private long nativeEngine;
    private boolean nativeBackendFailed;
    private boolean motionVectorOwnedByNative;

    public LSFGEffect(GLRenderer renderer, LSFGManager manager) {
        this.renderer = renderer;
        this.manager = manager;
    }

    @Override
    protected ShaderMaterial createMaterial() {
        return new LSFGMaterial(this);
    }

    public LSFGManager getManager() {
        return manager;
    }

    public int getQuality() {
        return quality;
    }

    public void setQuality(int quality) {
        this.quality = Math.max(0, Math.min(2, quality));
        applyNativeSettings();
    }

    public float getStability() {
        return stability;
    }

    public void setStability(float stability) {
        this.stability = Math.max(0.0f, Math.min(1.0f, stability));
        applyNativeSettings();
    }

    public void setTargetFPS(int targetFPS) {
        manager.setTargetFPS(targetFPS);
        applyNativeSettings();
    }

    public void setMultiplier(float multiplier) {
        manager.setMultiplier(multiplier);
    }

    public int getMotionVectorTexture() {
        return motionVectorTexture;
    }

    public int getCurrentTextureId() {
        return frameBuffers[currentFrameIndex].getTextureId();
    }

    public int getPreviousTextureId() {
        // Avoid a black startup frame before both temporal samples exist.
        return manager.getRealFramesCaptured() < 2
                ? getCurrentTextureId() : frameBuffers[1 - currentFrameIndex].getTextureId();
    }

    @Override
    public void onPreRender(RenderTarget readBuffer, RenderTarget ignored) {
        if (!manager.isActive() || manager.isGeneratedFrame()) return;

        int width = renderer.surfaceWidth;
        int height = renderer.surfaceHeight;
        if (width <= 0 || height <= 0) return;
        ensureFrameBuffers(width, height);

        currentFrameIndex = 1 - currentFrameIndex;
        copyToBuffer(readBuffer, frameBuffers[currentFrameIndex], width, height);
        manager.onFrameCaptured();

        int current = getCurrentTextureId();
        int previous = getPreviousTextureId();
        if (current == 0 || previous == 0 || manager.getRealFramesCaptured() < 2) return;
        runMotionEstimation(current, previous, width, height);
    }

    private void ensureFrameBuffers(int width, int height) {
        if (frameBuffers[0].getWidth() == width && frameBuffers[0].getHeight() == height
                && frameBuffers[0].isComplete() && frameBuffers[1].isComplete()) return;

        destroyNativeEngine();
        for (RenderTarget target : frameBuffers) {
            target.setFormat(GLES20.GL_RGBA);
            target.setMinFilter(GLES20.GL_LINEAR);
            target.setMagFilter(GLES20.GL_LINEAR);
            target.allocateFramebuffer(width, height);
            if (!target.isComplete() || target.getTextureId() == 0) {
                throw new IllegalStateException("Apex history framebuffer is incomplete");
            }
        }
        currentFrameIndex = 0;
    }

    private void copyToBuffer(RenderTarget source, RenderTarget destination, int width, int height) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, source.getFramebuffer());
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, destination.getTextureId());
        GLES20.glCopyTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private void runMotionEstimation(int current, int previous, int width, int height) {
        boolean processed = false;
        if (ENABLE_NATIVE_APEX && !nativeBackendFailed && ApexNative.isAvailable()) {
            try {
                if (nativeEngine == 0) {
                    nativeEngine = ApexNative.nativeCreateEngineGLES(width, height);
                    applyNativeSettings();
                }
                if (nativeEngine != 0) {
                    int[] outputTexture = new int[1];
                    processed = ApexNative.nativeProcessFrameGLES(
                            nativeEngine, current, previous, outputTexture);
                    if (processed && outputTexture[0] != 0) {
                        motionVectorTexture = outputTexture[0];
                        motionVectorOwnedByNative = true;
                    }
                }
            }
            catch (Throwable error) {
                nativeBackendFailed = true;
                destroyNativeEngine();
                Log.w(TAG, "Apex native processing failed; switching to GLES compute", error);
            }
        }
        if (!processed) runComputeFallback(current, previous, width, height);
    }

    private void applyNativeSettings() {
        if (nativeEngine == 0) return;
        try {
            ApexNative.nativeSetEnabled(nativeEngine, true);
            ApexNative.nativeSetQuality(nativeEngine, quality);
            ApexNative.nativeSetTargetFPS(nativeEngine, manager.getTargetFPS());
            ApexNative.nativeSetSharpenAmount(nativeEngine, stability);
        }
        catch (Throwable error) {
            nativeBackendFailed = true;
            Log.w(TAG, "Unable to configure Apex native engine", error);
        }
    }

    private void runComputeFallback(int current, int previous, int width, int height) {
        if (motionVectorOwnedByNative) {
            motionVectorTexture = 0;
            motionVectorHistoryTexture = 0;
            motionVectorWidth = 0;
            motionVectorHeight = 0;
            motionVectorOwnedByNative = false;
        }
        // Three deliberately distinct profiles: Fast estimates motion at 1/8
        // size, Balanced at 1/4, and Quality at 1/2.  The former six labels
        // shared these same quality tiers and therefore felt nearly identical.
        int scale = quality == 0 ? 8 : quality == 1 ? 4 : 2;
        int mvWidth = Math.max(1, width / scale);
        int mvHeight = Math.max(1, height / scale);
        ensureMotionVectorTextures(mvWidth, mvHeight);

        int oldHistory = motionVectorHistoryTexture;
        motionVectorHistoryTexture = motionVectorTexture;
        motionVectorTexture = oldHistory;

        computeMaterial.use(quality);
        if (computeMaterial.programId == 0) return;
        GLES20.glActiveTexture(GLES20.GL_TEXTURE4);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, current);
        GLES31.glUniform1i(GLES31.glGetUniformLocation(computeMaterial.programId, "currFrame"), 4);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE5);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, previous);
        GLES31.glUniform1i(GLES31.glGetUniformLocation(computeMaterial.programId, "prevFrame"), 5);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE6);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, motionVectorHistoryTexture);
        GLES31.glUniform1i(GLES31.glGetUniformLocation(computeMaterial.programId,
                "mvHistoryTexture"), 6);
        GLES31.glBindImageTexture(0, motionVectorTexture, 0, false, 0,
                GLES31.GL_WRITE_ONLY, GLES31.GL_RGBA16F);
        GLES31.glDispatchCompute((mvWidth + 15) / 16, (mvHeight + 7) / 8, 1);
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    }

    private void ensureMotionVectorTextures(int width, int height) {
        if (motionVectorTexture != 0
                && (motionVectorWidth != width || motionVectorHeight != height)) {
            GLES20.glDeleteTextures(2,
                    new int[]{motionVectorTexture, motionVectorHistoryTexture}, 0);
            motionVectorTexture = 0;
            motionVectorHistoryTexture = 0;
        }
        if (motionVectorTexture != 0) return;

        int[] textures = new int[2];
        GLES20.glGenTextures(2, textures, 0);
        int[] framebuffer = new int[1];
        GLES20.glGenFramebuffers(1, framebuffer, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer[0]);
        for (int texture : textures) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES31.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, GLES31.GL_RGBA16F, width, height);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, texture, 0);
            GLES20.glClearColor(0, 0, 0, 0);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glDeleteFramebuffers(1, framebuffer, 0);
        motionVectorTexture = textures[0];
        motionVectorHistoryTexture = textures[1];
        motionVectorWidth = width;
        motionVectorHeight = height;
    }

    public void resetGLResources() {
        releaseGLResources();
        nativeBackendFailed = false;
        manager.resetTimingState();
        super.destroy();
    }

    private void destroyNativeEngine() {
        if (nativeEngine == 0) return;
        try {
            ApexNative.nativeDestroyEngine(nativeEngine);
        }
        catch (Throwable ignored) {
        }
        nativeEngine = 0;
    }

    private void releaseGLResources() {
        destroyNativeEngine();
        computeMaterial.destroy();
        for (RenderTarget target : frameBuffers) target.destroy();
        if (motionVectorTexture != 0 && !motionVectorOwnedByNative) {
            GLES20.glDeleteTextures(2,
                    new int[]{motionVectorTexture, motionVectorHistoryTexture}, 0);
        }
        motionVectorTexture = 0;
        motionVectorHistoryTexture = 0;
        motionVectorWidth = 0;
        motionVectorHeight = 0;
        motionVectorOwnedByNative = false;
        currentFrameIndex = 0;
    }

    @Override
    public void destroy() {
        manager.setEnabled(false);
        releaseGLResources();
        super.destroy();
    }
}
