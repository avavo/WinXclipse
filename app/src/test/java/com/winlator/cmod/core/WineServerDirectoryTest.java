package com.winlator.cmod.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class WineServerDirectoryTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    @Test public void createsOnlyServerDirectoryAndRestrictsItEveryLaunch() throws Exception {
        File prefix=temporary.newFolder();AtomicInteger calls=new AtomicInteger();
        for(int n=0;n<2;n++)WineServerDirectory.prepare(prefix,path->{assertEquals(new File(prefix,".wineserver").toPath(),path);calls.incrementAndGet();});
        assertEquals(2,calls.get());assertArrayEquals(new String[]{".wineserver"},prefix.list());
    }
    @Test public void preservesExistingServerFilesWhileRepairingDirectory() throws Exception {
        File prefix=temporary.newFolder(),directory=new File(prefix,".wineserver");assertTrue(directory.mkdir());
        File lock=new File(directory,"lock");Files.write(lock.toPath(),new byte[]{1,2,3});
        WineServerDirectory.prepare(prefix,path->{});assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(lock.toPath()));
    }
    @Test public void refusesAFileInsteadOfDeletingItOrRunningChmod() throws Exception {
        File prefix=temporary.newFolder(),file=new File(prefix,".wineserver");Files.write(file.toPath(),new byte[]{7});
        assertThrows(IOException.class,()->WineServerDirectory.prepare(prefix,path->fail("not a directory")));
        assertArrayEquals(new byte[]{7},Files.readAllBytes(file.toPath()));
    }
    @Test public void permissionFailureIsReportedWithoutDeletingState() throws Exception {
        File prefix=temporary.newFolder();
        assertThrows(IOException.class,()->WineServerDirectory.prepare(prefix,path->{throw new IOException("chmod denied");}));
        assertTrue(new File(prefix,".wineserver").isDirectory());
    }
}
