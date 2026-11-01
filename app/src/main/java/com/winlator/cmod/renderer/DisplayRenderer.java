package com.winlator.cmod.renderer;

import android.view.Choreographer;
import com.winlator.cmod.renderer.lsfg.LSFGManager;
import com.winlator.cmod.widget.WinlatorHUD;
import com.winlator.cmod.widget.XServerView;
import com.winlator.cmod.xserver.Bitmask;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.WindowManager;

/** Common display controls; the API used by Wine/input/UI must not select a graphics backend. */
public abstract class DisplayRenderer implements WindowManager.OnWindowModificationListener,
        Pointer.OnPointerMotionListener, Choreographer.FrameCallback {
    public final XServerView xServerView;
    public final ViewTransformation viewTransformation = new ViewTransformation();
    public int surfaceWidth;
    public int surfaceHeight;
    public boolean viewportNeedsUpdate = true;

    protected DisplayRenderer(XServerView view) { xServerView = view; }

    public abstract void setRenderTargetSize(int width, int height);
    public abstract int getXScreenWidth();
    public abstract int getXScreenHeight();
    public abstract void drawFrame();
    public abstract void onMapWindow(Window window);
    public abstract void onUnmapWindow(Window window);
    public abstract void onChangeWindowZOrder(Window window);
    public abstract void onUpdateWindowContent(Window window);
    public abstract void onUpdateWindowGeometry(final Window window, boolean resized);
    public abstract void onUpdateWindowAttributes(Window window, Bitmask mask);
    public abstract void onPointerMove(short x, short y);
    public abstract void toggleFullscreen();
    public abstract int[] mapToForcedWindow(int x, int y);
    public abstract void setCursorVisible(boolean cursorVisible);
    public abstract boolean isCursorVisible();
    public abstract boolean isScreenOffsetYRelativeToCursor();
    public abstract void setScreenOffsetYRelativeToCursor(boolean screenOffsetYRelativeToCursor);
    public abstract String getForceFullscreenWMClass();
    public abstract void setForceFullscreenWMClass(String forceFullscreenWMClass);
    public abstract String[] getUnviewableWMClasses();
    public abstract void setUnviewableWMClasses(String... unviewableWMNames);
    public abstract boolean isFullscreen();
    public abstract float getMagnifierZoom();
    public abstract void setMagnifierZoom(float magnifierZoom);
    public abstract int getSurfaceWidth();
    public abstract int getSurfaceHeight();
    public abstract void setRenderCursorEnabled(boolean enabled);
    public abstract void drawCursorExplicitly();
    public abstract int getTextureFilterMode();
    public abstract void setTextureFilterMode(int textureFilterMode);
    public abstract boolean isSwapRedBlue();
    public abstract void setSwapRedBlue(boolean swapRedBlue);
    public abstract int getFpsLimit();
    public abstract void setFpsLimit(int fpsLimit);
    public abstract boolean isViewportNeedsUpdate();
    public abstract void setViewportNeedsUpdate(boolean viewportNeedsUpdate);
    public abstract VertexAttribute getQuadVertices();
    public abstract EffectComposer getEffectComposer ();
    public abstract LSFGManager getLSFGManager();
    public abstract void setApex(boolean enabled, int quality, int targetFPS, float stability);
    public abstract void setApex(boolean enabled, int quality, float multiplier, int targetFPS, float stability);
    public abstract void setApex(boolean enabled, int quality, float multiplier, int targetFPS, float stability, int backend, boolean lowLatencyMode);
    public abstract boolean isApexEnabled();
    public abstract boolean isApexRequestedEnabled();
    public abstract void setApexFailureListener(Runnable listener);
    public abstract int getApexQuality();
    public abstract int getApexTargetFPS();
    public abstract float getApexMultiplier();
    public abstract float getApexStability();
    public abstract int getApexBackend();
    public abstract boolean isApexLowLatency();
    public abstract void setWinlatorHUD(WinlatorHUD hud);
    public abstract void setGameplayMeasurement(com.winlator.cmod.core.GameplayMeasurement recording);
    public abstract void setAdditionalHuds(java.util.Collection<WinlatorHUD> panels);
    public abstract void startApexChoreographer();
    public abstract void stopApexChoreographer();
    public abstract void doFrame(long frameTimeNanos);
    abstract void onApexRuntimeFailure();
    abstract void drawScene(boolean includeCursor);
}
