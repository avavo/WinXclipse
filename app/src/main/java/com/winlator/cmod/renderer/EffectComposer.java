package com.winlator.cmod.renderer;

import android.opengl.GLES20;
import android.util.Log;

import com.winlator.cmod.renderer.effects.Effect;
import com.winlator.cmod.renderer.effects.FSREasuEffect;
import com.winlator.cmod.renderer.effects.ToonEffect;
import com.winlator.cmod.renderer.material.ShaderMaterial;

import java.util.ArrayList;
import java.util.List;

public class EffectComposer {
    // Constants
    private static final String TAG = "EffectComposer";
    private boolean isRendering = false;

    // Instance fields
    private final List<Effect> effects = new ArrayList<>();
    private RenderTarget readBuffer;
    private RenderTarget writeBuffer;
    private RenderTarget sceneBuffer;
    private int bufferWidth;
    private int bufferHeight;
    private int sceneBufferWidth;
    private int sceneBufferHeight;
    /** Upscale factor for the FSR scene buffer (1.5/1.7/2.0); <= 1 disables. */
    private volatile float sceneScale = 0.0f;
    private final GLRenderer renderer;

    // Constructor
    public EffectComposer(GLRenderer renderer) {
        this.renderer = renderer;
//        Log.d(TAG, "EffectComposer created");
    }

    /**
     * Enables FSR1-style upscaling: the X server content is composited into a
     * scene buffer at display/factor resolution and the first effect
     * (FSREasuEffect) upscales it to the display, where RCAS then runs.
     * Must be called before effects are added. A factor <= 1 disables it.
     */
    public synchronized void setSceneScale(float factor) {
        this.sceneScale = factor > 1.0001f ? factor : 0.0f;
    }

    public synchronized boolean isSceneUpscale() {
        return sceneScale > 0.0f;
    }

    // Initializes the buffers if they are not already initialized,
    // reallocating them whenever the surface size changes.
    private void initBuffers() {
//        Log.d(TAG, "initBuffers() called");

        int width = renderer.getSurfaceWidth();
        int height = renderer.getSurfaceHeight();

        if (readBuffer != null && writeBuffer != null
                && bufferWidth == width && bufferHeight == height) {
            initSceneBuffer(width, height);
            return;
        }

        releaseBuffers();

        readBuffer = new RenderTarget();
        readBuffer.allocateFramebuffer(width, height);

        writeBuffer = new RenderTarget();
        writeBuffer.allocateFramebuffer(width, height);

        bufferWidth = width;
        bufferHeight = height;
//        Log.d(TAG, "Initialized buffers with size: " + width + "x" + height);
        initSceneBuffer(width, height);
    }

    /**
     * Allocates the reduced scene buffer for FSR upscaling. The scene buffer
     * is display/factor, so the chosen mode (1.5x/1.7x/2.0x) directly sets
     * the internal rendering resolution and the EASU pass receives true
     * low-resolution input instead of an already bilinear-stretched image.
     */
    private void initSceneBuffer(int surfaceWidth, int surfaceHeight) {
        float factor = sceneScale;
        if (factor <= 1.0001f) {
            releaseSceneBuffer();
            return;
        }

        int sceneW = Math.max(1, Math.round(surfaceWidth / factor));
        int sceneH = Math.max(1, Math.round(surfaceHeight / factor));

        if (sceneBuffer != null && sceneBufferWidth == sceneW && sceneBufferHeight == sceneH) {
            return;
        }

        releaseSceneBuffer();
        sceneBuffer = new RenderTarget();
        sceneBuffer.allocateFramebuffer(sceneW, sceneH);
        sceneBufferWidth = sceneW;
        sceneBufferHeight = sceneH;
        Log.i(TAG, "FSR scene buffer: " + sceneW + "x" + sceneH
                + " (factor " + factor + ", display " + surfaceWidth + "x" + surfaceHeight + ")");
    }

    private void releaseSceneBuffer() {
        if (sceneBuffer != null) {
            sceneBuffer.destroy();
            sceneBuffer = null;
        }
        sceneBufferWidth = 0;
        sceneBufferHeight = 0;
    }

    private void releaseBuffers() {
        if (readBuffer != null) {
            readBuffer.destroy();
            readBuffer = null;
        }
        if (writeBuffer != null) {
            writeBuffer.destroy();
            writeBuffer = null;
        }
        releaseSceneBuffer();
    }

    public synchronized void addEffect(Effect effect) {
        if (!effects.contains(effect)) {
            effects.add(effect);
//            Log.d(TAG, "Effect added: " + effect.getClass().getSimpleName());
        } else {
//            Log.d(TAG, "Effect already present: " + effect.getClass().getSimpleName());
        }
        // Move this call to the end of a batch effect addition or modification to prevent immediate rendering
        renderer.xServerView.requestRender();
    }



    // Gets an effect by its class type
    public synchronized <T extends Effect> T getEffect(Class<T> effectClass) {
//        Log.d(TAG, "getEffect() called for: " + effectClass.getSimpleName());

        for (Effect effect : effects) {
            if (effect.getClass() == effectClass) {
//                Log.d(TAG, "Effect found: " + effectClass.getSimpleName());
                return effectClass.cast(effect);
            }
        }
//        Log.d(TAG, "Effect not found: " + effectClass.getSimpleName());
        return null;
    }

    // Checks if there are effects present
    public synchronized boolean hasEffects() {
        boolean hasEffects = !effects.isEmpty();
//        Log.d(TAG, "hasEffects() called. Effects present: " + hasEffects);
        return hasEffects;
    }

    // Removes a specific effect from the composer
    public synchronized void removeEffect(Effect effect) {
        if (effects.remove(effect)) {
//            Log.d(TAG, "Effect removed: " + effect.getClass().getSimpleName());
        } else {
//            Log.d(TAG, "Effect not found for removal: " + effect.getClass().getSimpleName());
        }
        renderer.xServerView.requestRender();
    }

    // Renders all the effects in the composer
    public synchronized void render() {
        // Check for recursive rendering
        if (isRendering) {
//            Log.d(TAG, "Render already in progress, skipping.");
            return;
        }

        isRendering = true; // Set flag to true

//        Log.d(TAG, "render() called");

        initBuffers();

        boolean useScene = sceneScale > 0.0f && sceneBuffer != null;

        // Set up framebuffer if there are effects to render
        if (hasEffects()) {
            if (useScene) {
                // Scene pass: composite the X server content at reduced resolution.
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sceneBuffer.getFramebuffer());
                renderer.setRenderTargetSize(sceneBufferWidth, sceneBufferHeight);
            } else {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, readBuffer.getFramebuffer());
            }
//            Log.d(TAG, "Binding to readBuffer framebuffer: " + readBuffer.getFramebuffer());
        } else {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
//            Log.d(TAG, "Binding to default framebuffer (0)");
        }

        // Draw the initial frame
        renderer.drawFrame();
//        Log.d(TAG, "Initial frame drawn");

        // Iterate through each effect and render it
        for (Effect effect : effects) {
            boolean renderToScreen = effect == effects.get(effects.size() - 1);
            int targetFramebuffer = renderToScreen ? 0 : writeBuffer.getFramebuffer();

            // Restore full-size render target for effect passes.
            if (useScene) renderer.setRenderTargetSize(bufferWidth, bufferHeight);

            // Bind appropriate framebuffer
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targetFramebuffer);
//            Log.d(TAG, "Binding to " + (renderToScreen ? "screen" : "writeBuffer") + " framebuffer: " + targetFramebuffer);

            GLES20.glViewport(0, 0, renderer.surfaceWidth, renderer.surfaceHeight);
            renderer.setViewportNeedsUpdate(true);
//            Log.d(TAG, "Viewport updated to size: " + renderer.surfaceWidth + "x" + renderer.surfaceHeight);

            // Clear the buffer
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
//            Log.d(TAG, "Framebuffer cleared");

            // Render the effect
            renderEffect(effect, useScene && effect == effects.get(0));
//            Log.d(TAG, "Effect rendered: " + effect.getClass().getSimpleName());

            // Swap the read and write buffers
            swapBuffers();
//            Log.d(TAG, "Buffers swapped");
        }

        if (useScene) renderer.setRenderTargetSize(bufferWidth, bufferHeight);

        isRendering = false; // Reset flag after rendering
    }

    // Renders a single effect
    private void renderEffect(Effect effect, boolean fromSceneBuffer) {
//        Log.d(TAG, "renderEffect() called");

        ShaderMaterial material = effect.getMaterial();
        if (material == null) {
//            Log.e(TAG, "Material is null for effect: " + effect.getClass().getSimpleName());
            return;
        }

        material.use();
//        Log.d(TAG, "ShaderMaterial used: " + material.getClass().getSimpleName());

        // Bind the quad vertices to the shader program
        renderer.getQuadVertices().bind(material.programId);
//        Log.d(TAG, "Quad vertices bound to program ID: " + material.programId);

        // Set uniform values
        if (fromSceneBuffer && effect instanceof FSREasuEffect) {
            FSREasuEffect easu = (FSREasuEffect) effect;
            int screenW = renderer.getXScreenWidth();
            int screenH = renderer.getXScreenHeight();
            // Aspect-fit rect of the X screen on the display (letterbox preserved).
            float aspect = Math.min(renderer.surfaceWidth / (float) screenW,
                    renderer.surfaceHeight / (float) screenH);
            int dstW = Math.round(screenW * aspect);
            int dstH = Math.round(screenH * aspect);
            int dstOffX = (renderer.surfaceWidth - dstW) / 2;
            int dstOffYGl = (renderer.surfaceHeight - dstH) / 2;
            // V-down offset to match the shader's coordinate space.
            int dstOffYDown = renderer.surfaceHeight - dstOffYGl - dstH;
            easu.setMapping(sceneBufferWidth, sceneBufferHeight,
                    0, 0, sceneBufferWidth, sceneBufferHeight,
                    dstOffX, dstOffYDown, dstW, dstH, renderer.surfaceHeight);
            material.setUniformVec2("resolution", renderer.surfaceWidth, renderer.surfaceHeight);
            material.setUniformVec4("uCon0", easu.getCon0());
            material.setUniformVec4("uDstRect", easu.getDstRect());
            material.setUniformFloat("uOutH", easu.getOutHeight());
            material.setUniformVec2("uTexel", 1.0f / sceneBufferWidth, 1.0f / sceneBufferHeight);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sceneBuffer.getTextureId());
            material.setUniformInt("screenTexture", 0);
        } else {
            material.setUniformVec2("resolution", renderer.surfaceWidth, renderer.surfaceHeight);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,
                    fromSceneBuffer ? sceneBuffer.getTextureId() : readBuffer.getTextureId());
            material.setUniformInt("screenTexture", 0);
        }
//        Log.d(TAG, "Uniforms set: resolution=" + renderer.surfaceWidth + "x" + renderer.surfaceHeight + ", screenTexture=" + readBuffer.getTextureId());

        // Draw the quad
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, renderer.quadVertices.count());
//        Log.d(TAG, "Quad drawn");

        // Unbind the texture
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
//        Log.d(TAG, "Texture unbound");
    }

    // Swaps the read and write buffers
    private void swapBuffers() {
        RenderTarget tmp = writeBuffer;
        writeBuffer = readBuffer;
        readBuffer = tmp;
//        Log.d(TAG, "swapBuffers() called. Buffers swapped.");
    }

    // Add a method to add the ToonEffect
    public synchronized void toggleToonEffect() {
        ToonEffect toonEffect = getEffect(ToonEffect.class);
        if (toonEffect != null) {
            removeEffect(toonEffect); // Remove if already present
            Log.d(TAG, "ToonEffect removed");
        } else {
            addEffect(new ToonEffect()); // Add if not present
            Log.d(TAG, "ToonEffect added");
        }
        renderer.xServerView.requestRender();
    }

}
