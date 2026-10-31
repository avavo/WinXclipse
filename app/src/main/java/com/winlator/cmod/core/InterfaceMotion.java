package com.winlator.cmod.core;

import android.animation.AnimatorInflater;
import android.animation.ValueAnimator;
import android.animation.StateListAnimator;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.CompoundButton;

import com.winlator.cmod.R;

/** Short, one-shot feedback only. No listeners replace the controls' own actions. */
public final class InterfaceMotion {
    private InterfaceMotion() {}

    public static void apply(View view) {
        boolean enabled = ValueAnimator.areAnimatorsEnabled();
        // Compound buttons already animate their check/track drawable. Keep
        // their text stationary and retain native switch/keyboard semantics.
        boolean target = !(view instanceof CompoundButton)
                && !(view instanceof com.google.android.material.floatingactionbutton.FloatingActionButton)
                && (view instanceof Button || view instanceof ImageButton
                    || (view instanceof ViewGroup && view.isClickable()));
        if (target) {
            Object owned = view.getTag(R.id.interface_motion_binding);
            if (!enabled) {
                if (owned != null && owned == view.getStateListAnimator()) {
                    view.setStateListAnimator(null);
                    view.setTag(R.id.interface_motion_binding, null);
                    view.setScaleX(1);
                    view.setScaleY(1);
                }
            } else if (owned == null || owned != view.getStateListAnimator()) {
                StateListAnimator animator = AnimatorInflater.loadStateListAnimator(view.getContext(), R.animator.control_press);
                view.setStateListAnimator(animator);
                view.setTag(R.id.interface_motion_binding, animator);
            }
        }
        if (!enabled) view.jumpDrawablesToCurrentState();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) apply(group.getChildAt(i));
        }
    }
}
