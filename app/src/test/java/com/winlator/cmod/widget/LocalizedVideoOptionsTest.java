package com.winlator.cmod.widget;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;
import android.widget.Spinner;
import com.winlator.cmod.R;
import com.winlator.cmod.contentdialog.DxvkConfSanitizer;
import com.winlator.cmod.contentdialog.VideoConfigDialog;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.lang.reflect.Proxy;
import java.util.Locale;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public class LocalizedVideoOptionsTest {
    private Context context(String language) {
        Context app=RuntimeEnvironment.getApplication();Configuration configuration=new Configuration(app.getResources().getConfiguration());
        configuration.setLocale(Locale.forLanguageTag(language));
        return new ContextThemeWrapper(app.createConfigurationContext(configuration),R.style.AppTheme_Dark);
    }
    @Test public void translatedVideoChoicesSaveTheSameRuntimeTokens() {
        for(String language:new String[]{"pt","es"}) {
            Context ctx=context(language);Object[][] saved={null};
            VideoConfigDialog.Config config=(VideoConfigDialog.Config)Proxy.newProxyInstance(VideoConfigDialog.Config.class.getClassLoader(),
                    new Class<?>[]{VideoConfigDialog.Config.class},(proxy,method,args)->{
                switch(method.getName()) {
                    case "getDisplayBackend":return "vulkan";
                    case "getVideoOptions":return java.util.Collections.emptyMap();
                    case "getWineRenderer":return "vulkan";
                    case "getGpuName":return "Device";
                    case "getPresentMode":return "mailbox";
                    case "getRefreshRate":return "auto";
                    case "getFsrMode":return "off";
                    case "getFsrQuality":return "balanced";
                    case "getFsrUpscale":return "1";
                    case "getTextureFilterMode":return 2;
                    case "apply":saved[0]=args;return null;
                    default:
                        if(method.getReturnType()==void.class)return null;
                        if(method.getReturnType()==int.class)return 0;
                        if(method.getReturnType()==boolean.class)return false;
                        return "0";
                }
            });
            VideoConfigDialog dialog=new VideoConfigDialog(ctx,config);dialog.show();
            Spinner upscale=dialog.findViewById(R.id.SVideoFsrUpscale),quality=dialog.findViewById(R.id.SVideoFsrMode);
            assertEquals(ctx.getString(R.string.on),upscale.getItemAtPosition(1));
            assertEquals(1,upscale.getSelectedItemPosition());
            assertEquals(android.view.View.VISIBLE,dialog.findViewById(R.id.LLVideoFsrMode).getVisibility());
            assertEquals(ctx.getString(R.string.fsr_quality_scale,"1.5"),quality.getItemAtPosition(1));
            ((Spinner)dialog.findViewById(R.id.SVideoTextureFilter)).setSelection(2);upscale.setSelection(1);quality.setSelection(4);
            dialog.findViewById(R.id.BTConfirm).performClick();
            assertNotNull(saved[0]);assertEquals("1",saved[0][4]);assertEquals("ultraperformance",saved[0][5]);
        }
    }
    @Test public void translatedImportWarningsKeepRamLimitsAndExportedConfigUnchanged() {
        String input="dxgi.maxDeviceMemory = 512\ndxvk.numCompilerThreads = 12\nd3d11.relaxedBarriers = True\n";
        DxvkConfSanitizer.Result reference=DxvkConfSanitizer.sanitize(input);
        for(String language:new String[]{"pt","es"}) {
            Context ctx=context(language);DxvkConfSanitizer.Result translated=DxvkConfSanitizer.sanitize(ctx,input);
            assertEquals(reference.sanitized,translated.sanitized);
            assertTrue(translated.sanitized.contains("dxgi.maxDeviceMemory = 512"));
            assertEquals(ctx.getString(R.string.dxvk_issue_threads,"12"),translated.issues.get(0));
            assertEquals(ctx.getString(R.string.dxvk_issue_barriers),translated.issues.get(1));
        }
    }
}
