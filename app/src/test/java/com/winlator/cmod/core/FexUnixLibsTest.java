package com.winlator.cmod.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import static org.junit.Assert.*;

public class FexUnixLibsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File file(File directory,String name) throws Exception {
        File file=new File(directory,name);Files.write(file.toPath(),new byte[]{1,2,3});return file;
    }
    @Test public void switchingToDllOnlyRemovesOldUnixLibsButPreservesWineModules() throws Exception {
        File directory=temporary.newFolder(),stale=file(directory,"libarm64ecfex.so"),wine=file(directory,"ntdll.so");
        assertFalse(FexUnixLibs.matches(directory,List.of()));
        FexUnixLibs.removeStale(directory,List.of());
        assertTrue(FexUnixLibs.matches(directory,List.of()));assertFalse(stale.exists());assertTrue(wine.exists());
    }
    @Test public void switchingBuildsKeepsOnlyManifestSelectedUnixLibs() throws Exception {
        File directory=temporary.newFolder(),selected=file(directory,"libwow64fex.so"),stale=file(directory,"libwow64fe.so");
        assertFalse(FexUnixLibs.matches(directory,List.of(selected)));
        FexUnixLibs.removeStale(directory,List.of(selected));
        assertTrue(FexUnixLibs.matches(directory,List.of(selected)));assertTrue(selected.exists());assertFalse(stale.exists());
        assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(selected.toPath()));
    }
    @Test public void unrelatedManifestTargetCannotPreserveAStaleSlot() throws Exception {
        File directory=temporary.newFolder(),other=temporary.newFolder();
        File stale=file(directory,"libwow64fex.so"),unrelated=file(other,"libwow64fex.so");
        FexUnixLibs.removeStale(directory,List.of(unrelated));
        assertFalse(stale.exists());assertTrue(unrelated.exists());
    }
}
