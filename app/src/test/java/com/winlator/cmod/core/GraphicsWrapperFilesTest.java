package com.winlator.cmod.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class GraphicsWrapperFilesTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private byte[] dll(boolean is64, int marker) {
        byte[] bytes = new byte[1024]; ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort(0,(short)0x5a4d);b.putInt(60,128);b.putInt(128,0x4550);
        b.putShort(132,(short)(is64?0x8664:0x14c));b.putShort(134,(short)1);
        int optional=is64?112:96;b.putShort(148,(short)optional);b.putShort(150,(short)0x2002);
        b.putShort(152,(short)(is64?0x20b:0x10b));b.putInt(212,512);
        b.putInt(152+optional+16,512);b.putInt(152+optional+20,512);bytes[800]=(byte)marker;return bytes;
    }
    private File file(File root,String relative,byte[] bytes) throws IOException {
        File f=new File(root,relative);Files.createDirectories(f.toPath().getParent());Files.write(f.toPath(),bytes);return f;
    }
    @Test public void validatesBothPeFormatsAndRejectsWrongBitness() throws Exception {
        File x86=file(temp.getRoot(),"x86.dll",dll(false,1)), x64=file(temp.getRoot(),"x64.dll",dll(true,2));
        assertTrue(GraphicsWrapperFiles.validDll(x86,false));assertTrue(GraphicsWrapperFiles.validDll(x64,true));
        assertFalse(GraphicsWrapperFiles.validDll(x86,true));assertFalse(GraphicsWrapperFiles.validDll(x64,false));
    }
    @Test public void detectsTruncatedBodiesDespiteIntactHeaders() throws Exception {
        File broken=file(temp.getRoot(),"broken.dll",java.util.Arrays.copyOf(dll(true,1),900));
        assertFalse(GraphicsWrapperFiles.validDll(broken,true));
        assertFalse(GraphicsWrapperFiles.validDll(file(temp.getRoot(),"empty.dll",new byte[0]),true));
        assertFalse(GraphicsWrapperFiles.validDll(new File(temp.getRoot(),"missing.dll"),true));
    }
    @Test public void malformedOffsetsAndNonDllImagesAreRejected() throws Exception {
        byte[] bytes=dll(true,1);ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(60,-1);
        assertFalse(GraphicsWrapperFiles.validDll(file(temp.getRoot(),"offset.dll",bytes),true));
        bytes=dll(true,1);ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(150,(short)2);
        assertFalse(GraphicsWrapperFiles.validDll(file(temp.getRoot(),"exe.dll",bytes),true));
    }
    @Test public void arm64ecAndArm64xRemainPe64Compatible() throws Exception {
        for(int machine:new int[]{0x8664,0xaa64,0xa641,0xa64e}){
            byte[] bytes=dll(true,1);ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(132,(short)machine);
            assertTrue(GraphicsWrapperFiles.validDll(file(temp.getRoot(),machine+".dll",bytes),true));
        }
    }
    @Test public void dx9IsRequiredEvenWhenDxgiAndD3d11Exist() throws Exception {
        File root=temp.newFolder();file(root,"system32/dxgi.dll",dll(false,1));file(root,"system32/d3d11.dll",dll(false,1));
        assertThrows(IOException.class,()->GraphicsWrapperFiles.requireDxvk(root,false));
        file(root,"system32/d3d9.dll",dll(false,1));GraphicsWrapperFiles.requireDxvk(root,false);
    }
    @Test public void wow64NeedsTheComplete32BitDxvkSet() throws Exception {
        File root=temp.newFolder();
        for(String name:new String[]{"dxgi.dll","d3d11.dll","d3d9.dll"})file(root,"system32/"+name,dll(true,1));
        assertThrows(IOException.class,()->GraphicsWrapperFiles.requireDxvk(root,true));
        for(String name:new String[]{"dxgi.dll","d3d11.dll","d3d9.dll"})file(root,"syswow64/"+name,dll(false,1));
        GraphicsWrapperFiles.requireDxvk(root,true);
    }
    @Test public void profileRoutingMatchesBundledRoutingForPure32BitWine() throws Exception {
        assertNull(GraphicsWrapperFiles.profileTarget("${system32}/d3d9.dll",false));
        assertEquals("system32/d3d9.dll",GraphicsWrapperFiles.profileTarget("${syswow64}/d3d9.dll",false));
        assertEquals("syswow64/d3d9.dll",GraphicsWrapperFiles.profileTarget("${syswow64}/d3d9.dll",true));
        assertThrows(IOException.class,()->GraphicsWrapperFiles.profileTarget("${libdir}/outside.dll",true));
        assertThrows(IOException.class,()->GraphicsWrapperFiles.profileTarget("${system32}/../outside.dll",true));
    }
    @Test public void successfulCommitReplacesAllFilesAndRemovesOnlyRequestedCompanions() throws Exception {
        File stage=temp.newFolder(),windows=temp.newFolder();
        byte[] old=dll(true,1),fresh=dll(true,2);
        file(windows,"system32/d3d9.dll",old);file(windows,"system32/dxwrapper.dll",old);file(windows,"system32/keep.dll",old);
        file(stage,"system32/d3d9.dll",fresh);
        GraphicsWrapperFiles.install(stage,windows,Set.of("system32/dxwrapper.dll"),true);
        assertArrayEquals(fresh,Files.readAllBytes(new File(windows,"system32/d3d9.dll").toPath()));
        assertFalse(new File(windows,"system32/dxwrapper.dll").exists());assertTrue(new File(windows,"system32/keep.dll").exists());
    }
    @Test public void failedSecondReplacementRestoresEveryOldFileAndRemoval() throws Exception {
        File stage=temp.newFolder(),windows=temp.newFolder();byte[] old=dll(true,1);
        for(String name:new String[]{"d3d9.dll","dxgi.dll"}){file(windows,"system32/"+name,old);file(stage,"system32/"+name,dll(true,2));}
        file(windows,"system32/legacy.dll",old);AtomicInteger moves=new AtomicInteger();
        assertThrows(IOException.class,()->GraphicsWrapperFiles.install(stage,windows,Set.of("system32/legacy.dll"),true,(a,b)->{
            if(moves.incrementAndGet()==4)throw new IOException("Injected replacement failure");
            Files.move(a,b,StandardCopyOption.REPLACE_EXISTING);
        }));
        for(String name:new String[]{"d3d9.dll","dxgi.dll","legacy.dll"})
            assertArrayEquals(old,Files.readAllBytes(new File(windows,"system32/"+name).toPath()));
    }
    @Test public void invalidPayloadCannotModifyAnyInstalledFile() throws Exception {
        File stage=temp.newFolder(),windows=temp.newFolder();byte[] old=dll(true,1);
        file(windows,"system32/d3d9.dll",old);file(stage,"system32/d3d9.dll",dll(true,2));file(stage,"system32/dxgi.dll",new byte[0]);
        assertThrows(IOException.class,()->GraphicsWrapperFiles.install(stage,windows,Collections.emptySet(),true));
        assertArrayEquals(old,Files.readAllBytes(new File(windows,"system32/d3d9.dll").toPath()));
    }
    @Test public void failedRemovalRestoresEarlierRemovalsAndNewDlls() throws Exception {
        File stage=temp.newFolder(),windows=temp.newFolder();byte[] old=dll(true,1);
        file(windows,"system32/d3d9.dll",old);file(stage,"system32/d3d9.dll",dll(true,2));
        file(windows,"system32/old-a.dll",old);file(windows,"system32/old-b.dll",old);
        AtomicInteger moves=new AtomicInteger();
        assertThrows(IOException.class,()->GraphicsWrapperFiles.install(stage,windows,Set.of("system32/old-a.dll","system32/old-b.dll"),true,(a,b)->{
            if(moves.incrementAndGet()==4)throw new IOException("Injected removal failure");
            Files.move(a,b,StandardCopyOption.REPLACE_EXISTING);
        }));
        for(String name:new String[]{"d3d9.dll","old-a.dll","old-b.dll"})assertArrayEquals(old,Files.readAllBytes(new File(windows,"system32/"+name).toPath()));
    }
    @Test public void failedRollbackPreservesTheOnlyOldCopy() throws Exception {
        File stage=temp.newFolder(),windows=temp.newFolder();byte[] old=dll(true,1);
        file(windows,"system32/d3d9.dll",old);file(stage,"system32/d3d9.dll",dll(true,2));
        AtomicInteger moves=new AtomicInteger();
        IOException failure=assertThrows(IOException.class,()->GraphicsWrapperFiles.install(stage,windows,Collections.emptySet(),true,(a,b)->{
            if(moves.incrementAndGet()>1)throw new IOException("Injected persistent filesystem failure");
            Files.move(a,b,StandardCopyOption.REPLACE_EXISTING);
        }));
        assertTrue(failure.getMessage().contains("preserved backup"));
        File[] backups=windows.getParentFile().listFiles((dir,name)->name.startsWith(".graphics-backup-"));
        assertNotNull(backups);assertEquals(1,backups.length);
        assertArrayEquals(old,Files.readAllBytes(new File(backups[0],"system32/d3d9.dll").toPath()));
    }
    @Test public void replacingHardlinkedWineDllDoesNotMutateSharedRuntime() throws Exception {
        File stage=temp.newFolder(),windows=temp.newFolder();byte[] old=dll(true,1),fresh=dll(true,2);
        File shared=file(temp.getRoot(),"runtime/shared.dll",old);File target=new File(windows,"system32/d3d9.dll");
        Files.createDirectories(target.toPath().getParent());Files.createLink(target.toPath(),shared.toPath());
        file(stage,"system32/d3d9.dll",fresh);GraphicsWrapperFiles.install(stage,windows,Collections.emptySet(),true);
        assertArrayEquals(old,Files.readAllBytes(shared.toPath()));assertArrayEquals(fresh,Files.readAllBytes(target.toPath()));
    }
    @Test public void removalCannotEscapePrefix() throws Exception {
        File stage=temp.newFolder(),windows=temp.newFolder();File outside=file(temp.getRoot(),"outside.dll",dll(true,1));
        assertThrows(IOException.class,()->GraphicsWrapperFiles.install(stage,windows,Set.of("system32/../../outside.dll"),true));
        assertTrue(outside.isFile());
    }
}
