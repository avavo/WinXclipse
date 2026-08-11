package com.winlator.cmod.core;

import android.content.Context;
import android.content.pm.ProviderInfo;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Point;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public class WinlatorFilesProvider extends DocumentsProvider {
    private static final String ALL_MIME_TYPES = "*/*";
    private boolean enabled;
    private File BASE_DIR;

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        super.attachInfo(context, info);
        BASE_DIR = context.getDataDir();
        enabled = PreferenceManager.getDefaultSharedPreferences(context).getBoolean("enable_file_provider", true);
    }

    @Override
    public String moveDocument(String sourceDocumentId, String sourceParentDocumentId, String targetParentDocumentId) throws FileNotFoundException {
        File source = new File(sourceDocumentId);
        File sourceParent = new File(sourceParentDocumentId);
        File targetParent = new File(targetParentDocumentId);
        File target = null;

        if (!sourceParent.exists())
            throw new FileNotFoundException("Source parent is not found: " + sourceParentDocumentId);

        if (!source.exists())
            throw new FileNotFoundException("Source file not found: " + sourceDocumentId);

        if (!Objects.equals(source.getParentFile(), sourceParent))
            throw new FileNotFoundException("Source has wrong parent: " + sourceDocumentId + " " + sourceParentDocumentId);

        if (!targetParent.exists())
            throw new FileNotFoundException("Target file not found: " + targetParentDocumentId);

        if (!targetParent.isDirectory())
            throw new FileNotFoundException("Target parent is not directory: " + targetParentDocumentId);

        target = new File(targetParentDocumentId, source.getName());
        if (target.exists())
            throw new FileNotFoundException("Target already exist");

        boolean ret = source.renameTo(target);
        if (!ret)
            throw new FileNotFoundException("Failed to move: " + sourceDocumentId);

        return target.getAbsolutePath();
    }

    @Override
    public void removeDocument(String documentId, String parentDocumentId) throws FileNotFoundException {
        File parent = new File(parentDocumentId);
        File target = new File(documentId);
        boolean ret;

        if (!parent.exists())
            throw new FileNotFoundException("Parent is not exist: " + parentDocumentId);

        if (!parent.isDirectory())
            throw new FileNotFoundException("Parent is not directory: " + parentDocumentId);

        if (!target.exists())
            throw new FileNotFoundException("File is not found: " + documentId);

        ret = target.delete();
        if (!ret)
            throw new FileNotFoundException("Failed to delete file: " + documentId);
    }

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[]{
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_AVAILABLE_BYTES
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[]{
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE
    };

    @Override
    public Cursor queryRoots(String[] projection) {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_ROOT_PROJECTION);
        final String applicationName = getContext().getString(R.string.app_name);

        final MatrixCursor.RowBuilder row = result.newRow();
        row.add(Root.COLUMN_ROOT_ID, getDocIdForFile(BASE_DIR));
        row.add(Root.COLUMN_DOCUMENT_ID, getDocIdForFile(BASE_DIR));
        row.add(Root.COLUMN_SUMMARY, null);
        row.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_SEARCH | Root.FLAG_SUPPORTS_IS_CHILD);
        row.add(Root.COLUMN_TITLE, applicationName);
        row.add(Root.COLUMN_MIME_TYPES, ALL_MIME_TYPES);
        row.add(Root.COLUMN_AVAILABLE_BYTES, BASE_DIR.getFreeSpace());
        row.add(Root.COLUMN_ICON, R.mipmap.ic_launcher);
        return result;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        includeFile(result, documentId, null);
        return result;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
        final File parent = getFileForDocId(parentDocumentId);
        final File[] children = parent.listFiles();
        final MatrixCursor result = new MatrixCursor(
                projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION,
                children != null ? children.length : 0);
        if (children == null) return result;

        final boolean parentWritable = parent.canWrite();
        for (File file : children) {
            includeFile(result, null, file, parentWritable);
        }
        return result;
    }

    @Override
    public ParcelFileDescriptor openDocument(final String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        final File file = getFileForDocId(documentId);
        final int accessMode = ParcelFileDescriptor.parseMode(mode);
        return ParcelFileDescriptor.open(file, accessMode);
    }

    @Override
    public AssetFileDescriptor openDocumentThumbnail(String documentId, Point sizeHint, CancellationSignal signal) throws FileNotFoundException {
        final File file = getFileForDocId(documentId);
        final ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        return new AssetFileDescriptor(pfd, 0, file.length());
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName) throws FileNotFoundException {
        File newFile = new File(parentDocumentId, displayName);
        int noConflictId = 2;
        while (newFile.exists()) {
            newFile = new File(parentDocumentId, displayName + " (" + noConflictId++ + ")");
        }
        try {
            boolean succeeded;
            if (Document.MIME_TYPE_DIR.equals(mimeType)) {
                succeeded = newFile.mkdir();
            } else {
                succeeded = newFile.createNewFile();
            }
            if (!succeeded) {
                throw new FileNotFoundException("Failed to create document with id " + newFile.getPath());
            }
        } catch (IOException e) {
            throw new FileNotFoundException("Failed to create document with id " + newFile.getPath());
        }
        return newFile.getPath();
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        File file = getFileForDocId(documentId);
        if (!file.delete()) {
            throw new FileNotFoundException("Failed to delete document with id " + documentId);
        }
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        File file = getFileForDocId(documentId);
        return getMimeType(file);
    }

    @Override
    public Cursor querySearchDocuments(String rootId, String query, String[] projection) throws FileNotFoundException {
        return querySearchDocumentsInternal(rootId, query, projection, null);
    }

    private Cursor querySearchDocumentsInternal(String rootId, String query, String[] projection,
                                                CancellationSignal signal) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        final File parent = getFileForDocId(rootId);
        final String normalizedQuery = query != null ? query.toLowerCase(Locale.ROOT) : "";
        final String basePath;
        try {
            basePath = BASE_DIR.getCanonicalPath();
        }
        catch (IOException e) {
            throw new FileNotFoundException("Unable to resolve provider root");
        }

        final LinkedList<File> pending = new LinkedList<>();
        final Set<String> visited = new HashSet<>();
        pending.add(parent);

        final int MAX_SEARCH_RESULTS = 50;
        while (!pending.isEmpty() && result.getCount() < MAX_SEARCH_RESULTS) {
            if (signal != null) signal.throwIfCanceled();
            final File file = pending.removeFirst();
            final String canonicalPath;
            try {
                canonicalPath = file.getCanonicalPath();
            }
            catch (IOException e) {
                continue;
            }

            if ((!canonicalPath.equals(basePath)
                    && !canonicalPath.startsWith(basePath + File.separator))
                    || !visited.add(canonicalPath)) {
                continue;
            }

            if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children != null) {
                    for (File child : children) pending.addLast(child);
                }
            }
            else if (file.getName().toLowerCase(Locale.ROOT).contains(normalizedQuery)) {
                includeFile(result, null, file);
            }
        }

        return result;
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        try {
            String parentPath = new File(parentDocumentId).getCanonicalPath();
            String childPath = new File(documentId).getCanonicalPath();
            return !childPath.equals(parentPath)
                    && childPath.startsWith(parentPath + File.separator);
        }
        catch (IOException e) {
            return false;
        }
    }

    private static String getDocIdForFile(File file) {
        return file.getAbsolutePath();
    }

    private static File getFileForDocId(String docId) throws FileNotFoundException {
        final File f = new File(docId);
        if (!f.exists()) throw new FileNotFoundException(f.getAbsolutePath() + " not found");
        return f;
    }

    private static String getMimeType(File file) {
        if (file.isDirectory()) {
            return Document.MIME_TYPE_DIR;
        }
        return getMimeTypeFromName(file.getName());
    }

    private static String getMimeTypeFromName(String name) {
        final int lastDot = name.lastIndexOf('.');
        if (lastDot >= 0) {
            final String extension = name.substring(lastDot + 1).toLowerCase(Locale.ROOT);
            final String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        File oldFile = new File(documentId);

        if (!oldFile.exists()) {
            throw new FileNotFoundException("File not found: " + documentId);
        }

        File parentDir = oldFile.getParentFile();
        File newFile = new File(parentDir, displayName);

        if (oldFile.renameTo(newFile)) {
            return newFile.getAbsolutePath();
        } else {
            throw new FileNotFoundException("Failed to rename document with id " + documentId);
        }
    }

    private void includeFile(MatrixCursor result, String docId, File file) throws FileNotFoundException {
        includeFile(result, docId, file, null);
    }

    private void includeFile(MatrixCursor result, String docId, File file,
                             Boolean knownParentWritable) throws FileNotFoundException {
        if (!enabled)
            throw new FileNotFoundException();

        if (docId == null) {
            docId = getDocIdForFile(file);
        } else {
            file = getFileForDocId(docId);
        }

        boolean directory;
        long size;
        long lastModified;
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    file.toPath(), BasicFileAttributes.class);
            directory = attributes.isDirectory();
            size = directory ? 0 : attributes.size();
            lastModified = attributes.lastModifiedTime().toMillis();
        }
        catch (IOException e) {
            directory = file.isDirectory();
            size = directory ? 0 : file.length();
            lastModified = file.lastModified();
        }
        final boolean writable = file.canWrite();
        final File parent = file.getParentFile();
        final boolean parentWritable = knownParentWritable != null
                ? knownParentWritable
                : parent != null && parent.canWrite();

        int flags = 0;
        if (directory) {
            if (writable) flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
        }
        else if (writable) {
            flags |= Document.FLAG_SUPPORTS_WRITE;
        }
        if (parentWritable) flags |= Document.FLAG_SUPPORTS_DELETE;

        // Add support for renaming files and directories
        if (writable) {
            flags |= Document.FLAG_SUPPORTS_RENAME;
        }

        final String displayName = file.getName();
        final String mimeType = directory ? Document.MIME_TYPE_DIR : getMimeTypeFromName(file.getName());
        if (mimeType.startsWith("image/")) flags |= Document.FLAG_SUPPORTS_THUMBNAIL;

        final MatrixCursor.RowBuilder row = result.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, docId);
        row.add(Document.COLUMN_DISPLAY_NAME, displayName);
        row.add(Document.COLUMN_SIZE, size);
        row.add(Document.COLUMN_MIME_TYPE, mimeType);
        row.add(Document.COLUMN_LAST_MODIFIED, lastModified);
        row.add(Document.COLUMN_FLAGS, flags);
        if (result.getColumnIndex(Document.COLUMN_ICON) >= 0) {
            row.add(Document.COLUMN_ICON, R.mipmap.ic_launcher);
        }
    }

}
