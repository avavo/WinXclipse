package com.winlator.cmod.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.*;

public class SparseFileWriterTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final int BLOCK = StreamUtils.BUFFER_SIZE;

    @Test public void leadingMiddleAndTrailingHolesPreserveEveryByteAndLogicalLength() throws Exception {
        byte[] bytes = new byte[BLOCK * 5 + 13];
        Arrays.fill(bytes, BLOCK, BLOCK * 2, (byte)0x91);
        Arrays.fill(bytes, BLOCK * 3, BLOCK * 4, (byte)0x42);
        File output = temp.newFile();
        long submitted = SparseFileWriter.copy(new ByteArrayInputStream(bytes), output, bytes.length);
        assertEquals(BLOCK * 2L + 1, submitted);
        assertEquals(bytes.length, output.length()); assertArrayEquals(bytes, Files.readAllBytes(output.toPath()));
    }

    @Test public void replacingOldNonzeroDataDoesNotLeakItIntoSkippedRanges() throws Exception {
        File output = temp.newFile(); byte[] old = new byte[BLOCK * 4]; Arrays.fill(old, (byte)0x7f);
        Files.write(output.toPath(), old);
        byte[] zeros = new byte[BLOCK * 3 + 7];
        assertEquals(1, SparseFileWriter.copy(new ByteArrayInputStream(zeros), output, zeros.length));
        assertEquals(zeros.length, output.length()); assertArrayEquals(zeros, Files.readAllBytes(output.toPath()));
    }

    @Test public void emptyFileIsTruncatedAndNeverReadsTheFollowingEntry() throws Exception {
        File output = temp.newFile(); Files.write(output.toPath(), new byte[]{1, 2, 3});
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[]{42});
        assertEquals(0, SparseFileWriter.copy(input, output, 0));
        assertEquals(0, output.length()); assertEquals(42, input.read());
    }

    @Test public void denseBinaryAndSmallTailIgnoreStaleBytesOutsideTheReadBlock() throws Exception {
        byte[] bytes = new byte[BLOCK + 9]; new Random(42).nextBytes(bytes);
        Arrays.fill(bytes, BLOCK, bytes.length, (byte)0);
        File output = temp.newFile();
        assertEquals(BLOCK + 1L, SparseFileWriter.copy(new ByteArrayInputStream(bytes), output, bytes.length));
        assertArrayEquals(bytes, Files.readAllBytes(output.toPath()));
    }

    @Test public void fragmentedReadsAndZeroProgressInputStillFinishWithoutOverreading() throws Exception {
        byte[] bytes = new byte[BLOCK * 2 + 17]; bytes[BLOCK + 7] = (byte)255; bytes[bytes.length - 1] = 9;
        ByteArrayInputStream underlying = new ByteArrayInputStream(Arrays.copyOf(bytes, bytes.length + 1));
        InputStream input = new FilterInputStream(underlying) {
            int calls;
            @Override public int read(byte[] b, int off, int len) {
                return ++calls % 3 == 0 ? 0 : underlying.read(b, off, Math.min(13, len));
            }
        };
        File output = temp.newFile(); SparseFileWriter.copy(input, output, bytes.length);
        assertArrayEquals(bytes, Files.readAllBytes(output.toPath())); assertEquals(1, underlying.available());
    }

    @Test public void declaredSizeLargerThanInputIsNotSuccessfulAndUsesLongArithmetic() throws Exception {
        for (long declared : new long[]{BLOCK, (long)Integer.MAX_VALUE + 200, Long.MAX_VALUE}) {
            assertThrows(EOFException.class, () -> SparseFileWriter.copy(
                    new ByteArrayInputStream(new byte[17]), temp.newFile(), declared));
        }
    }

    @Test public void inputFailureIsPropagatedOutputClosedAndSourceOwnedByCaller() throws Exception {
        boolean[] sourceClosed = {false};
        InputStream input = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("source disconnected"); }
            @Override public void close() { sourceClosed[0] = true; }
        };
        File output = temp.newFile();
        assertThrows(IOException.class, () -> SparseFileWriter.copy(input, output, BLOCK));
        assertFalse(sourceClosed[0]); assertTrue(output.delete());
    }

    @Test public void partialWritesAreCompletedAtTheirExactOffsets() throws Exception {
        byte[] bytes = new byte[BLOCK * 2 + 31]; Arrays.fill(bytes, BLOCK, bytes.length, (byte)0x63);
        File file = temp.newFile();
        try (SeekableByteChannel delegate = Files.newByteChannel(file.toPath(), StandardOpenOption.WRITE);
             LimitedChannel output = new LimitedChannel(delegate, 7)) {
            assertEquals(BLOCK + 31L, SparseFileWriter.copy(new ByteArrayInputStream(bytes), output, bytes.length));
        }
        assertArrayEquals(bytes, Files.readAllBytes(file.toPath()));
    }

    @Test public void noProgressWritingIsAnErrorInsteadOfAnInfiniteRetry() throws Exception {
        byte[] bytes = new byte[BLOCK]; Arrays.fill(bytes, (byte)1);
        try (SeekableByteChannel delegate = Files.newByteChannel(temp.newFile().toPath(), StandardOpenOption.WRITE);
             LimitedChannel output = new LimitedChannel(delegate, 0)) {
            assertThrows(IOException.class, () -> SparseFileWriter.copy(new ByteArrayInputStream(bytes), output, bytes.length));
        }
    }

    @Test public void invalidSizeDoesNotTruncateExistingOutput() throws Exception {
        File output = temp.newFile(); Files.write(output.toPath(), new byte[]{1, 2, 3});
        assertThrows(IllegalArgumentException.class, () -> SparseFileWriter.copy(new ByteArrayInputStream(new byte[0]), output, -1));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(output.toPath()));
    }

    @Test public void actualBundledPreloaderHasIdenticalSha256WithFarFewerSubmittedBytes() throws Exception {
        File archive = new File("src/main/assets/proton-9.0-arm64ec.txz");
        if (!archive.isFile()) archive = new File("app/src/main/assets/proton-9.0-arm64ec.txz");
        assertTrue("Original bundled Proton archive is required", archive.isFile());
        boolean found = false;
        try (XZCompressorInputStream compressed = new XZCompressorInputStream(
                new BufferedInputStream(new FileInputStream(archive)));
             TarArchiveInputStream tar = new TarArchiveInputStream(compressed)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                if (!entry.getName().replaceFirst("^\\./", "").equals("bin/wine-preloader")) continue;
                assertFalse(found); found = true;
                MessageDigest original = MessageDigest.getInstance("SHA-256");
                File output = temp.newFile();
                long submitted = SparseFileWriter.copy(new DigestInputStream(tar, original), output, entry.getSize());
                assertEquals(entry.getSize(), output.length());
                assertArrayEquals(original.digest(), digest(output));
                assertTrue("Preloader's zero padding should not be written", submitted < entry.getSize() / 100);
                System.out.println("Preloader logical bytes=" + entry.getSize() + "; submitted bytes=" + submitted);
                // Continue reading the complete archive, including compression integrity checks.
            }
            byte[] padding = new byte[BLOCK]; while (compressed.read(padding) != -1) {}
        }
        assertTrue(found);
    }

    private byte[] digest(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new DigestInputStream(new FileInputStream(file), digest)) {
            byte[] buffer = new byte[BLOCK]; while (input.read(buffer) != -1) {}
        }
        return digest.digest();
    }

    private static class LimitedChannel implements SeekableByteChannel {
        private final SeekableByteChannel delegate;
        private final int limit;
        LimitedChannel(SeekableByteChannel delegate, int limit) { this.delegate = delegate; this.limit = limit; }
        @Override public int write(ByteBuffer buffer) throws IOException {
            int original = buffer.limit(); buffer.limit(buffer.position() + Math.min(limit, buffer.remaining()));
            try { return delegate.write(buffer); } finally { buffer.limit(original); }
        }
        @Override public int read(ByteBuffer buffer) throws IOException { return delegate.read(buffer); }
        @Override public long position() throws IOException { return delegate.position(); }
        @Override public SeekableByteChannel position(long position) throws IOException { delegate.position(position); return this; }
        @Override public long size() throws IOException { return delegate.size(); }
        @Override public SeekableByteChannel truncate(long size) throws IOException { delegate.truncate(size); return this; }
        @Override public boolean isOpen() { return delegate.isOpen(); }
        @Override public void close() throws IOException { delegate.close(); }
    }
}
