package com.winlator.cmod.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewParent;
import android.widget.ScrollView;

/** Keeps a drag inside sidebar controls instead of the NavigationView's outer list. */
public class GestureScrollView extends ScrollView {
    public GestureScrollView(Context context) { super(context); }
    public GestureScrollView(Context context, AttributeSet attrs) { super(context, attrs); }
    public GestureScrollView(Context context, AttributeSet attrs, int style) {
        super(context, attrs, style);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        ViewParent parent = getParent();
        int action = event.getActionMasked();
        if (parent != null && action == MotionEvent.ACTION_DOWN) {
            parent.requestDisallowInterceptTouchEvent(true);
        }
        try {
            return super.dispatchTouchEvent(event);
        } finally {
            if (parent != null && (action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL)) {
                parent.requestDisallowInterceptTouchEvent(false);
            }
        }
    }
}
