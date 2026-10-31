package com.winlator.cmod.widget;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.winlator.cmod.R;

/** Wraps short catalogs and bounds long lists without filling the whole screen. */
public class DownloadCatalogLayout extends LinearLayout {
    public DownloadCatalogLayout(Context context, AttributeSet attrs) { super(context, attrs); }
    public static void prepareEmbedded(ViewGroup layout) {
        float density = layout.getResources().getDisplayMetrics().density;
        layout.setPadding(0, Math.round(8 * density), 0, 0);
        if (layout.getLayoutParams() != null) layout.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
        layout.findViewById(R.id.TVEmptyText).setMinimumHeight(Math.round(80 * density));
    }
    @Override protected void onMeasure(int width, int height) {
        Rect visible = new Rect();
        getWindowVisibleDisplayFrame(visible);
        int available = visible.height() > 0 ? visible.height() : getResources().getDisplayMetrics().heightPixels;
        int maximum = Math.max(1, Math.min(Math.round(available * 0.74f),
                Math.round(560 * getResources().getDisplayMetrics().density)));
        if (MeasureSpec.getMode(height) == MeasureSpec.UNSPECIFIED || MeasureSpec.getSize(height) > maximum)
            height = MeasureSpec.makeMeasureSpec(maximum, MeasureSpec.AT_MOST);
        super.onMeasure(width, height);
    }
}
