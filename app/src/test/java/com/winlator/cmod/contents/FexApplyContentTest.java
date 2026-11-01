package com.winlator.cmod.contents;

import android.app.Application;
import android.content.Context;
import com.winlator.cmod.xenvironment.ImageFs;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public class FexApplyContentTest {
    private ContentProfile profile() {
        ContentProfile profile=new ContentProfile();profile.type=ContentProfile.ContentType.CONTENT_TYPE_FEXCORE;
        profile.verName="test-build";profile.verCode=8;profile.fileList=new ArrayList<>();
        ContentProfile.ContentFile dll=new ContentProfile.ContentFile();dll.source="bridge.dll";
        dll.target="${system32}/libwow64fex.dll";profile.fileList.add(dll);return profile;
    }
    private void write(File file) throws Exception {file.getParentFile().mkdirs();Files.write(file.toPath(),new byte[]{1,2,3});}
    @Test public void alreadyAppliedDllOnlyPackageIsReconciledBeforeLaunch() throws Exception {
        Context context=RuntimeEnvironment.getApplication();ContentsManager manager=new ContentsManager(context);
        ContentProfile profile=profile();write(new File(ContentsManager.getInstallDir(context,profile),"bridge.dll"));
        File unixDir=new File(ImageFs.find(context).getLibDir(),"wine/aarch64-unix");
        File stale=new File(unixDir,"libarm64ecfex.so"),wine=new File(unixDir,"ntdll.so");
        assertTrue(manager.applyContent(profile));assertTrue(manager.isAppliedContent(profile));
        write(stale);write(wine);assertFalse(manager.isAppliedContent(profile));
        assertTrue(manager.applyContent(profile));assertTrue(manager.isAppliedContent(profile));
        assertFalse(stale.exists());assertTrue(wine.exists());
    }
    @Test public void failedPackageDoesNotDeleteThePreviousUnixLibrary() throws Exception {
        Context context=RuntimeEnvironment.getApplication();ContentsManager manager=new ContentsManager(context);
        ContentProfile profile=profile();File unixDir=new File(ImageFs.find(context).getLibDir(),"wine/aarch64-unix");
        File stale=new File(unixDir,"libarm64ecfex.so");write(stale);
        assertFalse(manager.applyContent(profile));assertTrue(stale.exists());
    }
}
