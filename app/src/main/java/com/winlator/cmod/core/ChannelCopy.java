package com.winlator.cmod.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** Bounded kernel transfers with a buffered fallback for Android/FUSE filesystems. */
public final class ChannelCopy {
    private ChannelCopy(){}
    interface Transfer {long copy(FileChannel source,long position,long count,FileChannel target) throws IOException;}
    public static void copy(FileChannel source,FileChannel target) throws IOException {
        copy(source,target,(in,position,count,out)->in.transferTo(position,count,out));
    }
    static void copy(FileChannel source,FileChannel target,Transfer transfer) throws IOException {
        long size=source.size(),position=0;ByteBuffer buffer=null;boolean kernel=true;
        while(position<size) {
            long count;
            try {count=kernel?transfer.copy(source,position,Math.min(8L*1024*1024,size-position),target):0;}
            catch(IOException unsupported){count=0;}
            if(count>0){position+=count;continue;}
            kernel=false; // Do not repeat an unsupported sendfile for every buffer.
            // A failed sendfile may have advanced the destination before throwing.
            // Restart at the last verified offset, never append a duplicate range.
            target.position(position);
            if(buffer==null)buffer=ByteBuffer.allocate((int)Math.min(256*1024,size-position));
            buffer.clear();buffer.limit((int)Math.min(buffer.capacity(),size-position));
            int read=source.read(buffer,position);
            if(read<=0)throw new IOException("Source ended during copy");
            buffer.flip();while(buffer.hasRemaining())if(target.write(buffer)<=0)throw new IOException("Destination stopped accepting bytes");
            position+=read;
        }
        if(source.size()!=size||target.size()!=size)throw new IOException("File size changed during copy");
    }
}
