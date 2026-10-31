package com.winlator.cmod.core;

import java.io.File;
import java.util.*;

/** Clipboard references never retain a Fragment, Activity or open provider cursor. */
public final class StorageClipboard {
    public static final class Item {
        public final String path, tree, document, name;
        private Item(String path, String tree, String document, String name) {
            this.path = path; this.tree = tree; this.document = document; this.name = name;
        }
        public static Item file(File file) { return new Item(file.getAbsolutePath(), null, null, file.getName()); }
        public static Item document(String tree, String document, String name) {
            return new Item(null, Objects.requireNonNull(tree), Objects.requireNonNull(document), Objects.requireNonNull(name));
        }
        public boolean isFile() { return path != null; }
        public String key() { return isFile() ? "file:" + path : "doc:" + tree + "|" + document; }
    }
    public static final class Snapshot {
        public final long version;
        public final boolean move;
        public final List<Item> items;
        private Snapshot(long version, boolean move, Collection<Item> items) {
            this.version = version; this.move = move;
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
        }
        public boolean hasDocuments() { for (Item item : items) if (!item.isFile()) return true; return false; }
        public List<File> files() {
            List<File> files = new ArrayList<>();
            for (Item item : items) {
                if (!item.isFile()) throw new IllegalStateException("Clipboard contains documents");
                files.add(new File(item.path));
            }
            return files;
        }
    }
    private static long version;
    private static Snapshot value = new Snapshot(0, false, Collections.emptyList());
    private StorageClipboard() {}
    public static synchronized Snapshot get() { return value; }
    public static synchronized boolean isEmpty() { return value.items.isEmpty(); }
    public static synchronized void set(Collection<Item> items, boolean move) { value = new Snapshot(++version, move, items); }
    public static synchronized void files(Collection<File> files, boolean move) {
        List<Item> items = new ArrayList<>(); for (File file : files) items.add(Item.file(file)); set(items, move);
    }
    public static synchronized void clear() { set(Collections.emptyList(), false); }
    public static synchronized void removeCompleted(Snapshot operation, Collection<Item> completed) {
        if (value.version != operation.version || !operation.move) return;
        Set<String> keys = new HashSet<>(); for (Item item : completed) keys.add(item.key());
        List<Item> remaining = new ArrayList<>();
        for (Item item : value.items) if (!keys.contains(item.key())) remaining.add(item);
        value = new Snapshot(++version, value.move, remaining);
    }
}
