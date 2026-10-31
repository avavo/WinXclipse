package com.winlator.cmod.contents;

import com.winlator.cmod.core.ContentOperationRegistry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class DependencyImportSessionTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void aPausedImportNeverLooksLikeAnUnavailablePackage() throws Exception {
        DependencyImportSession session = new DependencyImportSession(temp.getRoot(), null);
        session.pause();
        DependencyImportSession.Stopped failure = assertThrows(DependencyImportSession.Stopped.class,
                () -> session.acquire("http://127.0.0.1:9/unused.wcp", "package"));
        assertFalse(failure.cancelled);
        assertEquals(DependencyImportSession.Failure.UNAVAILABLE, session.failure("wineRuntime"));
        assertFalse(ContentOperationRegistry.hasActiveOperations());
    }

    @Test public void cancelStopsBeforeAnyInstallWork() throws Exception {
        DependencyImportSession session = new DependencyImportSession(temp.getRoot(), null);
        AtomicBoolean installed = new AtomicBoolean();
        session.cancel();
        DependencyImportSession.Stopped failure = assertThrows(DependencyImportSession.Stopped.class,
                () -> session.install("Wine", () -> { installed.set(true); return true; }));
        assertTrue(failure.cancelled);
        assertFalse(installed.get());
        assertFalse(ContentOperationRegistry.hasActiveOperations());
    }

    @Test public void pauseDuringCommitWaitsForTheInstallToFinishAndThenStopsResolution() throws Exception {
        DependencyImportSession session = new DependencyImportSession(temp.getRoot(), null);
        AtomicBoolean committed = new AtomicBoolean();
        assertThrows(DependencyImportSession.Stopped.class, () -> session.install("Box64", () -> {
            assertEquals(1, ContentOperationRegistry.getActiveInstalls());
            session.pause();
            committed.set(true);
            return true;
        }));
        assertTrue(committed.get());
        assertFalse(ContentOperationRegistry.hasActiveOperations());
    }

    @Test public void realDownloadFailureIsRecordedSeparatelyFromMissingCatalogData() {
        DependencyImportSession session = new DependencyImportSession(temp.getRoot(), null);
        session.failed("wineRuntime", DependencyImportSession.Failure.DOWNLOAD);
        session.failed("wineRuntime", DependencyImportSession.Failure.CATALOG);
        assertEquals(DependencyImportSession.Failure.DOWNLOAD, session.failure("wineRuntime"));
        assertEquals(DependencyImportSession.Failure.UNAVAILABLE, session.failure("driver"));
        session.failed("wineRuntime", DependencyImportSession.Failure.PACKAGE);
        assertEquals(DependencyImportSession.Failure.PACKAGE, session.failure("wineRuntime"));
    }

    @Test public void automaticImportsAndTheCatalogUseTheSameWorkspace() {
        DependencyImportSession session = new DependencyImportSession(temp.getRoot(), null);
        String url = "https://example.com/releases/Proton%2010-arm64.tzst?download=1";
        assertEquals(ResumableDownload.outputFile(temp.getRoot(), url, "Proton 10-arm64.tzst"), session.output(url));
    }

    @Test public void pausingAtDownloadCompletionDoesNotStartInstallationAndKeepsAValidCache() throws Exception {
        AtomicReference<DependencyImportSession> current = new AtomicReference<>();
        DependencyImportSession session = new DependencyImportSession(temp.getRoot(), (stage, name, percent) -> {
            if (stage == DependencyImportSession.Stage.DOWNLOADING && percent == 100) current.get().pause();
        });
        current.set(session);
        try (ResumableDownloadTest.Server server = new ResumableDownloadTest.Server((headers, out) ->
                ContentDownloadQueueTest.reply(out, new byte[]{1, 3, 5}))) {
            File output = session.output(server.url());
            assertThrows(DependencyImportSession.Stopped.class, () -> session.acquire(server.url(), "Proton"));
            assertTrue(output.isFile());
            assertTrue(ContentDownloadQueue.hasSavedDownload(server.url(), output));
            assertEquals(0, ContentOperationRegistry.getActiveInstalls());
        }
        assertFalse(ContentOperationRegistry.hasActiveOperations());
    }

    @Test public void cleanupRunsAfterACommittedInstallEvenIfTheUserPausedDuringIt() {
        DependencyImportSession session = new DependencyImportSession(temp.getRoot(), null);
        AtomicBoolean cleaned = new AtomicBoolean();
        assertThrows(DependencyImportSession.Stopped.class,
                () -> session.install("Proton", () -> { session.pause(); return true; }, () -> cleaned.set(true)));
        assertTrue(cleaned.get());
        assertFalse(ContentOperationRegistry.hasActiveOperations());
    }

    @Test public void pauseBeforeInstallationKeepsTheDownloadedPackageForResume() {
        DependencyImportSession session = new DependencyImportSession(temp.getRoot(), null);
        AtomicBoolean cleaned = new AtomicBoolean();
        session.pause();
        assertThrows(DependencyImportSession.Stopped.class,
                () -> session.install("Proton", () -> true, () -> cleaned.set(true)));
        assertFalse(cleaned.get());
    }
}
