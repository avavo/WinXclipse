package com.winlator.cmod.core;

import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Space;
import com.winlator.cmod.R;

/** Gives named boolean settings one switch column and one help column. */
public final class FormToggleRows {
    private FormToggleRows() {}
    private static int dp(View view, int value) { return Math.round(value * view.getResources().getDisplayMetrics().density); }
    public static void apply(View view) {
        if (view instanceof CheckBox && namedSetting(view)) style((CheckBox)view);
        if (view instanceof ImageView) {
            try {
                if(view.getResources().getResourceEntryName(view.getId()).endsWith("Help")
                        && view.getLayoutParams() instanceof LinearLayout.LayoutParams) help((ImageView)view);
            } catch(android.content.res.Resources.NotFoundException ignored) {}
        }
        if (view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for (int i=0;i<group.getChildCount();i++) apply(group.getChildAt(i));
            if (group instanceof LinearLayout && ((LinearLayout)group).getOrientation()==LinearLayout.HORIZONTAL)
                align((LinearLayout)group);
        }
    }
    private static void help(ImageView view) {
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(dp(view,36),dp(view,36));
        params.setMarginStart(dp(view,8)); view.setLayoutParams(params);
        view.setPadding(dp(view,6),dp(view,6),dp(view,6),dp(view,6));
    }
    private static boolean namedSetting(View view) {
        try { return view.getResources().getResourceEntryName(view.getId()).startsWith("CB"); }
        catch (android.content.res.Resources.NotFoundException ignored) { return false; }
    }
    private static void style(CheckBox box) {
        box.setButtonDrawable((Drawable)null); box.setBackground(null);
        box.setCompoundDrawablesRelativeWithIntrinsicBounds(null,null,box.getContext().getDrawable(R.drawable.compact_toggle_selector),null);
        box.setCompoundDrawablePadding(dp(box,8)); box.setGravity(Gravity.CENTER_VERTICAL);
        box.setMinHeight(dp(box,48)); box.setPadding(0,dp(box,4),0,dp(box,4));
    }
    private static void align(LinearLayout row) {
        CheckBox box=null;
        for(int i=0;i<row.getChildCount();i++) {
            View child=row.getChildAt(i);
            if(child instanceof CheckBox && namedSetting(child)) { if(box!=null)return; box=(CheckBox)child; }
            else if(!(child instanceof ImageView) && !(child instanceof Space)) return;
        }
        if(box==null)return;
        for(int i=row.getChildCount()-1;i>=0;i--) if(row.getChildAt(i) instanceof Space) row.removeViewAt(i);
        LinearLayout.LayoutParams boxParams=new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1);
        box.setLayoutParams(boxParams); row.setGravity(Gravity.CENTER_VERTICAL); row.setMinimumHeight(dp(row,52));
        int actions=0;
        for(int i=0;i<row.getChildCount();i++) if(row.getChildAt(i) instanceof ImageView) {
            ImageView help=(ImageView)row.getChildAt(i);
            help(help); actions++;
        }
        if(actions==0) { Space reserve=new Space(row.getContext()); row.addView(reserve,new LinearLayout.LayoutParams(dp(row,44),1)); }
    }
}
