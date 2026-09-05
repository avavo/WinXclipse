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

/** Dynamic catalog for Xclipse driver and wrapper downloads. */
public final class ExternalDownloadCatalog {
    private static final String TAG = "ExternalDownloadCatalog";
    private static final String DRIVER_CACHE_KEY = "xclipse_driver_release_cache_v2";
    private static final String DRIVER_REFRESH_TIME_KEY = "xclipse_driver_release_refresh_time";
    private static final String WRAPPER_CACHE_KEY = "xclipse_wrapper_release_cache_v3";
    private static final String WRAPPER_REFRESH_TIME_KEY = "xclipse_wrapper_release_refresh_time_v3";
    /** "owner/name" lists every release; "owner/name@tag" pins one release. */
    private static final String[] DRIVER_REPOSITORIES = {
            "WearyConcern1165/ExynosTools",
            "avavo/WinXclipse@drivers_0.9"
    };
    private static final String[] WRAPPER_REPOSITORIES = {
            "avavo/WinXclipse@wrappers"
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
        return refresh(DRIVER_REPOSITORIES, DRIVER_CACHE_KEY,
                DRIVER_REFRESH_TIME_KEY, false);
    }

    public List<Item> refreshWrappers() {
        return refresh(WRAPPER_REPOSITORIES, WRAPPER_CACHE_KEY,
                WRAPPER_REFRESH_TIME_KEY, true);
    }

    private List<Item> refresh(String[] repositories, String cacheKey,
                               String refreshTimeKey, boolean wrappers) {
        JSONArray combined = new JSONArray();
        boolean refreshed = false;
        for (String repository : repositories) {
            JSONArray releases = fetchReleases(repository);
            if (releases == null) continue;
            String repositoryName = repository.substring(repository.indexOf('/') + 1)
                    .replaceFirst("@.*$", "");
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
                    if (wrappers) {
                        if (!(lower.endsWith(".tzst") || lower.endsWith(".so"))) continue;
                    }
                    else if (!(lower.endsWith(".zip") || lower.endsWith(".tzst"))) continue;
                    String url = asset.optString("browser_download_url", "");
                    if (url.isEmpty()) continue;
                    JSONObject item = new JSONObject();
                    try {
                        String packageName = stripPackageSuffix(fileName);
                        item.put("name", wrappers
                                ? CustomWrapperManager.toDisplayName(packageName)
                                : packageName);
                        item.put("detail", repositoryName + " • " + tag);
                        item.put("url", url);
                        combined.put(item);
                    }
                    catch (Exception ignored) {}
                }
            }
        }
        if (refreshed && combined.length() > 0) {
            preferences.edit()
                    .putString(cacheKey, combined.toString())
                    .putLong(refreshTimeKey, System.currentTimeMillis())
                    .apply();
        }
        else {
            try {
                combined = new JSONArray(preferences.getString(cacheKey, "[]"));
            }
            catch (Exception ignored) {
                combined = new JSONArray();
            }
        }
        return parseItems(combined);
    }

    public List<Item> refreshDriversIfStale(long maximumAgeMs) {
        long refreshedAt = preferences.getLong(DRIVER_REFRESH_TIME_KEY, 0L);
        if (refreshedAt > 0L && System.currentTimeMillis() - refreshedAt < maximumAgeMs) {
            return getCachedDrivers();
        }
        return refreshDrivers();
    }

    public List<Item> refreshWrappersIfStale(long maximumAgeMs) {
        long refreshedAt = preferences.getLong(WRAPPER_REFRESH_TIME_KEY, 0L);
        if (refreshedAt > 0L && System.currentTimeMillis() - refreshedAt < maximumAgeMs) {
            return getCachedWrappers();
        }
        return refreshWrappers();
    }

    public List<Item> getCachedDrivers() {
        try {
            return parseItems(new JSONArray(preferences.getString(DRIVER_CACHE_KEY, "[]")));
        }
        catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public List<Item> getCachedWrappers() {
        try {
            return parseItems(new JSONArray(preferences.getString(WRAPPER_CACHE_KEY, "[]")));
        }
        catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private JSONArray fetchReleases(String repository) {
        HttpURLConnection connection = null;
        try {
            int tagIndex = repository.indexOf('@');
            String repo = tagIndex >= 0 ? repository.substring(0, tagIndex) : repository;
            String endpoint = tagIndex >= 0
                    ? "https://api.github.com/repos/" + repo + "/releases/tags/" + repository.substring(tagIndex + 1)
                    : "https://api.github.com/repos/" + repo + "/releases?per_page=30";
            URL url = new URL(endpoint);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "WinXclipse-Android");
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            try (InputStream input = connection.getInputStream()) {
                String body = new String(StreamUtils.copyToByteArray(input), StandardCharsets.UTF_8);
                // The list endpoint returns an array; a pinned "tags/{tag}" endpoint returns one object.
                try {
                    return new JSONArray(body);
                }
                catch (Exception arrayError) {
                    JSONArray single = new JSONArray();
                    single.put(new JSONObject(body));
                    return single;
                }
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
