package com.winlator.cmod.core;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Bounded decoding shared by export previews and user/online cover selection. */
public final class CommunityCover {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int MAX_EDGE = 1280;
    private CommunityCover() {}

    public static Bitmap decode(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (bytes.size() > MAX_BYTES - count) throw new IOException("Cover is too large");
            bytes.write(buffer, 0, count);
        }
        byte[] data = bytes.toByteArray();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0 || options.outWidth > 16384 || options.outHeight > 16384
                || (long)options.outWidth * options.outHeight > 64_000_000L)
            throw new IOException("Invalid cover dimensions");
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > MAX_EDGE) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (bitmap == null) throw new IOException("Unsupported cover image");
        // Phone photos commonly store rotation/mirroring in EXIF rather than pixels.
        // Apply it before PNG encoding so preview and the exported cover agree.
        try {
            android.media.ExifInterface exif = new android.media.ExifInterface(new java.io.ByteArrayInputStream(data));
            int orientation = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1);
            android.graphics.Matrix matrix = new android.graphics.Matrix();
            switch (orientation) {
                case 2: matrix.setScale(-1, 1); break;
                case 3: matrix.setRotate(180); break;
                case 4: matrix.setScale(1, -1); break;
                case 5: matrix.setRotate(90); matrix.postScale(-1, 1); break;
                case 6: matrix.setRotate(90); break;
                case 7: matrix.setRotate(270); matrix.postScale(-1, 1); break;
                case 8: matrix.setRotate(270); break;
                default: break;
            }
            if (!matrix.isIdentity()) {
                Bitmap oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                if (oriented != bitmap) { bitmap.recycle(); bitmap = oriented; }
            }
        } catch (IOException unsupportedExif) { /* Images without EXIF keep their decoded orientation. */ }
        return bitmap;
    }

    public static byte[] png(Bitmap bitmap) throws IOException {
        if (bitmap == null) return null;
        Bitmap scaled = bitmap;
        int longest = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (longest > MAX_EDGE) {
            float ratio = (float)MAX_EDGE / longest;
            scaled = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * ratio)),
                    Math.max(1, Math.round(bitmap.getHeight() * ratio)), true);
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, bytes) || bytes.size() == 0 || bytes.size() > MAX_BYTES)
                throw new IOException("Cannot encode cover");
            return bytes.toByteArray();
        } finally { if (scaled != bitmap) scaled.recycle(); }
    }
}
