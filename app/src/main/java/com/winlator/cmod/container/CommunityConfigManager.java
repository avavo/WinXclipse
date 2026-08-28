package com.winlator.cmod.container;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;

import com.winlator.cmod.BuildConfig;
import com.winlator.cmod.box86_64.Box86_64Preset;
import com.winlator.cmod.box86_64.Box86_64PresetManager;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.fexcore.FEXCorePreset;
import com.winlator.cmod.fexcore.FEXCorePresetManager;
import com.winlator.cmod.xenvironment.ImageFs;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Portable, text-based container configuration used by community configs. */
public final class CommunityConfigManager {
    public static final String FORMAT = "winxclipse-community-config";
    public static final int SCHEMA_VERSION = 1;
    private static final int MAX_CONFIG_BYTES = 5 * 1024 * 1024;

    private CommunityConfigManager() {}

    public static final class Metadata {
        public String gameName = "";
        public String device = Build.MANUFACTURER + " " + Build.MODEL;
        public String fps = "";
        public String ram = "";
        public String soc = Build.HARDWARE;
        public String gpu = "";
        public String author = "";
        public String discord = "";
        public String notes = "";
    }

    public static final class ContentResolution {
        public final JSONObject reference;
        public final ContentProfile exact;
        public final ContentProfile similar;
        public final int similarity;

        ContentResolution(JSONObject reference, ContentProfile exact,
                          ContentProfile similar, int similarity) {
            this.reference = reference;
            this.exact = exact;
            this.similar = similar;
            this.similarity = similarity;
        }

        public boolean isMissing() { return exact == null; }
        public String requestedName() {
            return reference.optString("entryName",
                    reference.optString("versionName", "Unknown"));
        }
    }

    public static JSONObject createManifest(Context context, Container container,
                                            Metadata metadata) throws JSONException {
        ContentsManager contents = new ContentsManager(context);
        contents.syncContents();

        String raw = FileUtils.readString(container.getConfigFile());
        JSONObject containerData = new JSONObject(raw == null ? "{}" : raw);
        containerData.put("wineVersion", container.getWineVersion());

        JSONArray refs = collectContentReferences(context, contents, container);
        containerData.put("contentSchema", SCHEMA_VERSION);
        containerData.put("contentRefs", refs);

        JSONObject info = new JSONObject();
        info.put("gameName", clean(metadata.gameName));
        info.put("device", clean(metadata.device));
        info.put("fps", clean(metadata.fps));
        info.put("ram", clean(metadata.ram));
        info.put("soc", clean(metadata.soc));
        info.put("gpu", clean(metadata.gpu));
        info.put("author", clean(metadata.author));
        info.put("discord", clean(metadata.discord));
        info.put("date", new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()));
        info.put("notes", clean(metadata.notes));

        JSONObject root = new JSONObject();
        root.put("format", FORMAT);
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("appVersion", BuildConfig.VERSION_NAME);
        root.put("metadata", info);
        root.put("contents", refs);
        JSONObject presets = collectCustomPresets(context, container);
        if (presets.length() > 0) root.put("presets", presets);
        root.put("container", containerData);
        return root;
    }

    public static File exportConfig(Context context, Container container, Metadata metadata)
            throws IOException, JSONException {
        JSONObject manifest = createManifest(context, container, metadata);
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "WinXclipse/CommunityConfigs");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Unable to create " + dir);

        String game = clean(metadata.gameName);
        if (game.isEmpty()) game = container.getName();
        String base = game.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        if (base.isEmpty()) base = "WinXclipse-config";
        File output = uniqueFile(dir, base + "-" + BuildConfig.VERSION_NAME, ".zip");

        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(output)))) {
            byte[] text = manifest.toString(2).getBytes(StandardCharsets.UTF_8);
            putEntry(zip, "config.txt", text);

            // Reuse the same shortcut artwork chosen by the browser/EXE/custom
            // policy, when this container already has one available.
            Shortcut artShortcut = bestShortcut(container, game);
            Bitmap cover = artShortcut != null ? artShortcut.getCoverArt() : null;
            if (cover != null) {
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                if (cover.compress(Bitmap.CompressFormat.PNG, 100, png)) {
                    putEntry(zip, "cover.png", png.toByteArray());
                }
            }
        }
        return output;
    }

    public static JSONObject readConfig(Context context, Uri uri) throws IOException, JSONException {
        try (InputStream raw = context.getContentResolver().openInputStream(uri)) {
            if (raw == null) throw new IOException("Unable to open configuration");
            BufferedInputStream buffered = new BufferedInputStream(raw);
            buffered.mark(8);
            int magic0 = buffered.read();
            int magic1 = buffered.read();
            buffered.reset();
            if (magic0 != 'P' || magic1 != 'K') {
                return validate(new JSONObject(readLimited(buffered)));
            }
            try (ZipInputStream zip = new ZipInputStream(buffered)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (!entry.isDirectory() && ("config.txt".equalsIgnoreCase(name)
                            || "manifest.json".equalsIgnoreCase(name))) {
                        return validate(new JSONObject(readLimited(zip)));
                    }
                }
            }
        }
        throw new IOException("config.txt was not found in the archive");
    }

    public static List<ContentResolution> resolveContents(Context context, JSONObject manifest)
            throws JSONException {
        ContentsManager manager = new ContentsManager(context);
        manager.syncContents();
        List<ContentProfile> installed = allProfiles(manager);
        JSONArray refs = manifest.optJSONArray("contents");
        if (refs == null) refs = manifest.getJSONObject("container").optJSONArray("contentRefs");
        List<ContentResolution> result = new ArrayList<>();
        if (refs == null) return result;

        for (int i = 0; i < refs.length(); i++) {
            JSONObject ref = refs.getJSONObject(i);
            String id = ref.optString("contentId", "");
            String type = ref.optString("type", "");
            String name = ref.optString("entryName", ref.optString("versionName", ""));
            String role = ref.optString("role", "");
            String requestedRuntimeArch = "wineRuntime".equals(role)
                    ? runtimeArchitecture(name) : "";

            // Built-in components are part of the application/imagefs, not entries in
            // ContentsManager.  Older schema-v1 exports nevertheless wrote them as
            // unresolved external content, causing a freshly exported default
            // container to ask for "none", the bundled DXVK and the bundled Proton.
            // Keep the value already stored in the container and do not resolve it.
            if (isDisabledValue(name)
                    || isBundledReference(context, ref.optString("role", ""), name)) {
                continue;
            }
            ContentProfile exact = null;
            ContentProfile similar = null;
            int best = -1;
            for (ContentProfile profile : installed) {
                if (!type.isEmpty() && !profile.type.toString().equalsIgnoreCase(type)) continue;
                if ("wineRuntime".equals(role)) {
                    if (profile.type != ContentProfile.ContentType.CONTENT_TYPE_WINE
                            && profile.type != ContentProfile.ContentType.CONTENT_TYPE_PROTON) continue;
                    String candidateArch = runtimeArchitecture(
                            ContentsManager.getEntryName(profile) + " " + profile.verName);
                    // A 32-bit/x86 runtime cannot replace ARM64EC or x86_64 (and
                    // vice versa), even when their version strings are close.
                    if (!requestedRuntimeArch.isEmpty()
                            && !requestedRuntimeArch.equals(candidateArch)) continue;
                }
                if (!id.isEmpty() && id.equals(profile.getContentId())) {
                    exact = profile;
                    break;
                }
                String entry = ContentsManager.getEntryName(profile);
                // A package imported by the user may have received its stable id
                // only after an older configuration was exported.  Exact visible
                // type/name/version is still the same content and must not prompt.
                if (normalize(entry).equals(normalize(name))
                        || normalize(profile.verName).equals(normalize(name))) {
                    exact = profile;
                    break;
                }
                int score = similarity(normalize(name), normalize(entry));
                if (score > best) {
                    best = score;
                    similar = profile;
                }
            }
            // Avoid offering a misleading replacement for unrelated names.
            if (best < 55 && !"wineRuntime".equals(role)) similar = null;
            result.add(new ContentResolution(ref, exact, similar, best));
        }
        return result;
    }

    public static JSONObject applyResolution(Context context, JSONObject manifest,
                                             List<ContentResolution> resolutions,
                                             List<ContentProfile> choices) throws IOException, JSONException {
        JSONObject data = new JSONObject(manifest.getJSONObject("container").toString());
        data.remove("id");
        data.remove("contentRefs");
        data.remove("contentSchema");
        for (int i = 0; i < resolutions.size(); i++) {
            ContentResolution resolution = resolutions.get(i);
            ContentProfile selected = choices.get(i);
            String role = resolution.reference.optString("role", "");
            if (selected == null) {
                if ("wineRuntime".equals(role)) data.put("wineVersion", WineInfo.MAIN_WINE_VERSION.identifier());
                continue;
            }
            String entry = ContentsManager.getEntryName(selected);
            if ("wineRuntime".equals(role)) data.put("wineVersion", entry);
            else if ("box64".equals(role)) data.put("box64Version", tail(entry));
            else if ("fexcore".equals(role)) data.put("fexcoreVersion", tail(entry));
            else if ("dxvk".equals(role) || "vkd3d".equals(role)) {
                String config = data.optString("dxwrapperConfig", "");
                String key = "dxvk".equals(role) ? "version" : "vkd3dVersion";
                data.put("dxwrapperConfig", replaceConfigValue(config, key, selected.verName));
            }
        }
        restoreCustomPresets(context, manifest.optJSONObject("presets"), data);
        return data;
    }

    private static JSONObject collectCustomPresets(Context context, Container container)
            throws JSONException {
        JSONObject result = new JSONObject();
        FEXCorePreset fex = FEXCorePresetManager.getPreset(context, container.getFEXCorePreset());
        if (fex != null && fex.isCustom()) {
            JSONObject data = new JSONObject();
            data.put("name", fex.name);
            data.put("envVars", FEXCorePresetManager.getEnvVars(context, fex.id).toString());
            result.put("fexcore", data);
        }
        Box86_64Preset box64 = Box86_64PresetManager.getPreset(
                "box64", context, container.getBox64Preset());
        if (box64 != null && box64.id.startsWith(Box86_64Preset.CUSTOM)) {
            JSONObject data = new JSONObject();
            data.put("name", box64.name);
            data.put("envVars", Box86_64PresetManager.getEnvVars(
                    "box64", context, box64.id).toString());
            result.put("box64", data);
        }
        return result;
    }

    private static void restoreCustomPresets(Context context, JSONObject presets,
                                             JSONObject containerData)
            throws IOException, JSONException {
        if (presets == null) return;
        JSONObject fex = presets.optJSONObject("fexcore");
        if (fex != null) {
            String id = FEXCorePresetManager.importPreset(context,
                    fex.optString("name"), fex.optString("envVars"));
            containerData.put("fexcorePreset", id);
        }
        JSONObject box64 = presets.optJSONObject("box64");
        if (box64 != null) {
            String id = Box86_64PresetManager.importPreset("box64", context,
                    box64.optString("name"), box64.optString("envVars"));
            containerData.put("box64Preset", id);
        }
    }

    private static JSONArray collectContentReferences(Context context, ContentsManager manager,
                                                       Container container)
            throws JSONException {
        JSONArray refs = new JSONArray();
        addReference(context, refs, "wineRuntime", findByEntry(manager, container.getWineVersion()), true,
                container.getWineVersion());
        addReference(context, refs, "box64", findByTail(manager, ContentProfile.ContentType.CONTENT_TYPE_BOX64,
                container.getBox64Version()), false, container.getBox64Version());
        addReference(context, refs, "fexcore", findByTail(manager, ContentProfile.ContentType.CONTENT_TYPE_FEXCORE,
                container.getFEXCoreVersion()), false, container.getFEXCoreVersion());
        String config = container.getDXWrapperConfig();
        addReference(context, refs, "dxvk", findByName(manager, ContentProfile.ContentType.CONTENT_TYPE_DXVK,
                configValue(config, "version")), false, configValue(config, "version"));
        addReference(context, refs, "vkd3d", findByName(manager, ContentProfile.ContentType.CONTENT_TYPE_VKD3D,
                configValue(config, "vkd3dVersion")), false, configValue(config, "vkd3dVersion"));
        return refs;
    }

    private static void addReference(Context context, JSONArray refs, String role,
                                     ContentProfile profile,
                                     boolean required, String fallbackName) throws JSONException {
        if (profile == null && (isDisabledValue(fallbackName)
                || isBundledReference(context, role, fallbackName))) return;
        if ((fallbackName == null || fallbackName.isEmpty()) && profile == null) return;
        JSONObject ref = new JSONObject();
        ref.put("role", role);
        ref.put("required", required);
        if (profile != null) {
            ref.put("contentId", profile.getContentId());
            ref.put("type", profile.type.toString());
            ref.put("versionName", profile.verName);
            ref.put("versionCode", profile.verCode);
            ref.put("entryName", ContentsManager.getEntryName(profile));
        } else {
            // Legacy/custom content may have no profile. Preserve its visible
            // filename so import can offer a fuzzy local replacement.
            ref.put("contentId", JSONObject.NULL);
            ref.put("type", "");
            ref.put("versionName", fallbackName);
            ref.put("versionCode", 0);
            ref.put("entryName", fallbackName);
        }
        refs.put(ref);
    }

    private static boolean isDisabledValue(String value) {
        String normalized = clean(value).toLowerCase(Locale.ENGLISH);
        return normalized.isEmpty() || "none".equals(normalized)
                || "disabled".equals(normalized) || "-- disabled --".equals(normalized);
    }

    /** Values distributed inside the base APK/imagefs and therefore portable without a content package. */
    private static boolean isBundledReference(Context context, String role, String value) {
        String normalized = clean(value);
        if (isDisabledValue(normalized)) return true;
        switch (role) {
            case "wineRuntime":
                if (WineInfo.isMainWineVersion(normalized)) return true;
                if (context != null) {
                    for (String entry : context.getResources().getStringArray(
                            com.winlator.cmod.R.array.wine_entries)) {
                        if (normalized.equalsIgnoreCase(entry)) return true;
                    }
                    File bundledRuntime = new File(ImageFs.find(context).getRootDir(),
                            "opt/" + normalized);
                    if (bundledRuntime.isDirectory()) return true;
                }
                return false;
            case "box64":
                return DefaultVersion.BOX64.equalsIgnoreCase(normalized);
            case "fexcore":
                return DefaultVersion.FEXCORE.equalsIgnoreCase(normalized);
            case "dxvk":
                return DefaultVersion.DXVK.equalsIgnoreCase(normalized);
            case "vkd3d":
                return DefaultVersion.VKD3D.equalsIgnoreCase(normalized);
            default:
                return false;
        }
    }

    private static ContentProfile findByEntry(ContentsManager manager, String entry) {
        return manager.getProfileByEntryName(entry);
    }

    private static ContentProfile findByTail(ContentsManager manager, ContentProfile.ContentType type,
                                             String value) {
        if (value == null) return null;
        List<ContentProfile> profiles = manager.getProfiles(type);
        if (profiles == null) return null;
        for (ContentProfile profile : profiles) {
            if (value.equals(profile.verName) || value.equals(tail(ContentsManager.getEntryName(profile)))) return profile;
        }
        return null;
    }

    private static ContentProfile findByName(ContentsManager manager, ContentProfile.ContentType type,
                                             String value) {
        return findByTail(manager, type, value);
    }

    private static List<ContentProfile> allProfiles(ContentsManager manager) {
        List<ContentProfile> profiles = new ArrayList<>();
        for (ContentProfile.ContentType type : ContentProfile.ContentType.values()) {
            List<ContentProfile> part = manager.getProfiles(type);
            if (part != null) profiles.addAll(part);
        }
        return profiles;
    }

    private static Shortcut bestShortcut(Container container, String gameName) {
        if (container.getManager() == null) return null;
        Shortcut best = null;
        int score = -1;
        for (Shortcut shortcut : container.getManager().loadShortcuts()) {
            if (shortcut.container.id != container.id || shortcut.getCoverArt() == null) continue;
            int current = similarity(normalize(gameName), normalize(shortcut.name));
            if (current > score) { score = current; best = shortcut; }
        }
        return best;
    }

    private static JSONObject validate(JSONObject root) throws JSONException {
        if (!FORMAT.equals(root.optString("format")) || !root.has("container")) {
            throw new JSONException("Unsupported WinXclipse configuration format");
        }
        int schema = root.optInt("schemaVersion", -1);
        if (schema < 1 || schema > SCHEMA_VERSION) {
            throw new JSONException("Unsupported community config schema: " + schema);
        }
        return root;
    }

    private static void putEntry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static String readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > MAX_CONFIG_BYTES) throw new IOException("Configuration is too large");
            output.write(buffer, 0, count);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private static File uniqueFile(File dir, String base, String extension) {
        File file = new File(dir, base + extension);
        int suffix = 2;
        while (file.exists()) file = new File(dir, base + "-" + suffix++ + extension);
        return file;
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static String tail(String entry) {
        int index = entry == null ? -1 : entry.indexOf('-');
        return index >= 0 ? entry.substring(index + 1) : entry;
    }
    private static String configValue(String config, String key) {
        if (config == null) return "";
        for (String item : config.split(",")) {
            String[] pair = item.split("=", 2);
            if (pair.length == 2 && key.equals(pair[0].trim())) return pair[1].trim();
        }
        return "";
    }
    private static String replaceConfigValue(String config, String key, String value) {
        StringBuilder out = new StringBuilder();
        boolean found = false;
        for (String item : (config == null ? "" : config).split(",")) {
            if (item.isEmpty()) continue;
            String[] pair = item.split("=", 2);
            if (out.length() > 0) out.append(',');
            if (pair.length == 2 && key.equals(pair[0].trim())) {
                out.append(key).append('=').append(value); found = true;
            } else out.append(item);
        }
        if (!found) {
            if (out.length() > 0) out.append(',');
            out.append(key).append('=').append(value);
        }
        return out.toString();
    }
    private static String normalize(String value) {
        return clean(value).toLowerCase(Locale.ENGLISH)
                .replaceAll("\\.(wcp|tzst|zip)$", "")
                .replaceAll("[^a-z0-9]+", "");
    }

    private static String runtimeArchitecture(String value) {
        String normalized = clean(value).toLowerCase(Locale.ENGLISH)
                .replace('-', '_').replace(' ', '_');
        if (normalized.contains("arm64ec") || normalized.contains("aarch64")) return "arm64ec";
        if (normalized.contains("x86_64") || normalized.contains("amd64")
                || normalized.contains("x64")) return "x86_64";
        if (normalized.matches(".*(^|_)x86($|_).*")) return "x86";
        return "";
    }
    private static int similarity(String left, String right) {
        if (left.equals(right)) return 100;
        int max = Math.max(left.length(), right.length());
        if (max == 0) return 100;
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1];
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            previous = current;
        }
        return Math.max(0, 100 - previous[right.length()] * 100 / max);
    }
}
