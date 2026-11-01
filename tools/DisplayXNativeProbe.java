package com.winlator.cmod.renderer;
import android.graphics.PixelFormat;
import android.media.ImageReader;
import android.media.Image;
import android.view.Surface;
import java.nio.ByteBuffer;

/** Uses temporary surfaces/socket only; does not start Wine or touch its prefix. */
public final class DisplayXNativeProbe {
    private static native long start(String layer, String socket);
    private static native long server(long handle);
    private static native boolean pending(long handle);
    private static native String error(long handle);
    private static native void close(long handle);
    public static void main(String[] args) throws Exception {
        System.load(args[0]); System.load(args[1]); System.load(args[2]);
        ImageReader reader = ImageReader.newInstance(64,64,PixelFormat.RGBA_8888,4);
        Surface surface = reader.getSurface(); long display = 0, probe = 0;
        int frames = 0;
        try {
            display = VulkanRenderer.nativeCreate(surface,true,true,false);
            probe = start(args[3],args[4]);
            VulkanRenderer.nativeDisplayX(display,server(probe));
            long deadline = System.nanoTime()+5_000_000_000L;
            while (frames<3 && System.nanoTime()<deadline) {
                String error = error(probe); if (!error.isEmpty()) throw new AssertionError(error);
                if (pending(probe) && VulkanRenderer.nativeBegin(display,64,64,false,false)) {
                    if (!VulkanRenderer.nativeDrawDirect(display,85,-1,-1,1,1,false)) throw new AssertionError("AHB import rejected");
                    if (!VulkanRenderer.nativePresent(display,new int[0],ByteBuffer.allocateDirect(0),false,false,false,0,0,0,16,16))
                        throw new AssertionError("AHB composition failed");
                    frames++;
                    Image image=null;
                    for(int retry=0;retry<100 && image==null;retry++) { image=reader.acquireNextImage(); if(image==null) Thread.sleep(5); }
                    if(image==null) throw new AssertionError("No displayed image");
                    try {
                        Image.Plane plane=image.getPlanes()[0];ByteBuffer pixels=plane.getBuffer();
                        int offset=32*plane.getRowStride()+32*plane.getPixelStride();
                        int r=pixels.get(offset)&255,g=pixels.get(offset+1)&255,b=pixels.get(offset+2)&255;
                        System.out.println("AHB frame "+frames+": RGB="+r+","+g+","+b);
                        if ((frames==1?r:frames==2?g:b)<240) throw new AssertionError("Incorrect guest image");
                    } finally { image.close(); }
                }
                Thread.sleep(10);
            }
            if (frames!=3) throw new AssertionError("Only "+frames+" guest frames; "+error(probe));
            System.out.println("PASS DisplayX: 3 guest Vulkan frames -> real AHB/fences/socket -> Vulkan compositor");
        } finally {
            if(display!=0) VulkanRenderer.nativeDestroy(display);
            if(probe!=0) close(probe);
            surface.release(); reader.close();
        }
    }
}
