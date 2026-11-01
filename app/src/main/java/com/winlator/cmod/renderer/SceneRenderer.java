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

public abstract class SceneRenderer extends DisplayRenderer {
    protected final XServer xServer;
    public final VertexAttribute quadVertices = new VertexAttribute("position", 2);
    protected final float[] tmpXForm1 = XForm.getInstance();
    protected final float[] tmpXForm2 = XForm.getInstance();
    protected final CursorMaterial cursorMaterial = new CursorMaterial();
    protected final WindowMaterial windowMaterial = new WindowMaterial();
    protected final Drawable rootCursorDrawable;
    protected final ArrayList<RenderableWindow> renderableWindows = new ArrayList<>();
    protected String forceFullscreenWMClass = null;
    protected boolean fullscreen = false;
    protected volatile boolean toggleFullscreen = false;
    protected boolean cursorVisible = true;
    protected boolean rootWindowDownsized = false;
    protected boolean screenOffsetYRelativeToCursor = false;
    protected String[] unviewableWMClasses = null;
    protected float magnifierZoom = 1.0f;
    protected boolean magnifierEnabled = true;
    // Size of the target the scene is currently composited into. Equals the
    // surface size except during the reduced-resolution FSR scene pass.
    protected int renderTargetWidth;
    protected int renderTargetHeight;
    protected final EffectComposer effectComposer;
    protected final LSFGManager lsfgManager;
    protected boolean renderCursorEnabled = true;
    protected long lastApexFrameNanos;
    protected volatile boolean requestedApexEnabled;
    protected volatile int requestedApexQuality = 1;
    protected volatile float requestedApexMultiplier;
    protected volatile int requestedApexTargetFPS = 60;
    protected volatile float requestedApexStability = 0.6f;
    protected volatile int requestedApexBackend = LSFGManager.BACKEND_GLES;
    protected volatile boolean requestedApexLowLatency;
    protected volatile Runnable apexFailureListener;
    protected volatile WinlatorHUD winlatorHUD;
    protected final java.util.concurrent.CopyOnWriteArrayList<WinlatorHUD> additionalHuds = new java.util.concurrent.CopyOnWriteArrayList<>();
    protected volatile com.winlator.cmod.core.GameplayMeasurement measurement;
    protected final java.util.concurrent.atomic.AtomicLong sourceSequence = new java.util.concurrent.atomic.AtomicLong();
    protected long measuredOutputSequence;
    protected long apexStatsStartNanos;
    protected volatile boolean apexChoreographerRunning;
    protected volatile int fpsLimit;
    public static final int TEXTURE_FILTER_NONE = 3;
    protected int lastForcedFilter = GLES20.GL_LINEAR;
    protected volatile int textureFilterMode;
    protected volatile boolean swapRedBlue;

    protected SceneRenderer(XServerView xServerView, XServer xServer) {
        super(xServerView);
        this.xServer = xServer;
        this.lsfgManager = new LSFGManager(this);
        this.effectComposer = createEffectComposer();
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

    public void renderDisplayFrame() {
        // Choreographer already supplies display-paced callbacks. Never sleep
        // on the GL compositor thread: a real Present can arrive while it is
        // blocked and lose the very frame time Apex is meant to preserve.
        lastApexFrameNanos = System.nanoTime();
        if (toggleFullscreen) {
            fullscreen = !fullscreen;
            toggleFullscreen = false;
            viewportNeedsUpdate = true;
            lsfgManager.notifySceneChangePending();

        }

        // Geometry changes must invalidate temporal samples before deciding
        // whether this draw can reuse them for a generated frame.
        lsfgManager.prepareFrame();

        long sequence = sourceSequence.get();
        drawFrame();
        if (!wasFramePresented()) return;
        lsfgManager.onPostDraw();
        boolean generated = lsfgManager.isActive() && lsfgManager.isGeneratedFrame();
        com.winlator.cmod.core.GameplayMeasurement recording = measurement;
        if (recording != null && recording.isRunning() && (generated || sequence != measuredOutputSequence))
            recording.outputFrame(System.nanoTime(), generated);
        if (!generated) measuredOutputSequence = sequence;
        updateApexHudStats();
    }

    public void drawFrame() {
        effectComposer.releaseRetiredEffects();
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
        if (isApplicationContent(window)) {
            sourceSequence.incrementAndGet();
            com.winlator.cmod.core.GameplayMeasurement recording = measurement;
            if (recording != null && recording.isRunning()) recording.sourceFrame(System.nanoTime());
            WinlatorHUD hud = winlatorHUD;
            if (hud != null) hud.onFrame();
            for (WinlatorHUD extra : additionalHuds) if (extra != hud) extra.onFrame();
        }
        lsfgManager.notifyRealFramePending();
        xServerView.requestRender();
    }

    protected static boolean isApplicationContent(Window window) {
        if (window.getWidth() <= 1 || window.getHeight() <= 1) return false;
        // Wine often renders into a child HWND whose WM_HINTS group belongs to
        // its top-level window. Requiring the drawable itself to own that group
        // drops real GL/DX9 frames. The desktop root is never a game source.
        Window ancestor = window;
        for (int depth = 0; ancestor != null && ancestor.getParent() != null && depth < 64; depth++) {
            if (ancestor.isApplicationWindow()) return true;
            ancestor = ancestor.getParent();
        }
        return false;
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
        if (screenOffsetYRelativeToCursor || magnifierZoom != 1f) lsfgManager.notifySceneChangePending();
        xServerView.requestRender();
    }









    public void toggleFullscreen() {
        toggleFullscreen = true;
        xServerView.requestRender();
    }

    protected Drawable createRootCursorDrawable() {
        Context context = xServerView.getContext();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.cursor, options);
        return Drawable.fromBitmap(bitmap);
    }

    protected void updateScene() {
        try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
            renderableWindows.clear();
            collectRenderableWindows(xServer.windowManager.rootWindow, xServer.windowManager.rootWindow.getX(), xServer.windowManager.rootWindow.getY());
            updateForcedWindowMap();
        }
    }

    /**
     * Where a force-fullscreen window really is and where it is drawn, as
     * {srcX, srcY, srcW, srcH, dstX, dstY, dstW, dstH}; null when no window is stretched.
     * Input is mapped through it so clicks land where the stretched image shows them.
     */
    protected volatile int[] forcedWindowMap;

    protected void updateForcedWindowMap() {
        int[] map = null;
        for (RenderableWindow window : renderableWindows) {
            if (!window.forceFullscreen || window.content == null) continue;
            int width = window.content.width, height = window.content.height;
            if (width <= 0 || height <= 0) continue;
            // Same fit as renderDrawable(..., forceFullscreen = true).
            short dstHeight = (short)Math.min(xServer.screenInfo.height, ((float)xServer.screenInfo.width / width) * height);
            short dstWidth = (short)(((float)dstHeight / height) * width);
            int dstX = (int)((xServer.screenInfo.width - dstWidth) * 0.5f);
            int dstY = (int)((xServer.screenInfo.height - dstHeight) * 0.5f);
            map = new int[]{window.rootX, window.rootY, width, height, dstX, dstY, dstWidth, dstHeight};
        }
        forcedWindowMap = map;
    }

    /** Screen point as the user sees it → point in the stretched window; null when none. */
    public int[] mapToForcedWindow(int x, int y) {
        int[] m = forcedWindowMap;
        if (m == null || m[6] <= 0 || m[7] <= 0) return null;
        int wx = m[0] + Math.round((x - m[4]) * (float)m[2] / m[6]);
        int wy = m[1] + Math.round((y - m[5]) * (float)m[3] / m[7]);
        return new int[]{Mathf.clamp(wx, m[0], m[0] + m[2] - 1), Mathf.clamp(wy, m[1], m[1] + m[3] - 1)};
    }

    /** The inverse, to draw the cursor over the stretched image. */
    protected int[] mapFromForcedWindow(int x, int y) {
        int[] m = forcedWindowMap;
        if (m == null || m[2] <= 0 || m[3] <= 0) return null;
        return new int[]{
                m[4] + Math.round((x - m[0]) * (float)m[6] / m[2]),
                m[5] + Math.round((y - m[1]) * (float)m[7] / m[3]),
        };
    }

    protected void collectRenderableWindows(Window window, int x, int y) {
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
                        // Wine may lower-case the executable name in WM_CLASS.
                        String wmClass = forceFullscreenWMClass.toLowerCase(java.util.Locale.ROOT);
                        boolean parentHasWMClass = parent.getClassName().toLowerCase(java.util.Locale.ROOT).contains(wmClass);
                        boolean hasWMClass = window.getClassName().toLowerCase(java.util.Locale.ROOT).contains(wmClass);
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

    protected void removeRenderableWindow(Window window) {
        for (int i = 0; i < renderableWindows.size(); i++) {
            if (renderableWindows.get(i).content == window.getContent()) {
                renderableWindows.remove(i);
                break;
            }
        }
    }

    protected void updateWindowPosition(Window window) {
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
        lsfgManager.notifySceneChangePending();
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
        lsfgManager.notifySceneChangePending();
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
                ? Math.min(4.0f, multiplier) : 0.0f;
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
            if (!applied) {
                requestedApexEnabled = false;
                notifyApexFailure();
            }
            lastApexFrameNanos = 0;
            apexStatsStartNanos = 0;
            forEachHud(hud -> {
                boolean active = requestedApexEnabled && lsfgManager.isActive();
                hud.setFrameGenerationStats(active, 0, 0, 0, lsfgManager.getMultiplier(),
                        lsfgManager.getEstimatedLatencyMs(), lsfgManager.getBackendName(),
                        lsfgManager.getBackendState(), lsfgManager.getBackendFailure(),
                        requestedApexLowLatency);
            });
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

    public void setApexFailureListener(Runnable listener) {
        apexFailureListener = listener;
    }

    protected void notifyApexFailure() {
        Runnable listener = apexFailureListener;
        if (listener != null) xServerView.post(listener);
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
        xServerView.queueEvent(() -> {
            apexStatsStartNanos = 0;
            lsfgManager.resetFrameCounts();
        });
    }

    public void setGameplayMeasurement(com.winlator.cmod.core.GameplayMeasurement recording) {
        measurement = recording;
    }

    public void setAdditionalHuds(java.util.Collection<WinlatorHUD> panels) {
        additionalHuds.clear(); additionalHuds.addAll(panels);
    }
    protected void forEachHud(java.util.function.Consumer<WinlatorHUD> update) {
        WinlatorHUD primary = winlatorHUD;
        if (primary != null) update.accept(primary);
        for (WinlatorHUD panel : additionalHuds) if (panel != primary) update.accept(panel);
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
        if (lsfgManager.shouldScheduleGeneratedFrame()) xServerView.requestRender();
        Choreographer.getInstance().postFrameCallback(this);
    }

    protected void updateApexHudStats() {
        WinlatorHUD hud = winlatorHUD;
        if (hud == null && additionalHuds.isEmpty()) {
            apexStatsStartNanos = 0;
            lsfgManager.resetFrameCounts();
            return;
        }
        long now = System.nanoTime();
        if (apexStatsStartNanos == 0) {
            apexStatsStartNanos = now;
            lsfgManager.resetFrameCounts();
            return;
        }
        long elapsed = now - apexStatsStartNanos;
        if (elapsed < 500_000_000L) return;
        int sourceFrames = lsfgManager.consumeGameFrameCount();
        int presentedRealFrames = lsfgManager.consumeActualRealFrameCount();
        int generatedFrames = lsfgManager.consumeGeneratedFrameCount();
        // Source FPS measures actual X11 content Presents from the game.  The
        // output counter measures draws completed by the Apex compositor and
        // therefore remains honest when several game Presents are coalesced
        // before the next physical display refresh.
        FrameRateSample sample = FrameRateSample.fromCounts(sourceFrames,
                presentedRealFrames, generatedFrames, elapsed);
        Log.d("ApexTelemetry", "source=" + Math.round(sample.sourceFps)
                + " compositor=" + Math.round(sample.outputFps)
                + " generated=" + Math.round(sample.generatedFps)
                + " multiplier=" + lsfgManager.getMultiplier()
                + " latencyMs=" + lsfgManager.getEstimatedLatencyMs());
        forEachHud(panel -> panel.setFrameGenerationStats(lsfgManager.isActive(), sample.sourceFps,
                sample.outputFps, sample.generatedFps,
                lsfgManager.getMultiplier(),
                lsfgManager.getEstimatedLatencyMs(), lsfgManager.getBackendName(),
                lsfgManager.getBackendState(), lsfgManager.getBackendFailure(),
                lsfgManager.isLowLatencyMode()));
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
        forEachHud(panel -> panel.setFrameGenerationStats(false, 0, 0, 0,
                lsfgManager.getMultiplier(),
                lsfgManager.getEstimatedLatencyMs(), lsfgManager.getBackendName(),
                lsfgManager.getBackendState(), lsfgManager.getBackendFailure(),
                requestedApexLowLatency));
        notifyApexFailure();
    }


    protected abstract EffectComposer createEffectComposer();
    protected boolean wasFramePresented() { return true; }
}
