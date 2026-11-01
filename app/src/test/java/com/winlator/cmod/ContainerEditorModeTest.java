package com.winlator.cmod;

import android.app.Application;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentFactory;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public class ContainerEditorModeTest {
    @Test public void recreationRetainsDefaultsModeInsteadOfCreatingARealContainer() throws Exception {
        for(int id:new int[]{-1,0,42}) {
            ContainerDetailFragment original=new ContainerDetailFragment(id);
            ContainerDetailFragment restored=(ContainerDetailFragment)new FragmentFactory().instantiate(
                    getClass().getClassLoader(),ContainerDetailFragment.class.getName());
            restored.setArguments(new Bundle(original.getArguments()));
            ActivityController<AppCompatActivity> host=Robolectric.buildActivity(AppCompatActivity.class);
            host.get().setTheme(R.style.AppTheme); host.create();
            // CREATED only: restore the editor's identity without starting its UI or Wine.
            host.get().getSupportFragmentManager().beginTransaction().add(restored,"editor").commitNow();
            java.lang.reflect.Field mode=ContainerDetailFragment.class.getDeclaredField("containerId");mode.setAccessible(true);
            assertEquals(id,mode.getInt(restored));
            host.destroy();
        }
    }
}
