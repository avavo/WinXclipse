package com.winlator.cmod.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.winlator.cmod.bigpicture.steamgrid.SteamGridDBApi;
import com.winlator.cmod.bigpicture.steamgrid.SteamGridGridsResponse;
import com.winlator.cmod.bigpicture.steamgrid.SteamGridGridsResponseDeserializer;
import com.winlator.cmod.bigpicture.steamgrid.SteamGridSearchResponse;
import com.winlator.cmod.container.Shortcut;

import java.io.File;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/** Centralized artwork policy used by both newly-created and existing shortcuts. */
public final class ShortcutArtworkManager {
    public static final String PREF_MODE = "shortcut_artwork_mode";
    public static final String EXTRA_MODE = "artworkMode";
    public static final String MODE_BROWSER = "browser";
    public static final String MODE_EXE = "exe";
    public static final String MODE_CUSTOM = "custom";

    private static final String TAG = "ShortcutArtwork";
    private static final String BASE_URL = "https://www.steamgriddb.com/api/v2/";
    private static final String BUILT_IN_API_KEY = "0324c52513634547a7b32d6d323635d0";
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<String> PENDING = Collections.synchronizedSet(new HashSet<String>());
    /** Session negative cache: shortcuts whose online lookup already failed. */
    private static final Set<String> FAILED = Collections.synchronizedSet(new HashSet<String>());
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient();
    private static volatile SteamGridDBApi steamGridApi;

    private ShortcutArtworkManager() {}

    public interface Callback {
        void onComplete(boolean success);
    }

    public static String getMode(Context context, Shortcut shortcut) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String global = preferences.getString(PREF_MODE, MODE_BROWSER);
        String mode = shortcut.getExtra(EXTRA_MODE, global);
        return MODE_EXE.equals(mode) || MODE_CUSTOM.equals(mode) ? mode : MODE_BROWSER;
    }

    public static void setMode(Shortcut shortcut, String mode) {
        shortcut.putExtra(EXTRA_MODE, mode);
        shortcut.saveData();
    }

    public static void deleteGeneratedArtwork(Shortcut shortcut) {
        File file = shortcut.getGeneratedCoverArtFile();
        if (file.isFile() && !file.delete()) Log.w(TAG, "Could not delete " + file);
        shortcut.reloadCoverArt();
    }

    /** Resolves the configured source. Browser is default and falls back to the EXE icon. */
    public static void ensure(Context context, Shortcut shortcut, boolean force, Callback callback) {
        String mode = getMode(context, shortcut);
        if (MODE_CUSTOM.equals(mode)) {
            post(callback, shortcut.getCoverArt() != null);
            return;
        }
        if (!force && shortcut.getCoverArt() != null) {
            post(callback, true);
            return;
        }
        final String pendingKey = shortcut.container.getRootDir().getPath()
                + File.separatorChar + shortcut.name;
        if (!force && FAILED.contains(pendingKey)) {
            // Already tried and failed this session; do not hammer the network
            // again on every rebind.
            post(callback, false);
            return;
        }
        if (!PENDING.add(pendingKey)) {
            post(callback, false);
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                boolean ok;
                if (MODE_BROWSER.equals(mode)) {
                    ok = downloadBrowserArtwork(context.getApplicationContext(), shortcut);
                    if (!ok) ok = createExeArtwork(shortcut);
                } else {
                    ok = createExeArtwork(shortcut);
                }
                shortcut.reloadCoverArt();
                boolean resolved = ok && shortcut.getCoverArt() != null;
                if (resolved) FAILED.remove(pendingKey); else FAILED.add(pendingKey);
                post(callback, resolved);
            } finally {
                PENDING.remove(pendingKey);
            }
        });
    }

    /** Uses the same browser artwork policy for community-config cards. */
    public static void ensureForGame(Context context, String gameName, File output, Callback callback) {
        if (output.isFile()) {
            post(callback, true);
            return;
        }
        EXECUTOR.execute(() -> {
            boolean ok = false;
            Bitmap bitmap = null;
            try {
                bitmap = fetchBrowserArtwork(context, gameName);
                File parent = output.getParentFile();
                if (bitmap != null && parent != null
                        && (parent.isDirectory() || parent.mkdirs())) {
                    ok = FileUtils.saveBitmapToFile(bitmap, output);
                }
            } catch (Exception e) {
                Log.w(TAG, "Community artwork failed for " + gameName, e);
            } finally {
                if (bitmap != null) bitmap.recycle();
            }
            post(callback, ok);
        });
    }

    private static boolean createExeArtwork(Shortcut shortcut) {
        File exe = shortcut.resolveExecutableFile();
        return exe != null && ExeIconExtractor.extractCover(exe, shortcut.getGeneratedCoverArtFile());
    }

    private static SteamGridDBApi getSteamGridApi() {
        SteamGridDBApi api = steamGridApi;
        if (api != null) return api;
        synchronized (ShortcutArtworkManager.class) {
            if (steamGridApi == null) {
                Gson gridsGson = new GsonBuilder()
                        .registerTypeAdapter(SteamGridGridsResponse.class,
                                new SteamGridGridsResponseDeserializer())
                        .create();
                steamGridApi = new Retrofit.Builder()
                        .baseUrl(BASE_URL)
                        .client(HTTP_CLIENT)
                        .addConverterFactory(GsonConverterFactory.create(gridsGson))
                        .build()
                        .create(SteamGridDBApi.class);
            }
            return steamGridApi;
        }
    }

    private static boolean downloadBrowserArtwork(Context context, Shortcut shortcut) {
        try {
            // Community cards call the same fetcher, so query cleanup, API key,
            // dimensions, filters and result selection cannot drift apart.
            Bitmap bitmap = fetchBrowserArtwork(context, shortcut.name);
            return bitmap != null && shortcut.saveGeneratedCoverArt(bitmap);
        } catch (Exception e) {
            Log.w(TAG, "Online artwork failed for " + shortcut.name, e);
            return false;
        }
    }

    private static Bitmap fetchBrowserArtwork(Context context, String gameName) throws Exception {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String key = BUILT_IN_API_KEY;
        if (prefs.getBoolean("enable_custom_api_key", false)) {
            String custom = prefs.getString("custom_api_key", "");
            if (custom != null && !custom.trim().isEmpty()) key = custom.trim();
        }

        SteamGridDBApi api = getSteamGridApi();
        String query = cleanSearchName(gameName);
        retrofit2.Response<SteamGridSearchResponse> search =
                api.searchGame("Bearer " + key, query).execute();
        if (!search.isSuccessful() || search.body() == null || search.body().data == null
                || search.body().data.isEmpty()) return null;

        // SteamGridDB autocomplete is not relevance-sorted for every executable
        // name. Rank normalized exact/token matches locally, then try several
        // candidates because the first game may not have a portrait grid.
        List<SteamGridSearchResponse.GameData> candidates =
                new ArrayList<>(search.body().data);
        candidates.sort((left, right) -> Integer.compare(
                searchMatchScore(query, right != null ? right.name : null),
                searchMatchScore(query, left != null ? left.name : null)));

        String imageUrl = null;
        int attempts = Math.min(5, candidates.size());
        for (int index = 0; index < attempts && imageUrl == null; index++) {
            SteamGridSearchResponse.GameData candidate = candidates.get(index);
            if (candidate == null) continue;
            retrofit2.Response<SteamGridGridsResponse> grids = api.getGridsByGameId(
                    "Bearer " + key, candidate.id,
                    "alternate,blurred,material", "600x900", "static").execute();
            if (grids.isSuccessful() && grids.body() != null && grids.body().data != null) {
                imageUrl = firstImageUrl(grids.body().data);
            }
        }
        if (imageUrl == null) return null;
        try (Response response = HTTP_CLIENT.newCall(
                new Request.Builder().url(imageUrl).build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            try (InputStream input = response.body().byteStream()) {
                return BitmapFactory.decodeStream(input);
            }
        }
    }

    private static String firstImageUrl(List<SteamGridGridsResponse.Grid> grids) {
        for (SteamGridGridsResponse.Grid grid : grids) {
            if (grid != null && grid.url != null && !grid.url.trim().isEmpty()) return grid.url;
        }
        return null;
    }

    private static String cleanSearchName(String name) {
        if (name == null) return "game";
        String clean = name.replace('_', ' ').replace('-', ' ')
                .replaceAll("(?i)\\.exe$", "")
                .replaceAll("(?i)\\b(?:launcher|shipping|win64|win32|x64|x86|dx11|dx12|vulkan|opengl)\\b", " ")
                .replaceAll("(?i)\\s+(?:demo|benchmark)$", "")
                .replaceAll("\\s+", " ").trim();
        return clean.isEmpty() ? "game" : clean;
    }

    private static int searchMatchScore(String query, String candidate) {
        String wanted = normalizeSearchText(query);
        String found = normalizeSearchText(candidate);
        if (found.isEmpty()) return Integer.MIN_VALUE;
        if (wanted.equals(found)) return 10_000;

        int score = 0;
        if (found.startsWith(wanted) || wanted.startsWith(found)) score += 2_000;
        if (found.contains(wanted) || wanted.contains(found)) score += 1_000;
        String[] wantedTokens = wanted.split(" ");
        String[] foundTokens = found.split(" ");
        for (String token : wantedTokens) {
            if (token.isEmpty()) continue;
            for (String resultToken : foundTokens) {
                if (token.equals(resultToken)) {
                    score += 200;
                    break;
                }
            }
        }
        score -= Math.abs(found.length() - wanted.length());
        return score;
    }

    private static String normalizeSearchText(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static void post(Callback callback, boolean success) {
        if (callback != null) MAIN.post(() -> callback.onComplete(success));
    }
}
