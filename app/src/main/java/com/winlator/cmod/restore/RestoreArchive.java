package com.winlator.cmod.restore;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** A selected backup is borrowed; only a copy made by this object belongs to cleanup. */
final class RestoreArchive implements Closeable {
    final File file;
    private final boolean temporary;

    private RestoreArchive(File file, boolean temporary) {
        this.file = file;
        this.temporary = temporary;
    }

    static RestoreArchive borrow(File original) throws IOException {
        if (!original.isFile() || !original.canRead()) throw new IOException("Backup file is unavailable");
        return new RestoreArchive(original, false);
    }

    /** Takes ownership of the stream, but never of its original document/file. */
    static RestoreArchive copy(InputStream input, File cache) throws IOException {
        if (input == null) throw new IOException("Could not open backup document");
        File temporary = null;
        try (InputStream source = input) {
            temporary = File.createTempFile("restore_", ".tmp", cache);
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[256 * 1024];
                int count;
                while ((count = source.read(buffer)) != -1) {
                    if (count == 0) {
                        int single = source.read();
                        if (single == -1) break;
                        output.write(single);
                    } else output.write(buffer, 0, count);
                }
                output.getFD().sync();
            }
            // Closing the provider's stream can also fail, so publish only after that close.
        } catch (IOException | RuntimeException failure) {
            if (temporary != null) temporary.delete();
            throw failure;
        }
        return new RestoreArchive(temporary, true);
    }

    @Override public void close() {
        if (temporary) file.delete(); // A failed cache cleanup must not erase/replace the source.
    }
}
