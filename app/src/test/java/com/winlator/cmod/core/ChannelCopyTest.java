package com.winlator.cmod.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Random;
import static org.junit.Assert.*;

public class ChannelCopyTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private byte[] payload(){byte[] data=new byte[600_017];new Random(17).nextBytes(data);return data;}
    private void check(ChannelCopy.Transfer transfer) throws Exception {
        byte[] bytes=payload();File source=temporary.newFile("source"),target=temporary.newFile("target");Files.write(source.toPath(),bytes);
        try(FileChannel in=FileChannel.open(source.toPath(),StandardOpenOption.READ);FileChannel out=FileChannel.open(target.toPath(),StandardOpenOption.WRITE)) {
            ChannelCopy.copy(in,out,transfer);
        }
        assertArrayEquals(bytes,Files.readAllBytes(target.toPath()));
    }
    @Test public void zeroKernelTransfersFallBackWithoutTruncation() throws Exception {check((in,p,n,out)->0);}
    @Test public void unsupportedTransferAfterPartialWriteDoesNotDuplicateBytes() throws Exception {
        check((in,p,n,out)->{out.write(ByteBuffer.wrap(new byte[]{9,9,9,9}));throw new IOException("sendfile unsupported");});
    }
    @Test public void mixedKernelAndBufferedRangesStayContiguous() throws Exception {
        check((in,p,n,out)->p==0?in.transferTo(p,1024,out):0);
    }
    @Test public void changingSourceSizeCannotReportSuccess() throws Exception {
        File source=temporary.newFile("source"),target=temporary.newFile("target");Files.write(source.toPath(),payload());
        try(FileChannel in=FileChannel.open(source.toPath(),StandardOpenOption.READ,StandardOpenOption.WRITE);FileChannel out=FileChannel.open(target.toPath(),StandardOpenOption.WRITE)) {
            assertThrows(IOException.class,()->ChannelCopy.copy(in,out,(from,p,n,to)->{long written=from.transferTo(p,n,to);from.truncate(1);return written;}));
        }
    }
}
