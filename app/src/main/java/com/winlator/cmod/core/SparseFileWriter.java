package com.winlator.cmod.core;

import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;

/** Copies all logical bytes, seeking over zero blocks instead of writing them. */
public final class SparseFileWriter {
    public static final long MIN_FILE_SIZE = 16L * 1024 * 1024;

    private SparseFileWriter() {}

    /** Returns bytes submitted for writing, not allocated filesystem blocks. Owns only the output. */
    public static long copy(InputStream source, File destination, long size) throws IOException {
        if (size < 0) throw new IllegalArgumentException("Negative file size");
        try (FileOutputStream output = new FileOutputStream(destination)) {
            return copy(source, output.getChannel(), size);
        }
    }

    static long copy(InputStream source, SeekableByteChannel output, long size) throws IOException {
        if (size < 0) throw new IllegalArgumentException("Negative file size");
        output.truncate(0);
        output.position(0);
        byte[] buffer = new byte[StreamUtils.BUFFER_SIZE];
        ByteBuffer block = ByteBuffer.wrap(buffer);
        long copied = 0, written = 0;
        while (copied < size) {
            int amount = (int)Math.min(buffer.length, size - copied);
            int count = 0;
            while (count < amount) {
                int read = source.read(buffer, count, amount - count);
                if (read < 0) throw new EOFException("Archive entry ended before its declared size");
                if (read == 0) {
                    int value = source.read();
                    if (value < 0) throw new EOFException("Archive entry ended before its declared size");
                    buffer[count++] = (byte)value;
                } else count += read;
            }
            block.clear();
            block.limit(amount);
            if (allZero(block, amount)) output.position(copied + amount);
            else written += writeFully(output, block);
            copied += amount;
        }
        // Seeking alone does not extend a file. Materialize its final byte if
        // the last blocks were zero, preserving trailing holes and exact size.
        if (output.size() < size) {
            output.position(size - 1);
            written += writeFully(output, ByteBuffer.wrap(new byte[]{0}));
        }
        return written;
    }

    private static boolean allZero(ByteBuffer block, int length) {
        int offset = 0;
        for (; offset + Long.BYTES <= length; offset += Long.BYTES) {
            if (block.getLong(offset) != 0) return false;
        }
        for (; offset < length; offset++) if (block.get(offset) != 0) return false;
        return true;
    }

    private static long writeFully(SeekableByteChannel output, ByteBuffer block) throws IOException {
        long written = 0;
        while (block.hasRemaining()) {
            int count = output.write(block);
            if (count <= 0) throw new IOException("No progress writing the extracted file");
            written += count;
        }
        return written;
    }
}
