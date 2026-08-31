package com.winlator.cmod.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.winlator.cmod.renderer.GLRenderer;
import com.winlator.cmod.xserver.XServer;

import java.util.concurrent.atomic.AtomicBoolean;

@SuppressLint("ViewConstructor")
public class XServerView extends GLSurfaceView {
    private final GLRenderer renderer;
    private final AtomicBoolean frameDispatchPosted = new AtomicBoolean();

    public XServerView(Context context, XServer xServer) {
        super(context);
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setEGLContextClientVersion(3);
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
