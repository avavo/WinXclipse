package com.winlator.cmod.core;

import android.app.Activity;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.view.View;
import android.widget.Button;

import com.winlator.cmod.R;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.widget.GlassPopupMenu;
import com.winlator.cmod.widget.GlassSpinner;
import com.winlator.cmod.widget.ThemedSpinnerAdapter;
import com.winlator.cmod.widget.FileProgressDialog;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class PopupStyleTest {
    private ActivityController<Activity> controller;
    private Activity activity;
    @Before public void setUp() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get(); activity.setTheme(R.style.AppTheme);
    }
    @After public void tearDown() { controller.pause().stop().destroy(); }

    @Test public void oldAndroidUsesTintedSurfaceAndInstallIsIdempotentAcrossShows() {
        ContentDialog dialog = new ContentDialog(activity);
        dialog.setMessage("test");
        PopupStyle.install(dialog);
        Object listener = dialog.getWindow().getDecorView().getTag(R.id.winx_popup_style);
        dialog.show();
        assertTrue(dialog.getWindow().getDecorView().getBackground() instanceof GradientDrawable);
        dialog.dismiss();
        dialog.show();
        assertSame(listener, dialog.getWindow().getDecorView().getTag(R.id.winx_popup_style));
        dialog.dismiss();
    }

    @Test public void platformBuilderKeepsButtonCallbacks() {
        AtomicInteger clicks = new AtomicInteger();
        android.app.AlertDialog dialog = new PopupStyle.PlatformBuilder(activity).setMessage("test")
                .setPositiveButton(android.R.string.ok, (d, which) -> clicks.incrementAndGet()).show();
        assertTrue(dialog.getWindow().getDecorView().getBackground() instanceof GradientDrawable);
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, clicks.get());
        dialog.dismiss();
    }

    @Test public void appCompatBuilderKeepsButtonCallbacks() {
        AtomicInteger clicks = new AtomicInteger();
        androidx.appcompat.app.AlertDialog dialog = new PopupStyle.Builder(activity).setMessage("test")
                .setPositiveButton(android.R.string.ok, (d, which) -> clicks.incrementAndGet()).show();
        assertTrue(dialog.getWindow().getDecorView().getBackground() instanceof GradientDrawable);
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, clicks.get());
        dialog.dismiss();
    }

    @Test public void anchoredMenuRetainsActionsAndDismissCallback() {
        Button anchor = new Button(activity); anchor.setText("menu");
        activity.setContentView(anchor);
        AtomicInteger clicks = new AtomicInteger(), dismissed = new AtomicInteger();
        GlassPopupMenu menu = new GlassPopupMenu(activity, anchor);
        menu.getMenu().add(0, 7, 0, "Action");
        menu.setOnMenuItemClickListener(item -> { clicks.incrementAndGet(); return true; });
        menu.setOnDismissListener(popup -> dismissed.incrementAndGet());
        menu.show();
        assertTrue(menu.getMenu().performIdentifierAction(7, 0));
        assertEquals(1, clicks.get());
        menu.dismiss();
        assertEquals(1, dismissed.get());
    }

    @Test public void sidebarBlurSoftensBothDirectionsWithoutDarkeningAUniformImage() {
        Bitmap image = Bitmap.createBitmap(17, 17, Bitmap.Config.ARGB_8888);
        image.eraseColor(Color.WHITE);
        SidebarBackdrop.blur(image);
        assertEquals(Color.WHITE, image.getPixel(0, 0));
        assertEquals(Color.WHITE, image.getPixel(8, 8));
        image.eraseColor(Color.BLACK);
        image.setPixel(8, 8, Color.WHITE);
        SidebarBackdrop.blur(image);
        assertTrue(Color.red(image.getPixel(8, 8)) > 0);
        assertTrue(Color.red(image.getPixel(8, 8)) < 255);
        assertTrue(Color.red(image.getPixel(7, 8)) > 0);
        assertTrue(Color.red(image.getPixel(8, 7)) > 0);
        image.recycle();
    }

    @Test public void sidebarCanCloseWithoutAnAvailableGameSurface() {
        View panel = new View(activity);
        try (SidebarBackdrop backdrop = new SidebarBackdrop(panel)) {
            backdrop.capture(null);
            backdrop.clear();
            assertNotNull(panel.getBackground());
        }
    }

    @Test public void spinnerSelectionKeepsOriginalObjectsAndDispatchesTheSelectedValue() {
        GlassSpinner spinner = new GlassSpinner(activity);
        Object first = new Object() { @Override public String toString() { return "QuAsAr 67"; } };
        Object second = new Object() { @Override public String toString() { return "Other driver"; } };
        spinner.setAdapter(new ThemedSpinnerAdapter<>(activity, new Object[]{first, second}));
        activity.setContentView(spinner);
        assertTrue(spinner.performClick());
        assertSame(first, spinner.getSelectedItem());
        spinner.setSelection(1);
        assertSame(second, spinner.getSelectedItem());
        spinner.setEnabled(false);
        assertFalse(spinner.performClick());
    }

    @Test public void fallbackSurfaceIsMoreOpaqueThanBlurSurface() {
        GradientDrawable blurred = PopupStyle.surface(activity, true);
        GradientDrawable fallback = PopupStyle.surface(activity, false);
        assertTrue(Color.alpha(blurred.getColor().getDefaultColor()) < Color.alpha(fallback.getColor().getDefaultColor()));
    }

    @Test public void spinnerDialogSelectsTheActualAdapterObjectAndCloses() {
        GlassSpinner spinner = new GlassSpinner(activity, Robolectric.buildAttributeSet()
                .addAttribute(android.R.attr.spinnerMode, "0").build());
        Object first = new Object(), second = new Object();
        spinner.setAdapter(new ThemedSpinnerAdapter<>(activity, new Object[]{first, second}));
        activity.setContentView(spinner);
        assertTrue(spinner.performClick());
        androidx.appcompat.app.AlertDialog dialog = (androidx.appcompat.app.AlertDialog)ShadowDialog.getLatestDialog();
        assertTrue(dialog.isShowing());
        dialog.getListView().performItemClick(null, 1, 1);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertSame(second, spinner.getSelectedItem());
        assertFalse(dialog.isShowing());
    }

    @Test @Config(sdk = 31)
    public void androidTwelveBlurListenerCanAttachDetachAndReattach() {
        ContentDialog dialog = new ContentDialog(activity);
        dialog.setMessage("test");
        dialog.show();
        assertTrue(dialog.getWindow().getDecorView().getBackground() instanceof GradientDrawable);
        dialog.dismiss();
        dialog.show();
        dialog.dismiss();
    }

    @Test public void downloadProgressKeepsCancellationAndCanClose() {
        DownloadProgressDialog progress = new DownloadProgressDialog(activity);
        AtomicInteger cancelled = new AtomicInteger();
        progress.show(cancelled::incrementAndGet);
        android.app.Dialog dialog = ShadowDialog.getLatestDialog();
        assertTrue(dialog.isShowing());
        dialog.findViewById(R.id.BTCancel).performClick();
        assertEquals(1, cancelled.get());
        progress.close();
        assertFalse(dialog.isShowing());
    }

    @Test public void fileProgressCanShowUpdateAndDismiss() {
        FileProgressDialog progress = new FileProgressDialog(activity);
        progress.show(R.string.copy);
        progress.updateItems("game.exe", 1, 3);
        android.app.Dialog dialog = ShadowDialog.getLatestDialog();
        assertTrue(dialog.isShowing());
        assertTrue(dialog.getWindow().getAttributes().width <= 288);
        progress.dismiss();
        assertFalse(dialog.isShowing());
    }

    @Test public void preloaderCanShowAndCloseWithTheSharedTheme() {
        PreloaderDialog progress = new PreloaderDialog(activity);
        progress.showOnUiThread(R.string.loading);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(progress.isShowing());
        progress.close();
        assertFalse(progress.isShowing());
    }
}
