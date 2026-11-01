package com.winlator.cmod.renderer;

import android.app.Application;
import com.winlator.cmod.core.EnvVars;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public class DisplayXDeploymentTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    @Test public void missingAndEmptyLayersDoNotActivateTheEnvironment() throws Exception {
        EnvVars environment=new EnvVars();File root=temporary.newFolder("imagefs");
        assertFalse(DisplayXDeployment.install(new File(root,"missing.so"),root,environment));
        assertFalse(DisplayXDeployment.install(temporary.newFile("empty.so"),root,environment));
        assertTrue(environment.isEmpty());assertFalse(new File(root,"usr").exists());
    }
    @Test public void manifestUsesPackagedLibraryAndLeavesOtherLayersAlone() throws Exception {
        File library=temporary.newFile("layer with spaces.so");Files.write(library.toPath(),new byte[]{1,2,3});
        File root=temporary.newFolder("imagefs");EnvVars environment=new EnvVars();
        environment.put("VK_INSTANCE_LAYERS","VK_LAYER_CACHE:VK_LAYER_BCN");
        assertTrue(DisplayXDeployment.install(library,root,environment));
        File directory=new File(root,"usr/share/vulkan/explicit_layer.d");
        JSONObject manifest=new JSONObject(new String(Files.readAllBytes(new File(directory,"winxclipse_displayx.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(DisplayXSession.LAYER,manifest.getJSONObject("layer").getString("name"));
        assertEquals(library.getAbsolutePath(),manifest.getJSONObject("layer").getString("library_path"));
        assertEquals("VK_LAYER_CACHE:VK_LAYER_BCN",environment.get("VK_INSTANCE_LAYERS"));
        assertEquals(1,directory.list().length);
    }
    @Test public void appUpdateReplacesTheOldNativePathAtomically() throws Exception {
        File root=temporary.newFolder("imagefs");EnvVars environment=new EnvVars();
        for(String name:new String[]{"old.so","new.so"}) {
            File library=temporary.newFile(name);Files.write(library.toPath(),new byte[]{3});
            assertTrue(DisplayXDeployment.install(library,root,environment));
        }
        File directory=new File(root,"usr/share/vulkan/explicit_layer.d");
        JSONObject layer=new JSONObject(new String(Files.readAllBytes(new File(directory,"winxclipse_displayx.json").toPath()),java.nio.charset.StandardCharsets.UTF_8)).getJSONObject("layer");
        assertTrue(layer.getString("library_path").endsWith("new.so"));assertEquals(1,directory.list().length);
    }
    @Test public void appendPreservesExistingLayersAndNeverDuplicatesDisplayX() {
        assertEquals(DisplayXSession.LAYER,DisplayXSession.appendLayer(null));
        assertEquals(DisplayXSession.LAYER,DisplayXSession.appendLayer(" "));
        String existing="VK_LAYER_CACHE:VK_LAYER_BCN";
        assertEquals(existing+":"+DisplayXSession.LAYER,DisplayXSession.appendLayer(existing));
        assertEquals(existing+":"+DisplayXSession.LAYER,DisplayXSession.appendLayer(DisplayXSession.appendLayer(existing)));
        assertEquals("A;"+DisplayXSession.LAYER,DisplayXSession.appendLayer("A;"+DisplayXSession.LAYER));
    }
}
