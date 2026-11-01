package com.winlator.cmod.renderer;

import android.util.Log;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.widget.XServerView;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;
import com.winlator.cmod.xserver.Window;
import java.io.File;

/** Owns one private DisplayX socket. Guest frames retain the normal X11 window/input tree. */
public final class DisplayXSession implements AutoCloseable {
    public static final String KEY="displayX";
    public static final String LAYER="VK_LAYER_WINXCLIPSE_displayx";
    private final XServerView view;
    private final XServer server;
    private final String path;
    private long handle;

    public DisplayXSession(XServerView view,XServer server,File socket) {
        this.view=view;this.server=server;path=socket.getAbsolutePath();
        try {System.loadLibrary("winx_vulkan");handle=nativeCreate(path);}
        catch(UnsatisfiedLinkError error){Log.w("DisplayX","Native transport unavailable",error);}
    }
    public synchronized long handle(){return handle;}
    public synchronized boolean isAvailable(){return handle!=0;}
    public synchronized void setFpsLimit(int fps){if(handle!=0)nativeFpsLimit(handle,Math.max(0,fps));}
    public synchronized void configure(EnvVars environment) {
        if(handle==0)return;
        environment.put("WINXCLIPSE_DISPLAYX_SOCKET",path);
        environment.put("VK_INSTANCE_LAYERS",appendLayer(environment.get("VK_INSTANCE_LAYERS")));
    }
    public static String appendLayer(String existing) {
        if(existing==null||existing.trim().isEmpty())return LAYER;
        for(String layer:existing.split("[:;,]"))if(LAYER.equals(layer.trim()))return existing;
        return existing+":"+LAYER;
    }
    // Called from the receiver worker after its producer fence has signalled.
    @SuppressWarnings("unused") private void onNativeFrame(int windowId) {
        synchronized(this){if(handle==0)return;}
        try(XLock lock=server.lock(XServer.Lockable.WINDOW_MANAGER)) {
            Window window=server.windowManager.getWindow(windowId);
            if(window!=null)view.getRenderer().onUpdateWindowContent(window);
        }
        view.requestRender();
    }
    @Override public void close(){long old;synchronized(this){old=handle;handle=0;}if(old!=0)nativeClose(old);}
    private native long nativeCreate(String path);
    private static native void nativeClose(long handle);
    private static native void nativeFpsLimit(long handle,int fps);
}
