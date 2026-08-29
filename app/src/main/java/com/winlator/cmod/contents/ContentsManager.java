package com.winlator.cmod.contents;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;

import com.winlator.cmod.core.EvshimPatcher;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.StreamUtils;
import com.winlator.cmod.core.TarCompressorUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ContentsManager {
    public static final String PROFILE_NAME = "profile.json";
    private static final String TAG = "ContentsManager";
    public static final String REMOTE_PROFILES = "contents.json";
    private static final String REMOTE_CACHE_KEY = "github_release_contents_cache";
    private static final String REMOTE_REFRESH_TIME_KEY = "github_release_contents_refresh_time";
    private static final String GITHUB_RELEASE_API = "https://api.github.com/repos/avavo/WinXclipse/releases/tags/";
    private static final String[] REMOTE_RELEASE_TAGS = {
            "runtime-fexcore-v0.8",
            "runtime-wine-proton-v0.8",
            "box",
            "dxvk-and-vkd3d"
    };
    public static final String[] DXVK_TRUST_FILES = {"${system32}/d3d8.dll", "${system32}/d3d9.dll", "${system32}/d3d10.dll", "${system32}/d3d10_1.dll",
            "${system32}/d3d10core.dll", "${system32}/d3d11.dll", "${system32}/dxgi.dll", "${syswow64}/d3d8.dll", "${syswow64}/d3d9.dll", "${syswow64}/d3d10.dll",
            "${syswow64}/d3d10_1.dll", "${syswow64}/d3d10core.dll", "${syswow64}/d3d11.dll", "${syswow64}/dxgi.dll"};
    public static final String[] VKD3D_TRUST_FILES = {"${system32}/d3d12core.dll", "${system32}/d3d12.dll",
            "${syswow64}/d3d12core.dll", "${syswow64}/d3d12.dll"};
    public static final String[] BOX64_TRUST_FILES = {"${bindir}/box64"};
    public static final String[] WOWBOX64_TRUST_FILES = {"${system32}/wowbox64.dll"};
    public static final String[] FEXCORE_TRUST_FILES = {"${system32}/libwow64fex.dll", "${system32}/libarm64ecfex.dll"};
    private Map<String, String> dirTemplateMap;
    private Map<ContentProfile.ContentType, List<String>> trustedFilesMap;

    private SharedPreferences preferences;

    public enum InstallFailedReason {
        ERROR_NOSPACE,
        ERROR_BADTAR,
        ERROR_NOPROFILE,
        ERROR_BADPROFILE,
        ERROR_MISSINGFILES,
        ERROR_EXIST,
        ERROR_UNTRUSTPROFILE,
        ERROR_UNKNOWN
    }

    public enum ContentDirName {
        CONTENT_MAIN_DIR_NAME("contents"),
        CONTENT_WINE_DIR_NAME("wine"),
        CONTENT_PROTON_DIR_NAME("proton"),
        CONTENT_DXVK_DIR_NAME("dxvk"),
        CONTENT_VKD3D_DIR_NAME("vkd3d"),
        CONTENT_BOX64_DIR_NAME("box64");

        private String name;

        ContentDirName(String name) {
            this.name = name;
        }

        @NonNull
        @Override
        public String toString() {
            return name;
        }
    }

    private final Context context;

    private HashMap<ContentProfile.ContentType, List<ContentProfile>> profilesMap;

    private ArrayList<ContentProfile> remoteProfiles;

    public ContentsManager(Context context) {
        this.context = context;
        this.preferences = context.getSharedPreferences("contents_manager_prefs", Context.MODE_PRIVATE);
    }

    // Method to mark the graphics driver as installed
    public void setGraphicsDriverInstalled(String driverVersion, boolean installed) {
        preferences.edit().putBoolean("graphics_driver_installed_" + driverVersion, installed).apply();
    }

    public interface OnInstallFinishedCallback {
        void onFailed(InstallFailedReason reason, Exception e);

        void onSucceed(ContentProfile profile);
    }

    public interface OnInstallProgressCallback {
        void onProgress(int progress);
    }

    public void setRemoteProfiles(String json) {
        try {
            remoteProfiles = new ArrayList<>();
            JSONArray content = new JSONArray(json);
            for (int i = 0; i < content.length(); i++) {
                try {
                    JSONObject object = content.getJSONObject(i);
                    ContentProfile remoteProfile = new ContentProfile();
                    remoteProfile.remoteUrl = object.optString("remoteUrl", "");
                    remoteProfile.type = ContentProfile.ContentType.getTypeByName(object.getString("type"));
                    remoteProfile.verName = object.optString("verName", "");
                    remoteProfile.verCode = object.optInt("verCode", 0);
                    remoteProfile.contentId = object.optString(ContentProfile.MARK_ID, "");
                    // Skip poisoned cache entries (older builds stored empty
                    // names for some release assets).
                    if (remoteProfile.verName.trim().isEmpty() || remoteProfile.remoteUrl.trim().isEmpty()) {
                        continue;
                    }
                    remoteProfiles.add(remoteProfile);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        syncContents();
    }

    /**
     * Refreshes the downloadable catalog directly from the four WinXclipse
     * GitHub releases. The bundled JSON supplies exact metadata for known
     * packages and is also the first-run/offline fallback. Successful results
     * are cached so adding an asset to a release never requires a new APK.
     * This method performs network I/O and must be called off the UI thread.
     */
    public String refreshRemoteProfiles(String bundledJson) {
        JSONArray bundled = parseCatalog(bundledJson);
        String cachedJson = preferences.getString(REMOTE_CACHE_KEY, null);
        JSONArray catalog = parseCatalog(cachedJson);
        if (catalog.length() == 0) catalog = parseCatalog(bundledJson);

        boolean refreshedAnyRelease = false;
        for (String tag : REMOTE_RELEASE_TAGS) {
            JSONObject release = fetchRelease(tag);
            if (release == null) continue;

            JSONArray assets = release.optJSONArray("assets");
            if (assets == null) continue;

            removeReleaseEntries(catalog, tag);
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset == null) continue;

                String name = asset.optString("name", "");
                String remoteUrl = asset.optString("browser_download_url", "");
                if (!isSupportedRemoteAsset(name) || remoteUrl.isEmpty()) continue;

                JSONObject profile = findProfileByUrl(bundled, remoteUrl);
                if (profile == null) profile = inferRemoteProfile(tag, asset, name, remoteUrl);
                else {
                    // Bundled metadata is the identity used across the app
                    // (container selection, dedup on startup); keep its
                    // verName and only fall back to the raw file name when
                    // the bundle entry has none.
                    if (profile.optString("verName", "").trim().isEmpty()) {
                        String stripped = ExternalDownloadCatalog.stripPackageSuffix(name);
                        if (stripped.trim().isEmpty()) continue;
                        try {
                            profile.put("verName", stripped);
                        }
                        catch (JSONException ignored) {}
                    }
                }
                if (profile == null || profile.optString("verName", "").trim().isEmpty()) continue;
                catalog.put(profile);
            }
            refreshedAnyRelease = true;
        }

        String result = catalog.toString();
        if (refreshedAnyRelease) preferences.edit()
                .putString(REMOTE_CACHE_KEY, result)
                .putLong(REMOTE_REFRESH_TIME_KEY, System.currentTimeMillis())
                .apply();
        return result;
    }

    /** Returns immediately from disk so opening the Proton/Wine list never
     * waits for four GitHub requests. Bundled entries are merged into an older
     * cache after an app update. */
    public String getCachedRemoteProfiles(String bundledJson) {
        JSONArray cached = parseCatalog(preferences.getString(REMOTE_CACHE_KEY, null));
        JSONArray bundled = parseCatalog(bundledJson);
        if (cached.length() == 0) return bundled.toString();
        for (int i = 0; i < bundled.length(); i++) {
            JSONObject candidate = bundled.optJSONObject(i);
            if (candidate == null) continue;
            String url = candidate.optString("remoteUrl", "");
            if (!url.isEmpty() && findProfileByUrl(cached, url) != null) continue;
            boolean duplicate = false;
            for (int j = 0; j < cached.length(); j++) {
                JSONObject existing = cached.optJSONObject(j);
                if (existing != null
                        && candidate.optString("type").equals(existing.optString("type"))
                        && candidate.optString("verName").equals(existing.optString("verName"))
                        && candidate.optInt("verCode") == existing.optInt("verCode")) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) cached.put(candidate);
        }
        return cached.toString();
    }

    public boolean shouldRefreshRemoteProfiles(long maximumAgeMs) {
        long refreshedAt = preferences.getLong(REMOTE_REFRESH_TIME_KEY, 0L);
        return refreshedAt <= 0L || System.currentTimeMillis() - refreshedAt >= maximumAgeMs;
    }

    private static JSONArray parseCatalog(String json) {
        if (json == null || json.trim().isEmpty()) return new JSONArray();
        try {
            return new JSONArray(json);
        }
        catch (JSONException e) {
            return new JSONArray();
        }
    }

    private JSONObject fetchRelease(String tag) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(GITHUB_RELEASE_API + tag).openConnection();
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "WinXclipse-Android");
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            String etag = preferences.getString("github_release_etag_" + tag, null);
            if (etag != null) connection.setRequestProperty("If-None-Match", etag);
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                String cachedRelease = preferences.getString("github_release_json_" + tag, null);
                return cachedRelease == null ? null : new JSONObject(cachedRelease);
            }
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.w("ContentsManager", "GitHub release request failed for " + tag
                        + ": HTTP " + responseCode);
                return null;
            }
            try (InputStream input = connection.getInputStream()) {
                String releaseJson = new String(StreamUtils.copyToByteArray(input), StandardCharsets.UTF_8);
                JSONObject release = new JSONObject(releaseJson);
                SharedPreferences.Editor editor = preferences.edit()
                        .putString("github_release_json_" + tag, releaseJson);
                String responseEtag = connection.getHeaderField("ETag");
                if (responseEtag != null) editor.putString("github_release_etag_" + tag, responseEtag);
                editor.apply();
                return release;
            }
        }
        catch (Exception e) {
            Log.w("ContentsManager", "Unable to refresh GitHub release " + tag, e);
            return null;
        }
        finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void removeReleaseEntries(JSONArray catalog, String tag) {
        String marker = "/releases/download/" + tag + "/";
        for (int i = catalog.length() - 1; i >= 0; i--) {
            JSONObject profile = catalog.optJSONObject(i);
            if (profile != null && profile.optString("remoteUrl", "").contains(marker)) catalog.remove(i);
        }
    }

    private static JSONObject findProfileByUrl(JSONArray catalog, String remoteUrl) {
        for (int i = 0; i < catalog.length(); i++) {
            JSONObject profile = catalog.optJSONObject(i);
            if (profile != null && remoteUrl.equals(profile.optString("remoteUrl", ""))) {
                try {
                    return new JSONObject(profile.toString());
                }
                catch (JSONException ignored) {
                    return profile;
                }
            }
        }
        return null;
    }

    private static boolean isSupportedRemoteAsset(String name) {
        String lower = name.toLowerCase();
        boolean contentPackage = lower.endsWith(".wcp")
                || lower.endsWith(".wcp.xz")
                || lower.endsWith(".xz")
                || lower.endsWith(".tzst")
                || lower.endsWith(".zst");
        if (!contentPackage) return false;
        return !lower.contains("dxvk-1.7.1")
                && !lower.contains("stripped");
    }

    private static JSONObject inferRemoteProfile(String tag, JSONObject asset, String name,
                                                   String remoteUrl) {
        String type;
        String lower = name.toLowerCase();
        if ("runtime-fexcore-v0.8".equals(tag)) type = "FEXCore";
        else if ("box".equals(tag)) type = "Box64";
        else if ("dxvk-and-vkd3d".equals(tag)) type = lower.contains("vkd3d") ? "VKD3D" : "DXVK";
        else type = lower.contains("proton") ? "Proton" : "Wine";

        String versionName = name.replaceFirst(
                "(?i)(?:\\.wcp(?:\\.xz)?|\\.xz|\\.tzst|\\.zst)$", "");
        // Drop an embedded wine-/proton- prefix: the content type already
        // carries it, otherwise the UI shows duplicates like "Proton-proton-...".
        versionName = versionName.replaceFirst("(?i)^(?:wine|proton)-", "");
        long assetId = asset.optLong("id", 0);
        int versionCode = assetId > 0 ? (int) (assetId % 2147483646L) + 1 : Math.abs(name.hashCode());
        if (versionCode == 0) versionCode = 1;

        JSONObject profile = new JSONObject();
        try {
            profile.put("type", type);
            profile.put("verName", versionName);
            profile.put("verCode", versionCode);
            profile.put("remoteUrl", remoteUrl);
        }
        catch (JSONException ignored) {
        }
        return profile;
    }

    public void syncContents() {
        profilesMap = new HashMap<>();

        // Ensure all content types are initialized in the profilesMap
        for (ContentProfile.ContentType type : ContentProfile.ContentType.values()) {
            profilesMap.put(type, new LinkedList<>());
        }

        for (ContentProfile.ContentType type : ContentProfile.ContentType.values()) {
            List<ContentProfile> profiles = profilesMap.get(type);


            // Load local profiles
            File typeFile = getContentTypeDir(context, type);
            File[] fileList = typeFile.listFiles();
            if (fileList != null) {
                for (File file : fileList) {
                    File proFile = new File(file, PROFILE_NAME);
                    if (proFile.exists() && proFile.isFile()) {
                        ContentProfile profile = readProfile(proFile);
                        if (profile != null) {
                            profile = repairDuplicatedTypeName(profile, proFile);
                            profiles.add(profile);
                            Log.d("ContentsManager", "Local profile loaded: " + profile.verName);
                        } else {
                            Log.w("ContentsManager", "Invalid local profile at: " + proFile.getAbsolutePath());
                        }
                    }
                }
            }

            // Add remote profiles for this type
            if (remoteProfiles != null) {
                for (ContentProfile remote : remoteProfiles) {
                    if (remote.type == type) {
                        boolean exists = false;
                        for (ContentProfile profile : profiles) {
                            if (profile.verName.equals(remote.verName) && profile.verCode == remote.verCode) {
                                exists = true;
                                break;
                            }
                        }
                        if (!exists) {
                            profiles.add(remote);
                            Log.d("ContentsManager", "Remote profile added: " + remote.verName);
                        }
                    }
                }
            }
        }
    }

    public void extraContentFile(Uri uri, OnInstallFinishedCallback callback) {
        extraContentFile(uri, null, callback);
    }

    public void extraContentFile(Uri uri, ContentProfile expectedProfile,
                                 OnInstallFinishedCallback callback) {
        extraContentFile(uri, expectedProfile, callback, null);
    }

    public void extraContentFile(Uri uri, ContentProfile expectedProfile,
                                 OnInstallFinishedCallback callback,
                                 OnInstallProgressCallback progressCallback) {
        reportInstallProgress(progressCallback, 5);

        File tmpRoot = getTmpDir(context);
        if (!tmpRoot.isDirectory() && !tmpRoot.mkdirs()) {
            callback.onFailed(InstallFailedReason.ERROR_UNKNOWN, null);
            return;
        }
        // Every installation gets its own staging directory. The old shared
        // tmp directory made concurrent installs delete/corrupt each other.
        File file = new File(tmpRoot, "install-" + UUID.randomUUID());
        if (!file.mkdirs()) {
            callback.onFailed(InstallFailedReason.ERROR_UNKNOWN, null);
            return;
        }

        TarCompressorUtils.Type compression = TarCompressorUtils.detectType(context, uri);
        boolean ret = compression != null
                && TarCompressorUtils.extract(compression, context, uri, file, null,
                (read, total) -> reportInstallProgress(progressCallback,
                        total > 0 ? 5 + (int)Math.min(65, read * 65L / total) : 5));
        if (!ret) {
            failStagedInstall(callback, InstallFailedReason.ERROR_BADTAR, null, file);
            return;
        }
        reportInstallProgress(progressCallback, 70);

        File proFile = new File(file, PROFILE_NAME);
        if (!proFile.exists()) {
            /* Raw runtime builds (e.g. a Proton .tzst saved straight from the
               browser into Downloads) often ship without an embedded profile.
               Synthesize one so they install through the standard flow. */
            if (!writeSynthesizedRuntimeProfile(file, getDisplayName(context, uri))) {
                failStagedInstall(callback, InstallFailedReason.ERROR_NOPROFILE, null, file);
                return;
            }
        }

        ContentProfile profile = readProfile(proFile);
        if (profile == null) {
            failStagedInstall(callback, InstallFailedReason.ERROR_BADPROFILE, null, file);
            return;
        }
        profile.setStagingDir(file);
        reportInstallProgress(progressCallback, 78);

        if (expectedProfile != null) {
            boolean actualIsRuntime = profile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE
                    || profile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON;
            boolean expectedIsRuntime = expectedProfile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE
                    || expectedProfile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON;
            if (profile.type != expectedProfile.type && !(actualIsRuntime && expectedIsRuntime)) {
                failStagedInstall(callback, InstallFailedReason.ERROR_BADPROFILE, null, file);
                return;
            }

            // The bundled remote catalog is authoritative for identity. Some
            // older WinXclipse runtime downloads were published with Wine/0
            // metadata even though they are catalogued as Proton. Normalize
            // the extracted profile so it remains stable after app restart.
            try {
                JSONObject normalizedProfile = new JSONObject(FileUtils.readString(proFile));
                normalizedProfile.put(ContentProfile.MARK_TYPE, expectedProfile.type.toString());
                normalizedProfile.put(ContentProfile.MARK_VERSION_NAME, expectedProfile.verName);
                normalizedProfile.put(ContentProfile.MARK_VERSION_CODE, expectedProfile.verCode);
                normalizedProfile.put(ContentProfile.MARK_ID, expectedProfile.getContentId());
                if (!FileUtils.writeString(proFile, normalizedProfile.toString(2))) {
                    failStagedInstall(callback, InstallFailedReason.ERROR_BADPROFILE, null, file);
                    return;
                }
                profile.type = expectedProfile.type;
                profile.verName = expectedProfile.verName;
                profile.verCode = expectedProfile.verCode;
                profile.contentId = expectedProfile.getContentId();
            }
            catch (JSONException e) {
                failStagedInstall(callback, InstallFailedReason.ERROR_BADPROFILE, e, file);
                return;
            }
        }

        String imagefsPath = context.getFilesDir().getAbsolutePath() + "/imagefs";
        int checkedFiles = 0;
        for (ContentProfile.ContentFile contentFile : profile.fileList) {
            File tmpFile = new File(file, contentFile.source);
            if (!tmpFile.exists() || !tmpFile.isFile() || !isSubPath(file.getAbsolutePath(), tmpFile.getAbsolutePath())) {
                failStagedInstall(callback, InstallFailedReason.ERROR_MISSINGFILES, null, file);
                return;
            }

            String realPath = getPathFromTemplate(contentFile.target);
            if (!isSubPath(imagefsPath, realPath) || isSubPath(ContentsManager.getContentDir(context).getAbsolutePath(), realPath) || realPath.contains("dosdevices")) {
                failStagedInstall(callback, InstallFailedReason.ERROR_UNTRUSTPROFILE, null, file);
                return;
            }
            checkedFiles++;
            if (!profile.fileList.isEmpty()) {
                reportInstallProgress(progressCallback,
                        80 + (checkedFiles * 15 / profile.fileList.size()));
            }
        }

        if (profile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE
                || profile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON) {
            File bin = new File(file, profile.wineBinPath);
            File lib = new File(file, profile.wineLibPath);
            File cp = new File(file, profile.winePrefixPack);

            if (!bin.exists() || !bin.isDirectory() || !lib.exists() || !lib.isDirectory() || !cp.exists() || !cp.isFile()) {
                failStagedInstall(callback, InstallFailedReason.ERROR_MISSINGFILES, null, file);
                return;
            }
        }

        reportInstallProgress(progressCallback, 97);
        callback.onSucceed(profile);
    }

    private static void failStagedInstall(OnInstallFinishedCallback callback,
                                          InstallFailedReason reason, Exception error,
                                          File stagingDir) {
        if (stagingDir != null) FileUtils.delete(stagingDir);
        callback.onFailed(reason, error);
    }

    private static void reportInstallProgress(OnInstallProgressCallback callback, int progress) {
        if (callback != null) callback.onProgress(progress);
    }

    private static String getDisplayName(Context context, Uri uri) {
        if (uri == null) return "";
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            return new File(uri.getPath()).getName();
        }
        try (android.database.Cursor cursor = context.getContentResolver().query(
                uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.isEmpty()) return name;
                }
            }
        }
        catch (Exception ignored) {
        }
        return "";
    }

    /** Locates the bin/lib layout of a raw Wine/Proton tree and writes a minimal
     * content profile for it. Returns false when the archive is not a runtime. */
    private static boolean writeSynthesizedRuntimeProfile(File root, String archiveName) {
        String name = archiveName == null || archiveName.isEmpty() ? "runtime" : archiveName;
        String verName = name.replaceFirst("(?i)\\.(tar\\.xz|tar\\.zst|txz|tzst|zst|xz|zip)$", "");
        if (verName.isEmpty()) return false;

        String binPath = null;
        for (String candidate : new String[]{"files/bin", "bin"}) {
            if (containsWineLauncher(new File(root, candidate))) {
                binPath = candidate;
                break;
            }
        }
        if (binPath == null) return false;

        String libPath = null;
        for (String candidate : new String[]{"files/lib", "lib", "files/lib64", "lib64"}) {
            if (new File(root, candidate).isDirectory()) {
                libPath = candidate;
                break;
            }
        }
        if (libPath == null) return false;

        /* The prefix pack is validated as an existing file but is not consumed
           by the runtime launcher; prefer an embedded pattern archive when one
           is present and fall back to the wine binary itself. */
        String prefixPack = findFirstFile(root, ".tzst");
        if (prefixPack == null) prefixPack = findFirstFile(root, ".txz");
        if (prefixPack == null) prefixPack = binPath + "/wine";

        try {
            JSONObject profileJSONObject = new JSONObject();
            profileJSONObject.put(ContentProfile.MARK_TYPE,
                    verName.toLowerCase(java.util.Locale.ENGLISH).contains("proton")
                            ? ContentProfile.ContentType.CONTENT_TYPE_PROTON.toString()
                            : ContentProfile.ContentType.CONTENT_TYPE_WINE.toString());
            profileJSONObject.put(ContentProfile.MARK_VERSION_NAME, verName);
            profileJSONObject.put(ContentProfile.MARK_VERSION_CODE,
                    Math.abs(verName.hashCode() % 2147483646) + 1);
            profileJSONObject.put(ContentProfile.MARK_ID, ContentProfile.buildContentId(
                    verName.toLowerCase(java.util.Locale.ENGLISH).contains("proton")
                            ? ContentProfile.ContentType.CONTENT_TYPE_PROTON
                            : ContentProfile.ContentType.CONTENT_TYPE_WINE,
                    verName, Math.abs(verName.hashCode() % 2147483646) + 1));
            profileJSONObject.put(ContentProfile.MARK_DESC, "");
            profileJSONObject.put(ContentProfile.MARK_FILE_LIST, new JSONArray());

            JSONObject runtimeJSONObject = new JSONObject();
            runtimeJSONObject.put(ContentProfile.MARK_WINE_BINPATH, binPath);
            runtimeJSONObject.put(ContentProfile.MARK_WINE_LIBPATH, libPath);
            runtimeJSONObject.put(ContentProfile.MARK_WINE_PREFIX_PACK, prefixPack);
            profileJSONObject.put(ContentProfile.MARK_WINE, runtimeJSONObject);

            return FileUtils.writeString(new File(root, PROFILE_NAME), profileJSONObject.toString(2));
        }
        catch (JSONException e) {
            return false;
        }
    }

    private static boolean containsWineLauncher(File dir) {
        File[] children = dir.listFiles();
        if (children == null) return false;
        for (File child : children) {
            String childName = child.getName().toLowerCase(java.util.Locale.ENGLISH);
            if (childName.startsWith("wine") && child.isFile()) return true;
        }
        return false;
    }

    private static String findFirstFile(File root, String extension) {
        File[] children = root.listFiles();
        if (children == null) return null;
        for (File child : children) {
            if (child.isFile() && child.getName().toLowerCase(java.util.Locale.ENGLISH).endsWith(extension)) {
                return child.getName();
            }
        }
        return null;
    }

    public void finishInstallContent(ContentProfile profile, OnInstallFinishedCallback callback) {
        File installPath = getInstallDir(context, profile);
        if (installPath.exists()) {
            if (profile != null && profile.getStagingDir() != null) FileUtils.delete(profile.getStagingDir());
            callback.onFailed(InstallFailedReason.ERROR_EXIST, null);
            return;
        }

        File parent = installPath.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            if (profile != null && profile.getStagingDir() != null) FileUtils.delete(profile.getStagingDir());
            callback.onFailed(InstallFailedReason.ERROR_UNKNOWN, null);
            return;
        }

        // Do not create installPath first: File.renameTo() cannot replace an
        // existing directory. This used to make every completed extraction
        // look permanently stuck at "Installing Content".
        File stagingDir = profile != null ? profile.getStagingDir() : null;
        if (stagingDir == null || !stagingDir.isDirectory()) {
            // Compatibility with an extraction started by an older caller.
            stagingDir = getTmpDir(context);
        }
        if (!stagingDir.renameTo(installPath)) {
            FileUtils.delete(stagingDir);
            callback.onFailed(InstallFailedReason.ERROR_UNKNOWN, null);
            return;
        }

        profile.setStagingDir(null);

        callback.onSucceed(profile);
    }

    /** Removes an extracted package that the user chose not to install. */
    public void discardStagedContent(ContentProfile profile) {
        if (profile == null) return;
        File stagingDir = profile.getStagingDir();
        if (stagingDir != null) FileUtils.delete(stagingDir);
        profile.setStagingDir(null);
    }

    /** Older remote-inferred profiles kept the archive's own "proton-"/"wine-"
     *  prefix inside verName, which duplicates the content-type prefix shown in
     *  the UI ("Proton-proton-..."). Rename the install directory and rewrite
     *  profile.json so existing installs display cleanly. */
    private static ContentProfile repairDuplicatedTypeName(ContentProfile profile, File profileFile) {
        String lower = profile.verName.toLowerCase(java.util.Locale.ENGLISH);
        if (profile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON && lower.startsWith("proton-")) {
            // fall through to rename below
        }
        else if (profile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE && lower.startsWith("wine-")) {
            // fall through to rename below
        }
        else return profile;

        String fixedVerName = profile.verName.substring(lower.indexOf('-') + 1);
        if (fixedVerName.isEmpty()) return profile;

        File currentDir = profileFile.getParentFile();
        File parent = currentDir != null ? currentDir.getParentFile() : null;
        File target = parent != null
                ? new File(parent, fixedVerName + "-" + profile.verCode) : null;
        if (target == null || target.exists() || !currentDir.renameTo(target)) {
            Log.w("ContentsManager", "Could not dedupe content name '" + profile.verName + "'");
            return profile;
        }
        try {
            JSONObject obj = new JSONObject(FileUtils.readString(new File(target, PROFILE_NAME)));
            obj.put(ContentProfile.MARK_VERSION_NAME, fixedVerName);
            FileUtils.writeString(new File(target, PROFILE_NAME), obj.toString(2));
        }
        catch (JSONException ignored) {}
        Log.i("ContentsManager", "Deduped content name '" + profile.verName + "' -> '" + fixedVerName + "'");
        profile.verName = fixedVerName;
        return profile;
    }

    public ContentProfile readProfile(File file) {
        try {
            ContentProfile profile = new ContentProfile();
            JSONObject profileJSONObject = new JSONObject(FileUtils.readString(file));
            String typeName = profileJSONObject.getString(ContentProfile.MARK_TYPE);
            ContentProfile.ContentType contentType = ContentProfile.ContentType.getTypeByName(typeName);
            if (contentType == null) return null;
            String verName = profileJSONObject.getString(ContentProfile.MARK_VERSION_NAME);
            int verCode = profileJSONObject.getInt(ContentProfile.MARK_VERSION_CODE);
            String desc = profileJSONObject.getString(ContentProfile.MARK_DESC);

            JSONArray fileJSONArray = profileJSONObject.getJSONArray(ContentProfile.MARK_FILE_LIST);
            List<ContentProfile.ContentFile> fileList = new ArrayList<>();
            for (int i = 0; i < fileJSONArray.length(); i++) {
                JSONObject contentFileJSONObject = fileJSONArray.getJSONObject(i);
                ContentProfile.ContentFile contentFile = new ContentProfile.ContentFile();
                contentFile.source = contentFileJSONObject.getString(ContentProfile.MARK_FILE_SOURCE);
                contentFile.target = contentFileJSONObject.getString(ContentProfile.MARK_FILE_TARGET);
                fileList.add(contentFile);
            }
            if (contentType == ContentProfile.ContentType.CONTENT_TYPE_WINE
                    || contentType == ContentProfile.ContentType.CONTENT_TYPE_PROTON) {
                // Mali-compatible WCP packages use the "wine" block for both
                // Wine and Proton. Keep the old WinXclipse "proton" block as a
                // fallback so existing packages remain installable.
                JSONObject runtimeJSONObject = profileJSONObject.optJSONObject(ContentProfile.MARK_WINE);
                if (runtimeJSONObject == null
                        && contentType == ContentProfile.ContentType.CONTENT_TYPE_PROTON) {
                    runtimeJSONObject = profileJSONObject.optJSONObject(ContentProfile.MARK_PROTON);
                }
                if (runtimeJSONObject == null) return null;

                profile.wineLibPath = runtimeJSONObject.getString(ContentProfile.MARK_WINE_LIBPATH);
                profile.wineBinPath = runtimeJSONObject.getString(ContentProfile.MARK_WINE_BINPATH);
                profile.winePrefixPack = runtimeJSONObject.getString(ContentProfile.MARK_WINE_PREFIX_PACK);

                // Legacy aliases retained for callers or packages built around
                // the earlier WinXclipse schema.
                profile.protonLibPath = profile.wineLibPath;
                profile.protonBinPath = profile.wineBinPath;
                profile.protonPrefixPack = profile.winePrefixPack;
            }

            profile.type = contentType;
            profile.verName = verName;
            profile.verCode = verCode;
            profile.desc = desc;
            profile.fileList = fileList;
            profile.contentId = profileJSONObject.optString(ContentProfile.MARK_ID, "");
            if (profile.contentId.trim().isEmpty()) {
                profile.contentId = profile.getContentId();
                // Backfill old/bundled/third-party manifests once so every
                // installed content can be referenced by exported configs.
                profileJSONObject.put(ContentProfile.MARK_ID, profile.contentId);
                FileUtils.writeString(file, profileJSONObject.toString(2));
            }
            return profile;
        } catch (Exception e) {
            return null;
        }
    }

    public List<ContentProfile> getProfiles(ContentProfile.ContentType type) {
        if (profilesMap != null)
            return profilesMap.get(type);
        return null;
    }

    /** True only after the package has been extracted into private storage.
     * Remote catalog entries share the same profile list, so the object being
     * present does not by itself mean the runtime can be launched. */
    public boolean isInstalledProfile(ContentProfile profile) {
        if (profile == null) return false;
        File installDir = getInstallDir(context, profile);
        return installDir.isDirectory()
                && new File(installDir, PROFILE_NAME).isFile();
    }

    public static File getInstallDir(Context context, ContentProfile profile) {
        return new File(getContentTypeDir(context, profile.type), profile.verName + "-" + profile.verCode);
    }

    public static File getContentDir(Context context) {
        return new File(context.getFilesDir(), ContentDirName.CONTENT_MAIN_DIR_NAME.toString());
    }

    public static File getContentTypeDir(Context context, ContentProfile.ContentType type) {
        return new File(getContentDir(context), type.toString());
    }

    public static File getTmpDir(Context context) {
        return new File(context.getFilesDir(), "tmp/" + ContentDirName.CONTENT_MAIN_DIR_NAME);
    }

    public static File getSourceFile(Context context, ContentProfile profile, String path) {
        return new File(getInstallDir(context, profile), path);
    }

    public static void cleanTmpDir(Context context) {
        File file = getTmpDir(context);
        FileUtils.delete(file);
        file.mkdirs();
    }

    public List<ContentProfile.ContentFile> getUnTrustedContentFiles(ContentProfile profile) {
        createTrustedFilesMap();
        List<ContentProfile.ContentFile> files = new ArrayList<>();
        for (ContentProfile.ContentFile contentFile : profile.fileList) {
            if (!trustedFilesMap.get(profile.type).contains(
                    Paths.get(getPathFromTemplate(contentFile.target)).toAbsolutePath().normalize().toString()))
                files.add(contentFile);
        }
        return files;
    }

    private boolean isSubPath(String parent, String child) {
        return Paths.get(child).toAbsolutePath().normalize().startsWith(Paths.get(parent).toAbsolutePath().normalize());
    }

    private void createDirTemplateMap() {
        if (dirTemplateMap == null) {
            dirTemplateMap = new HashMap<>();
            String imagefsPath = context.getFilesDir().getAbsolutePath() + "/imagefs";
            String drivecPath = imagefsPath + "/home/xuser/.wine/drive_c";
            dirTemplateMap.put("${libdir}", imagefsPath + "/usr/lib");
            dirTemplateMap.put("${system32}", drivecPath + "/windows/system32");
            dirTemplateMap.put("${syswow64}", drivecPath + "/windows/syswow64");
            dirTemplateMap.put("${bindir}", imagefsPath + "/usr/bin");
            dirTemplateMap.put("${sharedir}", imagefsPath + "/usr/share");
        }
    }

    private void createTrustedFilesMap() {
        if (trustedFilesMap == null) {
            trustedFilesMap = new HashMap<>();
            for (ContentProfile.ContentType type : ContentProfile.ContentType.values()) {
                List<String> pathList = new ArrayList<>();
                trustedFilesMap.put(type, pathList);

                String[] paths = switch (type) {
                    case CONTENT_TYPE_DXVK -> DXVK_TRUST_FILES;
                    case CONTENT_TYPE_VKD3D -> VKD3D_TRUST_FILES;
                    case CONTENT_TYPE_BOX64 -> BOX64_TRUST_FILES;
                    case CONTENT_TYPE_WOWBOX64 -> WOWBOX64_TRUST_FILES;
                    case CONTENT_TYPE_FEXCORE -> FEXCORE_TRUST_FILES;
                    default -> new String[0];
                };
                for (String path : paths)
                    pathList.add(Paths.get(getPathFromTemplate(path)).toAbsolutePath().normalize().toString());
            }
        }
    }

    private String getPathFromTemplate(String path) {
        createDirTemplateMap();
        String realPath = path;
        for (String key : dirTemplateMap.keySet()) {
            realPath = realPath.replace(key, dirTemplateMap.get(key));
        }
        return realPath;
    }

    public void removeContent(ContentProfile profile) {
        if (profilesMap.get(profile.type).contains(profile)) {
            FileUtils.delete(getInstallDir(context, profile));
            profilesMap.get(profile.type).remove(profile);
            syncContents();
        }
    }

    public static String getEntryName(ContentProfile profile) {
        return profile.type.toString() + '-' + profile.verName + '-' + profile.verCode;
    }

    public ContentProfile getProfileByEntryName(String entryName) {
        int firstDashIndex = entryName.indexOf('-');
        int lastDashIndex = entryName.lastIndexOf('-');

        try {
            String typeName = entryName.substring(0, firstDashIndex);
            String versionName = entryName.substring(firstDashIndex + 1, lastDashIndex);
            String versionCode = entryName.substring(lastDashIndex + 1);

            for (ContentProfile profile : profilesMap.get(ContentProfile.ContentType.getTypeByName(typeName))) {
                if (versionName.equals(profile.verName) && Integer.parseInt(versionCode) == profile.verCode)
                    return profile;
            }
        } catch (Exception e) {
        }

        return null;
    }

    /**
     * Finds an installed profile by its content type and visible version name.
     * This is intentionally independent from the entry-name version code so a
     * runtime such as FEXCore can be selected as "2608" in every screen.
     */
    public ContentProfile getProfile(ContentProfile.ContentType type, String versionName) {
        ContentProfile bestMatch = null;
        List<ContentProfile> profiles = profilesMap.get(type);
        if (profiles == null || versionName == null) return null;

        for (ContentProfile profile : profiles) {
            if (versionName.equalsIgnoreCase(profile.verName)
                    && (bestMatch == null || profile.verCode > bestMatch.verCode)) {
                bestMatch = profile;
            }
        }
        return bestMatch;
    }

    public boolean applyContent(ContentProfile profile) {
        boolean success = true;
        if (profile.type != ContentProfile.ContentType.CONTENT_TYPE_WINE && profile.type != ContentProfile.ContentType.CONTENT_TYPE_PROTON) {
            for (ContentProfile.ContentFile contentFile : profile.fileList) {
                File targetFile = new File(getPathFromTemplate(contentFile.target));
                File sourceFile = new File(getInstallDir(context, profile), contentFile.source);

                if (!sourceFile.isFile()) {
                    Log.e(TAG, "applyContent: missing source file " + sourceFile.getPath());
                    success = false;
                    continue;
                }

                // Copy to a temporary sibling first so a failed copy never leaves the
                // target deleted or truncated.
                File tempFile = new File(targetFile.getParentFile(), targetFile.getName() + ".tmp");
                FileUtils.delete(tempFile);
                if (!FileUtils.copy(sourceFile, tempFile)) {
                    Log.e(TAG, "applyContent: failed to copy " + sourceFile.getPath());
                    FileUtils.delete(tempFile);
                    success = false;
                    continue;
                }

                if (targetFile.exists() && !targetFile.delete()) {
                    Log.e(TAG, "applyContent: failed to remove old target " + targetFile.getPath());
                    FileUtils.delete(tempFile);
                    success = false;
                    continue;
                }

                if (!tempFile.renameTo(targetFile)) {
                    Log.e(TAG, "applyContent: failed to move " + tempFile.getPath());
                    FileUtils.delete(tempFile);
                    success = false;
                    continue;
                }

                if (profile.type == ContentProfile.ContentType.CONTENT_TYPE_BOX64) {
                    FileUtils.chmod(targetFile, 0771);
                }
            }
        }
        else {
            // If we end up needing to inject winebus.so into user-installed contents
//            File installDir = getInstallDir(context, profile);
//            boolean arm64ec = profile.verName.contains("arm64ec");
//            File wineRoot = new File(installDir,        // root of the .wcp
//                    profile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON
//                            ? profile.protonLibPath     // “proton-…”
//                            : profile.wineLibPath       // “wine-…”
//            ).getParentFile().getParentFile().getParentFile(); // climb back to top
//
//            EvshimPatcher.patchWineTree(context, wineRoot, arm64ec);
        }
        return success;
    }
}
