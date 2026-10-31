package com.winlator.cmod.container;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipFile;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class CommunityConfigArchiveTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final byte[] CONFIG = "{\"metadata\":{\"gameName\":\"Ação\"}}".getBytes(StandardCharsets.UTF_8);

    @Test public void includesExactUtf8MetadataAndSelectedCover() throws Exception {
        byte[] cover = new byte[]{1, 2, 3, 4};
        File file = CommunityConfigArchive.write(temp.getRoot(), "Ação-SM-S721B", CONFIG, cover);
        assertEquals("Ação-SM-S721B.zip", file.getName());
        try (ZipFile zip = new ZipFile(file)) {
            assertEquals(2, zip.size());
            assertArrayEquals(CONFIG, zip.getInputStream(zip.getEntry("config.txt")).readAllBytes());
            assertArrayEquals(cover, zip.getInputStream(zip.getEntry("cover.png")).readAllBytes());
        }
        assertEquals(1, temp.getRoot().listFiles().length);
    }

    @Test public void canExportOfflineWithoutAnImage() throws Exception {
        File file = CommunityConfigArchive.write(new File(temp.getRoot(), "exports"), "My Game", CONFIG, null);
        try (ZipFile zip = new ZipFile(file)) { assertEquals(1, zip.size()); assertNull(zip.getEntry("cover.png")); }
    }

    @Test public void doesNotReplaceExistingExports() throws Exception {
        File first = CommunityConfigArchive.write(temp.getRoot(), "Game", CONFIG, null);
        byte[] original = Files.readAllBytes(first.toPath());
        File second = CommunityConfigArchive.write(temp.getRoot(), "Game", new byte[]{9}, null);
        assertEquals("Game-2.zip", second.getName());
        assertArrayEquals(original, Files.readAllBytes(first.toPath()));
    }

    @Test public void concurrentExportsGetDistinctCompleteFiles() throws Exception {
        CompletableFuture<File> a = CompletableFuture.supplyAsync(() -> write("Game"));
        CompletableFuture<File> b = CompletableFuture.supplyAsync(() -> write("Game"));
        assertNotEquals(a.get().getName(), b.get().getName());
        for (File file : temp.getRoot().listFiles()) {
            assertTrue(file.getName().endsWith(".zip"));
            try (ZipFile zip = new ZipFile(file)) { assertArrayEquals(CONFIG, zip.getInputStream(zip.getEntry("config.txt")).readAllBytes()); }
        }
    }

    @Test public void boundsNamesAndPreventsDirectoryTraversal() throws Exception {
        File file = CommunityConfigArchive.write(temp.getRoot(), "../../Game/:?*<>|", CONFIG, null);
        assertEquals(temp.getRoot().getCanonicalFile(), file.getParentFile().getCanonicalFile());
        assertFalse(file.getName().startsWith("."));
        assertTrue(CommunityConfigArchive.write(temp.getRoot(), "a".repeat(500), CONFIG, null).getName().length() <= 104);
    }

    @Test public void rejectsInvalidPayloadBeforePublishingAnyFile() throws Exception {
        for (byte[] manifest : new byte[][]{null, new byte[0], new byte[CommunityConfigArchive.MAX_CONFIG_BYTES + 1]}) {
            assertThrows(java.io.IOException.class, () -> CommunityConfigArchive.write(temp.getRoot(), "Game", manifest, null));
        }
        assertThrows(java.io.IOException.class, () -> CommunityConfigArchive.write(temp.getRoot(), "Game", CONFIG, new byte[0]));
        assertThrows(java.io.IOException.class, () -> CommunityConfigArchive.write(temp.getRoot(), "Game", CONFIG,
                new byte[CommunityConfigArchive.MAX_COVER_BYTES + 1]));
        assertEquals(0, temp.getRoot().listFiles().length);
    }

    @Test public void directoryFailureDoesNotTouchExistingData() throws Exception {
        File target = temp.newFile("blocked"); Files.write(target.toPath(), new byte[]{5});
        assertThrows(java.io.IOException.class, () -> CommunityConfigArchive.write(target, "Game", CONFIG, null));
        assertArrayEquals(new byte[]{5}, Files.readAllBytes(target.toPath()));
    }

    private File write(String name) {
        try { return CommunityConfigArchive.write(temp.getRoot(), name, CONFIG, null); }
        catch (Exception failure) { throw new RuntimeException(failure); }
    }
}
