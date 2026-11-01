package com.winlator.cmod.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.SurfaceHolder;
import android.os.Handler;
import android.os.HandlerThread;
import com.winlator.cmod.renderer.VulkanRenderer;
import com.winlator.cmod.XrActivity;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.winlator.cmod.renderer.DisplayRenderer;
import com.winlator.cmod.core.DisplayRefreshRate;
import com.winlator.cmod.xserver.XServer;

import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;

@SuppressLint("ViewConstructor")
public class XServerView extends GLSurfaceView {
    private static final int EGL_CONTEXT_MAJOR_VERSION_KHR = 0x3098;
    private static final int EGL_CONTEXT_MINOR_VERSION_KHR = 0x30FB;
    private final DisplayRenderer renderer;
    private final AtomicBoolean frameDispatchPosted = new AtomicBoolean();
    private volatile float preferredRefreshRate;
    private final boolean vulkan;
    private HandlerThread renderThread;
    private volatile Handler renderHandler;
    private volatile boolean paused;
    private volatile boolean surfaceReady;
    private final java.util.concurrent.atomic.AtomicInteger surfaceGeneration = new java.util.concurrent.atomic.AtomicInteger();
    private final AtomicBoolean renderQueued = new AtomicBoolean();
    private int vulkanRenderMode = RENDERMODE_WHEN_DIRTY;

    public XServerView(Context context, XServer xServer) {
        this(context,xServer,com.winlator.cmod.renderer.DisplayBackend.VULKAN);
    }

    public XServerView(Context context, XServer xServer, String displayBackend) {
        super(context);
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // OpenXR currently owns GLES swapchain images. Normal Android sessions
        // use Vulkan, including compositor effects; no EGL context is created.
        vulkan = com.winlator.cmod.renderer.DisplayBackend.VULKAN.equals(
                com.winlator.cmod.renderer.DisplayBackend.normalize(displayBackend)) && !XrActivity.isEnabled(null);
        if (vulkan) {
            renderer = new VulkanRenderer(this,xServer);
            startRenderThread();
        } else {
            setEGLContextClientVersion(3);
            // GLSurfaceView's built-in ES3 factory requests only major version 3.
            // Some Samsung EGL stacks interpret that as an exact ES 3.0 context
            // even though the device supports 3.1/3.2, making Apex reject every
            // profile. Ask explicitly for 3.1 and retain an ES3 fallback so the
            // rest of the compositor can still start on older hardware.
            setEGLContextFactory(new EGLContextFactory() {
                @Override
                public EGLContext createContext(EGL10 egl, EGLDisplay display,
                        EGLConfig config) {
                    int[] es31 = {
                            EGL_CONTEXT_MAJOR_VERSION_KHR, 3,
                            EGL_CONTEXT_MINOR_VERSION_KHR, 1,
                            EGL10.EGL_NONE
                    };
                    EGLContext result = egl.eglCreateContext(display, config,
                            EGL10.EGL_NO_CONTEXT, es31);
                    if (result != null && result != EGL10.EGL_NO_CONTEXT) return result;
                    // Consume the failed 3.1 attempt before the portable ES3 retry.
                    egl.eglGetError();
                    int[] es3 = {EGL_CONTEXT_MAJOR_VERSION_KHR, 3, EGL10.EGL_NONE};
                    return egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, es3);
                }

                @Override
                public void destroyContext(EGL10 egl, EGLDisplay display,
                        EGLContext context) {
                    egl.eglDestroyContext(display, context);
                }
            });
            setEGLConfigChooser(8, 8, 8, 8, 0, 0);
            setPreserveEGLContextOnPause(true);
            com.winlator.cmod.renderer.GLRenderer glRenderer = new com.winlator.cmod.renderer.GLRenderer(this, xServer);
            renderer = glRenderer;
            setRenderer(glRenderer);
            setRenderMode(RENDERMODE_WHEN_DIRTY);
        }
    }

    public String getDisplayBackend() { return vulkan?"Vulkan":"OpenGL"; }

    public DisplayRenderer getRenderer() {
        return renderer;
    }

    public void setPreferredRefreshRate(float rate) {
        preferredRefreshRate = rate;
        post(() -> DisplayRefreshRate.requestSurface(getHolder().getSurface(), preferredRefreshRate));
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        if (!vulkan) super.surfaceCreated(holder);
        DisplayRefreshRate.requestSurface(holder.getSurface(), preferredRefreshRate);
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (vulkan) {
            surfaceReady=false;
            int generation=surfaceGeneration.incrementAndGet();
            queueEvent(() -> {
                if(generation!=surfaceGeneration.get())return;
                ((VulkanRenderer)renderer).open(holder.getSurface());
                surfaceReady=generation==surfaceGeneration.get();
                dispatchVulkanFrame();
            });
        } else super.surfaceChanged(holder, format, width, height);
        DisplayRefreshRate.requestSurface(holder.getSurface(), preferredRefreshRate);
    }

    public void setApexMode(boolean enabled) {
        post(() -> {
            setRenderMode(enabled ? RENDERMODE_CONTINUOUSLY : RENDERMODE_WHEN_DIRTY);
            if (!enabled) requestRender();
        });
    }

    @Override
    public void requestRender() {
        // Submit at most one compositor frame per Android display pulse. Guest
        // FPS remains independent, while SurfaceFlinger always receives a
        // complete frame instead of an arbitrary mid-scan update.
        if (!isAttachedToWindow()) {
            dispatchFrame();
            return;
        }
        if (frameDispatchPosted.compareAndSet(false, true)) {
            postOnAnimation(() -> {
                frameDispatchPosted.set(false);
                dispatchFrame();
            });
        }
    }

    private void startRenderThread() {
        if (renderHandler!=null) return;
        renderThread=new HandlerThread("WinXclipse Vulkan");
        renderThread.start();renderHandler=new Handler(renderThread.getLooper());
    }

    private void dispatchFrame() {
        if(vulkan)dispatchVulkanFrame();else super.requestRender();
    }

    private void dispatchVulkanFrame() {
        Handler handler=renderHandler;
        if(handler==null||paused||!surfaceReady)return;
        if(renderQueued.compareAndSet(false,true))handler.post(()->{
            renderQueued.set(false);
            if(!paused&&surfaceReady)((VulkanRenderer)renderer).render();
        });
    }

    @Override public void queueEvent(Runnable event) {
        if(!vulkan){super.queueEvent(event);return;}
        Handler handler=renderHandler;
        if(handler!=null)handler.post(event);
    }
    @Override public void setRenderMode(int mode) {
        if(vulkan)vulkanRenderMode=mode;else super.setRenderMode(mode);
    }
    @Override public int getRenderMode() { return vulkan?vulkanRenderMode:super.getRenderMode(); }
    @Override public void onPause() {
        if(vulkan){paused=true;queueEvent(()->((VulkanRenderer)renderer).invalidateHistory());}else super.onPause();
    }
    @Override public void onResume() {
        if(vulkan){paused=false;requestRender();}else super.onResume();
    }
    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        if(!vulkan){super.surfaceDestroyed(holder);return;}
        surfaceReady=false;surfaceGeneration.incrementAndGet();
        closeVulkanSurface();
    }
    private void closeVulkanSurface() {
        Handler handler=renderHandler;
        if(handler==null)return;
        CountDownLatch closed=new CountDownLatch(1);
        handler.post(()->{try{((VulkanRenderer)renderer).close();}finally{closed.countDown();}});
        try { if(!closed.await(10,TimeUnit.SECONDS))android.util.Log.e("XServerView","Timed out closing Vulkan surface"); }
        catch(InterruptedException error){Thread.currentThread().interrupt();}
    }
    @Override public void surfaceRedrawNeeded(SurfaceHolder holder) {
        if(vulkan)requestRender();else super.surfaceRedrawNeeded(holder);
    }
    @Override public void surfaceRedrawNeededAsync(SurfaceHolder holder,Runnable finished) {
        if(!vulkan){super.surfaceRedrawNeededAsync(holder,finished);return;}
        Handler handler=renderHandler;
        if(handler==null){finished.run();return;}
        handler.post(()->{try{if(surfaceReady&&!paused)((VulkanRenderer)renderer).render();}finally{finished.run();}});
    }
    @Override protected void onAttachedToWindow() {
        if(vulkan)startRenderThread();
        super.onAttachedToWindow();
    }
    @Override protected void onDetachedFromWindow() {
        if(vulkan) {
            surfaceReady=false;surfaceGeneration.incrementAndGet();closeVulkanSurface();
            HandlerThread thread=renderThread;renderHandler=null;renderThread=null;
            if(thread!=null)thread.quitSafely();
        }
        super.onDetachedFromWindow();
    }
}
