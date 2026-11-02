package com.winlator.cmod.inputcontrols;
import android.app.Application;
import android.content.*;
import java.io.File;
import java.util.ArrayList;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public class InputControlsManagerTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private Context context;
    @Before public void setup() throws Exception {
        File files=temporary.newFolder("files");
        context=new ContextWrapper(RuntimeEnvironment.getApplication()) { @Override public File getFilesDir(){return files;} };
    }
    @Test public void everyPresetStaysDeletedAcrossReloadAndEmptyDirectoryUntilExplicitRestore() {
        InputControlsManager manager=new InputControlsManager(context);
        int bundled=manager.getProfiles().size();assertTrue(bundled>0);
        for(ControlsProfile profile:new ArrayList<>(manager.getProfiles())) manager.removeProfile(profile);
        assertTrue(new InputControlsManager(context).getProfiles().isEmpty());
        manager=new InputControlsManager(context);assertEquals(bundled,manager.restoreDefaultProfiles());
        assertEquals(bundled,new InputControlsManager(context).getProfiles().size());
    }
    @Test public void deleteAllIsPermanentButRestoreDoesNotOverwriteCustomIdCollisionsOrEdits() {
        InputControlsManager manager=new InputControlsManager(context);int bundled=manager.getProfiles().size();
        manager.removeAllProfiles();manager=new InputControlsManager(context);
        assertTrue(manager.getProfiles().isEmpty());
        ControlsProfile custom=manager.createProfile("My custom layout");
        File file=ControlsProfile.getProfileFile(context,custom.id);
        String before=com.winlator.cmod.core.FileUtils.readString(file);
        assertEquals(bundled,manager.restoreDefaultProfiles());
        assertEquals(before,com.winlator.cmod.core.FileUtils.readString(file));
        assertEquals(bundled+1,new InputControlsManager(context).getProfiles().size());
        ControlsProfile edited=manager.getProfiles().stream().filter(p->p.id!=custom.id).findFirst().get();
        edited.setName("Edited bundled layout");edited.save();
        String saved=com.winlator.cmod.core.FileUtils.readString(ControlsProfile.getProfileFile(context,edited.id));
        new InputControlsManager(context).getProfiles();
        assertEquals(saved,com.winlator.cmod.core.FileUtils.readString(ControlsProfile.getProfileFile(context,edited.id)));
        assertEquals(0,manager.restoreDefaultProfiles());
    }
}
