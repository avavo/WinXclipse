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

/** GLES implementation of the shared X-server scene and display controls. */
public class GLRenderer extends SceneRenderer implements GLSurfaceView.Renderer {
    public GLRenderer(XServerView view, XServer server) { super(view, server); }
    @Override protected EffectComposer createEffectComposer() { return new EffectComposer(this); }
    @Override public void onDrawFrame(GL10 gl) { renderDisplayFrame(); }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLContextEpoch.beginContext();
        windowMaterial.invalidateContext();
        cursorMaterial.invalidateContext();
        quadVertices.invalidateContext();
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

        if (surfaceWidth != width || surfaceHeight != height) lsfgManager.notifySceneChangePending();
        surfaceWidth = width;
        surfaceHeight = height;
        renderTargetWidth = width;
        renderTargetHeight = height;
        viewTransformation.update(width, height, xServer.screenInfo.width, xServer.screenInfo.height);
        viewportNeedsUpdate = true;
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
            int[] shown = mapFromForcedWindow(x, y);
            if (shown != null) {
                x = (short)shown[0];
                y = (short)shown[1];
            }

            if (cursor != null) {
                if (cursor.isVisible()) renderDrawable(cursor.cursorImage, x - cursor.hotSpotX, y - cursor.hotSpotY, cursorMaterial);
            }
            else renderDrawable(rootCursorDrawable, x, y, cursorMaterial);
        }

        quadVertices.disable();
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
}
