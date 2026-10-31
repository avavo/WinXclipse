package com.winlator.cmod.core;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Filesystem and DocumentsProvider adapters. Document IDs are opaque, never filesystem paths. */
public final class StorageAccess {
    private static final String[] COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS};
    private final ContentResolver resolver;
    private final android.os.CancellationSignal cancellation;
    public StorageAccess(ContentResolver resolver) { this(resolver, null); }
    public StorageAccess(ContentResolver resolver, android.os.CancellationSignal cancellation) { this.resolver = resolver; this.cancellation = cancellation; }
    public TreeTransfer.Node resolve(StorageClipboard.Item ref) {
        return ref.isFile() ? file(new File(ref.path)) : new ProviderNode(Uri.parse(ref.tree), Uri.parse(ref.document), null);
    }
    public static TreeTransfer.Node file(File file) { return new FileNode(file); }
    public TreeTransfer.Node tree(Uri tree) {
        return new ProviderNode(tree, DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)), null);
    }
    public TreeTransfer.Node document(Uri tree, Uri document) { return new ProviderNode(tree, document, null); }
    public TreeTransfer.Node cancellable(TreeTransfer.Node node) {
        if (!(node instanceof ProviderNode)) return node;
        ProviderNode provider = (ProviderNode)node;
        return new ProviderNode(provider.tree, provider.uri, null);
    }
    public StorageClipboard.Item reference(TreeTransfer.Node node, TreeTransfer.Info info) {
        if (node instanceof FileNode) return StorageClipboard.Item.file(((FileNode)node).file);
        ProviderNode provider = (ProviderNode)node;
        return StorageClipboard.Item.document(provider.tree.toString(), provider.uri.toString(), info.name);
    }
    private TreeTransfer.Info metadata(Cursor cursor) throws IOException {
        String name = cursor.getString(1), mime = cursor.getString(2);
        if (name == null || mime == null) throw new IOException("Provider omitted required document metadata");
        int flags = cursor.isNull(5) ? 0 : cursor.getInt(5);
        boolean folder = Document.MIME_TYPE_DIR.equals(mime);
        return new TreeTransfer.Info(name, mime, folder, cursor.isNull(3) ? -1 : cursor.getLong(3),
                cursor.isNull(4) ? 0 : cursor.getLong(4), (flags & Document.FLAG_SUPPORTS_RENAME) != 0,
                (flags & Document.FLAG_SUPPORTS_DELETE) != 0, folder && (flags & Document.FLAG_DIR_SUPPORTS_CREATE) != 0);
    }
    private final class ProviderNode implements TreeTransfer.Node {
        final Uri tree, uri;
        final TreeTransfer.Info listing;
        private String knownName;
        ProviderNode(Uri tree, Uri uri, TreeTransfer.Info listing) { this.tree = tree; this.uri = uri; this.listing = listing; knownName = listing == null ? null : listing.name; }
        @Override public String key() { return "doc:" + uri.getAuthority() + ":" + DocumentsContract.getDocumentId(uri); }
        @Override public TreeTransfer.Info listedInfo() throws IOException { return listing != null ? listing : info(); }
        @Override public String recoveryName() throws IOException { return knownName != null ? knownName : info().name; }
        @Override public TreeTransfer.Info info() throws IOException {
            try (Cursor cursor = resolver.query(uri, COLUMNS, (android.os.Bundle)null, cancellation)) {
                if (cursor == null || !cursor.moveToFirst()) throw new FileNotFoundException("Document is unavailable");
                TreeTransfer.Info info = metadata(cursor); knownName = info.name; return info;
            } catch (RuntimeException error) { throw new IOException("Cannot read document metadata", error); }
        }
        @Override public List<TreeTransfer.Node> children() throws IOException {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(uri));
            List<TreeTransfer.Node> result = new ArrayList<>();
            try (Cursor cursor = resolver.query(children, COLUMNS, (android.os.Bundle)null, cancellation)) {
                if (cursor == null) throw new IOException("Provider returned no directory listing");
                BundleCheck.loading(cursor);
                while (cursor.moveToNext()) {
                    if (cancellation != null) cancellation.throwIfCanceled();
                    if (result.size() >= 100000) throw new IOException("Directory is too large");
                    String id = cursor.getString(0);
                    if (id == null) throw new IOException("Document has no ID");
                    result.add(new ProviderNode(tree, DocumentsContract.buildDocumentUriUsingTree(tree, id), metadata(cursor)));
                }
                return result;
            } catch (RuntimeException error) { throw new IOException("Cannot list document directory", error); }
        }
        @Override public TreeTransfer.Node create(String name, TreeTransfer.Info source) throws IOException {
            TreeTransfer.validName(name);
            try {
                Uri created = DocumentsContract.createDocument(resolver, uri,
                        source.directory ? Document.MIME_TYPE_DIR : source.mime, name);
                if (created == null) throw new IOException("Provider refused document creation");
                ProviderNode node = new ProviderNode(tree, created, null); node.knownName = name; return node;
            } catch (RuntimeException error) { throw new IOException("Cannot create document", error); }
        }
        @Override public TreeTransfer.Node rename(String name) throws IOException {
            TreeTransfer.validName(name);
            try {
                Uri renamed = DocumentsContract.renameDocument(resolver, uri, name);
                if (renamed == null) throw new IOException("Provider refused rename");
                ProviderNode node = new ProviderNode(tree, renamed, null); node.knownName = name; return node;
            } catch (RuntimeException error) { throw new IOException("Cannot rename document", error); }
        }
        @Override public void delete() throws IOException {
            try { if (!DocumentsContract.deleteDocument(resolver, uri)) throw new IOException("Provider refused deletion"); }
            catch (RuntimeException error) { throw new IOException("Cannot delete document", error); }
        }
        @Override public InputStream read() throws IOException {
            try {
                android.content.res.AssetFileDescriptor descriptor = resolver.openAssetFileDescriptor(uri, "r", cancellation);
                if (descriptor == null) throw new IOException("Provider returned no input stream");
                try { return descriptor.createInputStream(); }
                catch (IOException | RuntimeException error) { descriptor.close(); throw error; }
            } catch (RuntimeException error) { throw new IOException("Cannot open document", error); }
        }
        @Override public OutputStream write() throws IOException {
            try {
                android.content.res.AssetFileDescriptor descriptor = resolver.openAssetFileDescriptor(uri, "wt", cancellation);
                if (descriptor == null) throw new IOException("Provider returned no output stream");
                try { return descriptor.createOutputStream(); }
                catch (IOException | RuntimeException error) { descriptor.close(); throw error; }
            } catch (RuntimeException error) { throw new IOException("Cannot write document", error); }
        }
    }
    private static final class BundleCheck {
        static void loading(Cursor cursor) throws IOException {
            android.os.Bundle extras = cursor.getExtras();
            if (extras != null && (extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)
                    || extras.getString(DocumentsContract.EXTRA_ERROR) != null))
                throw new IOException("Provider directory listing is not complete");
        }
    }
    private static final class FileNode implements TreeTransfer.Node {
        final File file;
        FileNode(File file) { this.file = file; }
        @Override public String key() throws IOException { return "file:" + file.getCanonicalPath(); }
        @Override public TreeTransfer.Info info() throws IOException {
            BasicFileAttributes attrs = Files.readAttributes(file.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink() || !attrs.isDirectory() && !attrs.isRegularFile()) throw new IOException("Unsupported file type");
            return new TreeTransfer.Info(file.getName(), attrs.isDirectory() ? Document.MIME_TYPE_DIR : "application/octet-stream",
                    attrs.isDirectory(), attrs.size(), attrs.lastModifiedTime().toMillis(), file.getParentFile() != null && file.getParentFile().canWrite(),
                    file.getParentFile() != null && file.getParentFile().canWrite(), attrs.isDirectory() && file.canWrite());
        }
        @Override public List<TreeTransfer.Node> children() throws IOException {
            List<TreeTransfer.Node> result = new ArrayList<>();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(file.toPath())) {
                for (Path entry : entries) result.add(new FileNode(entry.toFile()));
            }
            return result;
        }
        @Override public TreeTransfer.Node create(String name, TreeTransfer.Info source) throws IOException {
            TreeTransfer.validName(name); Path target = file.toPath().resolve(name);
            if (source.directory) Files.createDirectory(target); else Files.createFile(target);
            return new FileNode(target.toFile());
        }
        @Override public TreeTransfer.Node rename(String name) throws IOException {
            TreeTransfer.validName(name); Path target = file.toPath().resolveSibling(name);
            Files.move(file.toPath(), target); return new FileNode(target.toFile());
        }
        @Override public void delete() throws IOException {
            Files.walkFileTree(file.toPath(), new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException { Files.delete(path); return FileVisitResult.CONTINUE; }
                @Override public FileVisitResult postVisitDirectory(Path path, IOException error) throws IOException {
                    if (error != null) throw error; Files.delete(path); return FileVisitResult.CONTINUE;
                }
            });
        }
        @Override public InputStream read() throws IOException { return Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS); }
        @Override public OutputStream write() throws IOException { return Files.newOutputStream(file.toPath(), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS); }
    }
}
