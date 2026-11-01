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
