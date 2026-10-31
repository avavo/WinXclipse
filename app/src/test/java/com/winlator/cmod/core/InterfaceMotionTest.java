package com.winlator.cmod.core;

import android.animation.ValueAnimator;
import android.app.Application;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;

import com.winlator.cmod.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class InterfaceMotionTest {
    private Context context() { return new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme); }
    private void scale(float scale) { ReflectionHelpers.setStaticField(ValueAnimator.class, "sDurationScale", scale); }

    @Test public void disablingMotionResetsAHeldPressWithoutReplacingItsAction() {
        scale(1);
        Button button = new Button(context());
        AtomicInteger clicks = new AtomicInteger();
        button.setOnClickListener(view -> clicks.incrementAndGet());
        InterfaceMotion.apply(button);
        assertNotNull(button.getTag(R.id.interface_motion_binding));
        button.performClick();
        button.setScaleX(0.98f); button.setScaleY(0.98f);
        scale(0);
        assertFalse(ValueAnimator.areAnimatorsEnabled());
        InterfaceMotion.apply(button);
        assertNull(button.getTag(R.id.interface_motion_binding));
        assertEquals(1f, button.getScaleX(), 0);
        assertEquals(1f, button.getScaleY(), 0);
        button.performClick();
        assertEquals(2, clicks.get());
    }

    @Test public void clickableCardsGetFeedbackAndRepeatedBindingDoesNotRestartIt() {
        scale(1);
        LinearLayout card = new LinearLayout(context());
        AtomicInteger clicks = new AtomicInteger();
        card.setOnClickListener(view -> clicks.incrementAndGet());
        InterfaceMotion.apply(card);
        Object animator = card.getStateListAnimator();
        assertNotNull(animator);
        InterfaceMotion.apply(card);
        assertSame(animator, card.getStateListAnimator());
        card.performClick();
        assertEquals(1, clicks.get());
    }

    @Test public void checkboxesKeepTheirNativeCheckedAnimationAndZeroScaleAddsNoPressAnimator() {
        scale(0);
        LinearLayout root = new LinearLayout(context());
        CheckBox check = new CheckBox(context());
        Object original = check.getStateListAnimator();
        Button button = new Button(context());
        root.addView(check); root.addView(button);
        InterfaceMotion.apply(root);
        assertNull(button.getTag(R.id.interface_motion_binding));
        assertSame(original, check.getStateListAnimator());
        check.performClick();
        assertTrue(check.isChecked());
        scale(1);
        InterfaceMotion.apply(root);
        assertNotNull(button.getTag(R.id.interface_motion_binding));
        assertNull(check.getTag(R.id.interface_motion_binding));
        assertSame(original, check.getStateListAnimator());
    }
}
