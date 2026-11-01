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
    private int pendingProgress = -1;

    public PreloaderDialog(Activity activity) {
        this.activity = activity;
    }

    private void create() {
        if (dialog != null) return;
        dialog = new Dialog(activity, com.winlator.cmod.core.PopupStyle.theme(activity));
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setContentView(R.layout.preloader_dialog);
        com.winlator.cmod.core.PopupStyle.installProgress(dialog);

        boolean dark = AppUtils.isDarkMode(activity);
        dialog.findViewById(R.id.LLPreloaderPanel).setBackgroundColor(android.graphics.Color.TRANSPARENT);
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
        // Never attempt to show a window backed by a dying activity
        // (stale dialog after activity recreation would crash with BadTokenException).
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (dialog == null) create();
        ((TextView)dialog.findViewById(R.id.TextView)).setText(textResId);
        applyProgress();
        try {
            dialog.show();
        }
        catch (Exception e) {}
    }

    public void setProgress(int progress) {
        activity.runOnUiThread(() -> {
            pendingProgress = Math.max(0, Math.min(100, progress));
            applyProgress();
        });
    }

    private void applyProgress() {
        if (dialog == null) return;
        CircularProgressIndicator indicator = dialog.findViewById(R.id.ProgressIndicator);
        TextView percent = dialog.findViewById(R.id.TVProgressPercent);
        if (pendingProgress < 0) {
            indicator.setIndeterminate(true);
            percent.setVisibility(android.view.View.GONE);
        } else {
            // The label and ring must change together; changing an indeterminate
            // Material indicator with an animation can defer the ring update.
            indicator.setIndeterminate(false);
            indicator.setMax(100);
            indicator.setProgressCompat(pendingProgress, false);
            percent.setText(pendingProgress + "%");
            percent.setVisibility(android.view.View.VISIBLE);
        }
    }

    public void setMessage(int resourceId) {
        activity.runOnUiThread(() -> {
            if (dialog != null && !activity.isFinishing() && !activity.isDestroyed())
                ((TextView)dialog.findViewById(R.id.TextView)).setText(resourceId);
        });
    }

    public void setIndeterminate() {
        activity.runOnUiThread(() -> {
            pendingProgress = -1;
            applyProgress();
        });
    }

    /** Lets the user dismiss this progress surface while the worker continues. */
    public synchronized void allowBackground() {
        if (dialog == null) create();
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);
    }

    public void showOnUiThread(final int textResId) {
        activity.runOnUiThread(() -> show(textResId));
    }

    public synchronized void close() {
        pendingProgress = -1;
        try {
            if (dialog != null) {
                dialog.dismiss();
            }
        }
        catch (Exception ignored) {
            // Dismiss is best-effort: the activity may already be gone.
        }
    }

    public void closeOnUiThread() {
        activity.runOnUiThread(this::close);
    }

    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }
}
