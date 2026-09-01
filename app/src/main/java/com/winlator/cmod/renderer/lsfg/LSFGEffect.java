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
    // Matches the stable 0.9.5 APK: libapex ships for compatibility/reference,
    // but the runtime always uses the GLES compute path.
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
    private boolean usingFallbackAfterNativeFailure;
    /**
     * glGetError after a compute dispatch may serialize the mobile GPU queue.
     * Validate the first dispatch and then sample periodically instead of
     * forcing that synchronization on every real game frame.
     */
    private int fallbackValidationCountdown;

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
        this.quality = Math.max(0, Math.min(3, quality));
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

    public void setBackend(int backend) {
        if (manager.getBackendMode() == LSFGManager.BACKEND_GLES) return;
        destroyNativeEngine();
        nativeBackendFailed = false;
        usingFallbackAfterNativeFailure = false;
        fallbackValidationCountdown = 0;
        manager.setBackendMode(LSFGManager.BACKEND_GLES);
    }

    public void setLowLatencyMode(boolean enabled) {
        manager.setLowLatencyMode(enabled);
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
        int backend = manager.getBackendMode();
        boolean nativeRequested = ENABLE_NATIVE_APEX && (backend == LSFGManager.BACKEND_NATIVE
                || backend == LSFGManager.BACKEND_AUTO);
        if (nativeRequested && !nativeBackendFailed && ApexNative.isAvailable()) {
            try {
                if (nativeEngine == 0) {
                    nativeEngine = ApexNative.nativeCreateEngineGLES(width, height);
                    if (nativeEngine == 0)
                        throw new IllegalStateException("libapex could not create its GLES engine");
                    applyNativeSettings();
                }
                if (nativeEngine != 0) {
                    int[] outputTexture = new int[1];
                    processed = ApexNative.nativeProcessFrameGLES(
                            nativeEngine, current, previous, outputTexture);
                    if (processed && outputTexture[0] != 0) {
                        motionVectorTexture = outputTexture[0];
                        motionVectorOwnedByNative = true;
                        usingFallbackAfterNativeFailure = false;
                        manager.reportBackendReady("libapex");
                    }
                    else throw new IllegalStateException("libapex rejected the frame");
                }
            }
            catch (Throwable error) {
                nativeBackendFailed = true;
                destroyNativeEngine();
                String reason = shortError(error);
                if (backend == LSFGManager.BACKEND_NATIVE) {
                    manager.reportBackendFailure(reason);
                    throw new IllegalStateException(reason, error);
                }
                usingFallbackAfterNativeFailure = true;
                manager.reportBackendFallback(reason);
                Log.w(TAG, "Apex native processing failed; switching to GLES compute", error);
            }
        }
        else if (backend == LSFGManager.BACKEND_NATIVE && !ApexNative.isAvailable()) {
            String reason = "libapex is not available in this APK/ABI";
            manager.reportBackendFailure(reason);
            throw new IllegalStateException(reason);
        }
        else if (backend == LSFGManager.BACKEND_AUTO && !ApexNative.isAvailable()) {
            usingFallbackAfterNativeFailure = true;
            manager.reportBackendFallback("libapex unavailable");
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
            manager.reportBackendFailure(shortError(error));
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
        // Four deliberately distinct profiles. Ultra keeps half-resolution
        // vectors and adds the widest refinement pass; it is intentionally the
        // expensive option for users who prefer fewer warps over throughput.
        int scale = quality == 0 ? 8 : quality == 1 ? 4 : quality == 2 ? 3 : 2;
        int mvWidth = Math.max(1, width / scale);
        int mvHeight = Math.max(1, height / scale);
        ensureMotionVectorTextures(mvWidth, mvHeight);

        int oldHistory = motionVectorHistoryTexture;
        motionVectorHistoryTexture = motionVectorTexture;
        motionVectorTexture = oldHistory;

        boolean validateDispatch = fallbackValidationCountdown <= 0;
        if (validateDispatch) {
            while (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
                // Discard stale errors so a sampled failure describes this dispatch.
            }
        }
        if (!computeMaterial.use(quality)) {
            String reason = computeMaterial.getLastError();
            manager.reportBackendFailure(reason);
            throw new IllegalStateException(reason);
        }
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
        if (validateDispatch) {
            int error = GLES20.glGetError();
            if (error != GLES20.GL_NO_ERROR) {
                String reason = "GLES motion dispatch failed: 0x" + Integer.toHexString(error);
                manager.reportBackendFailure(reason);
                throw new IllegalStateException(reason);
            }
            fallbackValidationCountdown = 120;
        }
        else fallbackValidationCountdown--;
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        if (usingFallbackAfterNativeFailure)
            manager.reportBackendFallback("libapex failed; GLES is active");
        else manager.reportBackendReady("GLES");
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
        int error = GLES20.glGetError();
        if (motionVectorTexture == 0 || motionVectorHistoryTexture == 0
                || error != GLES20.GL_NO_ERROR) {
            String reason = "Motion-vector texture allocation failed"
                    + (error == GLES20.GL_NO_ERROR ? "" : ": 0x" + Integer.toHexString(error));
            manager.reportBackendFailure(reason);
            throw new IllegalStateException(reason);
        }
    }

    public void resetGLResources() {
        releaseGLResources();
        nativeBackendFailed = false;
        usingFallbackAfterNativeFailure = false;
        fallbackValidationCountdown = 0;
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
        fallbackValidationCountdown = 0;
    }

    private static String shortError(Throwable error) {
        String message = error == null ? null : error.getMessage();
        if (message == null || message.trim().isEmpty())
            message = error == null ? "Unknown native backend failure"
                    : error.getClass().getSimpleName();
        return message.length() > 96 ? message.substring(0, 96) : message;
    }

    @Override
    public void destroy() {
        manager.setEnabled(false);
        releaseGLResources();
        super.destroy();
    }
}
