package com.winlator.cmod.core;

import android.app.Application;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.*;
import java.nio.file.Files;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class SparseArchiveExtractionTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void largeEntryAndFollowingSmallEntryKeepTheirDataAndArchiveBoundaries() throws Exception {
        byte[] payload = new byte[(int)SparseFileWriter.MIN_FILE_SIZE + 13];
        payload[0] = 0x7f; payload[65537] = 42; payload[payload.length - 1] = (byte)255;
        byte[] manifest = "{\"type\":\"Proton\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(archive)) {
            entry(tar, "bin/large", payload); entry(tar, "profile.json", manifest);
        }
        File output = temp.newFolder();
        assertTrue(TarCompressorUtils.extract(TarCompressorUtils.Type.NONE,
                new ByteArrayInputStream(archive.toByteArray()), output));
        assertArrayEquals(payload, Files.readAllBytes(new File(output, "bin/large").toPath()));
        assertArrayEquals(manifest, Files.readAllBytes(new File(output, "profile.json").toPath()));
    }

    @Test public void truncatedLargeArchiveCannotBeReportedAsInstalled() throws Exception {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        TarArchiveOutputStream tar = new TarArchiveOutputStream(header);
        TarArchiveEntry entry = new TarArchiveEntry("bin/incomplete"); entry.setSize(SparseFileWriter.MIN_FILE_SIZE);
        tar.putArchiveEntry(entry); tar.flush();
        // A complete header with no entry payload models a disconnected/corrupt package.
        assertFalse(TarCompressorUtils.extract(TarCompressorUtils.Type.NONE,
                new ByteArrayInputStream(header.toByteArray()), temp.newFolder()));
    }

    @Test public void missingXzFooterIsRejectedEvenWhenAllTarEntriesWereReadable() throws Exception {
        byte[] archive = compressedTar();
        assertFalse(TarCompressorUtils.extract(TarCompressorUtils.Type.XZ,
                new ByteArrayInputStream(java.util.Arrays.copyOf(archive, archive.length - 12)), temp.newFolder()));
    }

    @Test public void completeXzFooterAndTrailingTarPaddingAreAccepted() throws Exception {
        File output = temp.newFolder();
        assertTrue(TarCompressorUtils.extract(TarCompressorUtils.Type.XZ,
                new ByteArrayInputStream(compressedTar()), output));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(new File(output, "payload").toPath()));
    }

    @Test public void gnuSparseEntryUsesLogicalSizeAndKeepsItsLastExtentAndNextEntry() throws Exception {
        int extent = (int)SparseFileWriter.MIN_FILE_SIZE + 8;
        int lastOffset = extent * 2;
        byte[] data = new byte[extent + 1]; data[0] = 0x7f; data[extent] = 42;
        String pax = pax("GNU.sparse.size", Integer.toString(lastOffset + 1))
                + pax("GNU.sparse.numblocks", "2") + pax("GNU.sparse.map", "0," + extent + "," + lastOffset + ",1");
        byte[] paxBytes = pax.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(archive)) {
            TarArchiveEntry headers = new TarArchiveEntry("pax/sparse", TarConstants.LF_PAX_EXTENDED_HEADER_LC);
            headers.setSize(paxBytes.length); tar.putArchiveEntry(headers); tar.write(paxBytes); tar.closeArchiveEntry();
            entry(tar, "bin/sparse", data); entry(tar, "next", new byte[]{17});
        }
        File output = temp.newFolder();
        assertTrue(TarCompressorUtils.extract(TarCompressorUtils.Type.NONE,
                new ByteArrayInputStream(archive.toByteArray()), output));
        File file = new File(output, "bin/sparse"); assertEquals(lastOffset + 1L, file.length());
        byte[] bytes = Files.readAllBytes(file.toPath());
        assertEquals(0x7f, bytes[0]); assertEquals(0, bytes[extent + 1]); assertEquals(42, bytes[lastOffset]);
        assertArrayEquals(new byte[]{17}, Files.readAllBytes(new File(output, "next").toPath()));
    }

    private String pax(String key, String value) {
        String body = " " + key + "=" + value + "\n";
        int size = body.length() + 1;
        while (size != body.length() + Integer.toString(size).length()) size = body.length() + Integer.toString(size).length();
        return size + body;
    }

    private byte[] compressedTar() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (XZCompressorOutputStream compressed = new XZCompressorOutputStream(bytes);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(compressed, 128 * 1024)) {
            entry(tar, "payload", new byte[]{42});
        }
        return bytes.toByteArray();
    }

    private void entry(TarArchiveOutputStream tar, String name, byte[] bytes) throws Exception {
        TarArchiveEntry entry = new TarArchiveEntry(name); entry.setSize(bytes.length);
        tar.putArchiveEntry(entry); tar.write(bytes); tar.closeArchiveEntry();
    }
}
