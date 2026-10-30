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
}
