package com.winlator.cmod.contents;

import com.winlator.cmod.core.ContentOperationRegistry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class ContentDownloadQueueTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    static void reply(OutputStream out, byte[] data) throws Exception {
        out.write(("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: " + data.length
                + "\r\nETag: \"v1\"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(data);
        out.flush();
    }

    @Test public void queuedDownloadsCanPauseBeforeReceivingBytesAndResumeAfterAnotherJob() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1), releaseFirst = new CountDownLatch(1), queued = new CountDownLatch(1);
        AtomicBoolean secondContacted = new AtomicBoolean();
        File first = new File(temp.getRoot(), "first.wcp"), second = new File(temp.getRoot(), "second.wcp");
        ResumableDownload.Control secondControl = new ResumableDownload.Control();
        try (ResumableDownloadTest.Server one = new ResumableDownloadTest.Server((headers, out) -> {
            firstStarted.countDown();
            assertTrue(releaseFirst.await(3, TimeUnit.SECONDS));
            reply(out, new byte[]{1});
        }); ResumableDownloadTest.Server two = new ResumableDownloadTest.Server((headers, out) -> {
            secondContacted.set(true);
            reply(out, new byte[]{2, 3});
        })) {
            CompletableFuture<ResumableDownload.Result> firstJob = CompletableFuture.supplyAsync(() ->
                    ContentDownloadQueue.download(one.url(), first, new ResumableDownload.Control(), null, null));
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            CompletableFuture<ResumableDownload.Result> secondJob = CompletableFuture.supplyAsync(() ->
                    ContentDownloadQueue.download(two.url(), second, secondControl, null, queued::countDown));
            assertTrue(queued.await(2, TimeUnit.SECONDS));
            assertEquals(1, ContentOperationRegistry.getActiveDownloads());
            assertFalse(secondContacted.get());
            secondControl.pause();
            assertEquals(ResumableDownload.Result.PAUSED, secondJob.get(2, TimeUnit.SECONDS));
            assertTrue(ContentDownloadQueue.hasSavedDownload(two.url(), second));
            assertFalse(second.exists());
            releaseFirst.countDown();
            assertEquals(ResumableDownload.Result.COMPLETE, firstJob.get(2, TimeUnit.SECONDS));
            assertEquals(ResumableDownload.Result.COMPLETE, ContentDownloadQueue.download(
                    two.url(), second, new ResumableDownload.Control(), null, null));
        } finally { releaseFirst.countDown(); }
        assertTrue(secondContacted.get());
        assertArrayEquals(new byte[]{2, 3}, Files.readAllBytes(second.toPath()));
        assertFalse(ContentOperationRegistry.hasActiveOperations());
    }

    @Test public void cancellingAQueuedJobDoesNotDeleteTheActiveJobsOutputOrBlockTheQueue() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1), releaseFirst = new CountDownLatch(1), queued = new CountDownLatch(1);
        File first = new File(temp.getRoot(), "active.wcp"), cancelled = new File(temp.getRoot(), "cancelled.wcp");
        ResumableDownload.Control control = new ResumableDownload.Control();
        try (ResumableDownloadTest.Server server = new ResumableDownloadTest.Server((headers, out) -> {
            firstStarted.countDown();
            assertTrue(releaseFirst.await(3, TimeUnit.SECONDS));
            reply(out, new byte[]{4, 5, 6});
        })) {
            CompletableFuture<ResumableDownload.Result> active = CompletableFuture.supplyAsync(() ->
                    ContentDownloadQueue.download(server.url(), first, new ResumableDownload.Control(), null, null));
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            String noRequest = "http://127.0.0.1:9/must-not-connect.wcp";
            CompletableFuture<ResumableDownload.Result> waiting = CompletableFuture.supplyAsync(() ->
                    ContentDownloadQueue.download(noRequest, cancelled, control, null, queued::countDown));
            assertTrue(queued.await(2, TimeUnit.SECONDS));
            control.cancel();
            assertEquals(ResumableDownload.Result.CANCELLED, waiting.get(2, TimeUnit.SECONDS));
            assertFalse(ContentDownloadQueue.hasSavedDownload(noRequest, cancelled));
            releaseFirst.countDown();
            assertEquals(ResumableDownload.Result.COMPLETE, active.get(2, TimeUnit.SECONDS));
        } finally { releaseFirst.countDown(); }
        assertArrayEquals(new byte[]{4, 5, 6}, Files.readAllBytes(first.toPath()));
        assertFalse(ContentOperationRegistry.hasActiveOperations());
    }

    @Test public void completeDownloadCanBeConsumedAgainWithoutAnotherNetworkRequest() throws Exception {
        File output = new File(temp.getRoot(), "cached.wcp");
        String url;
        try (ResumableDownloadTest.Server server = new ResumableDownloadTest.Server((headers, out) -> reply(out, new byte[]{7}))) {
            url = server.url();
            assertEquals(ResumableDownload.Result.COMPLETE, ContentDownloadQueue.download(url, output,
                    new ResumableDownload.Control(), null, null));
        }
        assertEquals(ResumableDownload.Result.COMPLETE, ContentDownloadQueue.download(url, output,
                new ResumableDownload.Control(), null, null));
        ContentDownloadQueue.discard(output);
        assertFalse(ContentDownloadQueue.hasSavedDownload(url, output));
        assertFalse(output.exists());
        assertFalse(ContentOperationRegistry.hasActiveOperations());
    }

    @Test public void consumersKeepIndependentArchivesAfterCacheAndOtherConsumerAreRemoved() throws Exception {
        File output = new File(temp.getRoot(), "shared.wcp");
        ContentDownloadQueue.Artifact first;
        String url;
        try (ResumableDownloadTest.Server server = new ResumableDownloadTest.Server((headers, out) -> reply(out, new byte[]{3, 5, 7}))) {
            url = server.url();
            first = ContentDownloadQueue.acquire(url, output, new ResumableDownload.Control(), null, null);
        }
        try (ContentDownloadQueue.Artifact second = ContentDownloadQueue.acquire(url, output, new ResumableDownload.Control(), null, null)) {
            assertEquals(ResumableDownload.Result.COMPLETE, first.result);
            assertEquals(ResumableDownload.Result.COMPLETE, second.result);
            assertNotEquals(first.file, second.file);
            ContentDownloadQueue.discard(output);
            assertFalse(output.exists());
            assertArrayEquals(new byte[]{3, 5, 7}, Files.readAllBytes(first.file.toPath()));
            first.close(); first.close();
            assertFalse(first.file.exists());
            assertArrayEquals(new byte[]{3, 5, 7}, Files.readAllBytes(second.file.toPath()));
        } finally { first.close(); }
        assertEquals(0, temp.getRoot().listFiles().length);
    }

    @Test public void cleanupDuringCompletionWaitsForTheInstallerArchiveToBeAcquired() throws Exception {
        File output = new File(temp.getRoot(), "discard-during-completion.wcp");
        try (ResumableDownloadTest.Server server = new ResumableDownloadTest.Server((headers, out) -> reply(out, new byte[]{2, 4, 6}));
             ContentDownloadQueue.Artifact artifact = ContentDownloadQueue.acquire(server.url(), output, new ResumableDownload.Control(),
                     percent -> { if (percent == 100) ContentDownloadQueue.discard(output); }, null)) {
            assertEquals(ResumableDownload.Result.COMPLETE, artifact.result);
            assertFalse(output.exists());
            assertArrayEquals(new byte[]{2, 4, 6}, Files.readAllBytes(artifact.file.toPath()));
        }
        assertFalse(ContentOperationRegistry.hasActiveOperations());
    }

    @Test public void replacementAtSameCachePathCannotChangeAnExistingInstallerArchive() throws Exception {
        File output = new File(temp.getRoot(), "same-path.wcp");
        try (ResumableDownloadTest.Server one = new ResumableDownloadTest.Server((headers, out) -> reply(out, new byte[]{1, 2}));
             ResumableDownloadTest.Server two = new ResumableDownloadTest.Server((headers, out) -> reply(out, new byte[]{8, 9}));
             ContentDownloadQueue.Artifact first = ContentDownloadQueue.acquire(one.url(), output, new ResumableDownload.Control(), null, null);
             ContentDownloadQueue.Artifact second = ContentDownloadQueue.acquire(two.url(), output, new ResumableDownload.Control(), null, null)) {
            assertEquals(ResumableDownload.Result.COMPLETE, first.result);
            assertEquals(ResumableDownload.Result.COMPLETE, second.result);
            assertArrayEquals(new byte[]{1, 2}, Files.readAllBytes(first.file.toPath()));
            assertArrayEquals(new byte[]{8, 9}, Files.readAllBytes(second.file.toPath()));
            ContentDownloadQueue.discard(output);
        }
    }

    @Test public void acquisitionCleansProcessOrphansButPreservesLiveAndUnrelatedFiles() throws Exception {
        File output = new File(temp.getRoot(), "cleanup.wcp");
        File orphan = new File(temp.getRoot(), ".in-use-old-process-package.wcp");
        File unrelated = new File(temp.getRoot(), "user-package.wcp");
        Files.write(orphan.toPath(), new byte[]{8}); Files.write(unrelated.toPath(), new byte[]{9});
        try (ResumableDownloadTest.Server server = new ResumableDownloadTest.Server((headers, out) -> reply(out, new byte[]{1}));
             ContentDownloadQueue.Artifact first = ContentDownloadQueue.acquire(server.url(), output, new ResumableDownload.Control(), null, null);
             ContentDownloadQueue.Artifact second = ContentDownloadQueue.acquire(server.url(), output, new ResumableDownload.Control(), null, null)) {
            assertFalse(orphan.exists());
            assertTrue(first.file.isFile()); assertTrue(second.file.isFile());
            assertArrayEquals(new byte[]{9}, Files.readAllBytes(unrelated.toPath()));
            ContentDownloadQueue.discard(output);
        }
    }

    @Test public void pausedAcquisitionKeepsCompleteCacheWithoutLeakingAnInstallerArchive() throws Exception {
        File output = new File(temp.getRoot(), "pause.wcp"); ResumableDownload.Control control = new ResumableDownload.Control();
        String url;
        try (ResumableDownloadTest.Server server = new ResumableDownloadTest.Server((headers, out) -> reply(out, new byte[]{4}))) {
            url = server.url();
            try (ContentDownloadQueue.Artifact stopped = ContentDownloadQueue.acquire(url, output, control,
                    percent -> { if (percent == 100) control.pause(); }, null)) {
                assertEquals(ResumableDownload.Result.PAUSED, stopped.result); assertNull(stopped.file);
            }
        }
        try (ContentDownloadQueue.Artifact resumed = ContentDownloadQueue.acquire(url, output, new ResumableDownload.Control(), null, null)) {
            assertEquals(ResumableDownload.Result.COMPLETE, resumed.result);
            assertArrayEquals(new byte[]{4}, Files.readAllBytes(resumed.file.toPath()));
            ContentDownloadQueue.discard(output);
        }
        assertEquals(0, temp.getRoot().listFiles().length);
    }

    @Test public void cancelledAcquisitionDoesNotKeepCacheOrInstallerArchive() throws Exception {
        File output = new File(temp.getRoot(), "cancel.wcp"); ResumableDownload.Control control = new ResumableDownload.Control();
        try (ResumableDownloadTest.Server server = new ResumableDownloadTest.Server((headers, out) -> reply(out, new byte[]{4}));
             ContentDownloadQueue.Artifact stopped = ContentDownloadQueue.acquire(server.url(), output, control,
                     percent -> { if (percent == 100) control.cancel(); }, null)) {
            assertEquals(ResumableDownload.Result.CANCELLED, stopped.result); assertNull(stopped.file);
        }
        assertEquals(0, temp.getRoot().listFiles().length);
        assertFalse(ContentOperationRegistry.hasActiveOperations());
    }

    @Test public void cancellingAQueuedConsumerOfTheSamePackageKeepsTheActiveConsumerIntact() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), queued = new CountDownLatch(1);
        File output = new File(temp.getRoot(), "same-package.wcp");
        ResumableDownload.Control cancelled = new ResumableDownload.Control();
        try (ResumableDownloadTest.Server server = new ResumableDownloadTest.Server((headers, out) -> {
            started.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); reply(out, new byte[]{2, 5});
        })) {
            CompletableFuture<ContentDownloadQueue.Artifact> active = CompletableFuture.supplyAsync(() ->
                    ContentDownloadQueue.acquire(server.url(), output, new ResumableDownload.Control(), null, null));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            CompletableFuture<ContentDownloadQueue.Artifact> waiting = CompletableFuture.supplyAsync(() ->
                    ContentDownloadQueue.acquire(server.url(), output, cancelled, null, queued::countDown));
            assertTrue(queued.await(2, TimeUnit.SECONDS)); cancelled.cancel();
            try (ContentDownloadQueue.Artifact stopped = waiting.get(2, TimeUnit.SECONDS)) {
                assertEquals(ResumableDownload.Result.CANCELLED, stopped.result); assertNull(stopped.file);
            }
            release.countDown();
            try (ContentDownloadQueue.Artifact artifact = active.get(2, TimeUnit.SECONDS)) {
                assertEquals(ResumableDownload.Result.COMPLETE, artifact.result);
                assertArrayEquals(new byte[]{2, 5}, Files.readAllBytes(artifact.file.toPath()));
                ContentDownloadQueue.discard(output);
            }
        } finally { release.countDown(); }
        assertFalse(ContentOperationRegistry.hasActiveOperations());
    }
}
