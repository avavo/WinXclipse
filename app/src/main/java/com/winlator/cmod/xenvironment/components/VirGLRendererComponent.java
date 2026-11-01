package com.winlator.cmod.xenvironment.components;

import android.util.Log;

import androidx.annotation.Keep;

import com.winlator.cmod.renderer.DisplayRenderer;
import com.winlator.cmod.renderer.Texture;
import com.winlator.cmod.xconnector.Client;
import com.winlator.cmod.xconnector.ConnectionHandler;
import com.winlator.cmod.xconnector.RequestHandler;
import com.winlator.cmod.xconnector.UnixSocketConfig;
import com.winlator.cmod.xconnector.XConnectorEpoll;
import com.winlator.cmod.xenvironment.EnvironmentComponent;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.XServer;

import java.io.IOException;

public class VirGLRendererComponent extends EnvironmentComponent implements ConnectionHandler, RequestHandler {
    private final XServer xServer;
    private final UnixSocketConfig socketConfig;
    private XConnectorEpoll connector;
    private long sharedEGLContextPtr;

    static {
        System.loadLibrary("virglrenderer");
    }

    public VirGLRendererComponent(XServer xServer, UnixSocketConfig socketConfig) {
        this.xServer = xServer;
        this.socketConfig = socketConfig;
    }

    @Override
    public void start() {
        if (connector != null) return;
        connector = new XConnectorEpoll(socketConfig, this, this);
        connector.start();
    }

    @Override
    public void stop() {
        if (connector != null) {
            connector.stop();
            connector = null;
        }
    }

    @Keep
    private void killConnection(int fd) {
        connector.killConnection(connector.getClient(fd));
    }

    @Keep
    private long getSharedEGLContext() {
        // The Vulkan compositor has no EGL share group. VirGL creates its own
        // guest-rendering context and transfers completed front buffers below.
        if (xServer.getRenderer() instanceof com.winlator.cmod.renderer.VulkanRenderer) return 0;
        if (sharedEGLContextPtr != 0) return sharedEGLContextPtr;
        java.util.concurrent.CountDownLatch ready=new java.util.concurrent.CountDownLatch(1);
        try {
            DisplayRenderer renderer=xServer.getRenderer();
            renderer.xServerView.queueEvent(()->{
                try {sharedEGLContextPtr=getCurrentEGLContextPtr();}
                finally {ready.countDown();}
            });
            if(!ready.await(5,java.util.concurrent.TimeUnit.SECONDS))
                Log.w("VirGLRendererComponent","EGL sharing timed out; using pixel transfers");
        } catch(InterruptedException error) {Thread.currentThread().interrupt();return 0;}
        return sharedEGLContextPtr;
    }

    @Override
    public void handleConnectionShutdown(Client client) {
        long clientPtr = (long)client.getTag();
        destroyClient(clientPtr);
    }

    @Override
    public void handleNewConnection(Client client) {
        getSharedEGLContext();
        long clientPtr = handleNewConnection(client.clientSocket.fd);
        client.setTag(clientPtr);
    }

    @Override
    public boolean handleRequest(Client client) throws IOException {
        long clientPtr = (long)client.getTag();
        handleRequest(clientPtr);
        return true;
    }

    @Keep
    private void flushFrontbuffer(int drawableId, int framebuffer) {
        Drawable drawable = xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) {
            Log.e("VirGLRendererComponent", "Drawable not found for drawableId=" + drawableId);
            return;
        }

        synchronized (drawable.renderLock) {
            if (framebuffer == 0) {
                Log.e("VirGLRendererComponent", "Framebuffer is invalid for drawableId=" + drawableId);
                return;
            }

            Texture texture = drawable.getTexture();
            if (texture == null) {
                Log.e("VirGLRendererComponent", "Texture is null for drawableId=" + drawableId);
                return;
            }

            // Ensure existing data is valid before resetting
            try {
                if (xServer.getRenderer() instanceof com.winlator.cmod.renderer.VulkanRenderer || sharedEGLContextPtr==0)
                    readFrontbuffer(drawable, framebuffer);
                else texture.copyFromFramebuffer(framebuffer, drawable.width, drawable.height);
            } catch (Exception e) {
                Log.e("VirGLRendererComponent", "Error during framebuffer copy: " + e.getMessage(), e);
                return;
            }
        }

        Runnable onDrawListener = drawable.getOnDrawListener();
        if (onDrawListener != null) {
            onDrawListener.run();
        }
    }

    private java.nio.ByteBuffer readback;
    /** Executed with VirGL's own EGL context current, never on the compositor. */
    private void readFrontbuffer(Drawable drawable, int framebuffer) {
        int row=drawable.width*4, bytes=row*drawable.height;
        if(readback==null||readback.capacity()<bytes)readback=java.nio.ByteBuffer.allocateDirect(bytes);
        readback.clear();
        int[] previous=new int[1];
        android.opengl.GLES20.glGetIntegerv(android.opengl.GLES20.GL_FRAMEBUFFER_BINDING,previous,0);
        android.opengl.GLES20.glBindFramebuffer(android.opengl.GLES20.GL_FRAMEBUFFER,framebuffer);
        try {
            android.opengl.GLES20.glPixelStorei(android.opengl.GLES20.GL_PACK_ALIGNMENT,1);
            android.opengl.GLES20.glReadPixels(0,0,drawable.width,drawable.height,
                    android.opengl.GLES20.GL_RGBA,android.opengl.GLES20.GL_UNSIGNED_BYTE,readback);
            int error=android.opengl.GLES20.glGetError();
            if(error!=android.opengl.GLES20.GL_NO_ERROR)throw new IllegalStateException("VirGL readback: "+error);
            java.nio.ByteBuffer target=drawable.getData().duplicate();
            int stride=(drawable.getStridePixels()&0xffff)*4;
            for(int y=0;y<drawable.height;y++) {
                readback.limit(bytes);readback.position((drawable.height-1-y)*row);
                readback.limit(readback.position()+row);target.position(y*stride);target.put(readback);
            }
            drawable.getTexture().setFormat(android.opengl.GLES20.GL_RGBA);
            drawable.getTexture().setNeedsUpdate(true);
        } finally {android.opengl.GLES20.glBindFramebuffer(android.opengl.GLES20.GL_FRAMEBUFFER,previous[0]);}
    }


    private native long handleNewConnection(int fd);

    private native void handleRequest(long clientPtr);

    private native long getCurrentEGLContextPtr();

    private native void destroyClient(long clientPtr);

    private native void destroyRenderer(long clientPtr);
}
