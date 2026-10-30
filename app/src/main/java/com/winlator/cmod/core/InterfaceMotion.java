package com.winlator.cmod.core;

import android.animation.AnimatorInflater;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;

import com.winlator.cmod.R;

/** Short, one-shot feedback only. No listeners replace the controls' own actions. */
public final class InterfaceMotion {
    private InterfaceMotion() {}

    public static void apply(View view) {
        if (view instanceof Button || view instanceof ImageButton) {
            view.setStateListAnimator(ValueAnimator.areAnimatorsEnabled()
                    ? AnimatorInflater.loadStateListAnimator(view.getContext(), R.animator.control_press)
                    : null);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) apply(group.getChildAt(i));
        }
    }
}
