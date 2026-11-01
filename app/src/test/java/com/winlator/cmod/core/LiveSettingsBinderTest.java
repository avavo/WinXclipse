package com.winlator.cmod.core;

import android.app.Activity;
import android.app.Application;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.winlator.cmod.R;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contentdialog.SidebarDialogHost;
import com.winlator.cmod.widget.GlassSpinner;
import com.winlator.cmod.widget.ThemedSpinnerAdapter;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,qualifiers="w360dp-h640dp-mdpi")
public class LiveSettingsBinderTest {
    public static class Host extends Activity implements SidebarDialogHost {
        FrameLayout panel;
        @Override public boolean presentSidebarDialog(ContentDialog dialog) {
            View content=dialog.getContentView();
            if(content.getParent() instanceof ViewGroup) ((ViewGroup)content.getParent()).removeView(content);
            panel.addView(content,new FrameLayout.LayoutParams(-1,-1)); return true;
        }
        @Override public void dismissSidebarDialog(ContentDialog dialog) { panel.removeView(dialog.getContentView()); }
    }
    private void layout(View view,int width,int height) {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
        view.layout(0,0,width,height);
    }
    @Test public void userChangesKeepExistingCallbacksAndClosingCommitsPendingEdit() {
        try(var controller=Robolectric.buildActivity(Host.class).setup()) {
            Host host=controller.get();host.setTheme(R.style.AppTheme_Dark);
            LinearLayout root=new LinearLayout(host);root.setOrientation(LinearLayout.VERTICAL);host.setContentView(root);
            CheckBox box=new CheckBox(host);root.addView(box);
            GlassSpinner spinner=new GlassSpinner(host);root.addView(spinner);
            spinner.setAdapter(new ThemedSpinnerAdapter<>(host,new String[]{"one","two"}));
            AtomicInteger checked=new AtomicInteger(),selected=new AtomicInteger(-1),applied=new AtomicInteger();
            box.setOnCheckedChangeListener((button,value)->checked.incrementAndGet());
            spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
                public void onItemSelected(AdapterView<?> p,View v,int pos,long id){selected.set(pos);}
                public void onNothingSelected(AdapterView<?> p){}
            });
            layout(root,320,300);
            try(LiveSettingsBinder binder=new LiveSettingsBinder(root,applied::incrementAndGet)) {
                box.performClick();
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
                assertEquals(1,checked.get());assertEquals(1,applied.get());
                spinner.setSelection(1);layout(root,320,300);
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
                assertEquals(1,selected.get());assertEquals(2,applied.get());
                box.performClick();binder.commit();
                assertEquals(2,checked.get());assertEquals(3,applied.get());
            }
        }
    }
    @Test public void embeddedHudUsesFullBodyAndScrollsWhileXCommitsWithoutCancellation() {
        try(var controller=Robolectric.buildActivity(Host.class).setup()) {
            Host host=controller.get();host.setTheme(R.style.AppTheme_Dark);
            host.panel=new FrameLayout(host);host.setContentView(host.panel);
            ContentDialog dialog=new ContentDialog(host,R.layout.hud_config_dialog);
            AtomicInteger applied=new AtomicInteger(),cancelled=new AtomicInteger();
            dialog.setOnConfirmCallback(applied::incrementAndGet);dialog.setOnCancelCallback(cancelled::incrementAndGet);
            dialog.setTitle("HUD");dialog.setSidebarLiveUpdates(true);dialog.show();layout(host.panel,340,400);
            assertEquals(View.GONE,dialog.findViewById(R.id.LLBottomBar).getVisibility());
            ScrollView scroll=(ScrollView)((ViewGroup)dialog.getInflatedLayout()).getChildAt(0);
            assertTrue("HUD body must not consume the entire screen as unbounded content",scroll.getHeight()<400);
            assertTrue("All HUD options must be reachable",scroll.canScrollVertically(1));
            scroll.fullScroll(View.FOCUS_DOWN);layout(host.panel,340,400);
            assertTrue(scroll.getScrollY()>0);
            ((CheckBox)dialog.findViewById(R.id.CBMeasurementMah)).performClick();
            dialog.cancel();assertEquals(1,applied.get());assertEquals(0,cancelled.get());assertFalse(dialog.isShowing());
        }
    }
}
