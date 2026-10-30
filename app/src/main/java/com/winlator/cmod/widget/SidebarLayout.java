package com.winlator.cmod.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewParent;
import android.widget.LinearLayout;

import com.google.android.material.navigation.NavigationView;
import com.winlator.cmod.core.InterfaceMotion;

/** NavigationView headers otherwise receive an unbounded height from their RecyclerView. */
public class SidebarLayout extends LinearLayout {
    public SidebarLayout(Context context, AttributeSet attrs) { super(context, attrs); }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        InterfaceMotion.apply(this);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        ViewParent parent = getParent();
        while (parent != null && !(parent instanceof NavigationView)) parent = parent.getParent();
        if (parent instanceof View) {
            View host = (View) parent;
            int height = host.getMeasuredHeight() - host.getPaddingTop() - host.getPaddingBottom();
            if (height > 0) heightMeasureSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
