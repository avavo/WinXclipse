package com.winlator.cmod.core;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import androidx.appcompat.app.AlertDialog;
import com.winlator.cmod.R;
import java.io.File;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class)
public class CommunityPublicationDialogTest {
    private ActivityController<Activity> controller;
    private Activity activity;
    private int published, withdrawn;
    private boolean failWithdrawal;
    @Before public void setup() {
        controller=Robolectric.buildActivity(Activity.class).setup(); activity=controller.get(); activity.setTheme(R.style.AppTheme);
    }
    @After public void close() { controller.pause().stop().destroy(); }
    private AlertDialog start() {
        new CommunityPublicationDialog(activity,new File(activity.getFilesDir(),"config.zip"),()->{},new CommunityPublicationDialog.Service() {
            public String publish(Context c,File f) { published++; return "own-id"; }
            public void withdraw(Context c,String id) throws Exception {
                assertEquals("own-id",id); withdrawn++; if(failWithdrawal)throw new java.io.IOException();
            }
        },Runnable::run).start();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return (AlertDialog)ShadowDialog.getLatestDialog();
    }
    private Button button(View view,int text) {
        if(view instanceof Button && ((Button)view).getText().toString().equals(activity.getString(text)))return (Button)view;
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            Button found=button(((ViewGroup)view).getChildAt(i),text); if(found!=null)return found;
        }
        return null;
    }
    private Button button(AlertDialog dialog,int text) { Button b=button(dialog.getWindow().getDecorView(),text); assertNotNull(b); return b; }
    @Test public void publishesImmediatelyAndOkOnlyClosesTheWindow() {
        AlertDialog result=start(); assertEquals(1,published);
        Button ok=button(result,android.R.string.ok),cancel=button(result,R.string.community_cancel_submission);
        assertSame(ok.getParent(),cancel.getParent());
        ViewGroup actions=(ViewGroup)ok.getParent(); assertTrue(actions.indexOfChild(cancel)>actions.indexOfChild(ok));
        ok.performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(result.isShowing()); assertEquals(0,withdrawn);
    }
    @Test public void noKeepsPublicationAndYesWithdrawsIt() {
        AlertDialog result=start(); Button cancel=button(result,R.string.community_cancel_submission);
        cancel.performClick(); AlertDialog confirm=(AlertDialog)ShadowDialog.getLatestDialog();
        assertEquals(activity.getString(R.string.no),confirm.getButton(AlertDialog.BUTTON_NEGATIVE).getText());
        confirm.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0,withdrawn); assertTrue(result.isShowing());
        cancel.performClick(); confirm=(AlertDialog)ShadowDialog.getLatestDialog();
        assertEquals(activity.getString(R.string.yes),confirm.getButton(AlertDialog.BUTTON_POSITIVE).getText());
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1,withdrawn); assertEquals(View.GONE,cancel.getVisibility()); assertTrue(result.isShowing());
    }
    @Test public void failedWithdrawalKeepsRetryAction() {
        failWithdrawal=true; AlertDialog result=start(); Button cancel=button(result,R.string.community_cancel_submission);
        cancel.performClick(); ((AlertDialog)ShadowDialog.getLatestDialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(cancel.isEnabled()); assertEquals(View.VISIBLE,cancel.getVisibility()); assertEquals(1,withdrawn);
    }
}
