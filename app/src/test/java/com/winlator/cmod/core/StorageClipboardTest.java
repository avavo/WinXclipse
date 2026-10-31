package com.winlator.cmod.core;
import org.junit.*;
import java.io.File;
import java.util.*;
import static org.junit.Assert.*;

public class StorageClipboardTest {
    @Before public void reset() { StorageClipboard.clear(); }
    @Test public void snapshotsAreImmutableAndShareLocalAndDocumentReferences() {
        StorageClipboard.Item document = StorageClipboard.Item.document("content://docs/tree/root", "content://docs/tree/root/document/opaque-id", "Save é.dat");
        List<StorageClipboard.Item> items = new ArrayList<>(Arrays.asList(StorageClipboard.Item.file(new File("local")), document));
        StorageClipboard.set(items, true); StorageClipboard.Snapshot snapshot = StorageClipboard.get(); items.clear();
        assertEquals(2, snapshot.items.size()); assertTrue(snapshot.hasDocuments()); assertTrue(snapshot.move);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.items.clear());
        assertThrows(IllegalStateException.class, snapshot::files);
    }
    @Test public void completedOldMoveCannotClearANewerClipboard() {
        StorageClipboard.files(Collections.singleton(new File("old")), true); StorageClipboard.Snapshot old = StorageClipboard.get();
        StorageClipboard.files(Collections.singleton(new File("new")), false);
        StorageClipboard.removeCompleted(old, old.items);
        assertEquals("new", StorageClipboard.get().items.get(0).name); assertFalse(StorageClipboard.get().move);
    }
    @Test public void failedMovesRemainAndCopiesStayAvailable() {
        StorageClipboard.files(Arrays.asList(new File("a"), new File("b")), true); StorageClipboard.Snapshot move = StorageClipboard.get();
        StorageClipboard.removeCompleted(move, Collections.singleton(move.items.get(0)));
        assertEquals(1, StorageClipboard.get().items.size()); assertEquals("b", StorageClipboard.get().items.get(0).name);
        StorageClipboard.files(Collections.singleton(new File("copy")), false); StorageClipboard.Snapshot copy = StorageClipboard.get();
        StorageClipboard.removeCompleted(copy, copy.items); assertFalse(StorageClipboard.isEmpty());
    }
}
