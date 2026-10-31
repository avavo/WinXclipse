package com.winlator.cmod.core;

import android.app.Application;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsProvider;
import android.view.View;
import android.widget.FrameLayout;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import com.winlator.cmod.DocumentBrowserFragment;
import com.winlator.cmod.FileManagerFragment;
import com.winlator.cmod.R;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.util.ReflectionHelpers;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class DocumentStorageTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final String AUTHORITY = "com.winxclipse.tests.documents";
    private final Uri tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root:opaque");
    private Context context;
    private TestProvider provider;
    private StorageAccess access;
    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication(); StorageClipboard.clear();
        provider = new TestProvider(); provider.directory = temp.newFolder("provider");
        ProviderInfo info = new ProviderInfo(); info.authority = AUTHORITY; info.exported = true; info.grantUriPermissions = true;
        info.readPermission = info.writePermission = "android.permission.MANAGE_DOCUMENTS";
        provider.attachInfo(context, info); ShadowContentResolver.registerProviderInternal(AUTHORITY, provider);
        access = new StorageAccess(context.getContentResolver());
    }
    @After public void closedCursors() { assertEquals(provider.cursors, provider.closed); }

    @Test public void opaqueIdsAndUnicodeAreBrowsedWithoutFilesystemConversion() throws Exception {
        provider.add("保存 é.dat", new byte[]{3, 7});
        assertNull(FileUtils.getFilePathFromUriUsingSAF(context, tree));
        TreeTransfer.Node root = access.tree(tree); List<TreeTransfer.Node> files = root.children();
        assertEquals(1, files.size()); assertEquals("保存 é.dat", files.get(0).listedInfo().name);
        StorageClipboard.Item ref = access.reference(files.get(0), files.get(0).listedInfo());
        assertFalse(ref.isFile()); assertTrue(DocumentsContract.getDocumentId(Uri.parse(ref.document)).contains("opaque/"));
        try (InputStream input = access.resolve(ref).read()) { assertEquals(3, input.read()); assertEquals(7, input.read()); }
    }

    @Test public void realResolverCopyWorksBothWaysAndRenameReturnsANewDocumentId() throws Exception {
        File source = temp.newFile("game é.bin"); Files.write(source.toPath(), new byte[]{1, 2, 3, 4});
        TreeTransfer transfer = new TreeTransfer(new AtomicBoolean(), null);
        transfer.transfer(StorageAccess.file(source), access.tree(tree), false, false);
        TreeTransfer.Node document = access.tree(tree).children().get(0); String before = document.key();
        TreeTransfer.Node renamed = document.rename("other.bin"); assertNotEquals(before, renamed.key());
        assertEquals("other.bin", renamed.info().name);
        File output = temp.newFolder("output");
        assertTrue(transfer.transfer(renamed, StorageAccess.file(output), true, false).sourceRemoved);
        assertArrayEquals(new byte[]{1, 2, 3, 4}, Files.readAllBytes(new File(output, "other.bin").toPath()));
        assertTrue(access.tree(tree).children().isEmpty()); assertTrue(source.isFile());
    }

    @Test public void missingAccessAndIncompleteListingsAreErrorsInsteadOfEmptyFolders() throws Exception {
        provider.loading = true; assertThrows(IOException.class, () -> access.tree(tree).children()); provider.loading = false;
        provider.nullCursor = true; assertThrows(IOException.class, () -> access.tree(tree).children()); provider.nullCursor = false;
        provider.deny = true; assertThrows(IOException.class, () -> access.tree(tree).info());
        assertThrows(IOException.class, () -> access.tree(tree).children());
    }

    @Test public void unknownSizeAndReadOnlyFlagsAreKeptInProviderMetadata() throws Exception {
        TestProvider.Doc source = provider.add("unknown", new byte[]{42}); provider.unknown = true; provider.readOnly = true;
        TreeTransfer.Info root = access.tree(tree).info(); assertFalse(root.create);
        TreeTransfer.Node node = access.tree(tree).children().get(0);
        assertEquals(-1, node.listedInfo().size); assertFalse(node.listedInfo().delete); assertFalse(node.listedInfo().rename);
        File output = temp.newFolder(); new TreeTransfer(new AtomicBoolean(), null).transfer(node, StorageAccess.file(output), false, false);
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(new File(output, source.file.getName()).toPath()));
    }

    @Test public void savedLocationsUseCachedLabelsAndRemovingOneKeepsFiles() throws Exception {
        provider.add("save", new byte[]{42}); int before = provider.queries;
        DocumentTreeStore.put(context, tree, "USB é"); DocumentTreeStore.put(context, tree, "Updated");
        assertEquals(1, DocumentTreeStore.list(context).size()); assertEquals("Updated", DocumentTreeStore.list(context).get(0).name);
        assertEquals(before, provider.queries); provider.deny = true;
        assertEquals(1, DocumentTreeStore.list(context).size()); DocumentTreeStore.remove(context, tree.toString());
        assertTrue(DocumentTreeStore.list(context).isEmpty()); assertTrue(new File(provider.directory, "save").isFile());
    }

    @Test public void browsingNavigationAndRotationQueryTheProviderOnlyInBackground() throws Exception {
        TestProvider.Doc folder = provider.folder("Subpasta"); provider.add(folder, "save.bin", new byte[]{42}); provider.add("root.bin", new byte[]{17});
        provider.forbidMain = true;
        org.robolectric.android.controller.ActivityController<Host> activity = Robolectric.buildActivity(Host.class).setup();
        Host host = activity.get();
        try {
            host.getSupportFragmentManager().beginTransaction().replace(R.id.FLFragmentContainer, DocumentBrowserFragment.create(tree, "USB")).commitNow();
            RecyclerView list = host.findViewById(R.id.RecyclerViewFiles); await(() -> list.getAdapter() != null && list.getAdapter().getItemCount() == 2);
            measure(host); RecyclerView.ViewHolder folderRow = list.findViewHolderForAdapterPosition(0); assertNotNull(folderRow);
            folderRow.itemView.performClick(); await(() -> list.getAdapter().getItemCount() == 1);
            assertTrue(((android.widget.TextView)host.findViewById(R.id.TVCurrentPath)).getText().toString().contains("Subpasta"));
            activity.recreate(); host = activity.get(); Host restored = host;
            RecyclerView restoredList = restored.findViewById(R.id.RecyclerViewFiles);
            await(() -> restoredList.getAdapter() != null && restoredList.getAdapter().getItemCount() == 1);
            assertTrue(((android.widget.TextView)restored.findViewById(R.id.TVCurrentPath)).getText().toString().contains("Subpasta"));
            ((DocumentBrowserFragment)restored.getSupportFragmentManager().findFragmentById(R.id.FLFragmentContainer)).handleBack();
            await(() -> restoredList.getAdapter().getItemCount() == 2);
            assertFalse(provider.mainQuery);
        } finally { activity.pause().stop().destroy(); }
    }

    @Test public void documentClipboardPastesThroughTheActualLocalFileManager() throws Exception {
        provider.add("save.dat", new byte[]{7, 8, 9}); TreeTransfer.Node source = access.tree(tree).children().get(0);
        StorageClipboard.set(Collections.singleton(access.reference(source, source.listedInfo())), false);
        File output = temp.newFolder("local");
        org.robolectric.android.controller.ActivityController<Host> activity = Robolectric.buildActivity(Host.class).setup();
        Host host = activity.get();
        try {
            FileManagerFragment fragment = new FileManagerFragment();
            host.getSupportFragmentManager().beginTransaction().replace(R.id.FLFragmentContainer, fragment).commitNow();
            ReflectionHelpers.callInstanceMethod(fragment, "openStorage", ReflectionHelpers.ClassParameter.from(File.class, output),
                    ReflectionHelpers.ClassParameter.from(String.class, "C:"), ReflectionHelpers.ClassParameter.from(int.class, R.drawable.ic_drive_storage));
            host.findViewById(R.id.fabPaste).performClick();
            await(() -> new File(output, "save.dat").isFile() && !new File(output, ".wx-transfer").exists()
                    && !((AtomicBoolean)ReflectionHelpers.getField(fragment, "transferActive")).get());
            assertArrayEquals(new byte[]{7, 8, 9}, Files.readAllBytes(new File(output, "save.dat").toPath()));
            assertFalse(StorageClipboard.isEmpty());
        } finally { activity.pause().stop().destroy(); }
    }

    @Test public void pickingUriOnlyStorageOpensTheBrowserAndSavesTheGrantedLocation() throws Exception {
        provider.add("save.dat", new byte[]{42}); provider.forbidMain = true;
        org.robolectric.android.controller.ActivityController<Host> activity = Robolectric.buildActivity(Host.class).setup(); Host host = activity.get();
        try {
            FileManagerFragment local = new FileManagerFragment();
            host.getSupportFragmentManager().beginTransaction().replace(R.id.FLFragmentContainer, local).commitNow();
            android.content.Intent result = new android.content.Intent().setData(tree).addFlags(
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            local.onActivityResult(1042, android.app.Activity.RESULT_OK, result);
            await(() -> host.getSupportFragmentManager().findFragmentById(R.id.FLFragmentContainer) instanceof DocumentBrowserFragment);
            RecyclerView list = host.findViewById(R.id.RecyclerViewFiles);
            await(() -> list.getAdapter() != null && list.getAdapter().getItemCount() == 1);
            assertEquals(1, DocumentTreeStore.list(context).size()); assertEquals(tree.toString(), DocumentTreeStore.list(context).get(0).uri);
            ((DocumentBrowserFragment)host.getSupportFragmentManager().findFragmentById(R.id.FLFragmentContainer)).handleBack();
            await(() -> host.getSupportFragmentManager().findFragmentById(R.id.FLFragmentContainer) instanceof FileManagerFragment);
            assertTrue(new File(provider.directory, "save.dat").isFile()); assertFalse(provider.mainQuery);
        } finally { activity.pause().stop().destroy(); }
    }

    private void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < end) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); if (condition.getAsBoolean()) return; Thread.sleep(10); }
        fail("Background operation did not finish");
    }
    private void measure(Host host) {
        View root = host.findViewById(R.id.FLFragmentContainer);
        root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 360, 640); org.robolectric.shadows.ShadowLooper.idleMainLooper();
    }
    public static class Host extends AppCompatActivity {
        @Override protected void onCreate(Bundle state) {
            setTheme(R.style.AppTheme); super.onCreate(state); FrameLayout view = new FrameLayout(this); view.setId(R.id.FLFragmentContainer); setContentView(view);
        }
    }

    public static class TestProvider extends DocumentsProvider {
        File directory;
        private final Map<String, Doc> docs = new LinkedHashMap<>();
        private Doc root;
        volatile boolean deny, loading, nullCursor, unknown, readOnly, forbidMain, mainQuery;
        int queries, cursors, closed;
        static class Doc { String id; File file; Doc parent; Doc(String id, File file, Doc parent) { this.id = id; this.file = file; this.parent = parent; } }
        @Override public boolean onCreate() { root = new Doc("root:opaque", directory, null); docs.put(root.id, root); return true; }
        Doc add(String name, byte[] data) throws Exception { return add(root, name, data); }
        Doc add(Doc parent, String name, byte[] data) throws Exception {
            File file = new File(parent.file, name); Files.write(file.toPath(), data); Doc doc = new Doc("opaque/" + UUID.randomUUID(), file, parent); docs.put(doc.id, doc); return doc;
        }
        Doc folder(String name) throws Exception { File file = new File(directory, name); Files.createDirectory(file.toPath()); Doc doc = new Doc("opaque/" + UUID.randomUUID(), file, root); docs.put(doc.id, doc); return doc; }
        private Doc get(String id) throws FileNotFoundException { if (deny) throw new SecurityException("Grant revoked"); Doc doc = docs.get(id); if (doc == null) throw new FileNotFoundException(id); return doc; }
        private MatrixCursor cursor(String[] requested) {
            queries++; if (Looper.myLooper() == Looper.getMainLooper()) { mainQuery = true; if (forbidMain) throw new AssertionError("Provider queried on main thread"); }
            String[] projection = requested != null ? requested : new String[]{Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS};
            cursors++; return new MatrixCursor(projection) { @Override public void close() { if (!isClosed()) closed++; super.close(); } };
        }
        private void row(MatrixCursor cursor, Doc doc) {
            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String column : cursor.getColumnNames()) {
                Object value = null;
                if (column.equals(Document.COLUMN_DOCUMENT_ID)) value = doc.id;
                else if (column.equals(Document.COLUMN_DISPLAY_NAME)) value = doc.parent == null ? "USB" : doc.file.getName();
                else if (column.equals(Document.COLUMN_MIME_TYPE)) value = doc.file.isDirectory() ? Document.MIME_TYPE_DIR : "application/octet-stream";
                else if (column.equals(Document.COLUMN_SIZE)) value = unknown ? null : doc.file.length();
                else if (column.equals(Document.COLUMN_LAST_MODIFIED)) value = doc.file.lastModified();
                else if (column.equals(Document.COLUMN_FLAGS)) value = readOnly ? 0 : Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_RENAME | Document.FLAG_SUPPORTS_DELETE | (doc.file.isDirectory() ? Document.FLAG_DIR_SUPPORTS_CREATE : 0);
                row.add(value);
            }
        }
        @Override public Cursor queryRoots(String[] projection) { return new MatrixCursor(new String[]{"root_id"}); }
        @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException { Doc doc = get(id); MatrixCursor cursor = cursor(projection); row(cursor, doc); return cursor; }
        @Override public Cursor queryChildDocuments(String id, String[] projection, String sort) throws FileNotFoundException {
            Doc parent = get(id); if (nullCursor) return null;
            MatrixCursor cursor = cursor(projection); Bundle extras = new Bundle(); extras.putBoolean(DocumentsContract.EXTRA_LOADING, loading); cursor.setExtras(extras);
            for (Doc doc : new ArrayList<>(docs.values())) if (doc.parent == parent) row(cursor, doc); return cursor;
        }
        @Override public boolean isChildDocument(String parent, String child) {
            try {
                Doc expected = get(parent); for (Doc doc = get(child).parent; doc != null; doc = doc.parent) if (doc == expected) return true; return false;
            } catch (FileNotFoundException missing) { return false; }
        }
        @Override public String createDocument(String id, String mime, String name) throws FileNotFoundException {
            if (readOnly) throw new FileNotFoundException("Read only"); Doc parent = get(id);
            try {
                File file = new File(parent.file, name); if (mime.equals(Document.MIME_TYPE_DIR)) Files.createDirectory(file.toPath()); else Files.createFile(file.toPath());
                Doc doc = new Doc("opaque/" + UUID.randomUUID(), file, parent); docs.put(doc.id, doc); return doc.id;
            } catch (IOException error) { throw new FileNotFoundException(error.toString()); }
        }
        @Override public String renameDocument(String id, String name) throws FileNotFoundException {
            if (readOnly) throw new FileNotFoundException("Read only"); Doc doc = get(id); File previous = doc.file;
            File next = new File(doc.parent.file, name);
            try { Files.move(previous.toPath(), next.toPath()); } catch (IOException error) { throw new FileNotFoundException(error.toString()); }
            for (Doc child : docs.values()) if (child.file.toPath().startsWith(previous.toPath())) child.file = next.toPath().resolve(previous.toPath().relativize(child.file.toPath())).toFile();
            docs.remove(id); doc.id = "opaque/" + UUID.randomUUID(); docs.put(doc.id, doc); return doc.id;
        }
        @Override public void deleteDocument(String id) throws FileNotFoundException {
            Doc doc = get(id); if (readOnly || doc.parent == null) throw new FileNotFoundException("Read only");
            if (!FileUtils.delete(doc.file)) throw new FileNotFoundException("Delete failed");
            docs.values().removeIf(child -> child.file.toPath().startsWith(doc.file.toPath()));
        }
        @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
            if (signal != null) signal.throwIfCanceled(); Doc doc = get(id);
            if (readOnly && !mode.equals("r")) throw new FileNotFoundException("Read only");
            return ParcelFileDescriptor.open(doc.file, ParcelFileDescriptor.parseMode(mode));
        }
    }
}
