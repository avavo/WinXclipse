package com.winlator.cmod.core;

import android.content.res.ColorStateList;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import com.winlator.cmod.R;

/** Shared action treatment for normal pages, dialogs and embedded sidebar pages. */
public final class ControlStyle {
    private ControlStyle() {}
    public static void apply(View root) {
        style(root);
        FormToggleRows.apply(root);
    }
    private static boolean namedAction(View view) {
        try { return view.getResources().getResourceEntryName(view.getId()).startsWith("BT"); }
        catch(android.content.res.Resources.NotFoundException ignored) { return false; }
    }
    private static boolean outlinedIcon(View view) {
        // Settings toolbars use plain glyphs, including preset import and the
        // default-container gear. Field actions in container editors keep their outline.
        for (View ancestor=view; ancestor!=null;
                ancestor=ancestor.getParent() instanceof View ? (View)ancestor.getParent() : null)
            if (ancestor.getId()==R.id.SettingsPage) return false;
        try {
            String name=view.getResources().getResourceEntryName(view.getId());
            return name.contains("Download") || name.endsWith("Config") || name.endsWith("ConfigIcon")
                    || name.endsWith("Settings") || (name.startsWith("BTManage")&&name.contains("Content"));
        } catch(android.content.res.Resources.NotFoundException ignored) { return false; }
    }
    private static void style(View view) {
        if (view instanceof Button && !(view instanceof CompoundButton)) {
            androidx.core.view.ViewCompat.setBackgroundTintList(view,null);
            view.setBackgroundResource(R.drawable.button_neutral);
            ((Button)view).setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled},{}},new int[]{0x80FFFFFF,0xFFFFFFFF}));
        } else if (view instanceof android.widget.ImageView
                && !(view instanceof com.google.android.material.floatingactionbutton.FloatingActionButton)
                && (view instanceof ImageButton || namedAction(view))) {
            int start=view.getPaddingStart(),top=view.getPaddingTop(),end=view.getPaddingEnd(),bottom=view.getPaddingBottom();
            androidx.core.view.ViewCompat.setBackgroundTintList(view,null);
            view.setBackgroundResource(outlinedIcon(view)
                    ?R.drawable.rounded_icon_action_small:R.drawable.icon_action_ripple);
            view.setPaddingRelative(start,top,end,bottom);
            if(view.getLayoutParams() instanceof android.widget.LinearLayout.LayoutParams
                    && view.getParent() instanceof android.widget.LinearLayout
                    && ((android.widget.LinearLayout)view.getParent()).getOrientation()==android.widget.LinearLayout.HORIZONTAL)
                ((android.widget.LinearLayout.LayoutParams)view.getLayoutParams()).gravity=android.view.Gravity.CENTER_VERTICAL;
        }
        if(view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++)style(group.getChildAt(i));
        }
    }
}
