package com.winlator.cmod.core;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.*;
import android.widget.CheckBox;
import com.winlator.cmod.R;
import java.util.Locale;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,qualifiers="w320dp-h640dp-mdpi")
public class FormToggleRowsTest {
    private Rect bounds(ViewGroup root,View child) {
        Rect result=new Rect(0,0,child.getWidth(),child.getHeight()); root.offsetDescendantRectToMyCoords(child,result); return result;
    }
    @Test public void settingsToolsStayUnfilledWhileContainerFieldActionsKeepSmallerSurfaces() {
        Context context=new ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.AppTheme_Dark);
        ViewGroup sidebar=(ViewGroup)LayoutInflater.from(context).inflate(R.layout.xserver_sidebar,null);
        View power=sidebar.findViewById(R.id.BTSidebarExit);int padding=power.getPaddingTop();
        ControlStyle.apply(sidebar);
        assertTrue(power.getBackground() instanceof android.graphics.drawable.RippleDrawable);
        assertEquals(1,((android.graphics.drawable.RippleDrawable)power.getBackground()).getNumberOfLayers());
        assertEquals(padding,power.getPaddingTop());
        ViewGroup settings=(ViewGroup)LayoutInflater.from(context).inflate(R.layout.settings_fragment,null);
        View download=settings.findViewById(R.id.BTImportFEXCorePreset);int iconPadding=download.getPaddingStart();
        ControlStyle.apply(settings);ControlStyle.apply(settings);
        assertTrue(download.getBackground() instanceof android.graphics.drawable.RippleDrawable);
        assertEquals(1,((android.graphics.drawable.RippleDrawable)download.getBackground()).getNumberOfLayers());
        View gear=settings.findViewById(R.id.BTDefaultContainerConfigIcon);
        assertTrue(gear.getBackground() instanceof android.graphics.drawable.RippleDrawable);
        assertEquals(1,((android.graphics.drawable.RippleDrawable)gear.getBackground()).getNumberOfLayers());
        ViewGroup editor=(ViewGroup)LayoutInflater.from(context).inflate(R.layout.container_detail_fragment,null);
        ControlStyle.apply(editor);
        assertTrue(editor.findViewById(R.id.BTManageWineContent).getBackground() instanceof android.graphics.drawable.InsetDrawable);
        assertTrue(editor.findViewById(R.id.BTVideoConfig).getBackground() instanceof android.graphics.drawable.InsetDrawable);
        assertEquals(iconPadding,download.getPaddingStart());
        View add=settings.findViewById(R.id.BTAddFEXCorePreset);
        assertEquals(1,((android.graphics.drawable.RippleDrawable)add.getBackground()).getNumberOfLayers());
    }

    @Test public void floatingConfirmActionRetainsItsCircularBackground() {
        Context context=new ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.AppTheme_Dark);
        ViewGroup root=(ViewGroup)LayoutInflater.from(context).inflate(R.layout.settings_fragment,null);
        View confirm=root.findViewById(R.id.BTConfirm);android.graphics.drawable.Drawable original=confirm.getBackground();
        ControlStyle.apply(root);assertSame(original,confirm.getBackground());
    }

    @Test public void settingsSwitchEndsShareAColumnWithAndWithoutHelpButtons() {
        Context context=new ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.AppTheme_Dark);
        ViewGroup root=(ViewGroup)LayoutInflater.from(context).inflate(R.layout.settings_fragment,null);
        ControlStyle.apply(root);
        root.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(20000,View.MeasureSpec.AT_MOST));
        root.layout(0,0,root.getMeasuredWidth(),root.getMeasuredHeight());
        Integer end=null;
        for(int id:new int[]{R.id.CBDarkMode,R.id.CBFollowSystemTheme,R.id.CBUseAlternativeAppIcon,
                R.id.CBEnableWineDebug,R.id.CBEnableBox86_64Logs,R.id.SWShowBackendLogsSidebar,
                R.id.CBEnableFileProvider,R.id.CBOpenWithAndroidBrowser,R.id.CBLockLandscape,R.id.CBShareAndroidClipboard}) {
            CheckBox box=root.findViewById(id);assertNull(box.getButtonDrawable());
            assertNotNull(box.getCompoundDrawablesRelative()[2]);
            int right=bounds(root,box).right-box.getPaddingEnd();
            if(end==null)end=right;else assertEquals("setting "+id,end.intValue(),right);
        }
        android.widget.ImageView preview=(android.widget.ImageView)((ViewGroup)root.findViewById(R.id.CBUseAlternativeAppIcon).getParent()).getChildAt(0);
        if(preview!=null)assertTrue(preview.getWidth()>=48);
    }

    @Test public void controllerPlayersAndVibrationStayInOneColumnAtLargeFont() {
        Configuration config=new Configuration(RuntimeEnvironment.getApplication().getResources().getConfiguration());config.fontScale=1.5f;
        Context context=new ContextThemeWrapper(RuntimeEnvironment.getApplication().createConfigurationContext(config),R.style.AppTheme_Dark);
        ViewGroup root=(ViewGroup)LayoutInflater.from(context).inflate(R.layout.controller_assignment_dialog,null);
        ControlStyle.apply(root);
        root.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(20000,View.MeasureSpec.AT_MOST));
        root.layout(0,0,root.getMeasuredWidth(),root.getMeasuredHeight());
        Integer end=null;
        for(int id:new int[]{R.id.CBAutoGrabController,R.id.CBMasterVibration,R.id.CBPlayer1,R.id.CBVibrateP1,R.id.CBPlayer2,R.id.CBVibrateP2,R.id.CBPlayer3,R.id.CBVibrateP3,R.id.CBPlayer4,R.id.CBVibrateP4}) {
            CheckBox box=root.findViewById(id);assertNotNull(box.getCompoundDrawablesRelative()[2]);
            int right=bounds(root,box).right-box.getPaddingEnd();
            if(end==null)end=right;else assertEquals("controller "+id,end.intValue(),right);
            assertTrue(box.getHeight()>=48);
        }
    }

    @Test public void advancedSwitchAndHelpColumnsStayAlignedWithPortugueseWrapping() {
        Configuration cfg=new Configuration(RuntimeEnvironment.getApplication().getResources().getConfiguration()); cfg.setLocale(new Locale("pt","BR"));
        Context context=new ContextThemeWrapper(RuntimeEnvironment.getApplication().createConfigurationContext(cfg),R.style.AppTheme);
        ViewGroup root=(ViewGroup)LayoutInflater.from(context).inflate(R.layout.container_detail_fragment,null);
        root.findViewById(R.id.LLTabAdvanced).setVisibility(View.VISIBLE);
        FormToggleRows.apply(root); FormToggleRows.apply(root);
        root.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(10000,View.MeasureSpec.AT_MOST));
        root.layout(0,0,root.getMeasuredWidth(),root.getMeasuredHeight());
        int[] switches={R.id.CBWoW64Mode,R.id.CBWoW64PerformancePin,R.id.CBTranslationTurbo,R.id.CBGStreamerWorkaroundToggle,
            R.id.CBXPerfNramv,R.id.CBXPerfMaliCleanup,R.id.CBXPerfPerfcache,R.id.CBXPerfVramCap,R.id.CBXPerfMdiex,R.id.CBInlineBcnOptimized,R.id.CBDisableExperimentalBCN};
        Integer end=null;
        for(int id:switches) {
            CheckBox box=root.findViewById(id); assertNull(box.getButtonDrawable()); assertNotNull(box.getCompoundDrawablesRelative()[2]);
            Rect r=bounds(root,box); assertTrue(r.width()>100); assertTrue(r.height()>=48);
            if(end==null)end=r.right; else assertEquals("switch "+id,end.intValue(),r.right);
        }
        int[] helps={R.id.BTWOW64PerformancePinHelp,R.id.BTTranslationTurboHelp,R.id.BTXPerfNramvHelp,R.id.BTXPerfVramCapModeHelp,R.id.BTInlineBcnOptimizedHelp,R.id.BTDisableExperimentalBCNHelp};
        Integer right=null;
        for(int id:helps) { Rect r=bounds(root,root.findViewById(id)); assertEquals(36,r.width()); if(right==null)right=r.right; else assertEquals(right.intValue(),r.right); }
        CheckBox box=root.findViewById(R.id.CBDisableExperimentalBCN); box.setChecked(false); box.performClick(); assertTrue(box.isChecked());
    }
}
