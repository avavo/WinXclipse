package com.winlator.cmod.core;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;

/** One small, blurred game snapshot per drawer opening; no polling or GL readback loop. */
public final class SidebarBackdrop implements AutoCloseable {
    private final View panel;
    private final HandlerThread worker = new HandlerThread("SidebarBackdrop");
    private final Handler handler;
    private volatile int generation;
    private boolean closed;

    public SidebarBackdrop(View panel) {
        this.panel = panel;
        panel.setBackground(PopupStyle.surface(panel.getContext(), false));
        worker.start();
        handler = new Handler(worker.getLooper());
    }

    public void capture(SurfaceView source) {
        final int request = ++generation;
        if (closed || source == null || !source.getHolder().getSurface().isValid()
                || source.getWidth() <= 0 || source.getHeight() <= 0) return;
        float scale = Math.min(1f, 320f / Math.max(source.getWidth(), source.getHeight()));
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, Math.round(source.getWidth() * scale)),
                Math.max(1, Math.round(source.getHeight() * scale)), Bitmap.Config.ARGB_8888);
        final int width = source.getWidth(), height = source.getHeight();
        int[] location = new int[2];
        source.getLocationOnScreen(location);
        try {
            PixelCopy.request(source, bitmap, result -> {
                if (result != PixelCopy.SUCCESS || request != generation) { bitmap.recycle(); return; }
                blur(bitmap);
                panel.post(() -> {
                    if (closed || request != generation || !panel.isAttachedToWindow()) { bitmap.recycle(); return; }
                    panel.setBackground(new Drawable() {
                        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
                        private final Drawable tint = PopupStyle.surface(panel.getContext(), true);
                        @Override public void draw(Canvas canvas) {
                            int save = canvas.save();
                            canvas.clipRect(getBounds());
                            int[] panelLocation = new int[2];
                            panel.getLocationOnScreen(panelLocation);
                            float left = location[0] - panelLocation[0], top = location[1] - panelLocation[1];
                            canvas.drawBitmap(bitmap, null, new RectF(left, top, left + width, top + height), paint);
                            tint.setBounds(getBounds());
                            tint.draw(canvas);
                            canvas.restoreToCount(save);
                        }
                        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
                        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
                        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
                    });
                });
            }, handler);
        } catch (IllegalArgumentException e) { bitmap.recycle(); }
    }

    /** Runs on the worker and never touches a game texture or the renderer. */
    static void blur(Bitmap bitmap) {
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        int[] pixels = new int[width * height], output = new int[pixels.length];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        for (int pass = 0; pass < 4; pass++) {
            boolean horizontal = pass % 2 == 0;
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int red = 0, green = 0, blue = 0;
                for (int offset = -3; offset <= 3; offset++) {
                    int sx = horizontal ? Math.max(0, Math.min(width - 1, x + offset)) : x;
                    int sy = horizontal ? y : Math.max(0, Math.min(height - 1, y + offset));
                    int color = pixels[sy * width + sx];
                    red += (color >> 16) & 255;
                    green += (color >> 8) & 255;
                    blue += color & 255;
                }
                output[y * width + x] = 0xFF000000 | ((red / 7) << 16) | ((green / 7) << 8) | (blue / 7);
            }
            int[] swap = pixels; pixels = output; output = swap;
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
    }

    public void clear() {
        generation++;
        panel.setBackground(PopupStyle.surface(panel.getContext(), false));
    }

    @Override public void close() {
        closed = true;
        clear();
        worker.quitSafely();
    }
}
