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
}
