package com.winlator.cmod;
import android.content.Context;
import android.view.*;
import android.widget.*;
import androidx.fragment.app.FragmentActivity;
import com.winlator.cmod.core.ControlStyle;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="w320dp-h640dp-mdpi")
public class CommunityExportLayoutTest {
    @Test public void exportLabelsFieldsAndCoverActionsHaveSeparateTouchAreas() throws Exception {
        ActivityController<FragmentActivity> controller=Robolectric.buildActivity(FragmentActivity.class).setup().visible();
        try {
            FragmentActivity activity=controller.get();activity.setTheme(R.style.AppTheme_Dark);
            CommunityConfigExportDialogFragment fragment=new CommunityConfigExportDialogFragment();fragment.setArguments(new android.os.Bundle());fragment.setShowsDialog(false);
            activity.getSupportFragmentManager().beginTransaction().add(fragment,"fields").commitNow();
            LinearLayout fields=new LinearLayout(activity);fields.setOrientation(LinearLayout.VERTICAL);
            java.lang.reflect.Method field=CommunityConfigExportDialogFragment.class.getDeclaredMethod("field",LinearLayout.class,int.class,String.class);field.setAccessible(true);
            for(int label:new int[]{R.string.config_export_game,R.string.config_export_author,R.string.config_export_fps})
                field.invoke(fragment,fields,label,"Name or FPS value");
            java.lang.reflect.Method button=CommunityConfigExportDialogFragment.class.getDeclaredMethod("button",LinearLayout.class,int.class);button.setAccessible(true);
            for(int label:new int[]{R.string.config_export_find_cover,R.string.config_export_own_cover,R.string.config_export_no_cover}) button.invoke(fragment,fields,label);
            ControlStyle.apply(fields);
            fields.measure(View.MeasureSpec.makeMeasureSpec(288,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(3000,View.MeasureSpec.AT_MOST));fields.layout(0,0,fields.getMeasuredWidth(),fields.getMeasuredHeight());
            int bottom=0;
            for(int i=0;i<fields.getChildCount();i++) {
                View child=fields.getChildAt(i);assertTrue("Overlapping field "+i,child.getTop()>=bottom+6);bottom=child.getBottom();
                if(child instanceof EditText)assertTrue(child.getHeight()>=52);
                if(child instanceof Button) {
                    assertTrue(child.getHeight()>=48);assertNull(child.getBackgroundTintList());
                    android.graphics.drawable.Drawable background=child.getBackground().getCurrent();
                    assertTrue(background instanceof android.graphics.drawable.GradientDrawable);
                    assertTrue(((android.graphics.drawable.GradientDrawable)background).getCornerRadius()>=14);
                }
            }
        } finally {controller.pause().stop().destroy();}
    }
}
