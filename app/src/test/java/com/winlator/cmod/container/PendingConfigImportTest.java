package com.winlator.cmod.container;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;

import static org.junit.Assert.*;

public class PendingConfigImportTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private PendingConfigImport ready(ExecutorService worker, File receipt) throws Exception {
        PendingConfigImport pending = new PendingConfigImport(receipt, worker, (manifest, session) -> Collections.emptyList());
        CountDownLatch ready = new CountDownLatch(1);
        pending.observe(() -> { if (pending.state() == PendingConfigImport.State.READY && !pending.isRunning()) ready.countDown(); });
        pending.offer(new JSONObject().put("container", new JSONObject().put("wineVersion", "Proton-10.0-arm64-18")));
        pending.start();
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        return pending;
    }

    @Test public void activityRecreationAndDoubleConfirmationCannotCreateTwice() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            File receipt = new File(temp.getRoot(), "pending.json");
            PendingConfigImport pending = ready(worker, receipt);
            assertTrue(pending.beginApply());
            assertFalse(pending.beginApply());
            pending.pause(); // onDestroyView
            pending.cancel(); // stale dialog callback
            pending.start();
            assertEquals(PendingConfigImport.State.APPLYING, pending.state());
            assertTrue(receipt.isFile());
            assertThrows(java.io.IOException.class, () -> pending.offer(new JSONObject()));
            pending.complete();
            assertEquals(PendingConfigImport.State.IDLE, pending.state());
            assertFalse(pending.isRunning());
            assertFalse(receipt.exists());
        } finally { worker.shutdownNow(); }
    }

    @Test public void creationFailureRetainsTheExactConfigForRetry() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            File receipt = new File(temp.getRoot(), "pending.json");
            PendingConfigImport pending = ready(worker, receipt);
            byte[] saved = Files.readAllBytes(receipt.toPath());
            assertTrue(pending.beginApply());
            pending.applyFailed();
            assertEquals(PendingConfigImport.State.READY, pending.state());
            assertFalse(pending.isRunning());
            assertArrayEquals(saved, Files.readAllBytes(receipt.toPath()));
            assertEquals("Proton-10.0-arm64-18", pending.manifest().getJSONObject("container").getString("wineVersion"));
            assertTrue(pending.beginApply());
            pending.complete();
        } finally { worker.shutdownNow(); }
    }

    @Test public void resumeAfterProcessRestartReadsTheDurableUtf8Config() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            File receipt = new File(temp.getRoot(), "pending.json");
            PendingConfigImport original = new PendingConfigImport(receipt, worker, (manifest, session) -> Collections.emptyList());
            JSONObject config = new JSONObject().put("container", new JSONObject().put("name", "Ação española").put("wineVersion", "Proton-10-arm64-18"));
            original.offer(config);
            config.getJSONObject("container").put("wineVersion", "other-runtime");
            assertEquals("Proton-10-arm64-18", original.manifest().getJSONObject("container").getString("wineVersion"));
            assertTrue(new String(Files.readAllBytes(receipt.toPath()), StandardCharsets.UTF_8).contains("Ação española"));
            CountDownLatch done = new CountDownLatch(1);
            PendingConfigImport restarted = new PendingConfigImport(receipt, worker, (manifest, session) -> {
                assertEquals("Ação española", manifest.getJSONObject("container").getString("name"));
                assertEquals("Proton-10-arm64-18", manifest.getJSONObject("container").getString("wineVersion"));
                return Collections.emptyList();
            });
            assertEquals(PendingConfigImport.State.PAUSED, restarted.state());
            restarted.observe(() -> { if (restarted.state() == PendingConfigImport.State.READY) done.countDown(); });
            restarted.start();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(PendingConfigImport.State.READY, restarted.state());
        } finally { worker.shutdownNow(); }
    }

    private File publish(PendingConfigImport pending, File home, int id) throws Exception {
        File staging = new File(home, ".container-create-" + UUID.randomUUID());
        File target = new File(home, "xuser-" + id);
        ConfigImportJournal journal = pending.creationJournal();
        journal.prepare(home, staging, target);
        Files.write(new File(staging, ".container").toPath(), new JSONObject().put("id", id)
                .put("wineVersion", "Proton-10.0-arm64-18").toString().getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(new File(staging, ".wine/drive_c").toPath());
        journal.publish(home, staging, target);
        return target;
    }

    @Test public void processDeathAfterPublicationSkipsDependencyResolutionAndAnotherCreation() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            File receipt = new File(temp.getRoot(), "pending.json");
            File home = new File(temp.getRoot(), "home");
            assertTrue(home.mkdir());
            PendingConfigImport original = ready(worker, receipt);
            assertTrue(original.beginApply());
            File target = publish(original, home, 11);
            byte[] config = Files.readAllBytes(new File(target, ".container").toPath());
            // Kill before original.complete(): the old code would resolve and offer creation again.
            AtomicInteger resolves = new AtomicInteger();
            PendingConfigImport restarted = new PendingConfigImport(receipt, home, worker, (manifest, session) -> {
                resolves.incrementAndGet();
                return Collections.emptyList();
            });
            CountDownLatch done = new CountDownLatch(1);
            restarted.observe(() -> { if (restarted.state() == PendingConfigImport.State.IDLE && !restarted.isRunning()) done.countDown(); });
            restarted.start();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(0, resolves.get());
            assertFalse(restarted.beginApply());
            assertFalse(receipt.exists());
            assertEquals(1, home.list().length);
            assertArrayEquals(config, Files.readAllBytes(new File(target, ".container").toPath()));
        } finally { worker.shutdownNow(); }
    }

    @Test public void processDeathBeforePublicationCleansOnlyItsStagingAndReviewsAgain() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            File receipt = new File(temp.getRoot(), "pending.json");
            File home = new File(temp.getRoot(), "home");
            assertTrue(home.mkdir());
            PendingConfigImport original = ready(worker, receipt);
            assertTrue(original.beginApply());
            File staging = new File(home, ".container-create-" + UUID.randomUUID());
            original.creationJournal().prepare(home, staging, new File(home, "xuser-1"));
            Files.write(new File(staging, "partial").toPath(), new byte[]{1});
            File unrelated = new File(home, ".container-create-" + UUID.randomUUID());
            assertTrue(unrelated.mkdir());
            Files.write(new File(unrelated, "keep").toPath(), new byte[]{8});
            AtomicInteger resolves = new AtomicInteger();
            PendingConfigImport restarted = new PendingConfigImport(receipt, home, worker, (manifest, session) -> {
                assertFalse(staging.exists());
                assertEquals("Proton-10.0-arm64-18", manifest.getJSONObject("container").getString("wineVersion"));
                resolves.incrementAndGet();
                return Collections.emptyList();
            });
            CountDownLatch done = new CountDownLatch(1);
            restarted.observe(() -> { if (restarted.state() == PendingConfigImport.State.READY && !restarted.isRunning()) done.countDown(); });
            restarted.start();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(1, resolves.get());
            assertTrue(new File(unrelated, "keep").isFile());
            assertTrue(restarted.beginApply());
            publish(restarted, home, 1);
            restarted.complete();
            assertEquals(PendingConfigImport.State.IDLE, restarted.state());
            assertEquals(2, home.list().length); // The unrelated staging is untouched.
        } finally { worker.shutdownNow(); }
    }

    @Test public void aCompleteReceiptLeftOnDiskCannotRestartAnImport() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            File receipt = new File(temp.getRoot(), "pending.json") {
                @Override public boolean delete() { return false; }
            };
            PendingConfigImport original = ready(worker, receipt);
            assertTrue(original.beginApply());
            original.complete();
            assertTrue(receipt.isFile());
            AtomicInteger resolves = new AtomicInteger();
            PendingConfigImport restarted = new PendingConfigImport(receipt, worker, (manifest, session) -> {
                resolves.incrementAndGet(); return Collections.emptyList();
            });
            CountDownLatch done = new CountDownLatch(1);
            restarted.observe(() -> { if (restarted.state() == PendingConfigImport.State.IDLE && !restarted.isRunning()) done.countDown(); });
            restarted.start();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(0, resolves.get());
            assertFalse(restarted.beginApply());
        } finally { worker.shutdownNow(); }
    }

    @Test public void cancellationWaitsForTheWorkerBeforeClearingOrReplacingItsReceipt() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch release = new CountDownLatch(1);
        try {
            File receipt = new File(temp.getRoot(), "pending.json");
            CountDownLatch entered = new CountDownLatch(1), stopped = new CountDownLatch(1);
            PendingConfigImport pending = new PendingConfigImport(receipt, worker, (manifest, session) -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                session.checkRunning();
                return Collections.emptyList();
            });
            pending.observe(() -> { if (pending.state() == PendingConfigImport.State.CANCELLED && !pending.isRunning()) stopped.countDown(); });
            pending.offer(new JSONObject().put("container", new JSONObject().put("name", "First")));
            pending.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            pending.cancel();
            assertEquals(PendingConfigImport.State.STOPPING, pending.state());
            assertTrue(receipt.isFile());
            assertThrows(java.io.IOException.class, () -> pending.offer(new JSONObject()));
            release.countDown();
            assertTrue(stopped.await(5, TimeUnit.SECONDS));
            assertFalse(receipt.exists());
            pending.offer(new JSONObject().put("container", new JSONObject().put("name", "Second")));
            assertEquals("Second", pending.manifest().getJSONObject("container").getString("name"));
        } finally { release.countDown(); worker.shutdownNow(); }
    }

    @Test public void aCancelledReceiptThatCannotBeDeletedDoesNotReplayAfterRestart() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            File receipt = new File(temp.getRoot(), "pending.json") {
                @Override public boolean delete() { return false; }
            };
            PendingConfigImport pending = ready(worker, receipt);
            pending.cancel();
            assertEquals(PendingConfigImport.State.CANCELLED, pending.state());
            assertTrue(receipt.isFile());
            AtomicInteger resolves = new AtomicInteger();
            PendingConfigImport restarted = new PendingConfigImport(receipt, worker, (manifest, session) -> {
                resolves.incrementAndGet(); return Collections.emptyList();
            });
            CountDownLatch done = new CountDownLatch(1);
            restarted.observe(() -> { if (restarted.state() == PendingConfigImport.State.IDLE && !restarted.isRunning()) done.countDown(); });
            restarted.start();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(0, resolves.get());
        } finally { worker.shutdownNow(); }
    }

    @Test public void corruptPublishedConfigStopsRecoveryWithoutResolvingOrCreatingAgain() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            File receipt = new File(temp.getRoot(), "pending.json");
            File home = new File(temp.getRoot(), "home");
            assertTrue(home.mkdir());
            PendingConfigImport original = ready(worker, receipt);
            assertTrue(original.beginApply());
            File target = publish(original, home, 2);
            Files.write(new File(target, ".container").toPath(), new byte[]{'{'});
            AtomicInteger resolves = new AtomicInteger();
            PendingConfigImport restarted = new PendingConfigImport(receipt, home, worker, (manifest, session) -> {
                resolves.incrementAndGet(); return Collections.emptyList();
            });
            CountDownLatch failed = new CountDownLatch(1);
            restarted.observe(() -> { if (restarted.state() == PendingConfigImport.State.FAILED && !restarted.isRunning()) failed.countDown(); });
            restarted.start();
            assertTrue(failed.await(5, TimeUnit.SECONDS));
            assertEquals(0, resolves.get());
            assertFalse(restarted.beginApply());
            assertTrue(receipt.isFile());
            assertEquals(1, home.list().length);
        } finally { worker.shutdownNow(); }
    }
}
