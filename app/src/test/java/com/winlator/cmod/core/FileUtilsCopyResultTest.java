package com.winlator.cmod.core;

import android.app.Application;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public class FileUtilsCopyResultTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    @Test public void failedDirectoryEnumerationIsNotAnEmptySuccessfulCopy() throws Exception {
        File actual=temporary.newFolder("source");
        File unreadable=new File(actual.getPath()){@Override public String[] list(){return null;}};
        assertFalse(FileUtils.copy(unreadable,new File(temporary.getRoot(),"copy")));
        assertFalse(FileUtils.copy(RuntimeEnvironment.getApplication(),unreadable,new File(temporary.getRoot(),"copy2"),null));
    }
    @Test public void genuinelyEmptyFoldersStillCopySuccessfully() throws Exception {
        File source=temporary.newFolder("source");File target=new File(temporary.getRoot(),"copy");
        assertTrue(FileUtils.copy(source,target));assertTrue(target.isDirectory());assertEquals(0,target.list().length);
    }
    @Test public void copyingAnExistingFileOntoItselfDoesNotTruncateIt() throws Exception {
        File source=temporary.newFile("game.exe");byte[] bytes={1,2,3,4};java.nio.file.Files.write(source.toPath(),bytes);
        assertTrue(FileUtils.copy(source,source));assertArrayEquals(bytes,java.nio.file.Files.readAllBytes(source.toPath()));
        assertTrue(FileUtils.copy(RuntimeEnvironment.getApplication(),source,source,null));assertArrayEquals(bytes,java.nio.file.Files.readAllBytes(source.toPath()));
    }
}
