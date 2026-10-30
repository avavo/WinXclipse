package com.winlator.cmod.widget;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.widget.LinearLayout;

/** Bounds weighted dialog content while leaving its title and action row visible. */
public class DialogContentLayout extends LinearLayout {
    public DialogContentLayout(Context context, AttributeSet attrs) { super(context, attrs); }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        Rect visible = new Rect();
        getWindowVisibleDisplayFrame(visible);
        int screenHeight = visible.height() > 0 ? visible.height()
                : getResources().getDisplayMetrics().heightPixels;
        int maximum = Math.max(1, screenHeight
                - Math.round(32 * getResources().getDisplayMetrics().density));
        int mode = MeasureSpec.getMode(heightMeasureSpec);
        int size = MeasureSpec.getSize(heightMeasureSpec);
        if (mode == MeasureSpec.UNSPECIFIED || size > maximum) {
            heightMeasureSpec = MeasureSpec.makeMeasureSpec(maximum, MeasureSpec.AT_MOST);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
