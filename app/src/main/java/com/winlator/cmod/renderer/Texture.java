package com.winlator.cmod.renderer;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;

import com.winlator.cmod.XrActivity;
import com.winlator.cmod.xserver.Drawable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class Texture {
    protected int textureId = 0;
    private int wrapS = GLES20.GL_CLAMP_TO_EDGE;
    private int wrapT = GLES20.GL_CLAMP_TO_EDGE;
    private int magFilter = GLES20.GL_LINEAR;
    private int minFilter = GLES20.GL_LINEAR;
    protected int format = GLES11Ext.GL_BGRA;
    protected boolean needsUpdate = true;
    protected byte unpackAlignment = 4; // or add a getter method
    private final int[] dirtyRegion = new int[4];
    private ByteBuffer packedUploadBuffer;


    public void allocateTexture(short width, short height, ByteBuffer data) {
        int[] textureIds = new int[1];
        GLES20.glGenTextures(1, textureIds, 0);
        textureId = textureIds[0];

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);

        if (data != null) {
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, format, width, height, 0, format, GLES20.GL_UNSIGNED_BYTE, data);
        }

        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, wrapS);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, wrapT);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, magFilter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, minFilter);

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        // Data already uploaded via glTexImage2D, no pending sub-image.
        needsUpdate = false;
    }

    public int getWrapS() {
        return wrapS;
    }

    public void setWrapS(int wrapS) {
        this.wrapS = wrapS;
    }

    public int getWrapT() {
        return wrapT;
    }

    public void setWrapT(int wrapT) {
        this.wrapT = wrapT;
    }

    public int getMagFilter() {
        return magFilter;
    }

    public void setMagFilter(int magFilter) {
        this.magFilter = magFilter;
    }

    public int getMinFilter() {
        return minFilter;
    }

    public void setMinFilter(int minFilter) {
        this.minFilter = minFilter;
    }

    public int getFormat() {
        return format;
    }

    public void setFormat(int format) {
        this.format = format;
    }

    public boolean isNeedsUpdate() {
        return needsUpdate;
    }

    public void setNeedsUpdate(boolean needsUpdate) {
        this.needsUpdate = needsUpdate;
    }

    public void updateFromDrawable(Drawable drawable) {
        ByteBuffer data = drawable.getData();
        if (data == null) return;

        if (!isAllocated()) {
            ByteBuffer initialData = drawable.getStridePixels() == drawable.width
                    ? fullBufferView(data, drawable.width * drawable.height * 4)
                    : packRegion(drawable, data, 0, 0, drawable.width, drawable.height);
            allocateTexture(drawable.width, drawable.height, initialData);
            drawable.consumeDirtyRegion(dirtyRegion);
            return;
        }
        if (!needsUpdate) return;

        boolean hasDirtyRegion = drawable.consumeDirtyRegion(dirtyRegion);
        int x = hasDirtyRegion ? dirtyRegion[0] : 0;
        int y = hasDirtyRegion ? dirtyRegion[1] : 0;
        int width = hasDirtyRegion ? dirtyRegion[2] : drawable.width;
        int height = hasDirtyRegion ? dirtyRegion[3] : drawable.height;
        if (width <= 0 || height <= 0) {
            needsUpdate = false;
            return;
        }

        long dirtyArea = (long)width * height;
        long fullArea = (long)drawable.width * drawable.height;
        boolean uploadWholeDrawable = drawable.getStridePixels() == drawable.width
                && (dirtyArea * 2 >= fullArea);
        ByteBuffer uploadData;
        if (uploadWholeDrawable) {
            x = 0;
            y = 0;
            width = drawable.width;
            height = drawable.height;
            uploadData = fullBufferView(data, drawable.width * drawable.height * 4);
        }
        else {
            uploadData = packRegion(drawable, data, x, y, width, height);
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4);
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, x, y, width, height,
                format, GLES20.GL_UNSIGNED_BYTE, uploadData);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        needsUpdate = false;
    }

    private ByteBuffer packRegion(Drawable drawable, ByteBuffer source, int x, int y,
                                  int width, int height) {
        int required = width * height * 4;
        if (packedUploadBuffer == null || packedUploadBuffer.capacity() < required) {
            packedUploadBuffer = ByteBuffer.allocateDirect(required).order(ByteOrder.nativeOrder());
        }
        packedUploadBuffer.clear();
        packedUploadBuffer.limit(required);
        ByteBuffer row = source.duplicate();
        int stride = drawable.getStridePixels() & 0xFFFF;
        int rowBytes = width * 4;
        for (int line = 0; line < height; line++) {
            int start = ((y + line) * stride + x) * 4;
            row.clear();
            row.position(start);
            row.limit(start + rowBytes);
            packedUploadBuffer.put(row);
        }
        packedUploadBuffer.flip();
        return packedUploadBuffer;
    }

    private static ByteBuffer fullBufferView(ByteBuffer source, int size) {
        ByteBuffer view = source.duplicate();
        view.clear();
        view.limit(Math.min(size, view.capacity()));
        return view;
    }

    public void updateRegion(Drawable drawable, int x, int y, int width, int height) {
        ByteBuffer data = drawable.getData();
        if (data == null || !isAllocated()) return;
        ByteBuffer uploadData = packRegion(drawable, data, x, y, width, height);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4);
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, x, y, width, height,
                format, GLES20.GL_UNSIGNED_BYTE, uploadData);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    public boolean isAllocated() {
        return textureId > 0;
    }

    public int getTextureId() {
        return textureId;
    }

    public void copyFromFramebuffer(int framebuffer, short width, short height) {
        if (!isAllocated()) allocateTexture(width, height, null);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glCopyTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 0, 0, width, height, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        if (XrActivity.isEnabled(null)) XrActivity.getInstance().bindFramebuffer();
    }

    public void destroy() {
        if (textureId > 0) {
            int[] textureIds = new int[]{textureId};
            GLES20.glDeleteTextures(textureIds.length, textureIds, 0);
            textureId = 0;
        }
    }

    protected void generateTextureId() {
        int[] textureIds = new int[1];
        GLES20.glGenTextures(1, textureIds, 0);
        textureId = textureIds[0];
    }

    protected void setTextureParameters() {
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, wrapS);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, wrapT);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, magFilter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, minFilter);
    }

}
