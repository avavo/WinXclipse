package com.winlator.cmod.core;

import android.app.Dialog;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import com.winlator.cmod.R;

import java.util.function.Consumer;

/** Shared floating surfaces; listeners live exactly as long as the popup window. */
public final class PopupStyle {
    private PopupStyle() {}

    public static int theme(Context context) {
        return AppUtils.isDarkMode(context) ? R.style.ContentDialog_Dark : R.style.ContentDialog;
    }

    public static GradientDrawable surface(Context context, boolean blurred) {
        boolean dark = AppUtils.isDarkMode(context);
        GradientDrawable background = new GradientDrawable();
        background.setColor(dark ? (blurred ? 0xB31B1429 : 0xF01B1429)
                : (blurred ? 0xC9F7F3FF : 0xF5F7F3FF));
        background.setCornerRadius(16 * context.getResources().getDisplayMetrics().density);
        background.setStroke(Math.max(1, Math.round(context.getResources().getDisplayMetrics().density)),
                0x707C4DDF);
        return background;
    }

    public static void install(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        View decor = window.getDecorView();
        if (decor.getTag(R.id.winx_popup_style) != null) return;
        View.OnAttachStateChangeListener listener = new View.OnAttachStateChangeListener() {
            private Consumer<Boolean> blurListener;
            @Override public void onViewAttachedToWindow(View view) {
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                if (Build.VERSION.SDK_INT >= 31) {
                    WindowManager manager = dialog.getContext().getSystemService(WindowManager.class);
                    blurListener = enabled -> update(window, dialog.getContext(), enabled);
                    update(window, dialog.getContext(), manager.isCrossWindowBlurEnabled());
                    manager.addCrossWindowBlurEnabledListener(blurListener);
                } else update(window, dialog.getContext(), false);
            }
            @Override public void onViewDetachedFromWindow(View view) {
                if (Build.VERSION.SDK_INT >= 31 && blurListener != null) {
                    dialog.getContext().getSystemService(WindowManager.class)
                            .removeCrossWindowBlurEnabledListener(blurListener);
                    blurListener = null;
                }
            }
        };
        decor.setTag(R.id.winx_popup_style, listener);
        decor.addOnAttachStateChangeListener(listener);
        if (decor.isAttachedToWindow()) listener.onViewAttachedToWindow(decor);
    }

    public static void installProgress(Dialog dialog) {
        install(dialog);
        Window window = dialog.getWindow();
        if (window == null) return;
        android.util.DisplayMetrics metrics = dialog.getContext().getResources().getDisplayMetrics();
        window.setLayout(Math.max(1, Math.min(Math.round(400 * metrics.density),
                metrics.widthPixels - Math.round(32 * metrics.density))), WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private static void update(Window window, Context context, boolean enabled) {
        window.setBackgroundDrawable(surface(context, enabled));
        window.setDimAmount(enabled ? 0.12f : 0.24f);
        if (Build.VERSION.SDK_INT >= 31) {
            window.setBackgroundBlurRadius(enabled ? 60 : 0);
            WindowManager.LayoutParams params = window.getAttributes();
            params.setBlurBehindRadius(enabled ? 12 : 0);
            if (enabled) params.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            else params.flags &= ~WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            window.setAttributes(params);
        }
    }

    /** For anchored lists, whose public API exposes their attached decor rather than a Window. */
    public static void styleAnchoredList(android.widget.ListView list) {
        if (list == null) return;
        styleAnchoredView(list);
    }

    public static void styleAnchoredView(View content) {
        View root = content.getRootView();
        if (!(root.getLayoutParams() instanceof WindowManager.LayoutParams)) return;
        root.setBackground(surface(content.getContext(), false));
        if (Build.VERSION.SDK_INT < 31 || root.getTag(R.id.winx_popup_style) != null) return;
        WindowManager manager = content.getContext().getSystemService(WindowManager.class);
        Consumer<Boolean> listener = enabled -> {
            if (root.isAttachedToWindow()) updateAnchored(root, manager, enabled);
        };
        root.setTag(R.id.winx_popup_style, listener);
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { }
            @Override public void onViewDetachedFromWindow(View view) {
                manager.removeCrossWindowBlurEnabledListener(listener);
                root.setTag(R.id.winx_popup_style, null);
                root.removeOnAttachStateChangeListener(this);
            }
        });
        updateAnchored(root, manager, manager.isCrossWindowBlurEnabled());
        manager.addCrossWindowBlurEnabledListener(listener);
    }

    private static void updateAnchored(View root, WindowManager manager, boolean enabled) {
        WindowManager.LayoutParams params = (WindowManager.LayoutParams)root.getLayoutParams();
        root.setBackground(surface(root.getContext(), enabled));
        params.format = PixelFormat.TRANSLUCENT;
        params.setBlurBehindRadius(enabled ? 28 : 0);
        if (enabled) params.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
        else params.flags &= ~WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
        manager.updateViewLayout(root, params);
    }

    public static class Builder extends androidx.appcompat.app.AlertDialog.Builder {
        public Builder(Context context) { super(context, theme(context)); }
        public Builder(Context context, int theme) { super(context, theme); }
        @Override public androidx.appcompat.app.AlertDialog create() {
            androidx.appcompat.app.AlertDialog dialog = super.create();
            install(dialog);
            return dialog;
        }
    }

    public static class PlatformBuilder extends android.app.AlertDialog.Builder {
        public PlatformBuilder(Context context) { super(context, theme(context)); }
        public PlatformBuilder(Context context, int theme) { super(context, theme); }
        @Override public android.app.AlertDialog create() {
            android.app.AlertDialog dialog = super.create();
            install(dialog);
            return dialog;
        }
    }
}
