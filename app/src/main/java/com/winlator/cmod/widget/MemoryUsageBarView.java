package com.winlator.cmod.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;

import com.winlator.cmod.R;

/** Compact, theme-aware vertical memory meter used by the in-session task manager. */
public class MemoryUsageBarView extends View {
    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private int percent;
    private String memoryText = "0.00 / 0.00 GB";

    public MemoryUsageBarView(Context context) {
        this(context, null);
    }

    public MemoryUsageBarView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        int accent = resolveColor(context, android.R.attr.colorAccent, Color.rgb(109, 44, 223));
        int surface = resolveColor(context, R.attr.winxSurfaceColor, Color.BLACK);
        int onSurface = resolveColor(context, R.attr.winxOnSurfaceColor, Color.WHITE);

        backgroundPaint.setColor(surface);
        fillPaint.setColor(withAlpha(accent, 170));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1.2f));
        borderPaint.setColor(accent);

        titlePaint.setColor(onSurface);
        titlePaint.setTextAlign(Paint.Align.CENTER);
        titlePaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        titlePaint.setTextSize(sp(14));
        valuePaint.setColor(onSurface);
        valuePaint.setTextAlign(Paint.Align.CENTER);
        valuePaint.setTextSize(sp(11));
    }

    public void setMemoryUsage(int percent, long usedBytes, long totalBytes) {
        this.percent = Math.max(0, Math.min(100, percent));
        memoryText = String.format(java.util.Locale.US, "%.2f / %.2f GB",
                bytesToGb(usedBytes), bytesToGb(totalBytes));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float inset = dp(1.5f);
        float radius = dp(8);
        bounds.set(inset, inset, getWidth() - inset, getHeight() - inset);
        canvas.drawRoundRect(bounds, radius, radius, backgroundPaint);

        if (percent > 0) {
            float fillTop = bounds.bottom - bounds.height() * percent / 100f;
            canvas.save();
            Path clip = new Path();
            clip.addRoundRect(bounds, radius, radius, Path.Direction.CW);
            canvas.clipPath(clip);
            canvas.drawRect(bounds.left, fillTop, bounds.right, bounds.bottom, fillPaint);
            canvas.restore();
        }
        canvas.drawRoundRect(bounds, radius, radius, borderPaint);

        Paint.FontMetrics titleFm = titlePaint.getFontMetrics();
        Paint.FontMetrics valueFm = valuePaint.getFontMetrics();
        float center = getHeight() / 2f;
        canvas.drawText("RAM " + percent + "%", getWidth() / 2f,
                center - dp(4) - titleFm.descent, titlePaint);
        canvas.drawText(memoryText, getWidth() / 2f,
                center + dp(7) - valueFm.ascent / 2f, valuePaint);
    }

    private float bytesToGb(long bytes) {
        return bytes / (1024f * 1024f * 1024f);
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    private float sp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                getResources().getDisplayMetrics());
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int resolveColor(Context context, int attr, int fallback) {
        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(attr, value, true)) return fallback;
        if (value.resourceId != 0) {
            TypedArray array = context.obtainStyledAttributes(new int[]{attr});
            int color = array.getColor(0, fallback);
            array.recycle();
            return color;
        }
        return value.data;
    }
}
