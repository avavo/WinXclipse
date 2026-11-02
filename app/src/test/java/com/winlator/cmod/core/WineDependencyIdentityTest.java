package com.winlator.cmod.core;
import java.io.File;
import java.nio.file.Files;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
public class WineDependencyIdentityTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void presentationAndRamTweaksDoNotRepeatProbesButDriverAndApiChangesDo() throws Exception {
        EnvVars env=new EnvVars();env.put("ADRENOTOOLS_DRIVER_PATH",temp.getRoot().getAbsolutePath());env.put("ADRENOTOOLS_DRIVER_NAME","quasar.so");
        File driver=new File(temp.getRoot(),"quasar.so");Files.write(driver.toPath(),new byte[]{1});
        String initial=WineDependencyIdentity.build("proton-9","android-build",env);
        env.put("MESA_VK_WSI_PRESENT_MODE","immediate");env.put("WINXCLIPSE_DISPLAYX_FORMAT","rgba");env.put("WRAPPER_VMEM_MAX_SIZE","2048");
        assertEquals(initial,WineDependencyIdentity.build("proton-9","android-build",env));
        Files.write(driver.toPath(),new byte[]{1,2});assertNotEquals(initial,WineDependencyIdentity.build("proton-9","android-build",env));
        String updated=WineDependencyIdentity.build("proton-9","android-build",env);
        env.put("WRAPPER_VK_VERSION","1.4");assertNotEquals(updated,WineDependencyIdentity.build("proton-9","android-build",env));
        assertNotEquals(WineDependencyIdentity.build("proton-9","android-build",env),WineDependencyIdentity.build("proton-9","new-android-build",env));
    }
}
