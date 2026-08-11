package com.winlator.cmod.core;

import android.app.Activity;
import android.app.Dialog;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.google.android.material.progressindicator.CircularProgressIndicator;

import com.winlator.cmod.R;

public class PreloaderDialog {
    private final Activity activity;
    private Dialog dialog;

    public PreloaderDialog(Activity activity) {
        this.activity = activity;
    }

    private void create() {
        if (dialog != null) return;
        dialog = new Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setContentView(R.layout.preloader_dialog);

        boolean dark = PreferenceManager.getDefaultSharedPreferences(activity)
                .getBoolean("dark_mode", false);
        dialog.findViewById(R.id.LLPreloaderPanel).setBackgroundResource(
                dark ? R.drawable.preloader_panel_dark : R.drawable.preloader_panel_light);
        int foreground = ContextCompat.getColor(activity,
                dark ? android.R.color.white : android.R.color.black);
        int accent = ContextCompat.getColor(activity,
                dark ? R.color.colorAccentDark : R.color.colorAccent);
        ((TextView) dialog.findViewById(R.id.TextView)).setTextColor(foreground);
        ((TextView) dialog.findViewById(R.id.TVProgressPercent)).setTextColor(foreground);
        ((CircularProgressIndicator) dialog.findViewById(R.id.ProgressIndicator))
                .setIndicatorColor(accent);

        Window window = dialog.getWindow();
        if (window != null) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
    }

    public synchronized void show(int textResId) {
        if (isShowing()) return;
        close();
        if (dialog == null) create();
        ((TextView)dialog.findViewById(R.id.TextView)).setText(textResId);
        dialog.show();
    }

    public void setProgress(int progress) {
        activity.runOnUiThread(() -> {
            if (dialog == null) return;
            int safeProgress = Math.max(0, Math.min(100, progress));
            CircularProgressIndicator indicator = dialog.findViewById(R.id.ProgressIndicator);
            indicator.setIndeterminate(false);
            indicator.setMax(100);
            indicator.setProgressCompat(safeProgress, true);
            TextView percent = dialog.findViewById(R.id.TVProgressPercent);
            percent.setText(safeProgress + "%");
            percent.setVisibility(android.view.View.VISIBLE);
        });
    }

    public void setIndeterminate() {
        activity.runOnUiThread(() -> {
            if (dialog != null) {
                ((CircularProgressIndicator) dialog.findViewById(R.id.ProgressIndicator))
                        .setIndeterminate(true);
                dialog.findViewById(R.id.TVProgressPercent).setVisibility(android.view.View.GONE);
            }
        });
    }

    public void showOnUiThread(final int textResId) {
        activity.runOnUiThread(() -> show(textResId));
    }

    public synchronized void close() {
        try {
            if (dialog != null) {
                dialog.dismiss();
            }
        }
        catch (Exception e) {}
    }

    public void closeOnUiThread() {
        activity.runOnUiThread(this::close);
    }

    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }
}
