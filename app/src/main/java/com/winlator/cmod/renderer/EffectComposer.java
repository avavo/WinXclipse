package com.winlator.cmod.renderer;

import android.opengl.GLES20;
import android.util.Log;

import com.winlator.cmod.XrActivity;
import com.winlator.cmod.renderer.effects.Effect;
import com.winlator.cmod.renderer.effects.FSREasuEffect;
import com.winlator.cmod.renderer.effects.FSREffect;
import com.winlator.cmod.renderer.effects.ToonEffect;
import com.winlator.cmod.renderer.material.ShaderMaterial;

import java.util.ArrayList;
import java.util.List;

public class EffectComposer {
    // Constants
    private static final String TAG = "EffectComposer";
    private boolean isRendering = false;

    public boolean isRendering() { return isRendering; }

    // Instance fields
    private final List<Effect> effects = new ArrayList<>();
    private RenderTarget readBuffer;
    private RenderTarget writeBuffer;
    private RenderTarget sceneBuffer;
    private int bufferWidth;
    private int bufferHeight;
    private int sceneBufferWidth;
    private int sceneBufferHeight;
    /** Letterboxed content rect inside the scene buffer, as drawn by the
     * last scene pass (GL V-up coords; captured from viewTransformation). */
    private int sceneViewOffsetX;
    private int sceneViewOffsetYGl;
    private int sceneViewWidth;
    private int sceneViewHeight;
    /** Upscale factor for the FSR scene buffer (1.5/1.7/2.0); <= 1 disables. */
    private volatile float sceneScale = 0.0f;
    /** When set, the scene buffer uses this exact size instead of deriving it
     *  from sceneScale. Used when the GUEST already renders at a reduced
     *  resolution (X screen scaled by the FSR preset), so the composer must
     *  not downscale again. */
    private volatile int sceneTargetWidth;
    private volatile int sceneTargetHeight;
    /** Master switch: an EASU pass is active and a scene buffer is wanted. */
    private volatile boolean sceneUpscale = false;
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
        configureScene(factor > 1.0001f, factor > 1.0001f ? factor : 0.0f, 0, 0);
    }

    public synchronized boolean isSceneUpscale() {
        return sceneUpscale;
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
        if (!sceneUpscale) {
            releaseSceneBuffer();
            return;
        }

        int sceneW;
        int sceneH;
        if (sceneTargetWidth > 0 && sceneTargetHeight > 0) {
            // Explicit guest-resolution mode: the guest already renders at
            // this size, so never downscale it again.
            sceneW = Math.min(sceneTargetWidth, Math.max(1, surfaceWidth));
            sceneH = Math.min(sceneTargetHeight, Math.max(1, surfaceHeight));
        } else {
            float factor = sceneScale;
            if (factor <= 1.0001f) {
                releaseSceneBuffer();
                return;
            }
            sceneW = Math.max(1, Math.round(surfaceWidth / factor));
            sceneH = Math.max(1, Math.round(surfaceHeight / factor));
        }

        if (sceneBuffer != null && sceneBufferWidth == sceneW && sceneBufferHeight == sceneH) {
            return;
        }

        releaseSceneBuffer();
        sceneBuffer = new RenderTarget();
        sceneBuffer.allocateFramebuffer(sceneW, sceneH);
        sceneBufferWidth = sceneW;
        sceneBufferHeight = sceneH;
        Log.i(TAG, "FSR scene buffer: " + sceneW + "x" + sceneH
                + (sceneTargetWidth > 0 ? " (guest render scale)" : " (factor " + sceneScale + ")")
                + ", display " + surfaceWidth + "x" + surfaceHeight);
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
            // EASU must always run first: it consumes the reduced-resolution
            // scene buffer; every other effect works on full-size buffers.
            if (effect instanceof FSREasuEffect) effects.add(0, effect);
            else effects.add(effect);
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

    /**
     * Swaps the FSR passes and the scene scale in a single synchronized step
     * so a render can never observe a half-applied state (old RCAS consuming
     * the low-res scene buffer without EASU, or mapping with the wrong scale).
     * A factor <= 1 disables the scene buffer.
     */
    public synchronized void setFsrEffects(FSREasuEffect easu, FSREffect rcas, float sceneScaleFactor) {
        configureScene(easu != null && sceneScaleFactor > 1.0001f,
                sceneScaleFactor > 1.0001f ? sceneScaleFactor : 0.0f, 0, 0);
        replaceFsrPasses(easu, rcas);
    }

    /**
     * FSR with an explicit scene resolution: used when the guest renders at
     * a scaled-down X screen (real render scaling). The scene buffer matches
     * the guest output 1:1 and EASU performs the single upscale to display.
     */
    public synchronized void setFsrEffects(FSREasuEffect easu, FSREffect rcas, int targetWidth, int targetHeight) {
        configureScene(easu != null && targetWidth > 0 && targetHeight > 0,
                0.0f, targetWidth, targetHeight);
        replaceFsrPasses(easu, rcas);
    }

    private void configureScene(boolean enabled, float factor, int width, int height) {
        sceneUpscale = enabled;
        sceneScale = factor;
        sceneTargetWidth = width;
        sceneTargetHeight = height;
    }

    private void replaceFsrPasses(FSREasuEffect easu, FSREffect rcas) {
        for (int i = effects.size() - 1; i >= 0; i--) {
            Effect e = effects.get(i);
            if (e instanceof FSREasuEffect || e instanceof FSREffect) effects.remove(i);
        }
        if (easu != null) effects.add(0, easu);
        if (rcas != null) {
            if (easu != null) effects.add(1, rcas);
            else effects.add(rcas);
        }
        renderer.xServerView.requestRender();
    }

    /**
     * Drops all allocated buffers. Must be called when the EGL context is
     * (re)created: the old FBOs/textures belong to the destroyed context and
     * reusing them would render black until the surface size changes.
     */
    public synchronized void invalidateBuffers() {
        releaseBuffers();
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

        if (readBuffer == null || writeBuffer == null
                || !readBuffer.isComplete() || !writeBuffer.isComplete()
                || (sceneUpscale && (sceneBuffer == null || !sceneBuffer.isComplete()))) {
            // Preserve compatibility on drivers that reject offscreen targets:
            // render normally instead of leaving a permanent black screen.
            renderer.drawScene(false);
            isRendering = false;
            return;
        }

        boolean useScene = sceneUpscale && sceneBuffer != null;

        // Set up framebuffer if there are effects to render
        if (hasEffects()) {
            if (useScene) {
                // Scene pass: composite the X server content at reduced resolution.
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sceneBuffer.getFramebuffer());
                renderer.setRenderTargetSize(sceneBufferWidth, sceneBufferHeight);
                // Snapshot the exact rect the scene pass draws into, so EASU
                // samples the content region instead of guessing it.
                ViewTransformation vt = renderer.viewTransformation;
                sceneViewOffsetX = vt.viewOffsetX;
                sceneViewOffsetYGl = vt.viewOffsetY;
                sceneViewWidth = vt.viewWidth;
                sceneViewHeight = vt.viewHeight;
            } else {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, readBuffer.getFramebuffer());
            }
//            Log.d(TAG, "Binding to readBuffer framebuffer: " + readBuffer.getFramebuffer());
        } else {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
//            Log.d(TAG, "Binding to default framebuffer (0)");
        }

        // Draw the initial frame directly into the bound offscreen buffer.
        // Must not call GLRenderer.drawFrame() which would dispatch back to
        // the composer and cause a duplicate full-screen draw.
        renderer.drawScene(false);
//        Log.d(TAG, "Initial frame drawn");

        // Effects before (and including) the first FSREasuEffect read the raw
        // scene buffer; everything after it runs on the ping-pong buffers at
        // display resolution. Keying this on the EASU position instead of
        // index 0 keeps the pipeline correct regardless of effect ordering
        // (e.g. HDREffect added before or after the FSR passes).
        int sceneConsumer = -1;
        if (useScene && !effects.isEmpty()) {
            sceneConsumer = 0;
            for (int i = 0; i < effects.size(); i++) {
                if (effects.get(i) instanceof FSREasuEffect) {
                    sceneConsumer = i;
                    break;
                }
            }
        }

        // Every effect draws a full-screen replacement quad. Source-over
        // blending is both unnecessary and expensive for FSR's EASU/RCAS
        // passes on tile GPUs.
        if (!effects.isEmpty()) GLES20.glDisable(GLES20.GL_BLEND);

        // Iterate through each effect and render it
        for (int i = 0; i < effects.size(); i++) {
            Effect effect = effects.get(i);
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
            renderEffect(effect, i <= sceneConsumer);
//            Log.d(TAG, "Effect rendered: " + effect.getClass().getSimpleName());

            // Swap the read and write buffers
            swapBuffers();
//            Log.d(TAG, "Buffers swapped");
        }

        if (!effects.isEmpty()) GLES20.glEnable(GLES20.GL_BLEND);

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
            int srcOffX;
            int srcOffYDown;
            int srcViewW;
            int srcViewH;
            int dstOffX;
            int dstOffYDown;
            int dstW;
            int dstH;
            // XR immersive draws the windows stretched across the whole
            // target (renderWindows(forceFullscreen=true)), same as the
            // fullscreen toggle.
            if (renderer.isFullscreen()
                    || (XrActivity.isEnabled(null) && XrActivity.getInstance().getImmersive())) {
                // Fullscreen stretches the X screen across the whole target.
                srcOffX = 0;
                srcOffYDown = 0;
                srcViewW = sceneBufferWidth;
                srcViewH = sceneBufferHeight;
                dstOffX = 0;
                dstOffYDown = 0;
                dstW = renderer.surfaceWidth;
                dstH = renderer.surfaceHeight;
            } else {
                // Source: exact letterboxed content rect of the scene pass
                // (viewTransformation captured while the scene target was
                // bound). GL V-up offsets are flipped to the shader's V-down.
                srcOffX = sceneViewOffsetX;
                srcViewW = sceneViewWidth;
                srcViewH = sceneViewHeight;
                srcOffYDown = sceneBufferHeight - sceneViewOffsetYGl - sceneViewHeight;
                // Destination: viewTransformation was restored to display size
                // before this pass, so it holds the display content rect.
                ViewTransformation vt = renderer.viewTransformation;
                dstOffX = vt.viewOffsetX;
                dstW = vt.viewWidth;
                dstH = vt.viewHeight;
                dstOffYDown = renderer.surfaceHeight - vt.viewOffsetY - vt.viewHeight;
            }
            easu.setMapping(sceneBufferWidth, sceneBufferHeight,
                    srcOffX, srcOffYDown, srcViewW, srcViewH,
                    dstOffX, dstOffYDown, dstW, dstH, renderer.surfaceHeight);
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
