package com.winlator.cmod.widget;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import com.winlator.cmod.core.SystemMemoryUsage;
import com.winlator.cmod.core.TelemetryMath;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class)
public class MemoryReadingIntegrationTest {
    @Test public void androidReadingSurvivesUnavailableSwapAndMatchesTaskManagerFormatting() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        info.totalMem = 8L * 1024 * 1024 * 1024;
        info.availMem = 2L * 1024 * 1024 * 1024;
        Shadows.shadowOf((ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE)).setMemoryInfo(info);
        HudDataSource source = new HudDataSource(context);
        Method poll = HudDataSource.class.getDeclaredMethod("pollRam");
        poll.setAccessible(true);
        poll.invoke(source);
        SystemMemoryUsage reading = source.memory;
        assertEquals(info.totalMem, reading.total);
        assertEquals(info.availMem, reading.available);
        assertEquals(75, reading.usedPercent);
        MemoryUsageBarView bar = new MemoryUsageBarView(context);
        bar.setMemoryUsage(reading.usedPercent, reading.used, reading.total);
        Field text = MemoryUsageBarView.class.getDeclaredField("memoryText");
        text.setAccessible(true);
        assertEquals(TelemetryMath.ramPair(reading.total, reading.available), text.get(bar));
        assertTrue(text.get(bar).toString().endsWith("GiB"));
        assertEquals("75%", TelemetryMath.ram(reading.total, reading.available, 0));
        bar.setMemoryUsage(-1, -1, -1);
        assertEquals("N/A", text.get(bar));
        source.close();
    }
}
