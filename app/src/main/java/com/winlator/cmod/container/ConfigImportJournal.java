package com.winlator.cmod.container;

import com.winlator.cmod.core.FileUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;

/** Durable identity/checkpoint for one import; paths are private direct children of home. */
public final class ConfigImportJournal {
    private static final String FORMAT = "winxclipse-config-import";
    static final String MARKER = ".winxclipse-import";
    private final File receipt;
    private JSONObject record;

    private ConfigImportJournal(File receipt, JSONObject record) {
        this.receipt = receipt;
        this.record = record;
    }

    static ConfigImportJournal create(File receipt, JSONObject manifest) throws IOException {
        try {
            JSONObject record = new JSONObject().put("format", FORMAT).put("version", 1)
                    .put("importId", UUID.randomUUID().toString())
                    .put("manifest", new JSONObject(manifest.toString()));
            ConfigImportJournal journal = new ConfigImportJournal(receipt, record);
            journal.save(record);
            return journal;
        } catch (JSONException invalid) { throw new IOException("Invalid pending configuration", invalid); }
    }

    static ConfigImportJournal load(File receipt) throws IOException {
        try {
            if (!Files.isRegularFile(receipt.toPath(), LinkOption.NOFOLLOW_LINKS)
                    || receipt.length() > 8L * 1024 * 1024) throw new IOException("Invalid import receipt");
            JSONObject record = new JSONObject(new String(Files.readAllBytes(receipt.toPath()), StandardCharsets.UTF_8));
            // Receipts from earlier 0.9.6 revisions contained only the manifest.
            if (!FORMAT.equals(record.optString("format"))) return create(receipt, record);
            if (record.getInt("version") != 1 || !validId(record.getString("importId")))
                throw new IOException("Unsupported import receipt");
            record.getJSONObject("manifest");
            ConfigImportJournal journal = new ConfigImportJournal(receipt, record);
            journal.checkpoint(); // Validate names before any filesystem recovery.
            return journal;
        } catch (JSONException | RuntimeException invalid) { throw new IOException("Invalid import receipt", invalid); }
    }

    JSONObject manifest() throws IOException {
        try { return new JSONObject(record.getJSONObject("manifest").toString()); }
        catch (JSONException invalid) { throw new IOException("Invalid pending configuration", invalid); }
    }

    /** Persist the intended paths BEFORE mkdir/extraction. Never adopts an existing staging tree. */
    synchronized void prepare(File home, File staging, File destination) throws IOException {
        try {
            if (record.optBoolean("complete") || record.has("creation")) throw new IOException("Import needs recovery before creation");
            requireChild(home, staging);
            requireChild(home, destination);
            JSONObject next = new JSONObject(record.toString());
            next.put("creation", new JSONObject().put("staging", staging.getName()).put("target", destination.getName()));
            validateNames(next.getJSONObject("creation"));
            if (exists(staging) || exists(destination)) throw new IOException("Container destination already exists");
            save(next);
            if (!staging.mkdir()) throw new IOException("Could not create import staging directory");
            // The UUID is in the filename: createFile is one metadata operation, so
            // there is no partial marker write to mistake for somebody else's tree.
            Files.createFile(marker(staging).toPath());
        } catch (JSONException invalid) { throw new IOException("Invalid import checkpoint", invalid); }
    }

    /** Same-filesystem, no-replacement publication after the complete prefix/config were saved. */
    synchronized void publish(File home, File staging, File destination) throws IOException {
        JSONObject checkpoint = checkpoint();
        if (checkpoint == null || !staging.getName().equals(checkpoint.optString("staging"))
                || !destination.getName().equals(checkpoint.optString("target"))) throw new IOException("Import checkpoint changed");
        requireChild(home, staging);
        requireChild(home, destination);
        if (!owned(staging)) throw new IOException("Import staging identity changed");
        validateConfig(staging, destination.getName());
        Files.move(staging.toPath(), destination.toPath()); // Deliberately no REPLACE_EXISTING.
    }

    /** Returns true for an already published import, without resolving/downloading/creating again. */
    synchronized boolean recover(File home) throws IOException {
        if (record.optBoolean("complete")) return true;
        JSONObject checkpoint = checkpoint();
        if (checkpoint == null) return false;
        File destination = new File(home, checkpoint.optString("target"));
        File staging = new File(home, checkpoint.optString("staging"));
        requireChild(home, destination);
        requireChild(home, staging);
        if (owned(destination)) {
            // A broken published config must report a recoverable error, never create a duplicate.
            validateConfig(destination, destination.getName());
            return true;
        }
        if (exists(staging)) {
            if (!Files.isDirectory(staging.toPath(), LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Import staging is not a private directory");
            if (!owned(staging)) {
                // A kill between mkdir and marker write can leave only an empty directory.
                try (java.nio.file.DirectoryStream<Path> children = Files.newDirectoryStream(staging.toPath())) {
                    if (children.iterator().hasNext()) throw new IOException("Unidentified import staging retained");
                }
            }
            deleteTree(staging.toPath());
        }
        try {
            JSONObject next = new JSONObject(record.toString());
            next.remove("creation");
            save(next);
        } catch (JSONException invalid) { throw new IOException("Invalid import checkpoint", invalid); }
        // An unrelated destination (e.g. a concurrent creation) is never adopted or removed.
        return false;
    }

    synchronized void finish() throws IOException {
        // Normal completion only removes the receipt; avoid rewriting the full
        // manifest/fsync on the Activity's creation callback.
        if (receipt.delete() || !receipt.exists()) return;
        if (!record.optBoolean("complete")) {
            try { save(new JSONObject(record.toString()).put("complete", true)); }
            catch (JSONException invalid) { throw new IOException("Could not finish import receipt", invalid); }
        }
        // A durable complete record also prevents replay when deleting the receipt fails.
        receipt.delete();
    }

    private JSONObject checkpoint() throws IOException {
        if (!record.has("creation")) return null;
        JSONObject checkpoint = record.optJSONObject("creation");
        validateNames(checkpoint);
        return checkpoint;
    }

    private static void validateNames(JSONObject checkpoint) throws IOException {
        if (checkpoint == null || !checkpoint.optString("staging").matches("\\.container-create-[0-9a-f-]{36}")
                || !checkpoint.optString("target").matches("xuser-[1-9][0-9]{0,9}")) throw new IOException("Invalid import paths");
        String staging = checkpoint.optString("staging");
        if (!validId(staging.substring(".container-create-".length()))) throw new IOException("Invalid import staging ID");
        try { Integer.parseInt(checkpoint.optString("target").substring("xuser-".length())); }
        catch (NumberFormatException invalid) { throw new IOException("Invalid container ID", invalid); }
    }

    private boolean owned(File directory) throws IOException {
        if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) return false;
        File marker = marker(directory);
        return Files.isRegularFile(marker.toPath(), LinkOption.NOFOLLOW_LINKS) && marker.length() == 0;
    }

    private File marker(File directory) { return new File(directory, MARKER + "-" + record.optString("importId")); }

    private static void validateConfig(File directory, String targetName) throws IOException {
        File config = new File(directory, ".container");
        if (!Files.isRegularFile(config.toPath(), LinkOption.NOFOLLOW_LINKS) || config.length() > 8L * 1024 * 1024)
            throw new IOException("Published import configuration is unavailable");
        try {
            JSONObject data = new JSONObject(new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8));
            if (!targetName.equals("xuser-" + data.getInt("id")) || data.optString("wineVersion").trim().isEmpty())
                throw new IOException("Published import configuration does not match its container");
        } catch (JSONException invalid) { throw new IOException("Published import configuration is invalid", invalid); }
    }

    private static void requireChild(File home, File child) throws IOException {
        if (!home.getCanonicalFile().equals(child.getAbsoluteFile().getParentFile().getCanonicalFile())
                || Files.isSymbolicLink(child.toPath())) throw new IOException("Import path is outside its private home directory");
    }

    private static boolean exists(File file) { return Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS); }
    private static boolean validId(String value) {
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private void save(JSONObject next) throws IOException {
        if (!FileUtils.writeStringAtomic(receipt, next.toString())) throw new IOException("Could not save import receipt");
        record = next;
    }

    private static void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        }); // No FOLLOW_LINKS: never recursively visits drives mapped by the prefix.
    }
}
