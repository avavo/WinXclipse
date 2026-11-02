package com.winlator.cmod.core;

import android.app.Activity;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class)
public class SidebarBackdropTest {
    private ActivityController<Activity> controller;
    private View panel;
    private SidebarBackdrop backdrop;
    private Handler worker;
    private Consumer<SidebarBackdrop.Frame> complete;
    private final AtomicInteger opened = new AtomicInteger();

    @Before public void setup() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        Activity activity = controller.get(); activity.setTheme(com.winlator.cmod.R.style.AppTheme_Dark);
        panel = new View(activity); activity.setContentView(panel);
        backdrop = new SidebarBackdrop(panel, (source, handler, callback) -> { worker=handler; complete=callback; });
    }
    @After public void teardown() {
        backdrop.close(); controller.pause().stop().destroy();
    }
    private Bitmap frame() {
        Bitmap bitmap=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.BLUE); return bitmap;
    }
    private void deliver(Bitmap bitmap) {
        complete.accept(new SidebarBackdrop.Frame(bitmap,0,0,320,180));
        Shadows.shadowOf(worker.getLooper()).idle();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void delayedPixelCopyCannotReplaceBackgroundAfterTimeoutOpenedTheDrawer() {
        Drawable original=panel.getBackground();
        backdrop.capture(null,opened::incrementAndGet);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(701));
        assertEquals(1,opened.get());
        Bitmap late=frame(); deliver(late);
        assertTrue(late.isRecycled());
        assertSame(original,panel.getBackground());
        assertEquals(1,opened.get());
    }

    @Test public void successfulBlurIsInstalledBeforeOpeningAndRetainedIfNextCaptureTimesOut() {
        Drawable original=panel.getBackground();
        backdrop.capture(null,()-> { assertNotSame(original,panel.getBackground()); opened.incrementAndGet(); });
        deliver(frame());
        assertEquals(1,opened.get());
        Drawable cached=panel.getBackground();
        backdrop.capture(null,opened::incrementAndGet);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(701));
        assertEquals(2,opened.get()); assertSame(cached,panel.getBackground());
        Bitmap late=frame(); deliver(late);
        assertTrue(late.isRecycled()); assertSame(cached,panel.getBackground());
    }

    @Test public void blurredSnapshotCoversPanelInsetsAndDoesNotMoveWithDrawerScreenPosition() {
        panel.layout(0,0,380,900);
        backdrop.capture(null,opened::incrementAndGet); deliver(frame());
        Drawable background=panel.getBackground();background.setBounds(0,0,380,900);
        String previous=null;
        for(int x:new int[]{-380,-200,0}) {
            panel.setTranslationX(x);
            android.graphics.Canvas canvas=new android.graphics.Canvas(Bitmap.createBitmap(380,900,Bitmap.Config.ARGB_8888));
            background.draw(canvas);
            String description=Shadows.shadowOf(canvas).getDescription();
            assertTrue(description,description.contains("Bitmap"));
            if(previous!=null)assertEquals(previous,description);
            previous=description;
            // The backdrop no longer depends on the moving panel's global coordinates.
            assertEquals(1,opened.get());assertSame(background,panel.getBackground());
        }
    }

    @Test public void closedBackdropRejectsPendingCaptureAndNeverOpensTheDrawer() {
        backdrop.capture(null,opened::incrementAndGet); backdrop.close();
        Bitmap late=frame(); complete.accept(new SidebarBackdrop.Frame(late,0,0,320,180));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertTrue(late.isRecycled()); assertEquals(0,opened.get());
    }
}
