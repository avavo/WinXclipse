package com.winlator.cmod.widget;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.CheckBox;

import com.winlator.cmod.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import static org.junit.Assert.*;

/** Measures the active XML and actual Android Views; no Activity launches Wine/native code. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "w320dp-h480dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class ClassicInterfaceLayoutTest {
    private Context context() { return new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme); }

    private View inflate(int layout) { return LayoutInflater.from(context()).inflate(layout, null); }

    private void measure(View root, int width, int height) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST));
        root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
    }

    private void assertInside(ViewGroup root, View child) {
        Rect rect = new Rect(0, 0, child.getWidth(), child.getHeight());
        root.offsetDescendantRectToMyCoords(child, rect);
        Rect viewport = new Rect();
        root.getDrawingRect(viewport);
        assertTrue("Control has no size: " + child.getId(), rect.width() > 0 && rect.height() > 0);
        assertTrue("Control outside viewport: " + child.getId() + " " + rect + " in " + viewport,
                viewport.contains(rect));
    }

    @Test public void allDialogActionsRemainReachableInNarrowWindows() {
        ViewGroup root = (ViewGroup)inflate(R.layout.content_dialog);
        int[] actions = {R.id.BTDisableAll, R.id.BTEnableAll, R.id.BTReset, R.id.BTCancel, R.id.BTConfirm};
        for (int id : actions) root.findViewById(id).setVisibility(View.VISIBLE);
        measure(root, 288, 448);
        for (int id : actions) assertInside(root, root.findViewById(id));
    }

    @Test public void lastInputOptionCanBeReachedWithoutMovingTheActionButtons() {
        ViewGroup root = (ViewGroup)inflate(R.layout.content_dialog);
        android.widget.FrameLayout body = root.findViewById(R.id.FrameLayout);
        body.setVisibility(View.VISIBLE);
        View controls = LayoutInflater.from(context()).inflate(R.layout.input_controls_dialog, body, false);
        body.addView(controls);
        root.findViewById(R.id.LLTitleBar).setVisibility(View.VISIBLE);
        ((TextView)root.findViewById(R.id.TVTitle)).setText("Input controls");
        measure(root, 288, 240);
        ScrollView scroll = (ScrollView)controls;
        assertTrue("Input options need a bounded viewport", scroll.getChildAt(0).getHeight() > scroll.getHeight());
        scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
        assertTrue(scroll.getScrollY() > 0);
        assertInside(root, controls.findViewById(R.id.CBDisableTouchscreenMouse));
        assertInside(root, root.findViewById(R.id.BTConfirm));
    }

    @Test public void lastCpuAndGpuRowsAreReachableInTheInlineTaskManager() {
        ViewGroup root = (ViewGroup)inflate(R.layout.inline_task_manager);
        LinearLayout cpu = root.findViewById(R.id.LLInlineCPUInfo);
        for (int i = 0; i < 11; i++) {
            TextView row = new TextView(context());
            row.setTextSize(11);
            row.setText(i == 10 ? "GPU 75% 1200 MHz" : "CPU" + i + " Cortex-X4 2400 MHz 50%");
            cpu.addView(row);
        }
        root.measure(View.MeasureSpec.makeMeasureSpec(288, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 288, 320);
        ScrollView scroll = root.findViewById(R.id.SVInlineCPUInfo);
        scroll.scrollTo(0, cpu.getHeight());
        assertTrue(scroll.getScrollY() > 0);
        assertInside(root, cpu.getChildAt(10));
        assertInside(scroll, cpu.getChildAt(10));
        assertInside(root, root.findViewById(R.id.BTInlineNewTask));
    }

    @Test public void cpuSelectionCanChangeFromAnExplicitListToARange() {
        CPUListView cpu = new CPUListView(context());
        cpu.setCheckedCPUList("0");
        cpu.setCheckedCPUList(0, 1);
        assertEquals("0", cpu.getCheckedCPUListAsString());
    }

    @Test public void decacoreGridReflowsWithoutLosingChangesMadeOnScreen() {
        CPUListView cpu = new CPUListView(context(), null, 0, 10);
        cpu.setCheckedCPUList("0,4,9");
        measure(cpu, 300, 800);
        ((CheckBox)cpu.findViewWithTag("CPU4")).setChecked(false);
        ((CheckBox)cpu.findViewWithTag("CPU7")).setChecked(true);
        measure(cpu, 180, 800);
        assertEquals("0,7,9", cpu.getCheckedCPUListAsString());
        assertTrue(cpu.getChildCount() >= 4);
        for (int i = 0; i < 10; i++) assertInside(cpu, cpu.findViewWithTag("CPU" + i));
        measure(cpu, 500, 800);
        assertEquals("0,7,9", cpu.getCheckedCPUListAsString());
    }

    @Test public void wholeCpuTileTogglesItsCoreAndRangeAlsoWorksBeforeAStringSelection() {
        CPUListView cpu = new CPUListView(context(), null, 0, 10);
        cpu.setCheckedCPUList(7, 30);
        assertEquals("7,8,9", cpu.getCheckedCPUListAsString());
        CheckBox last = cpu.findViewWithTag("CPU9");
        ((View)last.getParent().getParent()).performClick();
        assertEquals("7,8", cpu.getCheckedCPUListAsString());
        cpu.setCheckedCPUList(null);
        assertEquals("0,1,2,3,4,5,6,7,8,9", cpu.getCheckedCPUListAsString());
    }

    @Test public void largeFontActionsAndCpuCoresFitWithoutOverlapping() {
        Configuration config = new Configuration(context().getResources().getConfiguration());
        config.fontScale = 1.6f;
        Context enlarged = new ContextThemeWrapper(context().createConfigurationContext(config), R.style.AppTheme);
        ViewGroup root = (ViewGroup)LayoutInflater.from(enlarged).inflate(R.layout.content_dialog, null);
        int[] ids = {R.id.BTDisableAll, R.id.BTEnableAll, R.id.BTReset, R.id.BTCancel, R.id.BTConfirm};
        for (int id : ids) root.findViewById(id).setVisibility(View.VISIBLE);
        measure(root, 288, 448);
        Rect previous = new Rect();
        for (int id : ids) {
            View child = root.findViewById(id);
            assertInside(root, child);
            Rect bounds = new Rect(0, 0, child.getWidth(), child.getHeight());
            root.offsetDescendantRectToMyCoords(child, bounds);
            assertFalse("Actions overlap", Rect.intersects(previous, bounds));
            previous = bounds;
        }
        CPUListView cpu = new CPUListView(enlarged, null, 0, 10);
        measure(cpu, 256, 800);
        assertTrue(cpu.getChildCount() >= 4);
        for (int i = 0; i < 10; i++) assertInside(cpu, cpu.findViewWithTag("CPU" + i));
    }

    @Test public void colorControlsFitTheDialogAndTheirLastProfileCanBeScrolledIntoView() {
        ViewGroup root = (ViewGroup)inflate(R.layout.content_dialog);
        android.widget.FrameLayout body = root.findViewById(R.id.FrameLayout);
        body.setVisibility(View.VISIBLE);
        View effects = LayoutInflater.from(context()).inflate(R.layout.screen_effect_dialog, body, false);
        ((android.widget.Spinner)effects.findViewById(R.id.SProfile)).setAdapter(new android.widget.ArrayAdapter<>(
                context(), android.R.layout.simple_spinner_item, new String[]{"Default"}));
        body.addView(effects);
        measure(root, 288, 280);
        ScrollView scroll = (ScrollView)effects;
        assertTrue(scroll.getChildAt(0).getHeight() > scroll.getHeight());
        assertInside(root, effects.findViewById(R.id.SBBrightness));
        scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
        assertInside(root, effects.findViewById(R.id.SProfile));
        assertInside(root, root.findViewById(R.id.BTConfirm));
    }

    @Test public void drawerFitsASmallWindowAndItsHeaderHasABoundedHeight() {
        ViewGroup root = (ViewGroup)inflate(R.layout.xserver_display_activity);
        root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 320, 480);
        com.google.android.material.navigation.NavigationView navigation = root.findViewById(R.id.NavigationView);
        assertTrue("Drawer must fit the host window", navigation.getMeasuredWidth() <= 320);
        View header = navigation.getHeaderView(0);
        assertTrue(header.getMeasuredHeight() > 0 && header.getMeasuredHeight() <= navigation.getMeasuredHeight());
        ScrollView sidebar = header.findViewById(R.id.SVSidebarMain);
        assertTrue("Sidebar options need a bounded viewport", sidebar.getHeight() > 0 && sidebar.getHeight() < header.getHeight());
    }

    @Test @Config(qualifiers = "w800dp-h480dp-mdpi")
    public void wideColorDialogKeepsBothColumnsWithinItsBody() {
        ViewGroup root = (ViewGroup)inflate(R.layout.content_dialog);
        android.widget.FrameLayout body = root.findViewById(R.id.FrameLayout);
        body.setVisibility(View.VISIBLE);
        View effects = LayoutInflater.from(context()).inflate(R.layout.screen_effect_dialog, body, false);
        ((android.widget.Spinner)effects.findViewById(R.id.SProfile)).setAdapter(new android.widget.ArrayAdapter<>(
                context(), android.R.layout.simple_spinner_item, new String[]{"Default"}));
        body.addView(effects);
        measure(root, 648, 400);
        assertInside(root, effects.findViewById(R.id.SBBrightness));
        assertInside(root, effects.findViewById(R.id.SProfile));
        assertTrue(effects.findViewById(R.id.SProfile).getWidth() > 0);
    }

    @Test public void translatedActionsFitBothThemesWithLargeFonts() {
        for (String language : new String[]{"en", "pt", "es"}) {
            for (int theme : new int[]{R.style.AppTheme, R.style.AppTheme_Dark}) {
                Configuration config = new Configuration(context().getResources().getConfiguration());
                config.fontScale = 1.6f;
                config.setLocale(new java.util.Locale(language));
                Context themed = new ContextThemeWrapper(context().createConfigurationContext(config), theme);
                ViewGroup root = (ViewGroup)LayoutInflater.from(themed).inflate(R.layout.content_dialog, null);
                int[] ids = {R.id.BTDisableAll, R.id.BTEnableAll, R.id.BTReset, R.id.BTCancel, R.id.BTConfirm};
                for (int id : ids) root.findViewById(id).setVisibility(View.VISIBLE);
                measure(root, 288, 448);
                for (int id : ids) assertInside(root, root.findViewById(id));
            }
        }
    }

    @Test public void swipingTheActualSidebarScrollsItsOptionsThroughTheNavigationParent() {
        androidx.drawerlayout.widget.DrawerLayout root = (androidx.drawerlayout.widget.DrawerLayout)inflate(R.layout.xserver_display_activity);
        root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 320, 480);
        View drawer = root.findViewById(R.id.NavigationView);
        root.openDrawer(drawer, false);
        ScrollView scroll = root.findViewById(R.id.SVSidebarMain);
        Rect bounds = new Rect(0, 0, scroll.getWidth(), scroll.getHeight());
        root.offsetDescendantRectToMyCoords(scroll, bounds);
        float x = bounds.left + Math.min(40, bounds.width() / 2f);
        float bottom = bounds.top + bounds.height() * .8f, top = bounds.top + bounds.height() * .2f;
        long time = android.os.SystemClock.uptimeMillis();
        touch(root, time, 0, android.view.MotionEvent.ACTION_DOWN, x, bottom);
        touch(root, time, 16, android.view.MotionEvent.ACTION_MOVE, x, (bottom + top) / 2);
        touch(root, time, 32, android.view.MotionEvent.ACTION_MOVE, x, top);
        touch(root, time, 48, android.view.MotionEvent.ACTION_UP, x, top);
        assertTrue("Navigation parent stole the vertical drag", scroll.getScrollY() > 0);
    }

    private void touch(View view, long down, int elapsed, int action, float x, float y) {
        android.view.MotionEvent event = android.view.MotionEvent.obtain(down, down + elapsed, action, x, y, 0);
        try { view.dispatchTouchEvent(event); }
        finally { event.recycle(); }
    }

    @Test public void horizontalColorAdjustmentStillWorksInsideTheNewScroller() {
        ScrollView root = (ScrollView)inflate(R.layout.screen_effect_dialog);
        ((android.widget.Spinner)root.findViewById(R.id.SProfile)).setAdapter(new android.widget.ArrayAdapter<>(
                context(), android.R.layout.simple_spinner_item, new String[]{"Default"}));
        measure(root, 256, 220);
        SeekBar brightness = root.findViewById(R.id.SBBrightness);
        Rect bounds = new Rect(0, 0, brightness.getWidth(), brightness.getHeight());
        root.offsetDescendantRectToMyCoords(brightness, bounds);
        float y = bounds.centerY();
        long time = android.os.SystemClock.uptimeMillis();
        touch(root, time, 0, android.view.MotionEvent.ACTION_DOWN, bounds.left + 8, y);
        touch(root, time, 16, android.view.MotionEvent.ACTION_MOVE, bounds.right - 8, y);
        touch(root, time, 32, android.view.MotionEvent.ACTION_UP, bounds.right - 8, y);
        assertTrue("Brightness drag stopped reaching the slider", brightness.getValue() > 0);
        assertEquals(0, root.getScrollY());
    }

    @Test public void ramStripKeepsScaledGlyphsInsideItsBounds() {
        for (float scale : new float[]{1f, 1.6f, 2f}) {
            Configuration config = new Configuration(context().getResources().getConfiguration());
            config.fontScale = scale;
            Context scaled = new ContextThemeWrapper(context().createConfigurationContext(config), R.style.AppTheme);
            MemoryUsageBarView memory = new MemoryUsageBarView(scaled);
            memory.setMinimumHeight(52);
            memory.setMemoryUsage(75, 9_000_000_000L, 12_000_000_000L);
            measure(memory, 256, 200);
            android.graphics.Canvas canvas = new android.graphics.Canvas();
            memory.draw(canvas);
            org.robolectric.shadows.ShadowCanvas shadow = org.robolectric.Shadows.shadowOf(canvas);
            assertEquals(2, shadow.getTextHistoryCount());
            float bottom = 0;
            for (int i = 0; i < 2; i++) {
                org.robolectric.shadows.ShadowCanvas.TextHistoryEvent text = shadow.getDrawnTextEvent(i);
                android.graphics.Paint.FontMetrics font = text.paint.getFontMetrics();
                assertTrue("RAM labels overlap", text.y + font.ascent >= bottom);
                bottom = text.y + font.descent;
                assertTrue("RAM label clipped at font scale " + scale, bottom <= memory.getHeight());
            }
        }
    }
}
