package com.winlator.cmod.widget;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.*;
import android.widget.*;
import com.winlator.cmod.R;
import com.winlator.cmod.core.PopupStyle;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,qualifiers="w360dp-h640dp-mdpi")
public class SidebarFeedbackLayoutTest {
    private Context context(float font) {
        Configuration c=new Configuration(RuntimeEnvironment.getApplication().getResources().getConfiguration());
        c.fontScale=font;c.setLocale(new java.util.Locale("pt","BR"));
        return new ContextThemeWrapper(RuntimeEnvironment.getApplication().createConfigurationContext(c),R.style.AppTheme_Dark);
    }
    private ViewGroup inflate(Context c,int resource) {
        ViewGroup root=(ViewGroup)LayoutInflater.from(c).inflate(resource,null);
        PopupStyle.styleContent(root);PopupStyle.styleContent(root);return root;
    }
    private void layout(View root,int width,int height) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
        root.layout(0,0,width,height);
    }
    private Rect bounds(ViewGroup root,View child) {
        Rect r=new Rect(0,0,child.getWidth(),child.getHeight());root.offsetDescendantRectToMyCoords(child,r);return r;
    }
    @Test public void footerKeepsThreeEqualTargetsAndEveryGlyphInsideItsPaddingAfterStyling() {
        for(int width:new int[]{288,356,380}) {
            ViewGroup root=inflate(context(1),R.layout.xserver_sidebar);layout(root,width,410);
            LinearLayout footer=root.findViewById(R.id.LLSidebarFooter);
            int firstWidth=root.findViewById(R.id.BTSidebarExit).getWidth();
            int previousRight=0;
            for(int id:new int[]{R.id.BTSidebarExit,R.id.BTSidebarPause,R.id.BTSidebarHelp}) {
                ImageButton button=root.findViewById(id);
                assertTrue(Math.abs(firstWidth-button.getWidth())<=1);
                assertEquals(48,button.getHeight());assertEquals(0,button.getTop());
                assertEquals(ImageView.ScaleType.CENTER_INSIDE,button.getScaleType());
                assertEquals(previousRight,button.getLeft());previousRight=button.getRight();
                RectF glyph=new RectF(0,0,button.getDrawable().getIntrinsicWidth(),button.getDrawable().getIntrinsicHeight());
                button.getImageMatrix().mapRect(glyph);
                assertTrue(glyph.left>=0 && glyph.top>=0);
                assertTrue(glyph.right<=button.getWidth()-button.getPaddingLeft()-button.getPaddingRight()+.1f);
                assertTrue(glyph.bottom<=button.getHeight()-button.getPaddingTop()-button.getPaddingBottom()+.1f);
            }
            assertEquals(footer.getWidth(),previousRight);
        }
    }
    @Test public void hudPanelActionHasARealGapAboveAppearanceAndEnableControls() {
        for(float font:new float[]{1,1.5f}) {
            ViewGroup root=inflate(context(font),R.layout.hud_config_dialog);layout(root,320,480);
            Rect button=bounds(root,root.findViewById(R.id.BTHudPanels));
            Rect appearance=bounds(root,root.findViewById(R.id.SPHudAppearance));
            Rect enable=bounds(root,root.findViewById(R.id.CBHudEnable));
            assertTrue(appearance.top-button.bottom>=12);
            assertTrue(enable.top-appearance.bottom>=8);
            assertTrue(button.width()>0 && appearance.width()>0);
        }
    }
    @Test public void fieldActionsAndCaptionHelpKeepSeparateAlignedColumnsAfterStyling() {
        for(float font:new float[]{1,1.5f}) for(int resource:new int[]{R.layout.container_detail_fragment,R.layout.shortcut_settings_dialog}) {
            ViewGroup root=inflate(context(font),resource);
            root.findViewById(R.id.BTGraphicsDriverConfig).setVisibility(View.VISIBLE);
            root.findViewById(R.id.BTDXWrapperConfig).setVisibility(View.VISIBLE);
            layout(root,320,640);
            Integer column=null;
            for(int[] pair:new int[][]{{R.id.SRenderer,R.id.BTVideoConfig},{R.id.SAudioDriver,R.id.BTAudioConfig},
                    {R.id.SDXWrapper,R.id.BTDXWrapperConfig},{R.id.SGraphicsDriver,R.id.BTGraphicsDriverConfig}}) {
                Rect field=bounds(root,root.findViewById(pair[0])),action=bounds(root,root.findViewById(pair[1]));
                assertEquals(field.centerY(),action.centerY());
                assertTrue(action.left>=field.right);assertEquals(48,action.width());assertEquals(48,action.height());
                if(column==null)column=action.centerX();else assertEquals(column.intValue(),action.centerX());
            }
            View label=root.findViewById(R.id.TVWrapperLabel),help=root.findViewById(R.id.BTHelpGraphicsDriver);
            assertSame(label.getParent(),help.getParent());
            assertEquals(bounds(root,label).centerY(),bounds(root,help).centerY());
            ViewGroup dxCaption=(ViewGroup)root.findViewById(R.id.BTHelpDXWrapper).getParent();
            assertEquals(bounds(root,dxCaption.getChildAt(0)).centerY(),bounds(root,root.findViewById(R.id.BTHelpDXWrapper)).centerY());
        }
    }
}
