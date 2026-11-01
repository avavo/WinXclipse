package com.winlator.cmod.renderer;

import android.util.Log;
import android.view.Surface;
import android.widget.Toast;
import com.winlator.cmod.R;
import com.winlator.cmod.widget.XServerView;
import com.winlator.cmod.xserver.*;
import java.nio.ByteBuffer;

/** Vulkan owns the Android surface, composition, FSR and frame generation. */
public final class VulkanRenderer extends SceneRenderer {
    static { System.loadLibrary("winx_vulkan"); }
    private long handle;
    private int lastPlanHash, realFrame;
    private int lastMotionQuality=-1;
    private boolean resetHistory = true, failed, presented, lastFg;
    private float scaleX,scaleY,offsetX,offsetY;
    private volatile boolean directPresentationAllowed=true;
    private Runnable afterComposition;
    private boolean frameUsesDisplayX;
    private boolean bypassX11,precisePresentation,presentAtRefresh=true;
    private String preferredSurfaceFormat="bgra";
    public void configureVideo(java.util.Map<String,String> options) {
        boolean direct=com.winlator.cmod.core.VideoSettings.enabled(options,"displayX",false);
        bypassX11=direct&&com.winlator.cmod.core.VideoSettings.enabled(options,"displayXBypass",false);
        precisePresentation=direct&&com.winlator.cmod.core.VideoSettings.enabled(options,"displayXPrecise",false);
        presentAtRefresh=!direct||com.winlator.cmod.core.VideoSettings.enabled(options,"displayXRefresh",true);
        preferredSurfaceFormat=com.winlator.cmod.core.VideoSettings.surfaceFormat(options,direct);
    }
    private volatile boolean presentedDisplayX;
    @Override public String getDisplayTransport() { return presentedDisplayX ? "DisplayX" : "X11"; }
    public void setDirectPresentationAllowed(boolean allowed){directPresentationAllowed=allowed;xServerView.requestRender();}
    /** Render-thread callback; PixelCopy callers receive a posted, composed buffer. */
    public void afterComposition(Runnable ready){afterComposition=ready;directPresentationAllowed=false;xServerView.requestRender();}

    public VulkanRenderer(XServerView view, XServer server) { super(view,server); }
    @Override protected EffectComposer createEffectComposer() { return new VulkanEffectComposer(this); }

    /** Called exclusively on XServerView's render thread. */
    public void open(Surface surface) {
        close();failed=false;
        try {
            handle=nativeCreate(surface,"rgba".equals(preferredSurfaceFormat),presentAtRefresh,precisePresentation);
            DisplayXSession direct=xServerView.getDisplayXSession();
            if(direct!=null)synchronized(direct){nativeDisplayX(handle,direct.handle());}
            long size=nativeSize(handle);
            surfaceWidth=(int)(size>>>32);surfaceHeight=(int)size;
            setRenderTargetSize(surfaceWidth,surfaceHeight);
            updateScene();resetHistory=true;
        } catch (RuntimeException error) { fail(error); }
    }

    public void close() {
        presentedDisplayX=false;
        if(handle!=0){nativeDestroy(handle);handle=0;}
        lsfgManager.resetTimingState();resetHistory=true;
    }

    public void invalidateHistory() {resetHistory=true;lsfgManager.notifySceneChangePending();}

    public void render() {
        if(handle==0||failed)return;
        try { renderDisplayFrame(); }
        catch(RuntimeException error){fail(error);}
    }

    private void fail(RuntimeException error) {
        if(failed)return;
        failed=true;Log.e("VulkanRenderer","Vulkan compositor failed",error);
        lsfgManager.reportBackendFailure(error.getMessage());lsfgManager.setEnabled(false);
        onApexRuntimeFailure();close();
        xServerView.post(()->Toast.makeText(xServerView.getContext(),
                xServerView.getContext().getString(R.string.vulkan_compositor_failed,error.getMessage()),Toast.LENGTH_LONG).show());
    }

    @Override public void drawFrame() {
        presented=false;
        frameUsesDisplayX=false;
        if(handle==0)return;
        nativeScanoutAllowed(handle,directPresentationAllowed&&bypassX11);
        nativePresentationTime(handle,xServerView.getTargetPresentationNanos());
        VulkanEffectComposer composer=(VulkanEffectComposer)effectComposer;
        composer.releaseRetiredEffects();
        VulkanEffectComposer.Plan plan=composer.plan(surfaceWidth,surfaceHeight,fullscreen,
                getXScreenWidth(),getXScreenHeight(),realFrame);
        boolean reset=resetHistory||lastPlanHash!=plan.configurationHash||lastFg!=lsfgManager.isActive()
                ||lastMotionQuality!=requestedApexQuality;
        if(reset&&lsfgManager.isActive()) {
            lsfgManager.notifySceneChangePending();
            lsfgManager.prepareFrame();
        }
        boolean capture=lsfgManager.shouldCaptureFrame();
        // Structural changes and long stalls reset the Java and GPU histories together.
        if(capture)reset|=lsfgManager.onFrameCaptured();
        if(!nativeBegin(handle,plan.sceneWidth,plan.sceneHeight,reset,textureFilterMode==1)) {
            long size=nativeSize(handle);
            surfaceWidth=(int)(size>>>32);surfaceHeight=(int)size;
            setRenderTargetSize(surfaceWidth,surfaceHeight);
            resetHistory=true;lsfgManager.resetTimingState();xServerView.requestRender();return;
        }
        resetHistory=false;lastPlanHash=plan.configurationHash;lastFg=lsfgManager.isActive();lastMotionQuality=requestedApexQuality;
        setRenderTargetSize(surfaceWidth,surfaceHeight);
        mapping();
        try(XLock lock=xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
            rootWindowDownsized=false;
            boolean single=false;
            if(fullscreen&&!renderableWindows.isEmpty()) {
                Drawable root=renderableWindows.get(0).content;
                single=root.width<getXScreenWidth()||root.height<getXScreenHeight();
                rootWindowDownsized=single;
            }
            int first=single?Math.max(0,renderableWindows.size()-1):firstVisible();
            for(int i=first;i<renderableWindows.size();i++) {
                RenderableWindow w=renderableWindows.get(i);
                draw(w.content,w.rootX,w.rootY,single||w.forceFullscreen,false,0);
            }
            drawCursorExplicitly();
        }
        int[] motionSize=com.winlator.cmod.renderer.lsfg.MotionVectorSize.forQuality(surfaceWidth,surfaceHeight,requestedApexQuality);
        presented=nativePresent(handle,plan.ids,plan.pushes,lsfgManager.isActive(),capture,
                lsfgManager.isGeneratedFrame(),lsfgManager.getInterpolationFactor(),
                requestedApexQuality,requestedApexStability,motionSize[0],motionSize[1]);
        if (presented) presentedDisplayX=frameUsesDisplayX;
        if(!presented){resetHistory=true;lsfgManager.resetTimingState();xServerView.requestRender();}
        if(presented&&!lsfgManager.isGeneratedFrame())realFrame++;
        if(presented&&nativeHasPendingFrames(handle))xServerView.requestRender();
        if(presented&&afterComposition!=null&&nativeCompositionReady(handle)) {
            Runnable ready=afterComposition;afterComposition=null;
            xServerView.postOnAnimation(()->xServerView.postOnAnimation(ready));
        }
        // Retire DisplayX GPU fences even if the game waits for this present
        // before submitting another. This redraw is not another source frame.
        if(presented&&xServerView.getDisplayXSession()!=null) {
            if(nativeNeedsCompletion(handle))xServerView.requestRender();
        }
        viewportNeedsUpdate=false;
    }

    private int firstVisible() {
        for(int i=renderableWindows.size()-1;i>=0;i--) {
            RenderableWindow w=renderableWindows.get(i);
            if(w.forceFullscreen||(w.rootX<=0&&w.rootY<=0&&w.rootX+w.content.width>=getXScreenWidth()
                    &&w.rootY+w.content.height>=getXScreenHeight()))return i;
        }
        return 0;
    }

    private void mapping() {
        float w=getXScreenWidth(),h=getXScreenHeight();
        float vx=fullscreen?0:viewTransformation.viewOffsetX;
        float vy=fullscreen?0:surfaceHeight-viewTransformation.viewOffsetY-viewTransformation.viewHeight;
        float vw=fullscreen?surfaceWidth:viewTransformation.viewWidth;
        float vh=fullscreen?surfaceHeight:viewTransformation.viewHeight;
        float zoom=screenOffsetYRelativeToCursor?1:magnifierZoom;
        float px=zoom!=1?clamp(xServer.pointer.getX()*zoom-w*.5f,0,w*Math.abs(1-zoom)):0;
        float py=0;
        if(screenOffsetYRelativeToCursor||zoom!=1)
            py=clamp(xServer.pointer.getY()*zoom-h*(screenOffsetYRelativeToCursor?.25f:.5f),
                    0,h*(zoom!=1?Math.abs(1-zoom):.5f));
        scaleX=vw/w*zoom;scaleY=vh/h*zoom;
        offsetX=vx-px*vw/w;offsetY=vy-py*vh/h;
        nativeContentRect(handle,vx/surfaceWidth,vy/surfaceHeight,vw/surfaceWidth,vh/surfaceHeight);
    }

    private static float clamp(float value,float low,float high){return Math.max(low,Math.min(high,value));}

    private void draw(Drawable drawable,int x,int y,boolean fit,boolean cursor,long keyOverride) {
        if(drawable==null||drawable.width<=0||drawable.height<=0)return;
        synchronized(drawable.renderLock) {
            int w=drawable.width,h=drawable.height;
            float dx=x,dy=y,dw=w,dh=h;
            if(fit) {
                dh=Math.min(getXScreenHeight(),(float)getXScreenWidth()/w*h);
                dw=dh/h*w;dx=(getXScreenWidth()-dw)*.5f;dy=(getXScreenHeight()-dh)*.5f;
            }
            Texture texture=drawable.getTexture();
            if(!cursor&&nativeDrawDirect(handle,drawable.id,
                    (offsetX+dx*scaleX)/surfaceWidth*2-1,(offsetY+dy*scaleY)/surfaceHeight*2-1,
                    (offsetX+(dx+dw)*scaleX)/surfaceWidth*2-1,(offsetY+(dy+dh)*scaleY)/surfaceHeight*2-1,
                    swapRedBlue)) { frameUsesDisplayX=true; return; }
            nativeDraw(handle,keyOverride!=0?keyOverride:Integer.toUnsignedLong(drawable.id),drawable.getData(),
                    w,h,drawable.getStridePixels()&0xffff,
                    (offsetX+dx*scaleX)/surfaceWidth*2-1,(offsetY+dy*scaleY)/surfaceHeight*2-1,
                    (offsetX+(dx+dw)*scaleX)/surfaceWidth*2-1,(offsetY+(dy+dh)*scaleY)/surfaceHeight*2-1,
                    cursor,!cursor&&swapRedBlue,texture==null||texture.isNeedsUpdate(),texture==null||texture.getFormat()!=android.opengl.GLES20.GL_RGBA);
            if(texture!=null)texture.setNeedsUpdate(false);
        }
    }

    @Override public void drawCursorExplicitly() {
        if(handle==0||!cursorVisible||rootWindowDownsized)return;
        Window point=xServer.inputDeviceManager.getPointWindow();
        Cursor cursor=point!=null?point.attributes.getCursor():null;
        int x=xServer.pointer.getClampedX(),y=xServer.pointer.getClampedY();
        int[] shown=mapFromForcedWindow(x,y);if(shown!=null){x=shown[0];y=shown[1];}
        if(cursor!=null) {
            if(cursor.isVisible())draw(cursor.cursorImage,x-cursor.hotSpotX,y-cursor.hotSpotY,false,true,
                    0x1_0000_0000L|Integer.toUnsignedLong(cursor.id));
        } else draw(rootCursorDrawable,x,y,false,true,Long.MIN_VALUE);
    }
    @Override void drawScene(boolean immersive) { drawFrame(); }
    @Override protected boolean wasFramePresented() { return presented; }

    private static native long nativeCreate(Surface surface,boolean rgba,boolean atRefresh,boolean precise);
    private static native void nativePresentationTime(long handle,long nanos);
    private static native boolean nativeHasPendingFrames(long handle);
    private static native long nativeSize(long handle);
    private static native void nativeDestroy(long handle);
    private static native void nativeDisplayX(long handle,long server);
    private static native boolean nativeNeedsCompletion(long handle);
    private static native void nativeScanoutAllowed(long handle,boolean enabled);
    private static native boolean nativeCompositionReady(long handle);
    private static native boolean nativeDrawDirect(long handle,int window,float x0,float y0,float x1,float y1,boolean swap);
    private static native boolean nativeBegin(long handle,int width,int height,boolean reset,boolean nearest);
    private static native void nativeContentRect(long handle,float x,float y,float width,float height);
    private static native void nativeDraw(long handle,long id,ByteBuffer pixels,int width,int height,int stride,
            float x0,float y0,float x1,float y1,boolean cursor,boolean swap,boolean update,boolean bgra);
    private static native boolean nativePresent(long handle,int[] ids,ByteBuffer pushes,boolean fg,boolean capture,
            boolean generated,float alpha,int quality,float stability,int motionWidth,int motionHeight);
}
