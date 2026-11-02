package com.winlator.cmod.core;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Applies startup registry settings in one pass, while Wine is stopped. */
public final class WineRegistryBatch implements AutoCloseable {
    private final File file;
    private final Map<String, Map<String, String>> sections = new LinkedHashMap<>();
    private boolean createKeys = true;
    private boolean closed;

    public WineRegistryBatch(File file) { this.file = file; }
    public void setCreateKeyIfNotExist(boolean value) { createKeys = value; }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void put(String key, String name, String rawValue) {
        if (closed) throw new IllegalStateException("Registry batch is closed");
        sections.computeIfAbsent("[" + escape(key) + "]", ignored -> new LinkedHashMap<>())
                .put(name == null ? "@=" : "\"" + escape(name) + "\"=", rawValue);
    }

    public void setStringValue(String key, String name, String value) {
        put(key, name, "\"" + escape(value == null ? "" : value) + "\"");
    }

    public void setDwordValue(String key, String name, int value) {
        put(key, name, String.format(Locale.ROOT, "dword:%08x", value));
    }

    public void setHexValue(String key, String name, byte[] bytes) {
        StringBuilder raw = new StringBuilder("hex:");
        for (byte item : bytes) {
            if (raw.length() > 4) raw.append(',');
            raw.append(String.format(Locale.ROOT, "%02x", item & 255));
        }
        put(key, name, raw.toString());
    }

    public void setHexValue(String key, String name, String hex) {
        StringBuilder raw = new StringBuilder("hex:");
        for (int i = 0; i < hex.length(); i += 2) {
            if (i > 0) raw.append(',');
            raw.append(hex, i, Math.min(i + 2, hex.length()));
        }
        put(key, name, raw.toString());
    }

    public void removeValue(String key, String name) { put(key, name, null); }

    private static boolean appendValues(BufferedWriter writer, Map<String, String> values) throws IOException {
        boolean added = false;
        if (values != null) for (Map.Entry<String, String> value : values.entrySet()) {
            if (value.getValue() == null) continue;
            writer.write(value.getKey());
            writer.write(value.getValue());
            writer.write('\n');
            added = true;
        }
        return added;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (sections.isEmpty()) return;
        Path temporary = null;
        try {
            temporary = Files.createTempFile(file.getParentFile().toPath(), ".wine-batch-", ".tmp");
            boolean changed = false;
            try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8);
                 BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                String header = reader.readLine();
                if (header == null || !header.matches("WINE REGISTRY Version \\d+[\\t ]*"))
                    throw new IOException("Invalid Wine registry header: " + file);
                writer.write(header);
                writer.write('\n');
                Map<String, String> current = null;
                boolean skipContinuation = false;
                String line;
                while ((line = reader.readLine()) != null) {
                    // Only discard the continuation of a value explicitly being replaced.
                    if (skipContinuation) {
                        skipContinuation = line.trim().endsWith("\\");
                        continue;
                    }
                    if (line.startsWith("[") && line.lastIndexOf(']') > 0) {
                        changed |= appendValues(writer, current);
                        current = sections.remove(line.substring(0, line.lastIndexOf(']') + 1));
                    }
                    String matched = null;
                    if (current != null) for (String name : current.keySet()) {
                        if (line.startsWith(name)) { matched = name; break; }
                    }
                    if (matched != null) {
                        String replacement = current.remove(matched);
                        skipContinuation = line.trim().endsWith("\\");
                        if (replacement == null) { changed = true; continue; }
                        String updated = matched + replacement;
                        changed |= !updated.equals(line);
                        line = updated;
                    }
                    writer.write(line);
                    writer.write('\n');
                }
                changed |= appendValues(writer, current);
                if (createKeys) for (Map.Entry<String, Map<String, String>> section : sections.entrySet()) {
                    // A removal from a missing key must not create that key.
                    if (section.getValue().values().stream().allMatch(value -> value == null)) continue;
                    writer.write('\n');
                    writer.write(section.getKey());
                    writer.write('\n');
                    changed |= appendValues(writer, section.getValue());
                }
            }
            // An unchanged policy/theme keeps the original bytes, inode and timestamp.
            // Publish only after the entire registry has been read and written successfully.
            if (changed) Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot apply Wine registry settings: " + file, failure);
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); }
            catch (IOException ignored) { /* Never discard the live registry on cleanup failure. */ }
        }
    }
}
