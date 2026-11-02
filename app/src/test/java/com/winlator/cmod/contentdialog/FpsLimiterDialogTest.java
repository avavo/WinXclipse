package com.winlator.cmod.contentdialog;

import android.app.Activity;
import android.app.Application;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import com.winlator.cmod.R;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,qualifiers="w360dp-h640dp-mdpi")
public class FpsLimiterDialogTest {
    public static class Host extends Activity implements SidebarDialogHost {
        FrameLayout panel;int presentations;
        @Override public boolean presentSidebarDialog(ContentDialog dialog) {
            View content=dialog.getContentView();
            if(content.getParent() instanceof ViewGroup)((ViewGroup)content.getParent()).removeView(content);
            panel.addView(content,new FrameLayout.LayoutParams(-1,-1));presentations++;return true;
        }
        @Override public void dismissSidebarDialog(ContentDialog dialog){panel.removeView(dialog.getContentView());}
    }
    private void setup(Host host){host.setTheme(R.style.AppTheme_Dark);host.panel=new FrameLayout(host);host.setContentView(host.panel);}
    private RadioButton choice(FpsLimiterDialog dialog,int value){return dialog.findViewById(R.id.RGFpsLimit).findViewWithTag(value);}
    private void idle(){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));}
    @Test public void presetsApplyInsideOneSidebarPageWithoutClosingIt() {
        try(var controller=Robolectric.buildActivity(Host.class).setup()) {
            Host host=controller.get();setup(host);AtomicInteger selected=new AtomicInteger();
            FpsLimiterDialog dialog=new FpsLimiterDialog(host,60,selected::set);dialog.show();
            assertTrue(choice(dialog,60).isChecked());assertEquals(0,selected.get());
            assertEquals(View.GONE,dialog.findViewById(R.id.LLBottomBar).getVisibility());
            choice(dialog,90).performClick();idle();
            assertEquals(90,selected.get());assertTrue(dialog.isShowing());
            assertEquals(1,host.presentations);assertEquals(1,host.panel.getChildCount());
            assertFalse(dialog.getWindow().getDecorView().isAttachedToWindow());
            dialog.cancel();assertEquals(90,selected.get());assertFalse(dialog.isShowing());
        }
    }
    @Test public void customInputStaysInTheSamePageAndRejectsInvalidRates() {
        try(var controller=Robolectric.buildActivity(Host.class).setup()) {
            Host host=controller.get();setup(host);AtomicInteger selected=new AtomicInteger();
            FpsLimiterDialog dialog=new FpsLimiterDialog(host,144,selected::set);dialog.show();
            EditText edit=dialog.findViewById(R.id.ETFpsLimit);
            assertTrue(choice(dialog,0).isChecked());assertEquals(View.VISIBLE,edit.getVisibility());
            edit.setText("5");idle();assertEquals(0,selected.get());assertNotNull(edit.getError());
            edit.setText("241");idle();assertEquals(0,selected.get());
            edit.setText("144");idle();assertEquals(144,selected.get());assertNull(edit.getError());
            assertEquals(1,host.presentations);assertEquals(1,host.panel.getChildCount());
            choice(dialog,60).performClick();dialog.cancel();
            assertEquals(60,selected.get());
        }
    }
    @Test public void choosingCustomNeverCreatesAnotherDialogAndBothLimitsAreAccepted() {
        try(var controller=Robolectric.buildActivity(Host.class).setup()) {
            Host host=controller.get();setup(host);AtomicInteger selected=new AtomicInteger();
            FpsLimiterDialog dialog=new FpsLimiterDialog(host,60,selected::set);dialog.show();
            choice(dialog,0).performClick();
            EditText edit=dialog.findViewById(R.id.ETFpsLimit);assertEquals(View.VISIBLE,edit.getVisibility());
            edit.setText("15");idle();assertEquals(15,selected.get());
            edit.setText("240");dialog.cancel();assertEquals(240,selected.get());
            assertEquals(1,host.presentations);
        }
    }
}
