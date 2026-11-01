package com.winlator.cmod.core;

import android.app.Activity;
import android.app.Application;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import com.winlator.cmod.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class TerminalSessionTest {
    private View layout(Activity activity) {
        activity.setTheme(R.style.AppTheme_Dark);
        View root = activity.getLayoutInflater().inflate(R.layout.activity_terminal, null);
        activity.setContentView(root);
        return root;
    }

    @Test public void missingSystemFilesDisableExecution() {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup()) {
            View root = layout(host.get());
            try (TerminalSession session = new TerminalSession(root, null)) {
                assertFalse(root.findViewById(R.id.executeButton).isEnabled());
                assertEquals(host.get().getString(R.string.terminal_imagefs_invalid),
                        ((TextView)root.findViewById(R.id.outputTextView)).getText().toString());
            }
        }
    }

    @Test public void commandsRunOffTheUiThreadAndReturnToTheSamePanel() throws Exception {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup()) {
            View root = layout(host.get());
            try (TerminalSession session = new TerminalSession(root, command -> {
                assertNotSame(Looper.getMainLooper().getThread(), Thread.currentThread());
                return "result: " + command;
            })) {
                ((EditText)root.findViewById(R.id.commandInput)).setText("echo test");
                Button button = root.findViewById(R.id.executeButton);
                button.performClick();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!button.isEnabled() && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.sleep(1);
                }
                assertTrue(button.isEnabled());
                assertTrue(((TextView)root.findViewById(R.id.outputTextView)).getText().toString().contains("result: echo test"));
            }
        }
    }

    @Test public void closingTheEditorStopsItsWorkerAndIgnoresLateOutput() throws Exception {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup()) {
            View root = layout(host.get());
            CountDownLatch started = new CountDownLatch(1), interrupted = new CountDownLatch(1);
            try (TerminalSession session = new TerminalSession(root, command -> {
                started.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException error) { interrupted.countDown(); Thread.currentThread().interrupt(); }
                return "late result";
            })) {
                ((EditText)root.findViewById(R.id.commandInput)).setText("long task");
                root.findViewById(R.id.executeButton).performClick();
                assertTrue(started.await(3, TimeUnit.SECONDS));
                session.close();
                assertTrue(interrupted.await(3, TimeUnit.SECONDS));
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertFalse(root.findViewById(R.id.executeButton).isEnabled());
                assertFalse(((TextView)root.findViewById(R.id.outputTextView)).getText().toString().contains("late result"));
            }
        }
    }
}
