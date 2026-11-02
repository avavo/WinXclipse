package com.winlator.cmod.container;

import android.app.Application;
import android.content.Context;
import com.winlator.cmod.core.FileUtils;
import java.io.File;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class)
public class ContainerDefaultsTest {
    @Test public void removedRamFixVariableStaysRemovedAcrossSaveReloadAndNewContainer() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        Container draft=ContainerDefaults.load(context,true);
        com.winlator.cmod.core.EnvVars env=new com.winlator.cmod.core.EnvVars(draft.getEnvVars());
        assertTrue(env.has("DXVK_CONFIG_FILE")); env.remove("DXVK_CONFIG_FILE");
        draft.setEnvVars(env.toString()); assertTrue(draft.saveData());
        Container saved=ContainerDefaults.load(context,false);
        assertFalse(new com.winlator.cmod.core.EnvVars(saved.getEnvVars()).has("DXVK_CONFIG_FILE"));
        Container created=new Container(7);
        File root=new File(context.getFilesDir(),"new-container-7"); assertTrue(root.mkdirs()); created.setRootDir(root);
        created.loadData(new org.json.JSONObject(FileUtils.readString(saved.getConfigFile())));
        created.putExtra("appVersion","0"); assertTrue(created.saveData());
        Container reopened=new Container(7); reopened.setRootDir(root); reopened.loadData(new org.json.JSONObject(FileUtils.readString(created.getConfigFile())));
        assertFalse(new com.winlator.cmod.core.EnvVars(reopened.getEnvVars()).has("DXVK_CONFIG_FILE"));
        reopened.setEnvVars(""); assertTrue(reopened.saveData());
        reopened.loadData(new org.json.JSONObject(FileUtils.readString(created.getConfigFile())));
        assertEquals("",reopened.getEnvVars());
    }
    @Test public void unsavedDraftDoesNotBecomeDefaultOrCreateWinePrefix() {
        Context context=RuntimeEnvironment.getApplication();
        assertNull(ContainerDefaults.load(context,false));
        Container draft=ContainerDefaults.load(context,true); assertNotNull(draft);
        assertEquals(Container.DEFAULT_EMULATOR,draft.getEmulator());
        draft.setScreenSize("800x600");
        assertNull(ContainerDefaults.load(context,false));
        assertFalse(new File(context.getFilesDir(),"imagefs/home/xuser-0").exists());
    }
    @Test public void savedSettingsAreIndependentOnEachReadAndOutsideUserContainers() {
        Context context=RuntimeEnvironment.getApplication();
        Container draft=ContainerDefaults.load(context,true); assertNotNull(draft);
        draft.setScreenSize("1920x1080"); draft.setShowFPS(false); draft.putExtra("renderer","egl");
        assertTrue(draft.saveData());
        File registry=new File(draft.getRootDir(),".wine/user.reg"); assertTrue(FileUtils.writeStringAtomic(registry,"registry fixture"));
        Container first=ContainerDefaults.load(context,false),second=ContainerDefaults.load(context,false);
        assertEquals("1920x1080",first.getScreenSize()); assertFalse(first.isShowFPS()); assertEquals("egl",first.getExtra("renderer"));
        first.setScreenSize("640x480"); first.putExtra("renderer","gl");
        assertEquals("1920x1080",second.getScreenSize()); assertEquals("egl",second.getExtra("renderer"));
        assertEquals("registry fixture",FileUtils.readString(new File(second.getRootDir(),".wine/user.reg")));
        assertEquals(new File(context.getFilesDir(),"container-defaults"),draft.getRootDir());
    }
}
