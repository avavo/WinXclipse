package com.winlator.cmod.core;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.BuildConfig;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/** Lightweight automatic update check backed by the project's latest GitHub release. */
public final class UpdateChecker {
    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/avavo/WinXclipse/releases/latest";
    private static final String RELEASES_URL =
            "https://github.com/avavo/WinXclipse/releases";
    private static final long PROMPT_INTERVAL_MS = 4L * 24L * 60L * 60L * 1000L;

    private UpdateChecker() {}

    public static void check(Activity activity) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        long now = System.currentTimeMillis();

        Executors.newSingleThreadExecutor().execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection)new URL(LATEST_RELEASE_API).openConnection();
                connection.setConnectTimeout(6000);
                connection.setReadTimeout(8000);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", "WinXclipse-Android");
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return;

                JSONObject release;
                try (InputStream input = connection.getInputStream()) {
                    release = new JSONObject(new String(StreamUtils.copyToByteArray(input),
                            StandardCharsets.UTF_8));
                }
                if (release.optBoolean("draft") || release.optBoolean("prerelease")) return;

                String tag = release.optString("tag_name", "");
                if (tag.isEmpty() || compareVersions(tag, BuildConfig.VERSION_NAME) <= 0) return;
                String page = release.optString("html_url", RELEASES_URL);
                long lastPrompt = preferences.getLong("update_last_prompt", 0);
                if (now - lastPrompt < PROMPT_INTERVAL_MS) return;

                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    preferences.edit().putLong("update_last_prompt",
                            System.currentTimeMillis()).apply();
                    new AlertDialog.Builder(activity)
                            .setTitle("WinXclipse update available")
                            .setMessage("Version " + tag + " is available. Update now?\n\n"
                                    + "Installed: " + BuildConfig.VERSION_NAME)
                            .setPositiveButton("Update", (dialog, which) -> activity.startActivity(
                                    new Intent(Intent.ACTION_VIEW, Uri.parse(page))))
                            .setNegativeButton("Later", null)
                            .show();
                });
            }
            catch (Exception ignored) {
                // Update checks must never prevent the app from starting offline.
            }
            finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    static int compareVersions(String left, String right) {
        int[] a = numericParts(left);
        int[] b = numericParts(right);
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            int av = i < a.length ? a[i] : 0;
            int bv = i < b.length ? b[i] : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private static int[] numericParts(String version) {
        String normalized = version.replaceFirst("^[vV]", "");
        String[] parts = normalized.split("[^0-9]+");
        int[] values = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { values[i] = parts[i].isEmpty() ? 0 : Integer.parseInt(parts[i]); }
            catch (NumberFormatException ignored) { values[i] = 0; }
        }
        return values;
    }
}
