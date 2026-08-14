package com.winlator.cmod.contents;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.winlator.cmod.core.StreamUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Dynamic catalog for Xclipse driver downloads that can change without an APK update. */
public final class ExternalDownloadCatalog {
    private static final String TAG = "ExternalDownloadCatalog";
    private static final String CACHE_KEY = "xclipse_driver_release_cache_v1";
    private static final String[] DRIVER_REPOSITORIES = {
            "WearyConcern1165/ExynosTools",
            "avavo/MdiEx"
    };

    public static final class Item {
        public final String name;
        public final String detail;
        public final String url;

        public Item(String name, String detail, String url) {
            this.name = name;
            this.detail = detail;
            this.url = url;
        }
    }

    private final SharedPreferences preferences;

    public ExternalDownloadCatalog(Context context) {
        preferences = context.getSharedPreferences("external_download_catalog", Context.MODE_PRIVATE);
    }

    public List<Item> refreshDrivers() {
        JSONArray combined = new JSONArray();
        boolean refreshed = false;
        for (String repository : DRIVER_REPOSITORIES) {
            JSONArray releases = fetchReleases(repository);
            if (releases == null) continue;
            refreshed = true;
            for (int i = 0; i < releases.length(); i++) {
                JSONObject release = releases.optJSONObject(i);
                if (release == null || release.optBoolean("draft", false)) continue;
                String tag = release.optString("tag_name", "");
                JSONArray assets = release.optJSONArray("assets");
                if (assets == null) continue;
                for (int j = 0; j < assets.length(); j++) {
                    JSONObject asset = assets.optJSONObject(j);
                    if (asset == null) continue;
                    String fileName = asset.optString("name", "");
                    String lower = fileName.toLowerCase(Locale.ENGLISH);
                    if (!(lower.endsWith(".zip") || lower.endsWith(".tzst"))) continue;
                    String url = asset.optString("browser_download_url", "");
                    if (url.isEmpty()) continue;
                    JSONObject item = new JSONObject();
                    try {
                        item.put("name", stripPackageSuffix(fileName));
                        item.put("detail", repository.substring(repository.indexOf('/') + 1) + " • " + tag);
                        item.put("url", url);
                        combined.put(item);
                    }
                    catch (Exception ignored) {}
                }
            }
        }
        if (refreshed && combined.length() > 0) {
            preferences.edit().putString(CACHE_KEY, combined.toString()).apply();
        }
        else {
            try {
                combined = new JSONArray(preferences.getString(CACHE_KEY, "[]"));
            }
            catch (Exception ignored) {
                combined = new JSONArray();
            }
        }
        return parseItems(combined);
    }

    public List<Item> getCachedDrivers() {
        try {
            return parseItems(new JSONArray(preferences.getString(CACHE_KEY, "[]")));
        }
        catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private JSONArray fetchReleases(String repository) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("https://api.github.com/repos/" + repository + "/releases?per_page=30");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "WinXclipse-Android");
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            try (InputStream input = connection.getInputStream()) {
                return new JSONArray(new String(StreamUtils.copyToByteArray(input), StandardCharsets.UTF_8));
            }
        }
        catch (Exception e) {
            Log.w(TAG, "Unable to refresh " + repository, e);
            return null;
        }
        finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static List<Item> parseItems(JSONArray array) {
        ArrayList<Item> result = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject value = array.optJSONObject(i);
            if (value == null) continue;
            String url = value.optString("url", "");
            if (url.isEmpty() || !seenUrls.add(url)) continue;
            result.add(new Item(value.optString("name", "Driver"),
                    value.optString("detail", ""), url));
        }
        return result;
    }

    public static String stripPackageSuffix(String fileName) {
        return fileName.replaceFirst("(?i)(?:\\.wcp(?:\\.(?:xz|zst))?|\\.(?:tzst|tstz|tzts|zst|xz|zip|so))$", "");
    }
}
