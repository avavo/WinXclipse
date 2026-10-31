package com.winlator.cmod.widget;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;
import androidx.appcompat.widget.AppCompatButton;
import com.winlator.cmod.R;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.GameplayMeasurement;
import com.winlator.cmod.core.GameplayReportStore;
import com.winlator.cmod.core.InterfaceMotion;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.function.Supplier;

/** Draggable, touch-bounded measurement control. No per-frame Android UI work. */
public final class GameplayMeasurementButton extends AppCompatButton {
    public static final String PREFS = "gameplay_measurement";
    private final GameplayMeasurement measurement = new GameplayMeasurement();
    private final SharedPreferences preferences;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Supplier<HudDataSource> source;
    private final Supplier<Map<String, String>> metadataSupplier;
    private Map<String, String> metadata;
    private GameplayMeasurement.Result result;
    private boolean foreground = true, sessionPaused, closed, saving;
    private float downX, downY, originalX, originalY;
    private boolean dragged;
    private long startedWallTime;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (closed || !foreground) return;
            if (measurement.isRunning()) {
                measurement.battery(source.get().battery);
                setText(getContext().getString(sessionPaused ? R.string.measurement_paused : R.string.measurement_stop)
                        + " " + duration(measurement.elapsedNanos(System.nanoTime())));
            }
            if (measurement.isRunning()) handler.postDelayed(this, 1000);
        }
    };
    public GameplayMeasurementButton(Context context, Supplier<HudDataSource> source,
            Supplier<Map<String, String>> metadata) {
        super(context);
        this.source = source; this.metadataSupplier = metadata;
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        setAllCaps(false); setTextSize(11); setTextColor(Color.WHITE);
        setPadding(dp(12), dp(6), dp(12), dp(6));
        setMinWidth(0); setMinimumWidth(0); setMinHeight(dp(40));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(235, 91, 57, 139)); background.setCornerRadius(dp(18));
        setBackground(background); setElevation(dp(4));
        InterfaceMotion.apply(this);
        setOnClickListener(view -> toggle());
        setOnLongClickListener(view -> {
            if (result == null) return false;
            ContentDialog dialog = new ContentDialog(getContext());
            dialog.setTitle(R.string.measurement_results);
            dialog.setMessage(fullReport());
            ((android.widget.Button)dialog.findViewById(R.id.BTConfirm)).setText(R.string.measurement_save);
            dialog.setOnConfirmCallback(this::saveReport);
            dialog.show();
            return true;
        });
        syncPreferences();
        post(() -> {
            View parent = (View)getParent();
            if (parent == null) return;
            setX(preferences.getFloat("x", Math.max(0, parent.getWidth() - getWidth() - dp(12))));
            setY(preferences.getFloat("y", dp(60)));
            constrain();
        });
    }
    public GameplayMeasurement getMeasurement() { return measurement; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    public void syncPreferences() {
        boolean enabled = preferences.getBoolean("button", false);
        if (!enabled && measurement.isRunning()) finish();
        setVisibility(enabled ? VISIBLE : GONE);
        if (!measurement.isRunning()) setText(result == null ? getContext().getString(R.string.measurement_start) : summary());
        post(this::constrain);
    }
    private void toggle() {
        if (measurement.isRunning()) { finish(); return; }
        result = null; startedWallTime = System.currentTimeMillis();
        metadata = new LinkedHashMap<>(metadataSupplier.get());
        measurement.start(System.nanoTime(), foreground && !sessionPaused);
        measurement.battery(source.get().battery);
        handler.removeCallbacks(tick); handler.post(tick);
    }
    private void finish() {
        measurement.battery(source.get().battery);
        result = measurement.finish(System.nanoTime());
        handler.removeCallbacks(tick); setText(summary()); post(this::constrain);
        if (preferences.getBoolean("auto_save", false)) saveReport();
    }
    public void setSessionActive(boolean visible, boolean paused) {
        if (measurement.isActive()) measurement.battery(source.get().battery);
        foreground = visible; sessionPaused = paused;
        measurement.setActive(visible && !paused, System.nanoTime());
        if (measurement.isActive()) measurement.battery(source.get().battery);
        handler.removeCallbacks(tick);
        if (visible && measurement.isRunning()) handler.post(tick);
    }
    public void close() {
        if (measurement.isRunning()) finish();
        closed = true; handler.removeCallbacksAndMessages(null);
    }
    private String number(double value, String unit) {
        return Double.isFinite(value) ? String.format(Locale.getDefault(), "%.2f %s", value, unit) : "N/A";
    }
    private String summary() {
        if (result == null) return getContext().getString(R.string.measurement_start);
        StringBuilder text = new StringBuilder(getContext().getString(R.string.measurement_results))
                .append(" · ").append(duration(result.durationNanos));
        if (preferences.getBoolean("fps", true)) text.append("\nFPS: ").append(number(result.source.average, ""));
        if (preferences.getBoolean("low", true)) text.append(" · 1% low: ").append(number(result.source.low1, ""));
        if (preferences.getBoolean("range", true)) text.append("\nMin / Max: ").append(number(result.source.minimum, ""))
                .append(" / ").append(number(result.source.maximum, ""));
        if (preferences.getBoolean("power", true)) text.append("\n").append(number(result.averageWatts, "W"))
                .append(" · ").append(number(result.wattHours, "Wh"));
        if (preferences.getBoolean("battery", true)) text.append("\n").append(getContext().getString(R.string.measurement_battery))
                .append(": ").append(number(result.batteryPercent, "%"));
        if (preferences.getBoolean("mah", true)) text.append(" · ").append(result.estimatedMah ? "≈" : "")
                .append(number(result.batteryMah, "mAh"));
        text.append("\n").append(getContext().getString(R.string.measurement_again));
        return text.toString();
    }
    private String fullReport() {
        StringBuilder text = new StringBuilder("WinXclipse — ")
                .append(getContext().getString(R.string.measurement_results)).append('\n')
                .append(java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(startedWallTime))).append('\n');
        if (metadata != null) for (Map.Entry<String, String> entry : metadata.entrySet())
            text.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
        text.append('\n').append(summary()).append('\n');
        if (result != null) text.append("\n").append(getContext().getString(R.string.measurement_source_note))
                .append("\nSource: ").append(result.source.count)
                .append("\nCompositor: ").append(result.output.count)
                .append("\nGenerated: ").append(result.generatedFrames)
                .append("\nOutput FPS: ").append(number(result.output.average, ""))
                .append("\n").append(getContext().getString(R.string.measurement_power_coverage)).append(": ")
                .append(number(result.powerSeconds, "s"))
                .append("\n").append(getContext().getString(R.string.measurement_charging_note));
        return text.toString();
    }
    private void saveReport() {
        if (result == null || saving) return;
        saving = true;
        Context context = getContext().getApplicationContext();
        String report = fullReport();
        java.util.concurrent.ExecutorService writer = java.util.concurrent.Executors.newSingleThreadExecutor();
        writer.execute(() -> {
            String path = null, error = null;
            try { path = GameplayReportStore.save(context, report); }
            catch (Exception failure) { error = failure.getMessage(); }
            String message = error == null ? context.getString(R.string.measurement_saved, path)
                    : context.getString(R.string.measurement_save_failed, error == null ? "" : error);
            handler.post(() -> { saving = false; if (!closed) Toast.makeText(context, message, Toast.LENGTH_LONG).show(); });
        });
        writer.shutdown();
    }
    private static String duration(long nanos) {
        long seconds = nanos / 1_000_000_000L;
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }
    private void constrain() {
        if (!(getParent() instanceof View)) return;
        View parent = (View)getParent();
        setMaxWidth(Math.max(dp(120), Math.min(dp(340), parent.getWidth() - dp(8))));
        setX(Math.max(0, Math.min(getX(), parent.getWidth() - getWidth())));
        setY(Math.max(0, Math.min(getY(), parent.getHeight() - getHeight())));
    }
    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight); post(this::constrain);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getRawX(); downY = event.getRawY(); originalX = getX(); originalY = getY(); dragged = false; break;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                if (dragged || Math.hypot(dx, dy) > dp(8)) {
                    dragged = true; setPressed(false); cancelLongPress();
                    setX(originalX + dx); setY(originalY + dy); constrain(); return true;
                }
                break;
            case MotionEvent.ACTION_UP:
                if (dragged) {
                    preferences.edit().putFloat("x", getX()).putFloat("y", getY()).apply();
                    setPressed(false); return true;
                }
                break;
            case MotionEvent.ACTION_CANCEL: dragged = false; break;
        }
        return super.onTouchEvent(event);
    }
}
