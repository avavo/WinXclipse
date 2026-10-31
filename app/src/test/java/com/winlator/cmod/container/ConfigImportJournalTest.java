package com.winlator.cmod.container;

import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

import static org.junit.Assert.*;

public class ConfigImportJournalTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private JSONObject manifest() throws Exception {
        return new JSONObject().put("container", new JSONObject().put("name", "Ação española")
                .put("wineVersion", "Proton-10-arm64-18").put("dxwrapperConfig", "ramFix=1"));
    }

    private File stage(File home) { return new File(home, ".container-create-" + UUID.randomUUID()); }
    private void config(File directory, int id) throws Exception {
        Files.write(new File(directory, ".container").toPath(), new JSONObject().put("id", id)
                .put("wineVersion", "Proton-10-arm64-18").put("dxwrapperConfig", "ramFix=1")
                .toString().getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(new File(directory, ".wine/drive_c").toPath());
        Files.write(new File(directory, ".wine/drive_c/save").toPath(), new byte[]{1, 2, 3});
    }
    private JSONObject json(File file) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }
    private void write(File file, JSONObject value) throws Exception {
        Files.write(file.toPath(), value.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test public void persistsAnIndependentUtf8ManifestAndStableIdentity() throws Exception {
        File receipt = new File(temp.getRoot(), "pending.json");
        JSONObject source = manifest();
        ConfigImportJournal created = ConfigImportJournal.create(receipt, source);
        String identity = json(receipt).getString("importId");
        source.getJSONObject("container").put("wineVersion", "other");
        JSONObject preview = created.manifest();
        preview.getJSONObject("container").put("name", "changed");
        ConfigImportJournal restored = ConfigImportJournal.load(receipt);
        assertEquals(identity, json(receipt).getString("importId"));
        assertEquals("Ação española", restored.manifest().getJSONObject("container").getString("name"));
        assertEquals("Proton-10-arm64-18", restored.manifest().getJSONObject("container").getString("wineVersion"));
    }

    @Test public void migratesLegacyReceiptsWithoutChangingTheRequestedRuntime() throws Exception {
        File receipt = new File(temp.getRoot(), "legacy.json");
        write(receipt, manifest());
        ConfigImportJournal restored = ConfigImportJournal.load(receipt);
        assertEquals("Proton-10-arm64-18", restored.manifest().getJSONObject("container").getString("wineVersion"));
        assertNotNull(json(receipt).getJSONObject("manifest"));
        assertFalse(restored.recover(temp.newFolder()));
    }

    @Test public void killAfterPublicationRecognizesTheOriginalContainerAndPreservesItsFiles() throws Exception {
        File receipt = new File(temp.getRoot(), "pending.json"), home = temp.newFolder();
        ConfigImportJournal original = ConfigImportJournal.create(receipt, manifest());
        File staging = stage(home), target = new File(home, "xuser-7");
        original.prepare(home, staging, target);
        config(staging, 7);
        byte[] saved = Files.readAllBytes(new File(staging, ".container").toPath());
        original.publish(home, staging, target);
        // No completion callback: construct a new journal exactly as a fresh process does.
        ConfigImportJournal restored = ConfigImportJournal.load(receipt);
        assertTrue(restored.recover(home));
        assertFalse(staging.exists());
        assertArrayEquals(saved, Files.readAllBytes(new File(target, ".container").toPath()));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(new File(target, ".wine/drive_c/save").toPath()));
        restored.finish();
        assertFalse(receipt.exists());
        assertEquals(1, home.list().length);
    }

    @Test public void incompleteStagingIsCleanedBeforeRetryUsingTheSameImportIdentity() throws Exception {
        File receipt = new File(temp.getRoot(), "pending.json"), home = temp.newFolder();
        ConfigImportJournal original = ConfigImportJournal.create(receipt, manifest());
        String identity = json(receipt).getString("importId");
        File first = stage(home), target = new File(home, "xuser-4");
        original.prepare(home, first, target);
        Files.createDirectories(new File(first, ".wine/drive_c/partial").toPath());
        Files.write(new File(first, ".wine/drive_c/partial/file").toPath(), new byte[]{4});
        ConfigImportJournal restored = ConfigImportJournal.load(receipt);
        assertFalse(restored.recover(home));
        assertFalse(first.exists());
        assertFalse(target.exists());
        assertFalse(json(receipt).has("creation"));
        assertEquals(identity, json(receipt).getString("importId"));
        File retry = stage(home);
        restored.prepare(home, retry, target);
        config(retry, 4);
        restored.publish(home, retry, target);
        assertTrue(ConfigImportJournal.load(receipt).recover(home));
        assertEquals(1, home.list().length);
    }

    private static final class Kill extends Error {}

    @Test public void checkpointIsDurableEvenWhenKilledBeforeMkdir() throws Exception {
        File receipt = new File(temp.getRoot(), "pending.json"), home = temp.newFolder();
        ConfigImportJournal journal = ConfigImportJournal.create(receipt, manifest());
        File beforeMkdir = new File(stage(home).getPath()) {
            @Override public boolean mkdir() { throw new Kill(); }
        };
        assertThrows(Kill.class, () -> journal.prepare(home, beforeMkdir, new File(home, "xuser-2")));
        assertTrue(json(receipt).has("creation"));
        assertFalse(ConfigImportJournal.load(receipt).recover(home));
        assertEquals(0, home.list().length);
        assertFalse(json(receipt).has("creation"));
    }

    @Test public void killBetweenMkdirAndMarkerCanRecoverTheEmptyOwnedSlot() throws Exception {
        File receipt = new File(temp.getRoot(), "pending.json"), home = temp.newFolder();
        ConfigImportJournal journal = ConfigImportJournal.create(receipt, manifest());
        File afterMkdir = new File(stage(home).getPath()) {
            @Override public boolean mkdir() { assertTrue(super.mkdir()); throw new Kill(); }
        };
        assertThrows(Kill.class, () -> journal.prepare(home, afterMkdir, new File(home, "xuser-2")));
        assertTrue(afterMkdir.isDirectory());
        assertFalse(ConfigImportJournal.load(receipt).recover(home));
        assertFalse(afterMkdir.exists());
    }

    @Test public void concurrentForeignDestinationIsNeverAdoptedOrReplaced() throws Exception {
        File receipt = new File(temp.getRoot(), "pending.json"), home = temp.newFolder();
        ConfigImportJournal journal = ConfigImportJournal.create(receipt, manifest());
        File staging = stage(home), target = new File(home, "xuser-3");
        journal.prepare(home, staging, target);
        config(staging, 3);
        assertTrue(target.mkdir());
        Files.write(new File(target, "foreign-save").toPath(), new byte[]{9});
        assertThrows(IOException.class, () -> journal.publish(home, staging, target));
        assertFalse(ConfigImportJournal.load(receipt).recover(home));
        assertArrayEquals(new byte[]{9}, Files.readAllBytes(new File(target, "foreign-save").toPath()));
        assertFalse(new File(target, ".container").exists());
        assertFalse(staging.exists());
    }

    @Test public void missingOrInvalidConfigCannotBePublished() throws Exception {
        File receipt = new File(temp.getRoot(), "pending.json"), home = temp.newFolder();
        ConfigImportJournal journal = ConfigImportJournal.create(receipt, manifest());
        File staging = stage(home), target = new File(home, "xuser-8");
        journal.prepare(home, staging, target);
        assertThrows(IOException.class, () -> journal.publish(home, staging, target));
        config(staging, 9);
        assertThrows(IOException.class, () -> journal.publish(home, staging, target));
        config(staging, 8);
        journal.publish(home, staging, target);
        Files.write(new File(target, ".container").toPath(), new byte[]{'{'});
        assertThrows(IOException.class, () -> ConfigImportJournal.load(receipt).recover(home));
        assertTrue(target.exists());
        assertTrue(receipt.exists());
    }

    @Test public void anEditedPublishedContainerIsNotRewrittenOrImportedAgain() throws Exception {
        File receipt = new File(temp.getRoot(), "pending.json"), home = temp.newFolder();
        ConfigImportJournal journal = ConfigImportJournal.create(receipt, manifest());
        File staging = stage(home), target = new File(home, "xuser-1");
        journal.prepare(home, staging, target);
        config(staging, 1);
        journal.publish(home, staging, target);
        File config = new File(target, ".container");
        write(config, json(config).put("name", "Edited by user").put("wineVersion", "Another-selected-runtime"));
        assertTrue(ConfigImportJournal.load(receipt).recover(home));
        assertEquals("Another-selected-runtime", json(config).getString("wineVersion"));
        assertEquals("Edited by user", json(config).getString("name"));
    }

    @Test public void failureToDeleteACompleteReceiptCannotReplayTheImport() throws Exception {
        File receipt = new File(temp.getRoot(), "pending.json") {
            @Override public boolean delete() { return false; }
        };
        ConfigImportJournal.create(receipt, manifest()).finish();
        assertTrue(receipt.isFile());
        assertTrue(json(receipt).getBoolean("complete"));
        assertTrue(ConfigImportJournal.load(receipt).recover(temp.newFolder()));
    }

    @Test public void invalidPathsAndUnsupportedFormatsCannotDriveRecovery() throws Exception {
        File receipt = new File(temp.getRoot(), "pending.json"), home = temp.newFolder();
        ConfigImportJournal.create(receipt, manifest());
        JSONObject original = json(receipt);
        for (String target : new String[]{"../xuser-1", "xuser-0", "xuser-2147483648", "xuser-1/child"}) {
            JSONObject record = new JSONObject(original.toString()).put("creation", new JSONObject()
                    .put("staging", stage(home).getName()).put("target", target));
            write(receipt, record);
            assertThrows(IOException.class, () -> ConfigImportJournal.load(receipt));
        }
        write(receipt, new JSONObject(original.toString()).put("version", 999));
        assertThrows(IOException.class, () -> ConfigImportJournal.load(receipt));
        write(receipt, new JSONObject(original.toString()).put("importId", "not-a-uuid"));
        assertThrows(IOException.class, () -> ConfigImportJournal.load(receipt));
        assertEquals(0, home.list().length);
    }

    @Test public void unidentifiedNonemptyStagingAndExistingAllocationArePreserved() throws Exception {
        File receipt = new File(temp.getRoot(), "pending.json"), home = temp.newFolder();
        ConfigImportJournal journal = ConfigImportJournal.create(receipt, manifest());
        File staging = stage(home), target = new File(home, "xuser-6");
        assertTrue(staging.mkdir());
        Files.write(new File(staging, "foreign").toPath(), new byte[]{7});
        assertThrows(IOException.class, () -> journal.prepare(home, staging, target));
        assertFalse(json(receipt).has("creation"));
        JSONObject record = json(receipt).put("creation", new JSONObject()
                .put("staging", staging.getName()).put("target", target.getName()));
        write(receipt, record); // Simulate a conflicting directory appearing after the checkpoint.
        assertThrows(IOException.class, () -> ConfigImportJournal.load(receipt).recover(home));
        assertTrue(new File(staging, "foreign").exists());
    }

    @Test public void pathsOutsideHomeAndMismatchedPublicationAreRejected() throws Exception {
        File receipt = new File(temp.getRoot(), "pending.json"), home = temp.newFolder(), outside = temp.newFolder();
        ConfigImportJournal journal = ConfigImportJournal.create(receipt, manifest());
        assertThrows(IOException.class, () -> journal.prepare(home, stage(outside), new File(home, "xuser-2")));
        assertFalse(json(receipt).has("creation"));
        File staging = stage(home), target = new File(home, "xuser-2");
        journal.prepare(home, staging, target);
        config(staging, 2);
        assertThrows(IOException.class, () -> journal.publish(home, staging, new File(home, "xuser-3")));
        assertTrue(staging.exists());
    }

    @Test public void recoveryDoesNotFollowPrefixLinksOrSymlinkedDestinationRoots() throws Exception {
        File receipt = new File(temp.getRoot(), "pending.json"), home = temp.newFolder(), outside = temp.newFolder();
        File save = new File(outside, "must-survive");
        Files.write(save.toPath(), new byte[]{5});
        File probe = new File(home, "link-probe");
        try { Files.createSymbolicLink(probe.toPath(), outside.toPath()); }
        catch (IOException | UnsupportedOperationException unsupported) { Assume.assumeNoException(unsupported); }
        Files.delete(probe.toPath());
        ConfigImportJournal journal = ConfigImportJournal.create(receipt, manifest());
        File staging = stage(home), target = new File(home, "xuser-2");
        journal.prepare(home, staging, target);
        Files.createSymbolicLink(new File(staging, "z-drive").toPath(), outside.toPath());
        assertFalse(ConfigImportJournal.load(receipt).recover(home));
        assertTrue(save.isFile());
        journal = ConfigImportJournal.load(receipt);
        journal.prepare(home, stage(home), target);
        Files.createSymbolicLink(target.toPath(), outside.toPath());
        assertThrows(IOException.class, () -> ConfigImportJournal.load(receipt).recover(home));
        assertTrue(save.isFile());
    }
}
