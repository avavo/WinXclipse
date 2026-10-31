package com.winlator.cmod.core;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import androidx.preference.PreferenceManager;
import org.json.JSONObject;
import java.util.*;

/** Saved labels avoid querying removable/cloud providers while drawing the locations screen. */
public final class DocumentTreeStore {
    private static final String KEY = "file_manager_document_trees";
    public static final class Entry {
        public final String uri, name;
        Entry(String uri, String name) { this.uri = uri; this.name = name; }
    }
    private DocumentTreeStore() {}
    public static List<Entry> list(Context context) {
        List<Entry> result = new ArrayList<>();
        for (String encoded : PreferenceManager.getDefaultSharedPreferences(context).getStringSet(KEY, Collections.emptySet())) {
            try {
                JSONObject value = new JSONObject(encoded);
                String uri = value.getString("uri"), name = value.getString("name");
                Uri parsed = Uri.parse(uri);
                if ("content".equals(parsed.getScheme()) && DocumentsContract.isTreeUri(parsed)) result.add(new Entry(uri, name));
            } catch (Exception ignored) {}
        }
        result.sort(Comparator.comparing(entry -> entry.name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }
    public static synchronized void put(Context context, Uri uri, String name) {
        List<Entry> entries = list(context); entries.removeIf(entry -> entry.uri.equals(uri.toString()));
        entries.add(new Entry(uri.toString(), name)); save(context, entries);
    }
    public static synchronized void remove(Context context, String uri) {
        List<Entry> entries = list(context); entries.removeIf(entry -> entry.uri.equals(uri)); save(context, entries);
    }
    private static void save(Context context, List<Entry> entries) {
        Set<String> encoded = new LinkedHashSet<>();
        for (Entry entry : entries) {
            try { encoded.add(new JSONObject().put("uri", entry.uri).put("name", entry.name).toString()); }
            catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
        }
        PreferenceManager.getDefaultSharedPreferences(context).edit().putStringSet(KEY, encoded).apply();
    }
}
