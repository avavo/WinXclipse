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


/** Shared floating surfaces; listeners live exactly as long as the popup window. */
public final class PopupStyle {
    private PopupStyle() {}

    public static int theme(Context context) {
        return AppUtils.isDarkMode(context) ? R.style.ContentDialog_Dark : R.style.ContentDialog;
    }

    public static GradientDrawable surface(Context context, boolean blurred) {
        boolean dark = AppUtils.isDarkMode(context);
        GradientDrawable background = new GradientDrawable();
        background.setColor(dark ? (blurred ? 0xA01B1429 : 0xFA1B1429)
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
        // The first traversal can occur after show() returns. Keep that first
        // frame legible as well; the attach listener captures the blurred backdrop.
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        update(window, dialog.getContext(), false);
        View.OnAttachStateChangeListener listener = new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
                update(window, dialog.getContext(), false);
                styleContent(decor);
                WINDOWS.put(decor, new java.lang.ref.WeakReference<>(window));
                android.app.Activity activity = PopupBackdrop.activity(dialog.getContext());
                if (activity != null) PopupBackdrop.capture(decor, activity.getWindow());
            }
            @Override public void onViewDetachedFromWindow(View view) { WINDOWS.remove(decor); }
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
    private static final java.util.WeakHashMap<View, java.lang.ref.WeakReference<Window>> WINDOWS = new java.util.WeakHashMap<>();

    public static void styleAnchoredList(android.widget.ListView list) { styleAnchoredView(list); }
    public static void styleAnchoredList(android.widget.ListView list, View anchor) {
        if (list == null) return;
        View root = list.getRootView();
        // The original popup background is on an intermediate container, not just its root.
        View current = list;
        while (current != root) {
            current.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            if (!(current.getParent() instanceof View)) break;
            current = (View)current.getParent();
        }
        root.setBackground(surface(list.getContext(), false));
        if (root.getLayoutParams() instanceof WindowManager.LayoutParams) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams)root.getLayoutParams();
            params.format = PixelFormat.TRANSLUCENT;
            list.getContext().getSystemService(WindowManager.class).updateViewLayout(root, params);
        }
        styleContent(list);
        Window source = null;
        java.lang.ref.WeakReference<Window> reference = WINDOWS.get(anchor.getRootView());
        if (reference != null) source = reference.get();
        android.app.Activity activity = PopupBackdrop.activity(anchor.getContext());
        if (source == null && activity != null) source = activity.getWindow();
        boolean inSidebar = false;
        for (android.view.ViewParent parent = anchor.getParent(); parent != null; parent = parent.getParent())
            if (parent instanceof com.google.android.material.navigation.NavigationView) { inSidebar = true; break; }
        PopupBackdrop.capture(root, source, !inSidebar);
    }
    public static void styleAnchoredView(View content) {
        if (content == null) return;
        styleContent(content);
        View root = content.getRootView();
        root.setBackground(surface(content.getContext(), false));
        android.app.Activity activity = PopupBackdrop.activity(content.getContext());
        if (activity != null) PopupBackdrop.capture(root, activity.getWindow());
    }

    public static void styleContent(View view) {
        ControlStyle.apply(view);
        styleFields(view);
    }

    private static void styleFields(View view) {
        boolean dark = AppUtils.isDarkMode(view.getContext());
        if (view instanceof android.widget.Spinner || view instanceof android.widget.EditText
                || view instanceof com.winlator.cmod.widget.MultiSelectionComboBox) {
            view.setBackgroundResource(dark ? R.drawable.glass_field_dark : R.drawable.glass_field_light);
        } else {
            android.graphics.drawable.Drawable background = view.getBackground();
            int color = background instanceof android.graphics.drawable.ColorDrawable
                    ? ((android.graphics.drawable.ColorDrawable)background).getColor() : 0;
            if (background instanceof GradientDrawable && ((GradientDrawable)background).getColor() != null)
                color = ((GradientDrawable)background).getColor().getDefaultColor();
            if (color == 0xFF000000 || color == 0xFF050505) {
                if (view instanceof android.widget.TextView) view.setBackgroundColor(0);
                else view.setBackgroundResource(dark ? R.drawable.glass_field_dark : R.drawable.glass_field_light);
            }
        }
        if (view instanceof android.widget.TextView && AppUtils.isDarkMode(view.getContext())) {
            android.widget.TextView text = (android.widget.TextView)view;
            int color = text.getCurrentTextColor();
            for (int resource : new int[]{R.color.colorAccent, R.color.colorAccentDark,
                    R.color.colorPrimaryDark, R.color.colorPrimaryDarkModeDark}) {
                if (color == androidx.core.content.ContextCompat.getColor(view.getContext(), resource)) {
                    text.setTextColor(0xFFF8F5FF); break;
                }
            }
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) styleFields(group.getChildAt(i));
        }
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
