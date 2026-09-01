package com.winlator.cmod.renderer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.EGL14;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Choreographer;

import com.winlator.cmod.BuildConfig;
import com.winlator.cmod.R;
import com.winlator.cmod.XrActivity;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.math.XForm;
import com.winlator.cmod.renderer.material.CursorMaterial;
import com.winlator.cmod.renderer.material.ShaderMaterial;
import com.winlator.cmod.renderer.material.WindowMaterial;
import com.winlator.cmod.renderer.lsfg.LSFGEffect;
import com.winlator.cmod.renderer.lsfg.LSFGManager;
import com.winlator.cmod.widget.WinlatorHUD;
import com.winlator.cmod.widget.XServerView;
import com.winlator.cmod.xserver.Bitmask;
import com.winlator.cmod.xserver.Cursor;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.WindowAttributes;
import com.winlator.cmod.xserver.WindowManager;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;

import java.util.ArrayList;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class GLRenderer implements GLSurfaceView.Renderer, WindowManager.OnWindowModificationListener,
        Pointer.OnPointerMotionListener, Choreographer.FrameCallback {
    public final XServerView xServerView;
    private final XServer xServer;
    public final VertexAttribute quadVertices = new VertexAttribute("position", 2);
    private final float[] tmpXForm1 = XForm.getInstance();
    private final float[] tmpXForm2 = XForm.getInstance();
    private final CursorMaterial cursorMaterial = new CursorMaterial();
    private final WindowMaterial windowMaterial = new WindowMaterial();
    public final ViewTransformation viewTransformation = new ViewTransformation();
    private final Drawable rootCursorDrawable;
    private final ArrayList<RenderableWindow> renderableWindows = new ArrayList<>();
    private String forceFullscreenWMClass = null;
    private boolean fullscreen = false;
    private boolean toggleFullscreen = false;
    public boolean viewportNeedsUpdate = true;
    private boolean cursorVisible = true;
    private boolean rootWindowDownsized = false;
    private boolean screenOffsetYRelativeToCursor = false;
    private String[] unviewableWMClasses = null;
    private float magnifierZoom = 1.0f;
    private boolean magnifierEnabled = true;
    public int surfaceWidth;
    public int surfaceHeight;
    // Size of the target the scene is currently composited into. Equals the
    // surface size except during the reduced-resolution FSR scene pass.
    private int renderTargetWidth;
    private int renderTargetHeight;
    private final EffectComposer effectComposer;
    private final LSFGManager lsfgManager;
    private boolean renderCursorEnabled = true;
    private long lastApexFrameNanos;
    private volatile boolean requestedApexEnabled;
    private volatile int requestedApexQuality = 1;
    private volatile float requestedApexMultiplier;
    private volatile int requestedApexTargetFPS = 60;
    private volatile float requestedApexStability = 0.6f;
    private volatile int requestedApexBackend = LSFGManager.BACKEND_GLES;
    private volatile boolean requestedApexLowLatency;
    private WinlatorHUD winlatorHUD;
    private long apexStatsStartNanos;
    private volatile boolean apexChoreographerRunning;
    private volatile int fpsLimit;
    public static final int TEXTURE_FILTER_NONE = 3;
    private int lastForcedFilter = GLES20.GL_LINEAR;
    private volatile int textureFilterMode;
    private volatile boolean swapRedBlue;

    public GLRenderer(XServerView xServerView, XServer xServer) {
        this.xServerView = xServerView;
        this.xServer = xServer;
        this.lsfgManager = new LSFGManager(this);
        this.effectComposer = new EffectComposer(this);
        rootCursorDrawable = createRootCursorDrawable();

        quadVertices.put(new float[]{
            0.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 0.0f,
            1.0f, 1.0f
        });

        xServer.windowManager.addOnWindowModificationListener(this);
        xServer.pointer.addOnPointerMotionListener(this);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GPUImage.checkIsSupported();

        // Keep the Android compositor tear-free even when the guest uses an
        // uncapped immediate swapchain. This does not cap guest-side FPS.
        try {
            if (EGL14.eglGetCurrentDisplay() != EGL14.EGL_NO_DISPLAY)
                EGL14.eglSwapInterval(EGL14.eglGetCurrentDisplay(), 1);
        }
        catch (RuntimeException ignored) {
        }

        // FBOs/textures from a previous EGL context are dead; force the
        // composer to reallocate against the new context.
        effectComposer.invalidateBuffers();
        LSFGEffect lsfg = effectComposer.getEffect(LSFGEffect.class);
        if (lsfg != null) lsfg.resetGLResources();
        lastApexFrameNanos = 0;

        GLES20.glFrontFace(GLES20.GL_CCW);
        GLES20.glDisable(GLES20.GL_CULL_FACE);

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(false);

        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        if (XrActivity.isEnabled(null)) {
            XrActivity activity = XrActivity.getInstance();
            activity.init();
            width = activity.getWidth();
            height = activity.getHeight();
            GLES20.glViewport(0, 0, width, height);
            magnifierEnabled = false;
        }

        surfaceWidth = width;
        surfaceHeight = height;
        renderTargetWidth = width;
        renderTargetHeight = height;
        viewTransformation.update(width, height, xServer.screenInfo.width, xServer.screenInfo.height);
        viewportNeedsUpdate = true;
    }

    /**
     * Switches the size of the scene render target (and the matching view
     * transformation). Used by EffectComposer to composite the X server
     * content into a reduced-resolution buffer for FSR upscaling.
     */
    public void setRenderTargetSize(int width, int height) {
        if (renderTargetWidth == width && renderTargetHeight == height) return;
        renderTargetWidth = width;
        renderTargetHeight = height;
        viewTransformation.update(width, height, xServer.screenInfo.width, xServer.screenInfo.height);
        viewportNeedsUpdate = true;
    }

    public int getXScreenWidth() {
        return xServer.screenInfo.width;
    }

    public int getXScreenHeight() {
        return xServer.screenInfo.height;
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (lsfgManager.isActive()) {
            long interval = lsfgManager.getOutputFrameIntervalNanos();
            long elapsed = System.nanoTime() - lastApexFrameNanos;
            if (lastApexFrameNanos != 0 && elapsed < interval) {
                long remaining = interval - elapsed;
                try {
                    Thread.sleep(remaining / 1_000_000L, (int)(remaining % 1_000_000L));
                }
                catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        lastApexFrameNanos = System.nanoTime();
        lsfgManager.prepareFrame();

        if (toggleFullscreen) {
            fullscreen = !fullscreen;
            toggleFullscreen = false;
            viewportNeedsUpdate = true;

        }

        drawFrame();
        lsfgManager.onPostDraw();
        updateApexHudStats();
    }

    public void drawFrame() {
        // If any post-processing effects are active, delegate entirely to the
        // composer which renders the scene once into an offscreen buffer. The
        // previous implementation drew the scene to screen and then again into
        // the buffer, doubling GPU work when FSR/HDR was enabled.
        if (effectComposer.hasEffects() && !effectComposer.isRendering()) {
            effectComposer.render();
            if (XrActivity.isEnabled(null) && XrActivity.getInstance().getImmersive()) {
                // XR immersive path still needs controller updates.
                try { XrActivity.getInstance().updateControllers(); } catch (Throwable ignored) {}
            }
            return;
        }
        drawScene(false);
    }

    void drawScene(boolean xrImmersive) {
        boolean xrFrame = false;
        if (!xrImmersive && XrActivity.isEnabled(null)) {
            XrActivity xr = XrActivity.getInstance();
            xrImmersive = xr.getImmersive();
            xrFrame = xr.beginFrame(xrImmersive, xr.getSBS());
        }

        // Update the viewport if necessary
        if (viewportNeedsUpdate && magnifierEnabled) {
            if (fullscreen) {
                GLES20.glViewport(0, 0, renderTargetWidth, renderTargetHeight);
            }
            else {
                GLES20.glViewport(viewTransformation.viewOffsetX, viewTransformation.viewOffsetY, viewTransformation.viewWidth, viewTransformation.viewHeight);
            }
            viewportNeedsUpdate = false;
        }

        // Clear the screen before drawing
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // Apply basic transformations and draw windows
        if (magnifierEnabled) {
            // Apply magnifier transformations if enabled
            float pointerX = 0;
            float pointerY = 0;
            float magnifierZoom = !screenOffsetYRelativeToCursor ? this.magnifierZoom : 1.0f;

            if (magnifierZoom != 1.0f) {
                pointerX = Mathf.clamp(xServer.pointer.getX() * magnifierZoom - xServer.screenInfo.width * 0.5f, 0, xServer.screenInfo.width * Math.abs(1.0f - magnifierZoom));
            }

            if (screenOffsetYRelativeToCursor || magnifierZoom != 1.0f) {
                float scaleY = magnifierZoom != 1.0f ? Math.abs(1.0f - magnifierZoom) : 0.5f;
                float offsetY = xServer.screenInfo.height * (screenOffsetYRelativeToCursor ? 0.25f : 0.5f);
                pointerY = Mathf.clamp(xServer.pointer.getY() * magnifierZoom - offsetY, 0, xServer.screenInfo.height * scaleY);
            }

            XForm.makeTransform(tmpXForm2, -pointerX, -pointerY, magnifierZoom, magnifierZoom, 0);
        } else {
            if (!fullscreen) {
                int pointerY = 0;
                if (screenOffsetYRelativeToCursor) {
                    short halfScreenHeight = (short)(xServer.screenInfo.height / 2);
                    pointerY = Mathf.clamp(xServer.pointer.getY() - halfScreenHeight / 2, 0, halfScreenHeight);
                }

                XForm.makeTransform(tmpXForm2, viewTransformation.sceneOffsetX, viewTransformation.sceneOffsetY - pointerY, viewTransformation.sceneScaleX, viewTransformation.sceneScaleY, 0);

                GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
                GLES20.glScissor(viewTransformation.viewOffsetX, viewTransformation.viewOffsetY, viewTransformation.viewWidth, viewTransformation.viewHeight);
            } else {
                XForm.identity(tmpXForm2);
            }
        }

        // Render windows without effects
        renderWindows(xrImmersive);

        // Render cursor if enabled
        if (renderCursorEnabled && cursorVisible && !rootWindowDownsized) renderCursor();

        // Disable scissor test if magnifier is disabled and not in fullscreen mode
        if (!magnifierEnabled && !fullscreen) {
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        }

        // Finalize XR frame if supported
        if (xrFrame) {
            XrActivity.getInstance().endFrame();
            XrActivity.getInstance().updateControllers();
            xServerView.requestRender();
        }
    }


    @Override
    public void onMapWindow(Window window) {
        lsfgManager.notifySceneChangePending();
        xServerView.queueEvent(this::updateScene);
        xServerView.requestRender();
    }

    @Override
    public void onUnmapWindow(Window window) {
        lsfgManager.notifySceneChangePending();
        xServerView.queueEvent(this::updateScene);
        xServerView.requestRender();
    }

    @Override
    public void onChangeWindowZOrder(Window window) {
        lsfgManager.notifySceneChangePending();
        xServerView.queueEvent(this::updateScene);
        xServerView.requestRender();
    }

    @Override
    public void onUpdateWindowContent(Window window) {
        lsfgManager.notifyRealFramePending();
        xServerView.requestRender();
    }

    @Override
    public void onUpdateWindowGeometry(final Window window, boolean resized) {
        lsfgManager.notifySceneChangePending();
        if (resized) {
            xServerView.queueEvent(this::updateScene);
        }
        else xServerView.queueEvent(() -> updateWindowPosition(window));
        xServerView.requestRender();
    }

    @Override
    public void onUpdateWindowAttributes(Window window, Bitmask mask) {
        if (mask.isSet(WindowAttributes.FLAG_CURSOR)) xServerView.requestRender();
    }

    @Override
    public void onPointerMove(short x, short y) {
        xServerView.requestRender();
    }

    private void renderDrawable(Drawable drawable, int x, int y, ShaderMaterial material) {
        renderDrawable(drawable, x, y, material, false);
    }

    private void renderDrawable(Drawable drawable, int x, int y, ShaderMaterial material, boolean forceFullscreen) {
        if (drawable == null) return;
        synchronized (drawable.renderLock) {
            Texture texture = drawable.getTexture();
            texture.updateFromDrawable(drawable);

            if (forceFullscreen) {
                short newHeight = (short)Math.min(xServer.screenInfo.height, ((float)xServer.screenInfo.width / drawable.width) * drawable.height);
                short newWidth = (short)(((float)newHeight / drawable.height) * drawable.width);
                XForm.set(tmpXForm1, (xServer.screenInfo.width - newWidth) * 0.5f, (xServer.screenInfo.height - newHeight) * 0.5f, newWidth, newHeight);
            }
            else XForm.set(tmpXForm1, x, y, drawable.width, drawable.height);

            XForm.multiply(tmpXForm1, tmpXForm1, tmpXForm2);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.getTextureId());
            if (material == windowMaterial) {
                if (textureFilterMode != TEXTURE_FILTER_NONE) {
                    int filter = textureFilterMode == 1 ? GLES20.GL_NEAREST : GLES20.GL_LINEAR;
                    if (texture.getMagFilter() != filter || texture.getMinFilter() != filter) {
                        texture.setMagFilter(filter);
                        texture.setMinFilter(filter);
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter);
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter);
                    }
                    lastForcedFilter = filter;
                } else if (lastForcedFilter != GLES20.GL_LINEAR
                        && texture.getMagFilter() == lastForcedFilter
                        && texture.getMinFilter() == lastForcedFilter) {
                    texture.setMagFilter(GLES20.GL_LINEAR);
                    texture.setMinFilter(GLES20.GL_LINEAR);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                }
            }
            GLES20.glUniform1i(material.getUniformLocation("texture"), 0);
            GLES20.glUniform1fv(material.getUniformLocation("xform"), tmpXForm1.length, tmpXForm1, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, quadVertices.count());
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }
    }

    private void renderWindows(boolean forceFullscreen) {
        // WindowMaterial always writes alpha=1. Blending every opaque desktop
        // and game pixel only adds bandwidth on tile GPUs such as Xclipse.
        GLES20.glDisable(GLES20.GL_BLEND);
        windowMaterial.use();
        GLES20.glUniform2f(windowMaterial.getUniformLocation("viewSize"), xServer.screenInfo.width, xServer.screenInfo.height);
        GLES20.glUniform1i(windowMaterial.getUniformLocation("swapRB"), swapRedBlue ? 1 : 0);
        quadVertices.bind(windowMaterial.programId);

        boolean singleWindow = forceFullscreen;
        try (XLock lock = xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
            rootWindowDownsized = false;
            if (fullscreen && !renderableWindows.isEmpty()) {
                RenderableWindow root = renderableWindows.get(0);
                if ((root.content.width < xServer.screenInfo.width) || (root.content.height < xServer.screenInfo.height)) {
                    rootWindowDownsized = true;
                    singleWindow = true;
                }
            }
            if (singleWindow && !renderableWindows.isEmpty()) {
                RenderableWindow window = renderableWindows.get(renderableWindows.size() - 1);
                renderDrawable(window.content, window.rootX, window.rootY, windowMaterial, true);
            } else {
                int firstVisible = 0;
                // Everything below the highest opaque full-screen window is
                // guaranteed to be covered. Keep later popups/menus intact.
                for (int i = renderableWindows.size() - 1; i >= 0; i--) {
                    RenderableWindow window = renderableWindows.get(i);
                    if (window.forceFullscreen
                            || (window.rootX <= 0 && window.rootY <= 0
                            && window.rootX + window.content.width >= xServer.screenInfo.width
                            && window.rootY + window.content.height >= xServer.screenInfo.height)) {
                        firstVisible = i;
                        break;
                    }
                }
                for (int i = firstVisible; i < renderableWindows.size(); i++) {
                    RenderableWindow window = renderableWindows.get(i);
                    renderDrawable(window.content, window.rootX, window.rootY, windowMaterial, window.forceFullscreen);
                }
            }
        }

        quadVertices.disable();
        GLES20.glEnable(GLES20.GL_BLEND);

        if (BuildConfig.DEBUG) {
            int error = GLES20.glGetError();
            if (error != GLES20.GL_NO_ERROR) {
                Log.e("GLRenderer", "OpenGL Error: " + error);
            }
        }

    }

    private void renderCursor() {
        cursorMaterial.use();
        GLES20.glUniform2f(cursorMaterial.getUniformLocation("viewSize"), xServer.screenInfo.width, xServer.screenInfo.height);
        quadVertices.bind(cursorMaterial.programId);

        try (XLock lock = xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
            Window pointWindow = xServer.inputDeviceManager.getPointWindow();
            Cursor cursor = pointWindow != null ? pointWindow.attributes.getCursor() : null;
            short x = xServer.pointer.getClampedX();
            short y = xServer.pointer.getClampedY();

            if (cursor != null) {
                if (cursor.isVisible()) renderDrawable(cursor.cursorImage, x - cursor.hotSpotX, y - cursor.hotSpotY, cursorMaterial);
            }
            else renderDrawable(rootCursorDrawable, x, y, cursorMaterial);
        }

        quadVertices.disable();
    }

    public void toggleFullscreen() {
        toggleFullscreen = true;
        xServerView.requestRender();
    }

    private Drawable createRootCursorDrawable() {
        Context context = xServerView.getContext();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.cursor, options);
        return Drawable.fromBitmap(bitmap);
    }

    private void updateScene() {
        try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
            renderableWindows.clear();
            collectRenderableWindows(xServer.windowManager.rootWindow, xServer.windowManager.rootWindow.getX(), xServer.windowManager.rootWindow.getY());
        }
    }

    private void collectRenderableWindows(Window window, int x, int y) {
        if (!window.attributes.isMapped()) return;
        if (window != xServer.windowManager.rootWindow) {
            boolean viewable = true;

            if (unviewableWMClasses != null) {
                String wmClass = window.getClassName();
                for (String unviewableWMClass : unviewableWMClasses) {
                    if (wmClass.contains(unviewableWMClass)) {
                        if (window.attributes.isEnabled()) window.disableAllDescendants();
                        viewable = false;
                        break;
                    }
                }
            }

            if (viewable) {
                if (forceFullscreenWMClass != null) {
                    short width = window.getWidth();
                    short height = window.getHeight();
                    boolean forceFullscreen= false;

                    if (width >= 320 && height >= 200 && width < xServer.screenInfo.width && height < xServer.screenInfo.height) {
                        Window parent = window.getParent();
                        boolean parentHasWMClass = parent.getClassName().contains(forceFullscreenWMClass);
                        boolean hasWMClass = window.getClassName().contains(forceFullscreenWMClass);
                        if (hasWMClass) {
                            forceFullscreen = !parentHasWMClass && window.getChildCount() == 0;
                        }
                        else {
                            short borderX = (short)(parent.getWidth() - width);
                            short borderY = (short)(parent.getHeight() - height);
                            if (parent.getChildCount() == 1 && borderX > 0 && borderY > 0 && borderX <= 12) {
                                forceFullscreen = true;
                                removeRenderableWindow(parent);
                            }
                        }
                    }

                    renderableWindows.add(new RenderableWindow(window.getContent(), x, y, forceFullscreen));
                }
                else renderableWindows.add(new RenderableWindow(window.getContent(), x, y));
            }
        }

        for (Window child : window.getChildren()) {
            collectRenderableWindows(child, child.getX() + x, child.getY() + y);
        }
    }

    private void removeRenderableWindow(Window window) {
        for (int i = 0; i < renderableWindows.size(); i++) {
            if (renderableWindows.get(i).content == window.getContent()) {
                renderableWindows.remove(i);
                break;
            }
        }
    }

    private void updateWindowPosition(Window window) {
        for (RenderableWindow renderableWindow : renderableWindows) {
            if (renderableWindow.content == window.getContent()) {
                renderableWindow.rootX = window.getRootX();
                renderableWindow.rootY = window.getRootY();
                break;
            }
        }
    }

    public void setCursorVisible(boolean cursorVisible) {
        this.cursorVisible = cursorVisible;
        xServerView.requestRender();
    }

    public boolean isCursorVisible() {
        return cursorVisible;
    }

    public boolean isScreenOffsetYRelativeToCursor() {
        return screenOffsetYRelativeToCursor;
    }

    public void setScreenOffsetYRelativeToCursor(boolean screenOffsetYRelativeToCursor) {
        this.screenOffsetYRelativeToCursor = screenOffsetYRelativeToCursor;
        xServerView.requestRender();
    }

    public String getForceFullscreenWMClass() {
        return forceFullscreenWMClass;
    }

    public void setForceFullscreenWMClass(String forceFullscreenWMClass) {
        this.forceFullscreenWMClass = forceFullscreenWMClass;
    }

    public String[] getUnviewableWMClasses() {
        return unviewableWMClasses;
    }

    public void setUnviewableWMClasses(String... unviewableWMNames) {
        this.unviewableWMClasses = unviewableWMNames;
    }

    public boolean isFullscreen() {
        return fullscreen;
    }

    public float getMagnifierZoom() {
        return magnifierZoom;
    }

    public void setMagnifierZoom(float magnifierZoom) {
        this.magnifierZoom = magnifierZoom;
        xServerView.requestRender();
    }

    public int getSurfaceWidth() {
        return surfaceWidth;
    }

    public int getSurfaceHeight() {
        return surfaceHeight;
    }

    public void setRenderCursorEnabled(boolean enabled) {
        renderCursorEnabled = enabled;
    }

    public void drawCursorExplicitly() {
        if (!cursorVisible || rootWindowDownsized) return;
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        boolean immersiveXR = XrActivity.isEnabled(null)
                && XrActivity.getInstance().getImmersive();
        if (fullscreen || immersiveXR) {
            GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
        }
        else {
            GLES20.glViewport(viewTransformation.viewOffsetX, viewTransformation.viewOffsetY,
                    viewTransformation.viewWidth, viewTransformation.viewHeight);
        }
        renderCursor();
        viewportNeedsUpdate = true;
    }

    public int getTextureFilterMode() {
        return textureFilterMode;
    }

    public void setTextureFilterMode(int textureFilterMode) {
        // Raw value: 0 = bilinear, 1 = nearest, 2 = FSR (sharpen/upscale states).
        // Sampling stays NEAREST only for 1; everything else uses LINEAR.
        this.textureFilterMode = textureFilterMode;
        xServerView.requestRender();
    }

    public boolean isSwapRedBlue() {
        return swapRedBlue;
    }

    public void setSwapRedBlue(boolean swapRedBlue) {
        this.swapRedBlue = swapRedBlue;
        xServerView.requestRender();
    }

    public int getFpsLimit() {
        return fpsLimit;
    }

    /**
     * The actual pacing happens in PresentExtension, before Wine receives the
     * idle notification for its submitted frame.  Keeping it there avoids
     * sleeping the Android GL compositor thread and the regressions caused by
     * the former onDrawFrame limiter.
     */
    public void setFpsLimit(int fpsLimit) {
        this.fpsLimit = Math.max(0, fpsLimit);
    }

    public boolean isViewportNeedsUpdate() {
        return viewportNeedsUpdate;
    }

    public void setViewportNeedsUpdate(boolean viewportNeedsUpdate) {
        this.viewportNeedsUpdate = viewportNeedsUpdate;
    }

    public VertexAttribute getQuadVertices() {
        return quadVertices;
    }

    public EffectComposer getEffectComposer (){
        return effectComposer;
    }

    public LSFGManager getLSFGManager() {
        return lsfgManager;
    }

    public void setApex(boolean enabled, int quality, int targetFPS, float stability) {
        setApex(enabled, quality, 0.0f, targetFPS, stability);
    }

    public void setApex(boolean enabled, int quality, float multiplier,
            int targetFPS, float stability) {
        setApex(enabled, quality, multiplier, targetFPS, stability,
                requestedApexBackend, requestedApexLowLatency);
    }

    public void setApex(boolean enabled, int quality, float multiplier,
            int targetFPS, float stability, int backend, boolean lowLatencyMode) {
        requestedApexEnabled = enabled;
        requestedApexQuality = Math.max(0, Math.min(2, quality));
        requestedApexMultiplier = multiplier >= 1.5f
                ? Math.min(10.0f, multiplier) : 0.0f;
        requestedApexTargetFPS = Math.max(15, Math.min(240, targetFPS));
        requestedApexStability = Math.max(0.0f, Math.min(1.0f, stability));
        requestedApexBackend = Math.max(LSFGManager.BACKEND_GLES,
                Math.min(LSFGManager.BACKEND_AUTO, backend));
        requestedApexLowLatency = lowLatencyMode;
        xServerView.queueEvent(() -> {
            boolean applied = effectComposer.setLSFGEnabled(requestedApexEnabled,
                    requestedApexQuality, requestedApexMultiplier,
                    requestedApexTargetFPS, requestedApexStability,
                    requestedApexBackend, requestedApexLowLatency);
            if (!applied) requestedApexEnabled = false;
            lastApexFrameNanos = 0;
            apexStatsStartNanos = 0;
            WinlatorHUD hud = winlatorHUD;
            if (hud != null) {
                boolean active = requestedApexEnabled && lsfgManager.isActive();
                hud.setFrameGenerationStats(active, 0, 0,
                        lsfgManager.getEstimatedLatencyMs(), lsfgManager.getBackendName(),
                        lsfgManager.getBackendState(), lsfgManager.getBackendFailure(),
                        requestedApexLowLatency);
            }
            xServerView.requestRender();
        });
    }

    public boolean isApexEnabled() {
        return lsfgManager.isActive();
    }

    /**
     * Returns the state selected by the user immediately, without waiting for
     * the GL thread to finish applying it.  Sidebar clicks can arrive while an
     * earlier enable request is still queued, so using only isApexEnabled()
     * can invert the first click and turn Apex back on.
     */
    public boolean isApexRequestedEnabled() {
        return requestedApexEnabled;
    }

    public int getApexQuality() {
        return requestedApexQuality;
    }

    public int getApexTargetFPS() {
        return requestedApexTargetFPS;
    }

    public float getApexMultiplier() {
        return requestedApexMultiplier;
    }

    public float getApexStability() {
        return requestedApexStability;
    }

    public int getApexBackend() {
        return requestedApexBackend;
    }

    public boolean isApexLowLatency() {
        return requestedApexLowLatency;
    }

    public void setWinlatorHUD(WinlatorHUD hud) {
        winlatorHUD = hud;
        apexStatsStartNanos = 0;
    }

    /**
     * GLSurfaceView continuous mode is not reliable on every vendor EGL stack
     * while the guest is idle between real Presents. Drive one request from
     * Android's display clock as well, matching the scheduler used by the
     * original Apex implementation.
     */
    public void startApexChoreographer() {
        if (apexChoreographerRunning) return;
        apexChoreographerRunning = true;
        new Handler(Looper.getMainLooper()).post(() ->
                Choreographer.getInstance().postFrameCallback(this));
    }

    public void stopApexChoreographer() {
        apexChoreographerRunning = false;
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!apexChoreographerRunning) return;
        if (lsfgManager.isActive()) xServerView.requestRender();
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void updateApexHudStats() {
        WinlatorHUD hud = winlatorHUD;
        if (hud == null) return;
        long now = System.nanoTime();
        if (apexStatsStartNanos == 0) apexStatsStartNanos = now;
        long elapsed = now - apexStatsStartNanos;
        if (elapsed < 500_000_000L) return;
        float seconds = elapsed / 1_000_000_000.0f;
        int sourceFrames = lsfgManager.consumeGameFrameCount();
        int presentedRealFrames = lsfgManager.consumeActualRealFrameCount();
        int generatedFrames = lsfgManager.consumeGeneratedFrameCount();
        // Source FPS measures actual X11 content Presents from the game.  The
        // output counter measures draws completed by the Apex compositor and
        // therefore remains honest when several game Presents are coalesced
        // before the next physical display refresh.
        float realFps = seconds > 0 ? sourceFrames / seconds : 0;
        float outputFps = seconds > 0
                ? (presentedRealFrames + generatedFrames) / seconds : 0;
        hud.setFrameGenerationStats(lsfgManager.isActive(), realFps, outputFps,
                lsfgManager.getEstimatedLatencyMs(), lsfgManager.getBackendName(),
                lsfgManager.getBackendState(), lsfgManager.getBackendFailure(),
                lsfgManager.isLowLatencyMode());
        apexStatsStartNanos = now;
    }

    /** Called by the composer when the runtime backend is rejected after setup. */
    void onApexRuntimeFailure() {
        requestedApexEnabled = false;
        lastApexFrameNanos = 0;
        apexStatsStartNanos = 0;
        if (lsfgManager.getBackendFailure().isEmpty())
            lsfgManager.reportBackendFailure("Frame-generation compositor failed");
        WinlatorHUD hud = winlatorHUD;
        if (hud != null) hud.setFrameGenerationStats(false, 0, 0,
                lsfgManager.getEstimatedLatencyMs(), lsfgManager.getBackendName(),
                lsfgManager.getBackendState(), lsfgManager.getBackendFailure(),
                requestedApexLowLatency);
    }


}
