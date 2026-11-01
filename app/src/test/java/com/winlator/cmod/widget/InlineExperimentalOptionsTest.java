package com.winlator.cmod.widget;
import android.app.Application;
import android.content.Context;
import android.view.*;
import android.widget.*;
import com.winlator.cmod.R;
import com.winlator.cmod.contentdialog.ExperimentalPerformanceDialog;
import com.winlator.cmod.contentdialog.GraphicsDriverConfigDialog;
import com.winlator.cmod.core.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.util.function.Supplier;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,qualifiers="w360dp-h640dp-mdpi")
public class InlineExperimentalOptionsTest {
    private Context context(){return new ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.AppTheme_Dark);}
    @Test public void inlineValuesPreserveOtherKeysAndFollowTheRequestedOrder() {
        for(int layout:new int[]{R.layout.container_detail_fragment,R.layout.shortcut_settings_dialog}) {
            View root=LayoutInflater.from(context()).inflate(layout,null);
            Supplier<String> read=ExperimentalPerformanceDialog.bindOptions(root,"vramCap=1,vramCapMode=6144,wow64Pin=0,custom=kept");
            ((CheckBox)root.findViewById(R.id.CBXPerfNramv)).setChecked(true);
            ((CheckBox)root.findViewById(R.id.CBXPerfPerfcache)).setChecked(true);
            KeyValueSet values=ExperimentalPerformanceDialog.parseConfig(read.get());
            assertEquals("6144",values.get("vramCapMode"));assertEquals("1",values.get("ramAggro"));
            assertEquals("1",values.get("perfcache"));assertEquals("kept",values.get("custom"));assertEquals("0",values.get("wow64Pin"));
            ViewGroup options=(ViewGroup)root.findViewById(R.id.CBXPerfNramv).getParent().getParent();
            int[] ordered={R.id.CBXPerfNramv,R.id.CBXPerfMaliCleanup,R.id.CBXPerfPerfcache,R.id.CBXPerfVramCap,R.id.SXPerfVramCapMode,R.id.CBXPerfMdiex};
            for(int i=0;i<ordered.length;i++)assertSame(options.getChildAt(i),root.findViewById(ordered[i]).getParent());
            assertEquals(View.GONE,((View)root.findViewById(R.id.CBExperimentalPerformance).getParent()).getVisibility());
        }
        assertEquals("1",ExperimentalPerformanceDialog.parseConfig("").get("wow64Pin"));
    }
    @Test public void disablingBcnClearsOptimizationAndDoesNotRestoreItWhenReenabled() {
        View root=LayoutInflater.from(context()).inflate(R.layout.container_detail_fragment,null);
        View anchor=root.findViewById(R.id.BTGraphicsDriverConfig);anchor.setTag("experimentalBcn=1;bcnXclipseOptimized=1;version=test");
        CheckBox legacy=root.findViewById(R.id.CBExperimentalBCN);
        InlineBcnOptions.bind(root,anchor,legacy);
        CheckBox disabled=root.findViewById(R.id.CBDisableExperimentalBCN),optimized=root.findViewById(R.id.CBInlineBcnOptimized);
        assertFalse(disabled.isChecked());assertTrue(optimized.isChecked());
        disabled.setChecked(true);assertFalse(legacy.isChecked());assertFalse(optimized.isChecked());
        assertEquals(View.GONE,root.findViewById(R.id.LLInlineBcnOptimized).getVisibility());
        assertEquals("0",GraphicsDriverConfigDialog.parseGraphicsDriverConfig(anchor.getTag().toString()).get("experimentalBcn"));
        disabled.setChecked(false);assertTrue(legacy.isChecked());assertFalse(optimized.isChecked());
        assertEquals("test",GraphicsDriverConfigDialog.parseGraphicsDriverConfig(anchor.getTag().toString()).get("version"));
    }
    @Test public void gpuLabelKeepsDeviceAsTheRuntimeValue() {
        ThemedSpinnerAdapter<String> adapter=new ThemedSpinnerAdapter<>(context(),new String[]{"Device"});
        assertEquals("Device",adapter.getItem(0));
        assertEquals("Xclipse",((TextView)adapter.getView(0,null,new FrameLayout(context()))).getText().toString());
    }
}
