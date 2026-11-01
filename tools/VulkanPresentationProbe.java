package com.winlator.cmod.renderer;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import java.nio.ByteBuffer;
/** One frame on a disposable SurfaceTexture; no Activity, APK install or app data. */
public final class VulkanPresentationProbe {
 public static void main(String[] args) throws Exception {
  System.load(args[0]);System.load(args[1]);
  for(int mode=0;mode<4;mode++) {
   SurfaceTexture texture=new SurfaceTexture(false);texture.setDefaultBufferSize(64,64);Surface surface=new Surface(texture);long handle=0;
   try {
    handle=VulkanRenderer.nativeCreate(surface,(mode&1)!=0,true,(mode&2)!=0);
    if(handle==0)throw new AssertionError("No compositor");
    if(!VulkanRenderer.nativeBegin(handle,64,64,true,false))throw new AssertionError("No begin");
    VulkanRenderer.nativePresentationTime(handle,(mode&2)!=0?System.nanoTime()+8_333_333:0);
    if(!VulkanRenderer.nativePresent(handle,new int[0],ByteBuffer.allocateDirect(0),false,false,false,0,0,0,16,16))throw new AssertionError("No present");
    System.out.println("PASS Vulkan native frame: format="+((mode&1)!=0?"RGBA":"BGRA")+" precise="+((mode&2)!=0));
   } finally {if(handle!=0)VulkanRenderer.nativeDestroy(handle);surface.release();texture.release();}
  }
 }
}
final class VulkanRenderer {
 static native long nativeCreate(Surface surface,boolean rgba,boolean refresh,boolean precise);
 static native void nativeDestroy(long handle);
 static native boolean nativeBegin(long handle,int w,int h,boolean reset,boolean nearest);
 static native void nativePresentationTime(long handle,long nanos);
 static native boolean nativePresent(long handle,int[] effects,ByteBuffer pushes,boolean fg,boolean capture,boolean generated,float alpha,int quality,float stability,int motionW,int motionH);
}
