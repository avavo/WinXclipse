package com.winlator.cmod.container;

import android.app.Application;
import com.winlator.cmod.core.Callback;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public class ContainerExportResultTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private File export(File source,File output) throws Exception {
        Container container=new Container(1);container.setRootDir(source);
        ContainerManager manager=new ContainerManager(RuntimeEnvironment.getApplication());
        AtomicInteger calls=new AtomicInteger();AtomicReference<File> result=new AtomicReference<>();
        manager.exportContainer(container,output,value->{result.set(value);calls.incrementAndGet();});
        long deadline=System.nanoTime()+5_000_000_000L;
        while(calls.get()==0&&System.nanoTime()<deadline){ShadowLooper.idleMainLooper();Thread.sleep(10);}
        ShadowLooper.idleMainLooper();assertEquals("completion callback count",1,calls.get());return result.get();
    }
    @Test public void completeCopyReturnsItsActualDirectory() throws Exception {
        File source=temporary.newFolder("xuser-1"),output=temporary.newFolder("backups");
        Files.write(new File(source,"settings.dat").toPath(),new byte[]{1,2,3});
        File result=export(source,output);assertNotNull(result);assertEquals(new File(output,"xuser-1"),result);
        assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(new File(result,"settings.dat").toPath()));
    }
    @Test public void existingBackupReportsFailureOnceAndKeepsItsData() throws Exception {
        File source=temporary.newFolder("xuser-1"),output=temporary.newFolder("backups");
        File existing=new File(output,"xuser-1");assertTrue(existing.mkdir());
        File marker=new File(existing,"marker");Files.write(marker.toPath(),new byte[]{9});
        assertNull(export(source,output));assertArrayEquals(new byte[]{9},Files.readAllBytes(marker.toPath()));
    }
    @Test public void unreadableDirectoryDoesNotLeaveAPartialBackupOrReportSuccess() throws Exception {
        File actual=temporary.newFolder("xuser-1"),output=temporary.newFolder("backups");
        File unreadable=new File(actual.getPath()){@Override public String[] list(){return null;}};
        assertNull(export(unreadable,output));assertFalse(new File(output,"xuser-1").exists());assertTrue(actual.isDirectory());
    }
}
