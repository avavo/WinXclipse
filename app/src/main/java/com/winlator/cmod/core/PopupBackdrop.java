package com.winlator.cmod.core;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.*;

/** A bounded, one-shot backdrop. Works even when the device disables window blurs. */
public final class PopupBackdrop {
    private static final HandlerThread WORKER = new HandlerThread("PopupBackdrop");
    private static final Handler HANDLER;
    static { WORKER.start(); HANDLER = new Handler(WORKER.getLooper()); }
    private PopupBackdrop() {}

    public static Activity activity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        return context instanceof Activity ? (Activity) context : null;
    }

    public static void capture(View target, Window source) { capture(target, source, true); }
    public static void capture(View target, Window source, boolean gameSurface) {
        target.post(() -> captureNow(target, source, gameSurface));
    }
    private static void captureNow(View target, Window source, boolean gameSurface) {
        if (source == null || !target.isAttachedToWindow()) return;
        View decor = source.getDecorView();
        if (decor.getWidth() <= 0 || decor.getHeight() <= 0) return;
        SurfaceView surface = gameSurface ? findSurface(decor) : null;
        View origin = surface != null ? surface : decor;
        int width = origin.getWidth(), height = origin.getHeight();
        float scale = Math.min(1f, 360f / Math.max(width, height));
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, Math.round(width * scale)),
                Math.max(1, Math.round(height * scale)), Bitmap.Config.ARGB_8888);
        int[] position = new int[2]; origin.getLocationOnScreen(position);
        PixelCopy.OnPixelCopyFinishedListener callback = result -> {
            if (result != PixelCopy.SUCCESS) { bitmap.recycle(); return; }
            SidebarBackdrop.blur(bitmap);
            target.post(() -> {
                if (!target.isAttachedToWindow()) { bitmap.recycle(); return; }
                target.setBackground(new Snapshot(target, bitmap, position[0], position[1], width, height));
            });
        };
        try {
            if (surface != null) PixelCopy.request(surface, bitmap, callback, HANDLER);
            else PixelCopy.request(source, bitmap, callback, HANDLER);
        } catch (IllegalArgumentException error) { bitmap.recycle(); }
    }

    private static SurfaceView findSurface(View root) {
        if (root instanceof SurfaceView && root.isShown() && root.getWidth() > 0
                && ((SurfaceView)root).getHolder().getSurface().isValid()) return (SurfaceView)root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)root;
            for (int i = 0; i < group.getChildCount(); i++) {
                SurfaceView surface = findSurface(group.getChildAt(i));
                if (surface != null) return surface;
            }
        }
        return null;
    }

    private static final class Snapshot extends Drawable {
        private final View target;
        private final Bitmap bitmap;
        private final int x, y, width, height;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Drawable tint;
        Snapshot(View target, Bitmap bitmap, int x, int y, int width, int height) {
            this.target = target; this.bitmap = bitmap; this.x = x; this.y = y;
            this.width = width; this.height = height;
            tint = PopupStyle.surface(target.getContext(), true);
        }
        @Override public void draw(Canvas canvas) {
            int saved = canvas.save();
            Path clip = new Path();
            float radius = 16 * target.getResources().getDisplayMetrics().density;
            clip.addRoundRect(new RectF(getBounds()), radius, radius, Path.Direction.CW);
            canvas.clipPath(clip);
            int[] position = new int[2]; target.getLocationOnScreen(position);
            float left = x - position[0], top = y - position[1];
            canvas.drawBitmap(bitmap, null, new RectF(left, top, left + width, top + height), paint);
            tint.setBounds(getBounds()); tint.draw(canvas);
            canvas.restoreToCount(saved);
        }
        @Override public void setAlpha(int value) { paint.setAlpha(value); }
        @Override public void setColorFilter(ColorFilter value) { paint.setColorFilter(value); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
