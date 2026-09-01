package com.winlator.cmod.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.winlator.cmod.renderer.GLRenderer;
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
    private final GLRenderer renderer;
    private final AtomicBoolean frameDispatchPosted = new AtomicBoolean();

    public XServerView(Context context, XServer xServer) {
        super(context);
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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
        renderer = new GLRenderer(this, xServer);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    public GLRenderer getRenderer() {
        return renderer;
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
            super.requestRender();
            return;
        }
        if (frameDispatchPosted.compareAndSet(false, true)) {
            postOnAnimation(() -> {
                frameDispatchPosted.set(false);
                super.requestRender();
            });
        }
    }
}
