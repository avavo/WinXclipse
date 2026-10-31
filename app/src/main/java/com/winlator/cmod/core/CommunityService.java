package com.winlator.cmod.core;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** IO-thread client. The APK contains only the public endpoint and its own upload token. */
public final class CommunityService {
    private static final int PAGE_LIMIT = 4 * 1024 * 1024, INDEX_LIMIT = 64 * 1024 * 1024;
    private static final Object CLIENT_LOCK = new Object(), CACHE_LOCK = new Object();
    private CommunityService() {}

    public static final class Entry {
        public final String id, downloadUrl, coverUrl, sourceUrl, sha256;
        public final int revision;
        public final long size, updatedAt;
        public final boolean verified, deleted;
        public final JSONObject metadata;
        private Entry(JSONObject value) throws Exception {
            id = value.getString("id"); revision = value.getInt("revision");
            downloadUrl = value.getString("downloadUrl"); coverUrl = value.isNull("coverUrl") ? "" : value.getString("coverUrl");
            sourceUrl = value.isNull("sourceUrl") ? "" : value.getString("sourceUrl"); sha256 = value.getString("sha256");
            size = value.getLong("size"); updatedAt = value.getLong("updatedAt");
            verified = value.getBoolean("verified"); deleted = value.getBoolean("deleted");
            metadata = value.getJSONObject("metadata");
            if (!CommunityEndpoint.trustedFile(downloadUrl, id, revision, false)
                    || !coverUrl.isEmpty() && !CommunityEndpoint.trustedFile(coverUrl, id, revision, true)
                    || !sourceUrl.isEmpty() && !CommunityEndpoint.legacySource(sourceUrl)
                    || !CommunityEndpoint.validArchive(sha256, size)
                    || metadata.optString("gameName", "").trim().isEmpty()) throw new IOException("Invalid catalog entry");
        }
    }

    public static final class Catalog {
        public final List<Entry> entries = new ArrayList<>();
        public final Set<String> managedSources = new HashSet<>();
        public boolean offline;
        private Catalog(JSONArray array) throws Exception {
            if (array.length() > 50000) throw new IOException("Catalog too large");
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < array.length(); i++) {
                Entry entry = new Entry(array.getJSONObject(i));
                if (!ids.add(entry.id)) throw new IOException("Duplicate catalog identity");
                entries.add(entry);
                if (!entry.sourceUrl.isEmpty()) managedSources.add(entry.sourceUrl);
            }
        }
    }

    public static Catalog load(Context context) {
        File cache = new File(context.getFilesDir(), "community_service_catalog.json");
        try {
            JSONArray all = new JSONArray(); int cursor = 0, bytes = 0;
            do {
                JSONObject page = requestJson("/api/v1/catalog?cursor=" + cursor, "GET", null, null);
                if (page.getInt("apiVersion") != 1) throw new IOException("Unsupported catalog");
                JSONArray items = page.getJSONArray("items");
                if (items.length() > 200) throw new IOException("Invalid page");
                bytes += page.toString().getBytes(StandardCharsets.UTF_8).length;
                if (bytes > INDEX_LIMIT) throw new IOException("Catalog too large");
                for (int i = 0; i < items.length(); i++) all.put(items.getJSONObject(i));
                int next = page.isNull("nextCursor") ? -1 : page.getInt("nextCursor");
                if (next != -1 && (next <= cursor || next > 50000)) throw new IOException("Invalid cursor");
                cursor = next;
            } while (cursor != -1);
            Catalog result = new Catalog(all);
            FileUtils.writeStringAtomic(cache, all.toString());
            return result;
        } catch (Exception unavailable) {
            try (InputStream input = new FileInputStream(cache)) {
                Catalog cached = new Catalog(new JSONArray(new String(read(input, INDEX_LIMIT), StandardCharsets.UTF_8)));
                cached.offline = true; return cached;
            } catch (Exception ignored) {
                try { Catalog empty = new Catalog(new JSONArray()); empty.offline = true; return empty; }
                catch (Exception impossible) { throw new AssertionError(impossible); }
            }
        }
    }

    public static File archive(Context context, Entry entry) throws Exception {
        // Separate from the legacy verified-only ZIP cache. Never infer trust from a file.
        synchronized (CACHE_LOCK) {
            File dir = new File(context.getFilesDir(), "community-service-archives");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot prepare archive cache");
            File output = new File(dir, entry.id + "-" + entry.revision + "-" + entry.sha256 + ".zip");
            if (output.length() == entry.size && PackageIdentity.matchesSha256(output, entry.sha256)) return output;
            File stage = File.createTempFile("download-", ".part", dir);
            HttpURLConnection connection = connect(entry.downloadUrl, "GET");
            try {
                if (connection.getResponseCode() != 200) throw new IOException("Archive unavailable");
                try (InputStream input = connection.getInputStream(); FileOutputStream out = new FileOutputStream(stage)) {
                    byte[] buffer = new byte[65536]; int count; long size = 0;
                    while ((count = input.read(buffer)) != -1) {
                        size += count; if (size > entry.size) throw new IOException("Archive exceeds expected size");
                        out.write(buffer, 0, count);
                    }
                    out.getFD().sync();
                }
                if (stage.length() != entry.size || !PackageIdentity.matchesSha256(stage, entry.sha256)) throw new IOException("Archive digest mismatch");
                if (output.exists() && !output.delete() || !stage.renameTo(output)) throw new IOException("Cannot save archive");
                return output;
            } finally { connection.disconnect(); stage.delete(); }
        }
    }

    public static Entry publish(Context context, File archive) throws Exception {
        if (!archive.isFile() || archive.length() < 22 || archive.length() > CommunityEndpoint.MAX_ZIP) throw new ServiceException(413, "file_too_large");
        synchronized (CLIENT_LOCK) {
            SharedPreferences prefs = context.getSharedPreferences("community_service", Context.MODE_PRIVATE);
            for (int attempt = 0; attempt < 2; attempt++) {
                String credential = prefs.getString("upload_token", "");
                if (!credential.matches("[A-Za-z0-9_-]{43}")) {
                    credential = requestJson("/api/v1/clients", "POST", null, null).getString("token");
                    if (!credential.matches("[A-Za-z0-9_-]{43}") || !prefs.edit().putString("upload_token", credential).commit())
                        throw new IOException("Cannot preserve upload identity");
                }
                try { return new Entry(requestJson("/api/v1/configs", "POST", credential, archive)); }
                catch (ServiceException error) {
                    if (error.status != 401 || attempt != 0) throw error;
                    prefs.edit().remove("upload_token").commit();
                }
            }
        }
        throw new IOException("Unable to publish config");
    }

    public static final class ServiceException extends IOException {
        public final int status;
        ServiceException(int status, String code) { super(code); this.status = status; }
    }

    private static HttpURLConnection connect(String url, String method) throws IOException {
        HttpURLConnection connection = (HttpURLConnection)new URL(url).openConnection();
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(15000); connection.setReadTimeout(45000);
        connection.setRequestMethod(method); connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "WinXclipse-Community/1"); return connection;
    }

    private static JSONObject requestJson(String path, String method, String token, File archive) throws Exception {
        HttpURLConnection connection = connect(CommunityEndpoint.ORIGIN + path, method);
        try {
            if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
            if ("POST".equals(method)) {
                connection.setDoOutput(true); connection.setFixedLengthStreamingMode(archive == null ? 0 : archive.length());
                connection.setRequestProperty("Content-Type", archive == null ? "application/json" : "application/zip");
                try (OutputStream out = connection.getOutputStream()) {
                    if (archive != null) try (InputStream in = new FileInputStream(archive)) {
                        byte[] buffer = new byte[65536]; int count;
                        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                    }
                }
            }
            int status = connection.getResponseCode();
            try (InputStream input = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream()) {
                JSONObject response = new JSONObject(new String(read(input, PAGE_LIMIT), StandardCharsets.UTF_8));
                if (status < 200 || status >= 300) throw new ServiceException(status, response.optString("error", "request_failed"));
                return response;
            }
        } finally { connection.disconnect(); }
    }

    private static byte[] read(InputStream input, int maximum) throws IOException {
        if (input == null) throw new IOException("Empty API response");
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[16384]; int count;
        while ((count = input.read(buffer)) != -1) {
            if ((long)out.size() + count > maximum) throw new IOException("Response exceeds limit");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }
}
