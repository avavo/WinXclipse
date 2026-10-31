package com.winlator.cmod.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Real filesystem/receipt and rename tests; extraction is a controlled fixture, not host Wine. */
public class ImageFsMaintenanceTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final String WINE = "proton-9.0-arm64ec";
    private static final String[] WINES = {WINE};

    private File root() { return new File(temp.getRoot(), "imagefs"); }
    private File receipt() { return new File(root(), ".winlator/.img_version"); }
    private File wine() { return new File(root(), "opt/" + WINE); }
    private void write(File file, String text) throws IOException {
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }
    private String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
    private void ready(File wine) throws IOException {
        for (String path : new String[]{"bin/wine", "bin/wine-preloader", "bin/wineserver", "share/wine/wine.inf",
                "lib/wine/aarch64-unix/ntdll.so", "lib/wine/aarch64-windows/ntdll.dll",
                "lib/wine/aarch64-windows/kernel32.dll", "lib/wine/i386-windows/ntdll.dll",
                "lib/wine/i386-windows/kernel32.dll"}) {
            write(new File(wine, path), "complete " + path);
        }
    }

    private class Backend implements ImageFsMaintenance.Backend {
        int imageExtractions, wineExtractions, dependencyPreparations, imageUpdates;
        boolean replacement, brokenImage, brokenWine, failDependencies, failImageUpdate;
        String refuseDelete;
        @Override public boolean extractImage(File root) throws IOException {
            imageExtractions++;
            write(new File(root, "usr/bin/base"), "base-25");
            return !brokenImage;
        }
        @Override public boolean extractWine(String version, File staging) throws IOException {
            wineExtractions++;
            write(new File(staging, "bin/wine"), "new launcher");
            if (!brokenWine) ready(staging);
            return true; // Exercise validation even when the archive adapter reports success.
        }
        @Override public boolean hasReplacementWine() { return replacement; }
        @Override public void prepareDependencies() throws IOException {
            dependencyPreparations++;
            if (failDependencies) throw new IOException("Dependency preparation interrupted");
        }
        @Override public void imageUpdated() throws IOException {
            imageUpdates++;
            if (failImageUpdate) throw new IOException("Could not update container metadata");
        }
        @Override public boolean delete(File file) {
            return !file.getName().equals(refuseDelete) && FileUtils.delete(file);
        }
    }

    @Test public void missingWineRepairsOnlyItsTreeAndDoesNotResetContainerState() throws Exception {
        write(receipt(), "25");
        File base = new File(root(), "usr/bin/base");
        File container = new File(root(), "home/xuser-1/container.json");
        File legacy = new File(root(), "opt/installed-wine/wine-8/bin/wine");
        write(base, "existing base"); write(container, "keep selected runtime"); write(legacy, "old runtime");
        Backend backend = new Backend();
        assertEquals(ImageFsMaintenance.Result.WINE_REPAIRED,
                ImageFsMaintenance.prepare(root(), 25, WINES, false, backend));
        assertEquals(0, backend.imageExtractions); assertEquals(0, backend.imageUpdates);
        assertEquals(1, backend.wineExtractions); assertEquals(1, backend.dependencyPreparations);
        assertEquals("existing base", read(base)); assertEquals("keep selected runtime", read(container));
        assertEquals("old runtime", read(legacy)); assertEquals("25", read(receipt()));
        assertTrue(ImageFsMaintenance.hasBundledWine(wine()));
    }

    @Test public void launcherAloneDoesNotSkipRepair() throws Exception {
        write(receipt(), "25"); write(new File(wine(), "bin/wine"), "launcher");
        Backend backend = new Backend();
        ImageFsMaintenance.prepare(root(), 25, WINES, false, backend);
        assertEquals(1, backend.wineExtractions); assertEquals(0, backend.imageExtractions);
        assertTrue(ImageFsMaintenance.hasBundledWine(wine()));
    }

    @Test public void incompleteExtractionDoesNotReplaceOrTruncatePreviousWine() throws Exception {
        write(receipt(), "25");
        File previous = new File(wine(), "bin/wine"); write(previous, "keep previous launcher");
        Backend backend = new Backend() {
            @Override public boolean extractWine(String version, File staging) throws IOException {
                assertEquals("keep previous launcher", read(previous));
                return super.extractWine(version, staging);
            }
        };
        backend.brokenWine = true;
        assertThrows(IOException.class, () -> ImageFsMaintenance.prepare(root(), 25, WINES, false, backend));
        assertEquals("keep previous launcher", read(previous));
        assertEquals("25", read(receipt())); assertEquals(0, backend.dependencyPreparations);
        assertNoStaging();
        backend.brokenWine = false;
        assertEquals(ImageFsMaintenance.Result.WINE_REPAIRED,
                ImageFsMaintenance.prepare(root(), 25, WINES, false, backend));
        assertTrue(ImageFsMaintenance.hasBundledWine(wine()));
    }

    @Test public void updatePreservesSavesAndBothLegacyRuntimeLocations() throws Exception {
        write(receipt(), "24");
        File save = new File(root(), "home/xuser-3/.wine/drive_c/game/save.dat");
        File installed = new File(root(), "opt/installed-wine/proton-10/bin/wine");
        File legacy = new File(root(), "opt/wine-8.0/bin/wine");
        File staleBase = new File(root(), "usr/bin/stale");
        write(save, "save data"); write(installed, "installed runtime"); write(legacy, "legacy runtime");
        write(staleBase, "outdated base"); ready(wine()); write(new File(wine(), "old-only"), "obsolete");
        Backend backend = new Backend();
        assertEquals(ImageFsMaintenance.Result.IMAGE_UPDATED,
                ImageFsMaintenance.prepare(root(), 25, WINES, false, backend));
        assertEquals("save data", read(save)); assertEquals("installed runtime", read(installed));
        assertEquals("legacy runtime", read(legacy)); assertFalse(staleBase.exists());
        assertFalse(new File(wine(), "old-only").exists()); assertEquals("25", read(receipt()));
        assertEquals(1, backend.imageUpdates); assertEquals(1, backend.wineExtractions);
        assertNoStaging();
        assertEquals(0, new File(root(), "opt").listFiles(f -> f.getName().startsWith(".previous-")).length);
    }

    @Test public void imageFailureLeavesNoSuccessReceiptAndRetryPreservesUserData() throws Exception {
        write(receipt(), "24");
        File save = new File(root(), "home/xuser-2/save"); write(save, "preserve");
        Backend backend = new Backend(); backend.brokenImage = true;
        assertThrows(IOException.class, () -> ImageFsMaintenance.prepare(root(), 25, WINES, false, backend));
        assertFalse(receipt().exists()); assertEquals("preserve", read(save));
        assertEquals(0, backend.wineExtractions); assertEquals(0, backend.imageUpdates);
        backend.brokenImage = false;
        ImageFsMaintenance.prepare(root(), 25, WINES, false, backend);
        assertEquals("25", read(receipt())); assertEquals("preserve", read(save));
    }

    @Test public void failedRequiredDependencyDoesNotPublishImageReceipt() throws Exception {
        Backend backend = new Backend(); backend.failDependencies = true;
        assertThrows(IOException.class, () -> ImageFsMaintenance.prepare(root(), 25, WINES, false, backend));
        assertFalse(receipt().exists()); assertEquals(0, backend.imageUpdates);
    }

    @Test public void failedContainerMetadataUpdateDoesNotPublishImageReceipt() throws Exception {
        Backend backend = new Backend(); backend.failImageUpdate = true;
        assertThrows(IOException.class, () -> ImageFsMaintenance.prepare(root(), 25, WINES, false, backend));
        assertFalse(receipt().exists()); assertEquals(1, backend.dependencyPreparations);
    }

    @Test public void missingBakedWineWithAReplacementDoesNotReinstallItEveryBoot() throws Exception {
        write(receipt(), "25"); Backend backend = new Backend(); backend.replacement = true;
        assertEquals(ImageFsMaintenance.Result.DEPENDENCIES_READY,
                ImageFsMaintenance.prepare(root(), 25, WINES, false, backend));
        assertEquals(0, backend.imageExtractions); assertEquals(0, backend.wineExtractions);
        assertEquals(1, backend.dependencyPreparations); assertEquals("25", read(receipt()));
    }

    @Test public void validBootDoesNotExtractAnythingAndForcedReinstallStillWorks() throws Exception {
        write(receipt(), "25"); ready(wine()); Backend backend = new Backend();
        ImageFsMaintenance.prepare(root(), 25, WINES, false, backend);
        assertEquals(0, backend.imageExtractions); assertEquals(0, backend.wineExtractions);
        ImageFsMaintenance.prepare(root(), 25, WINES, true, backend);
        assertEquals(1, backend.imageExtractions); assertEquals(1, backend.wineExtractions);
        assertEquals(1, backend.imageUpdates); assertEquals(2, backend.dependencyPreparations);
    }

    @Test public void malformedReceiptTriggersACompleteRetry() throws Exception {
        for (String value : new String[]{"", "interrupted", "-1", "999999999999999999999999"}) {
            write(receipt(), value); Backend backend = new Backend();
            ImageFsMaintenance.prepare(root(), 25, WINES, false, backend);
            assertEquals(1, backend.imageExtractions); assertEquals("25", read(receipt()));
        }
    }

    @Test public void unreadableBasePathAbortsBeforeExtractingOrPublishingSuccess() throws Exception {
        write(receipt(), "24"); File blocked = new File(root(), "usr/keep"); write(blocked, "data");
        Backend backend = new Backend(); backend.refuseDelete = "usr";
        assertThrows(IOException.class, () -> ImageFsMaintenance.prepare(root(), 25, WINES, false, backend));
        assertFalse(receipt().exists()); assertEquals(0, backend.imageExtractions);
        assertEquals("data", read(blocked));
    }

    @Test public void missingCoreFilesAndDirectoriesNamedLikeDllsAreNotUsableWine() throws Exception {
        ready(wine()); assertTrue(ImageFsMaintenance.hasBundledWine(wine()));
        for (String path : new String[]{"bin/wine-preloader", "bin/wineserver", "share/wine/wine.inf",
                "lib/wine/aarch64-unix/ntdll.so", "lib/wine/aarch64-windows/ntdll.dll",
                "lib/wine/aarch64-windows/kernel32.dll", "lib/wine/i386-windows/ntdll.dll",
                "lib/wine/i386-windows/kernel32.dll"}) {
            File file = new File(wine(), path); Files.delete(file.toPath()); assertTrue(file.mkdir());
            write(new File(file, "nested"), "directory is not the required file");
            assertFalse(path, ImageFsMaintenance.hasBundledWine(wine()));
            assertTrue(FileUtils.delete(file)); ready(wine());
        }
    }

    @Test public void secondStartupWaitsAndRechecksTheCommittedReceipt() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch extracting = new CountDownLatch(1), release = new CountDownLatch(1), secondEntered = new CountDownLatch(1);
        AtomicInteger images = new AtomicInteger();
        Backend first = new Backend() {
            @Override public boolean extractImage(File root) throws IOException {
                images.incrementAndGet(); extracting.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Timed out"); }
                catch (InterruptedException error) { throw new IOException(error); }
                return super.extractImage(root);
            }
        };
        Backend second = new Backend() {
            @Override public boolean extractImage(File root) throws IOException {
                images.incrementAndGet(); return super.extractImage(root);
            }
        };
        try {
            Future<ImageFsMaintenance.Result> a = executor.submit(() -> ImageFsMaintenance.prepare(root(), 25, WINES, false, first));
            assertTrue(extracting.await(10, TimeUnit.SECONDS));
            Future<ImageFsMaintenance.Result> b = executor.submit(() -> {
                secondEntered.countDown(); return ImageFsMaintenance.prepare(root(), 25, WINES, false, second);
            });
            assertTrue(secondEntered.await(10, TimeUnit.SECONDS));
            assertFalse(b.isDone()); release.countDown();
            assertEquals(ImageFsMaintenance.Result.IMAGE_UPDATED, a.get(10, TimeUnit.SECONDS));
            assertEquals(ImageFsMaintenance.Result.DEPENDENCIES_READY, b.get(10, TimeUnit.SECONDS));
            assertEquals(1, images.get()); assertEquals(0, second.wineExtractions);
            assertEquals("25", read(receipt()));
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test public void discardsOnlyOwnedAbandonedStagingOnNextBoot() throws Exception {
        write(receipt(), "25"); ready(wine());
        File abandoned = new File(temp.getRoot(), ".imagefs-wine-imagefs-" + UUID.randomUUID());
        File unrelated = new File(temp.getRoot(), ".imagefs-wine-user-content");
        File otherRoot = new File(temp.getRoot(), ".imagefs-wine-other-" + UUID.randomUUID());
        write(new File(abandoned, "partial"), "unfinished");
        write(new File(unrelated, "keep"), "user data"); write(new File(otherRoot, "keep"), "other root");
        ImageFsMaintenance.prepare(root(), 25, WINES, false, new Backend());
        assertFalse(abandoned.exists()); assertTrue(unrelated.isDirectory()); assertTrue(otherRoot.isDirectory());
    }

    private void assertNoStaging() {
        assertEquals(0, temp.getRoot().listFiles(file -> file.getName().startsWith(".imagefs-wine-imagefs-")).length);
    }
}
