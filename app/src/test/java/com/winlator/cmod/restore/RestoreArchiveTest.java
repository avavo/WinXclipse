package com.winlator.cmod.restore;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class RestoreArchiveTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void borrowingNeverDeletesTheOriginalEvenWhenItLivesInCache() throws Exception {
        File original = temp.newFile("selected-backup.tar");
        byte[] content = {1, 2, 3, 4};
        Files.write(original.toPath(), content);
        try (RestoreArchive archive = RestoreArchive.borrow(original)) {
            assertEquals(original, archive.file);
        }
        assertArrayEquals(content, Files.readAllBytes(original.toPath()));
        try (RestoreArchive archive = RestoreArchive.borrow(original)) {
            throw new IOException("Extractor failed");
        } catch (IOException expected) {
            assertEquals("Extractor failed", expected.getMessage());
        }
        assertArrayEquals(content, Files.readAllBytes(original.toPath()));
    }

    @Test public void documentCopyOwnsOnlyTheTemporaryFileAndClosesItsStream() throws Exception {
        File original = temp.newFile("original.tar"), cache = temp.newFolder();
        byte[] content = new byte[300_000];
        for (int i = 0; i < content.length; i++) content[i] = (byte)i;
        Files.write(original.toPath(), content);
        AtomicBoolean closed = new AtomicBoolean();
        InputStream input = new ByteArrayInputStream(content) {
            @Override public void close() { closed.set(true); }
        };
        File copied;
        try (RestoreArchive archive = RestoreArchive.copy(input, cache)) {
            copied = archive.file;
            assertTrue(closed.get());
            assertNotEquals(original.getCanonicalFile(), copied.getCanonicalFile());
            assertEquals(cache, copied.getParentFile());
            assertArrayEquals(content, Files.readAllBytes(copied.toPath()));
        }
        assertFalse(copied.exists());
        assertArrayEquals(content, Files.readAllBytes(original.toPath()));
    }

    @Test public void providerReadFailureClosesTheStreamAndRemovesThePartialCopy() throws Exception {
        File cache = temp.newFolder();
        AtomicBoolean closed = new AtomicBoolean();
        InputStream failing = new InputStream() {
            int reads;
            @Override public int read() throws IOException {
                if (++reads > 3) throw new IOException("Disconnected provider");
                return 7;
            }
            @Override public void close() { closed.set(true); }
        };
        assertThrows(IOException.class, () -> RestoreArchive.copy(failing, cache));
        assertTrue(closed.get());
        assertEquals(0, cache.list().length);
    }

    @Test public void failureToCreateTheCacheFileStillClosesTheAlreadyOpenedDocument() throws Exception {
        File unavailableCache = temp.newFile();
        AtomicBoolean closed = new AtomicBoolean();
        InputStream input = new ByteArrayInputStream(new byte[]{2}) {
            @Override public void close() { closed.set(true); }
        };
        assertThrows(IOException.class, () -> RestoreArchive.copy(input, unavailableCache));
        assertTrue(closed.get());
        assertTrue(unavailableCache.isFile());
    }

    @Test public void providerCloseFailureDoesNotPublishOrLeakAnOtherwiseCompleteCopy() throws Exception {
        File cache = temp.newFolder();
        InputStream input = new ByteArrayInputStream(new byte[]{1, 2}) {
            @Override public void close() throws IOException { throw new IOException("Provider close failed"); }
        };
        assertThrows(IOException.class, () -> RestoreArchive.copy(input, cache));
        assertEquals(0, cache.list().length);
    }

    @Test public void temporaryCopyIsAlsoCleanedWhenExtractionFailsAndCloseIsIdempotent() throws Exception {
        File cache = temp.newFolder();
        RestoreArchive archive = RestoreArchive.copy(new ByteArrayInputStream(new byte[]{1}), cache);
        try (RestoreArchive ignored = archive) { throw new IOException("Bad archive"); }
        catch (IOException expected) { assertEquals("Bad archive", expected.getMessage()); }
        archive.close();
        assertFalse(archive.file.exists());
        assertEquals(0, cache.list().length);
    }

    @Test public void zeroLengthProviderReadsDoNotTruncateTheDocument() throws Exception {
        File cache = temp.newFolder();
        InputStream input = new ByteArrayInputStream(new byte[]{3, 4, 5}) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) { return 0; }
        };
        try (RestoreArchive archive = RestoreArchive.copy(input, cache)) {
            assertArrayEquals(new byte[]{3, 4, 5}, Files.readAllBytes(archive.file.toPath()));
        }
        assertThrows(IOException.class, () -> RestoreArchive.copy(null, cache));
        assertThrows(IOException.class, () -> RestoreArchive.borrow(new File(cache, "missing.tar")));
        assertThrows(IOException.class, () -> RestoreArchive.borrow(cache));
    }
}
