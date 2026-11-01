package com.winlator.cmod.widget;

import android.app.Application;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Spinner;
import com.winlator.cmod.R;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.contentdialog.VideoConfigDialog;
import com.winlator.cmod.renderer.DisplayBackend;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import java.lang.reflect.Proxy;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,qualifiers="w320dp-h640dp-mdpi")
public class VideoRendererSettingsTest {
    private Context context(){return new ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.AppTheme_Dark);}
    private String savedWine="vulkan";
    private boolean applied;
    private boolean savedDisplayX;
    private String compositor="vulkan";
    private java.util.Map<String,String> videoOptions=new java.util.HashMap<>();
    private VideoConfigDialog.Config config() {
        return (VideoConfigDialog.Config)Proxy.newProxyInstance(VideoConfigDialog.Config.class.getClassLoader(),
                new Class<?>[]{VideoConfigDialog.Config.class},(proxy,method,args)->{
            switch(method.getName()) {
                case "getDisplayBackend":return compositor;
                case "getWineRenderer":return savedWine;
                case "setWineRenderer":savedWine=(String)args[0];return null;
                case "getVideoOptions":return videoOptions;
                case "setVideoOptions":videoOptions.putAll((java.util.Map<String,String>)args[0]);return null;
                case "setDisplayBackend":savedWine=(String)args[0];return null;
                case "isDisplayXEnabled":return savedDisplayX;
                case "setDisplayXEnabled":savedDisplayX=(boolean)args[0];return null;
                case "getGpuName":return "Device";
                case "getPresentMode":return "mailbox";
                case "getRefreshRate":return "auto";
                case "getFsrMode":return "off";
                case "getFsrQuality":return "balanced";
                case "getFrameGenerationProfile":return "fast";
                case "getFrameGenerationMultiplier":return "2";
                case "getFrameGenerationTargetFPS":return "60";
                case "getFrameGenerationBackend":return "gles";
                case "apply":applied=true;return null;
                default:
                    if(method.getReturnType()==int.class)return 0;
                    if(method.getReturnType()==boolean.class)return false;
                    return "0";
            }
        });
    }

    @Test public void visibleSelectorUsesDisplaySettingWithoutOverwritingWine() {
        Container container=new Container(1);container.putExtra("renderer","gl");
        Spinner spinner=new GlassSpinner(context());
        DisplayBackend.bind(spinner,container.getExtra(DisplayBackend.KEY,"vulkan"));
        assertEquals("Vulkan",spinner.getSelectedItem());
        spinner.setSelection(1);
        container.putExtra(DisplayBackend.KEY,DisplayBackend.selected(spinner));
        assertEquals("gl",container.getExtra("renderer"));
        assertEquals("gl",container.getExtra(DisplayBackend.KEY));
        DisplayBackend.bind(spinner,container.getExtra(DisplayBackend.KEY,"vulkan"));
        assertEquals("OpenGL",spinner.getSelectedItem());
    }

    @Test public void gearStagesWineSelectionUntilConfirm() {
        VideoConfigDialog dialog=new VideoConfigDialog(context(),config());dialog.show();
        ((Spinner)dialog.findViewById(R.id.SVideoWineRenderer)).setSelection(0);
        ShadowLooper.idleMainLooper();
        assertEquals("vulkan",savedWine);
        dialog.findViewById(R.id.BTConfirm).performClick();
        assertEquals("gl",savedWine);assertTrue(applied);
    }

    @Test public void cancelDoesNotChangeTheWineSetting() {
        VideoConfigDialog dialog=new VideoConfigDialog(context(),config());dialog.show();
        ((Spinner)dialog.findViewById(R.id.SVideoWineRenderer)).setSelection(0);
        dialog.findViewById(R.id.BTCancel).performClick();
        assertEquals("vulkan",savedWine);assertFalse(applied);
    }
    @Test public void displayXIsStagedAndConfirmedWithVideoOptions() {
        VideoConfigDialog dialog=new VideoConfigDialog(context(),config());dialog.show();
        ((android.widget.CheckBox)dialog.findViewById(R.id.CBVideoDisplayX)).setChecked(true);
        assertFalse(savedDisplayX);dialog.findViewById(R.id.BTConfirm).performClick();
        assertTrue(savedDisplayX);assertTrue(applied);
    }
    @Test public void cancellingDisplayXKeepsTheSavedChoice() {
        savedDisplayX=true;VideoConfigDialog dialog=new VideoConfigDialog(context(),config());dialog.show();
        android.widget.CheckBox box=dialog.findViewById(R.id.CBVideoDisplayX);assertTrue(box.isChecked());box.setChecked(false);
        dialog.findViewById(R.id.BTCancel).performClick();assertTrue(savedDisplayX);assertFalse(applied);
    }

    @Test public void gearRemainsOneVerticalColumnAtPhoneWidth() {
        ViewGroup root=(ViewGroup)LayoutInflater.from(context()).inflate(R.layout.video_config_dialog,null);
        root.measure(View.MeasureSpec.makeMeasureSpec(288,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640,View.MeasureSpec.AT_MOST));
        root.layout(0,0,root.getMeasuredWidth(),root.getMeasuredHeight());
        LinearLayout content=(LinearLayout)root.getChildAt(0);
        assertEquals(LinearLayout.VERTICAL,content.getOrientation());
        int last=0;
        for(int id:new int[]{R.id.SVideoWineRenderer,R.id.SVideoGPUName,R.id.SVideoPresentMode,
                R.id.SVideoRefreshRate,R.id.SVideoTextureFilter,R.id.SVideoVkBasaltEffect}) {
            View v=root.findViewById(id);
            assertEquals(LinearLayout.VERTICAL,((LinearLayout)v.getParent()).getOrientation());
            android.graphics.Rect bounds=new android.graphics.Rect(0,0,v.getWidth(),v.getHeight());
            content.offsetDescendantRectToMyCoords(v,bounds);
            assertTrue(bounds.top>=last);last=bounds.bottom;
            assertTrue(v.getWidth()>150);assertTrue(bounds.right<=content.getWidth());
        }
    }

    @Test public void invalidAndMissingCompositorValuesDefaultToVulkan() {
        for(String value:new String[]{null,"","gdi","typo"})assertEquals("vulkan",DisplayBackend.normalize(value));
        for(String value:new String[]{"gl","OpenGL"," GLES "})assertEquals("gl",DisplayBackend.normalize(value));
    }

    @Test public void androidCompositorIsOutsideAndWineIsInsideTheGear() {
        for (int layout : new int[]{R.layout.container_detail_fragment, R.layout.shortcut_settings_dialog}) {
            View root = LayoutInflater.from(context()).inflate(layout, null);
            assertEquals(View.GONE, root.findViewById(R.id.SWineRenderer).getVisibility());
            assertEquals(View.VISIBLE, root.findViewById(R.id.SRenderer).getVisibility());
        }
    }
    @Test public void openGlCannotEnableDisplayXAndDoesNotExposeItsOptions() {
        compositor="gl";savedDisplayX=true;
        VideoConfigDialog dialog=new VideoConfigDialog(context(),config());dialog.show();
        android.widget.CheckBox box=dialog.findViewById(R.id.CBVideoDisplayX);
        assertFalse(box.isEnabled());assertFalse(box.isChecked());
        assertEquals(View.GONE,dialog.findViewById(R.id.LLVideoDisplayXOptions).getVisibility());
        dialog.findViewById(R.id.BTConfirm).performClick();assertFalse(savedDisplayX);
    }
    @Test public void formatsAndDisplayXOptionsAreIndependentAndPersist() {
        VideoConfigDialog dialog=new VideoConfigDialog(context(),config());dialog.show();
        Spinner vk=dialog.findViewById(R.id.SVideoVulkanSurfaceFormat),dx=dialog.findViewById(R.id.SVideoDisplayXSurfaceFormat);
        assertEquals("BGRA",vk.getSelectedItem());assertEquals("RGBA",dx.getSelectedItem());
        android.widget.CheckBox box=dialog.findViewById(R.id.CBVideoDisplayX);box.setChecked(true);
        assertEquals(View.VISIBLE,dialog.findViewById(R.id.LLVideoDisplayXOptions).getVisibility());
        assertEquals(View.GONE,vk.getVisibility());assertEquals(View.VISIBLE,dx.getVisibility());
        dx.setSelection(0);((android.widget.CheckBox)dialog.findViewById(R.id.CBDisplayXEveryBuffer)).setChecked(true);
        ((Spinner)dialog.findViewById(R.id.SVideoOpenGLDriver)).setSelection(1);
        dialog.findViewById(R.id.BTConfirm).performClick();
        assertEquals("bgra",videoOptions.get("surfaceFormatDisplayX"));
        assertEquals("1",videoOptions.get("displayXEveryBuffer"));assertEquals("native",videoOptions.get("openGlDriver"));
        assertEquals("vulkan",compositor);
    }
    @Test public void compactMangoOneFitsBelowTwoHundredDensityIndependentPixels() {
        WinlatorHUD hud = new WinlatorHUD(context(), "compact-mango-test");
        hud.setHudStyle(1);
        hud.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST));
        float density = context().getResources().getDisplayMetrics().density;
        assertTrue(hud.getMeasuredWidth() < 200 * density);
        assertTrue(hud.getMeasuredHeight() > 60 * density);
        hud.layout(0, 0, hud.getMeasuredWidth(), hud.getMeasuredHeight());
        android.graphics.Bitmap image = android.graphics.Bitmap.createBitmap(hud.getWidth(),hud.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
        hud.draw(new android.graphics.Canvas(image));
        image.recycle();
    }
}
