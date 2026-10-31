package com.winlator.cmod.contents;

import android.app.Application;
import android.content.Context;

import com.winlator.cmod.core.FileUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class BundledWineDetectionTest {
    private void write(File root, String path) throws Exception {
        File file = new File(root, path);
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), new byte[]{1});
    }

    @Test public void incompleteBakedRuntimeCannotCountAsItsOwnInstalledReplacement() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File root = ContentsManager.getBakedInProtonDir(context);
        if (root.exists()) assertTrue(FileUtils.delete(root));
        write(root, "bin/wine");
        ContentProfile profile = new ContentProfile(); profile.bakedInRuntime = true;
        assertFalse(ContentsManager.hasBakedInProton(context));
        assertFalse(new ContentsManager(context).isInstalledProfile(profile));
    }

    @Test public void installedDetectionUsesTheSameCoreRequirementsAsStartupRepair() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File root = ContentsManager.getBakedInProtonDir(context);
        if (root.exists()) assertTrue(FileUtils.delete(root));
        for (String path : new String[]{"bin/wine", "bin/wine-preloader", "bin/wineserver", "share/wine/wine.inf",
                "lib/wine/aarch64-unix/ntdll.so", "lib/wine/aarch64-windows/ntdll.dll",
                "lib/wine/aarch64-windows/kernel32.dll", "lib/wine/i386-windows/ntdll.dll",
                "lib/wine/i386-windows/kernel32.dll"}) write(root, path);
        ContentProfile profile = new ContentProfile(); profile.bakedInRuntime = true;
        ContentsManager manager = new ContentsManager(context);
        assertTrue(ContentsManager.hasBakedInProton(context)); assertTrue(manager.isInstalledProfile(profile));
        Files.delete(new File(root, "bin/wineserver").toPath());
        assertFalse(ContentsManager.hasBakedInProton(context)); assertFalse(manager.isInstalledProfile(profile));
    }
}
