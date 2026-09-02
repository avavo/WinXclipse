package com.winlator.cmod.container;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import com.winlator.cmod.BuildConfig;
import com.winlator.cmod.box86_64.Box86_64Preset;
import com.winlator.cmod.box86_64.Box86_64PresetManager;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.contents.CustomWrapperManager;
import com.winlator.cmod.contents.Downloader;
import com.winlator.cmod.contents.ExternalDownloadCatalog;
import com.winlator.cmod.contents.XclipseDriverManager;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.GPUInformation;
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
import java.io.FileInputStream;
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
    private static final int MAX_COVER_BYTES = 16 * 1024 * 1024;

    private CommunityConfigManager() {}

    public static final class Metadata {
        public String gameName = "";
        public String device;
        public String model;
        public String fps = "";
        public String ram = "";
        public String soc;
        public String gpu;
        public String author = "";
        public String discord = "";
        public String notes = "";
        public String compatibility = "";

        public Metadata() {
            DeviceProfile profile = getCurrentDeviceProfile();
            device = profile.device;
            model = profile.model;
            soc = profile.soc;
            gpu = profile.gpu;
        }
    }

    public static final class DeviceProfile {
        public final String device;
        public final String model;
        public final String soc;
        public final String gpu;

        DeviceProfile(String device, String model, String soc, String gpu) {
            this.device = device;
            this.model = model;
            this.soc = soc;
            this.gpu = gpu;
        }

        public String fullLabel() {
            StringBuilder label = new StringBuilder(device);
            if (!model.isEmpty()) label.append(" | ").append(model);
            if (!soc.isEmpty()) label.append(" | ").append(soc);
            if (!gpu.isEmpty()) label.append(" | ").append(gpu);
            return label.toString();
        }
    }

    private static final DeviceProfile[] SUPPORTED_DEVICES = {
            new DeviceProfile("Galaxy S22", "SM-S901", "Exynos 2200", "Xclipse 920"),
            new DeviceProfile("Galaxy S22+", "SM-S906", "Exynos 2200", "Xclipse 920"),
            new DeviceProfile("Galaxy S22 Ultra", "SM-S908", "Exynos 2200", "Xclipse 920"),
            new DeviceProfile("Galaxy S23 FE", "SM-S711", "Exynos 2200", "Xclipse 920"),
            new DeviceProfile("Galaxy S24", "SM-S921", "Exynos 2400", "Xclipse 940"),
            new DeviceProfile("Galaxy S24+", "SM-S926", "Exynos 2400", "Xclipse 940"),
            new DeviceProfile("Galaxy S24 FE", "SM-S721", "Exynos 2400e", "Xclipse 940"),
            new DeviceProfile("Galaxy S25 FE", "SM-S731", "Exynos 2500", "Xclipse 950"),
            new DeviceProfile("Galaxy Z Flip7", "SM-F766", "Exynos 2500", "Xclipse 950"),
            new DeviceProfile("Galaxy Z Flip7 FE", "SM-F761", "Exynos 2400", "Xclipse 940"),
            new DeviceProfile("Galaxy Z Flip8", "SM-F776", "Exynos 2600", "Xclipse 960"),
            new DeviceProfile("Galaxy S26", "SM-S942", "Exynos 2600", "Xclipse 960"),
            new DeviceProfile("Galaxy S26+", "SM-S947", "Exynos 2600", "Xclipse 960"),
            new DeviceProfile("Galaxy A55 5G", "SM-A556", "Exynos 1480", "Xclipse 530"),
            new DeviceProfile("Galaxy A37 5G", "SM-A376", "Exynos 1480", "Xclipse 530"),
            new DeviceProfile("Galaxy A56 5G", "SM-A566", "Exynos 1580", "Xclipse 540"),
            new DeviceProfile("Galaxy Tab S10 FE (Wi-Fi)", "SM-X520", "Exynos 1580", "Xclipse 540"),
            new DeviceProfile("Galaxy Tab S10 FE (5G)", "SM-X526B", "Exynos 1580", "Xclipse 540"),
            new DeviceProfile("Galaxy Tab S10 FE+ (Wi-Fi)", "SM-X620", "Exynos 1580", "Xclipse 540"),
            new DeviceProfile("Galaxy Tab S10 FE+ (5G)", "SM-X626B", "Exynos 1580", "Xclipse 540"),
            new DeviceProfile("Galaxy A57 5G", "SM-A576", "Exynos 1680", "Xclipse 550")
    };

    /** Matches Samsung model prefixes while preserving the exact regional model reported by Android. */
    public static DeviceProfile getCurrentDeviceProfile() {
        String model = clean(Build.MODEL).toUpperCase(Locale.ENGLISH);
        DeviceProfile matched = getDeviceProfileForModel(model);
        if (matched != null) return new DeviceProfile(matched.device,
                model.isEmpty() ? matched.model : model, matched.soc, matched.gpu);

        String device = clean(Build.MANUFACTURER + " " + Build.MODEL);
        String soc = "";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) soc = clean(Build.SOC_MODEL);
        if (soc.isEmpty()) soc = GPUInformation.getExynosModel();
        if (soc.isEmpty()) soc = clean(Build.HARDWARE);
        String gpu = "";
        try {
            String renderer = GPUInformation.getRendererName();
            if (renderer != null && !"Unknown GPU".equalsIgnoreCase(renderer.trim()))
                gpu = renderer.replaceFirst("(?i)^Samsung\\s+", "").trim();
        }
        catch (Throwable ignored) {}
        return new DeviceProfile(device, model, soc, gpu);
    }

    /** Resolve a release/export model such as SM-X526B to its canonical device and SoC. */
    public static DeviceProfile getDeviceProfileForModel(String reportedModel) {
        String reported = clean(reportedModel).toUpperCase(Locale.ENGLISH);
        if (reported.isEmpty()) return null;
        String canonical = canonicalSamsungModel(reported);
        for (DeviceProfile profile : SUPPORTED_DEVICES) {
            String candidate = canonicalSamsungModel(profile.model);
            if (!canonical.isEmpty() && canonical.equals(candidate)) return profile;
            if (reported.contains(profile.model.toUpperCase(Locale.ENGLISH))) return profile;
        }
        // Tolerate vendor strings that omit the family letter, for example
        // "SM-926B" for the otherwise canonical SM-S926B.
        java.util.regex.Matcher shortModel = java.util.regex.Pattern
                .compile("SM-(\\d{3})").matcher(reported);
        if (shortModel.find()) {
            String digits = shortModel.group(1);
            for (DeviceProfile profile : SUPPORTED_DEVICES) {
                if (canonicalSamsungModel(profile.model).endsWith(digits)) return profile;
            }
        }
        return null;
    }

    /** Correct stale release metadata without changing the actual container settings. */
    public static void normalizeDeviceMetadata(JSONObject metadata) {
        if (metadata == null) return;
        String model = clean(metadata.optString("model"));
        if (model.isEmpty()) model = clean(metadata.optString("device"));
        DeviceProfile profile = getDeviceProfileForModel(model);
        if (profile == null) return;
        try {
            metadata.put("device", profile.device);
            metadata.put("soc", profile.soc);
            metadata.put("gpu", profile.gpu);
            String exactModel = exactSamsungModel(model);
            if (!exactModel.isEmpty()) metadata.put("model", exactModel);
        }
        catch (JSONException ignored) {}
    }

    private static String canonicalSamsungModel(String value) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("SM-[A-Z]\\d{3}", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(clean(value));
        return matcher.find() ? matcher.group().toUpperCase(Locale.ENGLISH) : "";
    }

    private static String exactSamsungModel(String value) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("SM-[A-Z]\\d{3}[A-Z0-9]*", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(clean(value));
        return matcher.find() ? matcher.group().toUpperCase(Locale.ENGLISH) : "";
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
        info.put("model", clean(metadata.model));
        info.put("fps", clean(metadata.fps));
        info.put("ram", clean(metadata.ram));
        info.put("soc", clean(metadata.soc));
        info.put("gpu", clean(metadata.gpu));
        info.put("author", clean(metadata.author));
        info.put("discord", clean(metadata.discord));
        info.put("date", new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()));
        info.put("notes", clean(metadata.notes));
        info.put("compatibility", normalizeCompatibility(metadata.compatibility));

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

    /** Stable English labels used by exports, release assets and catalog cards. */
    public static String normalizeCompatibility(String value) {
        String normalized = clean(value).toLowerCase(Locale.ENGLISH)
                .replace('_', ' ').replace('-', ' ').replaceAll("\\s+", " ");
        switch (normalized) {
            case "boots but crashes":
            case "boots then crashes":
            case "crashes":
                return "Boots but crashes";
            case "menu only":
            case "boots to menu":
                return "Menu only";
            case "playable":
            default:
                // Schema-v1 configs predate this field. They were published as
                // working presets, so retain Playable as their compatibility label.
                return "Playable";
        }
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
        String model = clean(metadata.model).replaceAll("[^A-Za-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        File output = uniqueFile(dir, base + (model.isEmpty() ? "" : "-" + model), ".zip");

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
        if (uri != null && "file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
            return readConfig(new File(uri.getPath()));
        }
        try (InputStream raw = context.getContentResolver().openInputStream(uri)) {
            if (raw == null) throw new IOException("Unable to open configuration");
            return readConfig(raw);
        }
    }

    public static JSONObject readConfig(File file) throws IOException, JSONException {
        try (InputStream raw = new FileInputStream(file)) {
            return readConfig(raw);
        }
    }

    private static JSONObject readConfig(InputStream raw) throws IOException, JSONException {
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
        throw new IOException("config.txt was not found in the archive");
    }

    /** Extracts the preferred embedded cover, or the first valid image in the ZIP. */
    public static boolean extractEmbeddedCover(File archive, File output) {
        byte[] fallback = null;
        try (ZipInputStream zip = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || !isImageName(entry.getName())) continue;
                byte[] bytes = readLimited(zip, MAX_COVER_BYTES);
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                        || bounds.outWidth > 8192 || bounds.outHeight > 8192) continue;
                String lower = new File(entry.getName()).getName().toLowerCase(Locale.ENGLISH);
                if (lower.startsWith("cover.") || lower.startsWith("artwork.")) {
                    return writeCover(output, bytes);
                }
                if (fallback == null) fallback = bytes;
            }
        } catch (Exception ignored) {}
        return fallback != null && writeCover(output, fallback);
    }

    public static List<ContentResolution> resolveContents(Context context, JSONObject manifest)
            throws JSONException {
        // Schema-v1 exports already contain the selected external Xclipse
        // driver in graphicsDriverConfig, but did not create a ContentProfile
        // reference for it. Recover that existing information before the new
        // container is created, so old published ZIPs do not need re-exporting.
        ensureGraphicsDriver(context, manifest);
        ensureWrapper(context, manifest);
        ContentsManager manager = new ContentsManager(context);
        try {
            manager.syncContents();
        }
        catch (RuntimeException error) {
            // syncContents initializes every profile bucket before reading disk,
            // so resolution can still continue and offer fallbacks when one
            // locally installed profile is malformed.
            Log.w("CommunityConfig", "Could not read all installed content", error);
        }
        JSONArray refs = manifest.optJSONArray("contents");
        if (refs == null) refs = manifest.getJSONObject("container").optJSONArray("contentRefs");
        List<ContentResolution> result = new ArrayList<>();
        if (refs == null) return result;

        // The installed profiles on disk are authoritative. If an exact package
        // is missing, recover a matching WCP the user already downloaded before
        // showing a missing-content prompt.
        try {
            installExactPackagesFromDownloads(context, manager, refs);
            manager.syncContents();
        }
        catch (RuntimeException error) {
            // A corrupt/unreadable WCP in Downloads must not make a valid
            // community configuration itself look invalid. Missing content is
            // handled by the normal resolver dialog below.
            Log.w("CommunityConfig", "Could not recover content from Downloads", error);
            try {
                manager.syncContents();
            }
            catch (RuntimeException syncError) {
                Log.w("CommunityConfig", "Could not refresh installed content", syncError);
            }
        }
        List<ContentProfile> installed = allInstalledProfiles(manager);

        for (int i = 0; i < refs.length(); i++) {
            JSONObject ref = refs.optJSONObject(i);
            if (ref == null) continue;
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
                if (!isCompatibleProfile(profile, type, role)) continue;
                if ("wineRuntime".equals(role)) {
                    String candidateArch = runtimeArchitecture(
                            ContentsManager.getEntryName(profile) + " " + profile.verName);
                    // A 32-bit/x86 runtime cannot replace ARM64EC or x86_64 (and
                    // vice versa), even when their version strings are close.
                    if (!requestedRuntimeArch.isEmpty()
                            && !requestedRuntimeArch.equals(candidateArch)) continue;
                }
                String entry = ContentsManager.getEntryName(profile);
                // A package imported by the user may have received its stable id
                // only after an older configuration was exported.  Exact visible
                // type/name/version is still the same content and must not prompt.
                if ((!id.isEmpty() && id.equals(profile.getContentId()))
                        || matchesReference(profile, ref)) {
                    exact = profile;
                    break;
                }
                int score = similarity(contentIdentity(name), contentIdentity(entry));
                if (score > best) {
                    best = score;
                    similar = profile;
                }
            }
            result.add(new ContentResolution(ref, exact, similar, best));
        }
        return result;
    }

    private static void ensureGraphicsDriver(Context context, JSONObject manifest) {
        JSONObject data = manifest.optJSONObject("container");
        if (data == null) return;
        String driverConfig = data.optString("graphicsDriverConfig", "");
        String requested = semicolonConfigValue(driverConfig, "version");
        if (isDisabledValue(requested)
                || DefaultVersion.WRAPPER.equalsIgnoreCase(requested)) return;

        XclipseDriverManager driverManager = new XclipseDriverManager(context);
        String installedId = driverManager.findInstalledDriverId(requested);
        if (installedId.isEmpty()) {
            File downloads = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            List<File> archives = new ArrayList<>();
            collectDriverPackages(downloads, 0, new int[]{0}, archives);
            File best = bestDriverPackage(requested, archives);
            if (best != null) {
                installedId = driverManager.installDriver(Uri.fromFile(best));
                if (installedId.isEmpty()) {
                    installedId = driverManager.findInstalledDriverId(requested);
                }
            }
        }

        if (installedId.isEmpty()) {
            ExternalDownloadCatalog catalog = new ExternalDownloadCatalog(context);
            ExternalDownloadCatalog.Item best = bestRemoteDriver(
                    requested, catalog.refreshDrivers());
            if (best != null && best.url != null && !best.url.isEmpty()) {
                File downloaded = new File(context.getCacheDir(),
                        "community-driver-" + Integer.toHexString(best.url.hashCode()) + ".zip");
                try {
                    if ((downloaded.isFile() || Downloader.downloadFile(best.url, downloaded))) {
                        installedId = driverManager.installDriver(Uri.fromFile(downloaded));
                        if (installedId.isEmpty()) {
                            installedId = driverManager.findInstalledDriverId(requested);
                        }
                    }
                }
                finally {
                    FileUtils.delete(downloaded);
                }
            }
        }

        if (!installedId.isEmpty()) {
            try {
                data.put("graphicsDriverConfig", replaceSemicolonConfigValue(
                        driverConfig, "version", installedId));
                Log.i("CommunityConfig", "Recovered Xclipse driver " + requested
                        + " as " + installedId);
            }
            catch (JSONException ignored) {}
        }
        else {
            Log.w("CommunityConfig", "Could not automatically recover Xclipse driver "
                    + requested);
        }
    }

    /**
     * Restores a downloadable wrapper referenced by old community configs.
     * The wrapper selection already lives in the container JSON, so installing
     * its archive under the same stable id is enough; configs never need to be
     * exported again just because a wrapper moved out of the APK selector.
     */
    private static void ensureWrapper(Context context, JSONObject manifest) {
        JSONObject data = manifest.optJSONObject("container");
        if (data == null) return;
        String requested = Container.normalizeGraphicsDriver(
                data.optString("graphicsDriver", Container.DEFAULT_GRAPHICS_DRIVER));
        if (!requested.startsWith("wrapper-") || isBundledWrapper(requested)) return;

        CustomWrapperManager manager = new CustomWrapperManager(context);
        String requestedId = CustomWrapperManager.toIdentifier(requested);
        if (manager.getInstalledIds().contains(requestedId)) return;

        File downloads = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        List<File> archives = new ArrayList<>();
        collectDriverPackages(downloads, 0, new int[]{0}, archives);
        for (File archive : archives) {
            String lower = archive.getName().toLowerCase(Locale.ENGLISH);
            if (!lower.endsWith(".tzst")) continue;
            String candidate = CustomWrapperManager.toIdentifier(
                    ExternalDownloadCatalog.stripPackageSuffix(archive.getName()));
            if (!requestedId.equals(candidate)) continue;
            if (manager.install(Uri.fromFile(archive), requested) != null) return;
        }

        ExternalDownloadCatalog catalog = new ExternalDownloadCatalog(context);
        ExternalDownloadCatalog.Item match = null;
        for (ExternalDownloadCatalog.Item item : catalog.refreshWrappers()) {
            if (requestedId.equals(CustomWrapperManager.toIdentifier(item.name))) {
                match = item;
                break;
            }
        }
        if (match == null || match.url == null || match.url.isEmpty()) {
            Log.w("CommunityConfig", "Wrapper not available in catalog: " + requested);
            return;
        }

        File downloaded = new File(context.getCacheDir(),
                "community-wrapper-" + Integer.toHexString(match.url.hashCode()) + ".tzst");
        try {
            if ((downloaded.isFile() || Downloader.downloadFile(match.url, downloaded))
                    && manager.install(Uri.fromFile(downloaded), requested) != null) {
                Log.i("CommunityConfig", "Recovered wrapper " + requestedId);
            }
        }
        finally {
            FileUtils.delete(downloaded);
        }
    }

    private static boolean isBundledWrapper(String wrapper) {
        switch (wrapper.toLowerCase(Locale.ENGLISH)) {
            case "wrapper-default":
            case "wrapper-cmod-v1":
            case "wrapper-kirimu":
                return true;
            default:
                return false;
        }
    }

    private static void collectDriverPackages(File directory, int depth, int[] visited,
                                              List<File> output) {
        if (directory == null || depth > 3 || visited[0] >= 800
                || output.size() >= 200 || !directory.isDirectory()) return;
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (++visited[0] > 800 || output.size() >= 200) return;
            if (file.isDirectory()) collectDriverPackages(file, depth + 1, visited, output);
            else if (file.getName().toLowerCase(Locale.ENGLISH)
                    .matches(".*\\.(?:zip|tzst)$")) output.add(file);
        }
    }

    private static File bestDriverPackage(String requested, List<File> packages) {
        File best = null;
        int bestScore = -1;
        for (File candidate : packages) {
            int score = XclipseDriverManager.driverMatchScore(requested,
                    ExternalDownloadCatalog.stripPackageSuffix(candidate.getName()));
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return bestScore >= 82 ? best : null;
    }

    private static ExternalDownloadCatalog.Item bestRemoteDriver(
            String requested, List<ExternalDownloadCatalog.Item> items) {
        ExternalDownloadCatalog.Item best = null;
        int bestScore = -1;
        for (ExternalDownloadCatalog.Item item : items) {
            int score = XclipseDriverManager.driverMatchScore(requested, item.name);
            if (score > bestScore) {
                bestScore = score;
                best = item;
            }
        }
        return bestScore >= 82 ? best : null;
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
                if ("wineRuntime".equals(role)) {
                    data.put("wineVersion", WineInfo.MAIN_WINE_VERSION.identifier());
                }
                else if ("box64".equals(role)) {
                    data.put("box64Version", DefaultVersion.BOX64);
                }
                else if ("fexcore".equals(role)) {
                    data.put("fexcoreVersion", DefaultVersion.FEXCORE);
                }
                else if ("dxvk".equals(role) || "vkd3d".equals(role)) {
                    String config = data.optString("dxwrapperConfig", "");
                    String key = "dxvk".equals(role) ? "version" : "vkd3dVersion";
                    String fallback = "dxvk".equals(role) ? DefaultVersion.DXVK : DefaultVersion.VKD3D;
                    data.put("dxwrapperConfig", replaceConfigValue(config, key, fallback));
                }
                continue;
            }
            String entry = ContentsManager.getEntryName(selected);
            if ("wineRuntime".equals(role)) data.put("wineVersion", entry);
            else if ("box64".equals(role)) data.put("box64Version", tail(entry));
            else if ("fexcore".equals(role)) data.put("fexcoreVersion", tail(entry));
            else if ("dxvk".equals(role) || "vkd3d".equals(role)) {
                String config = data.optString("dxwrapperConfig", "");
                String key = "dxvk".equals(role) ? "version" : "vkd3dVersion";
                // Keep the installed profile's versionCode in the selector. It
                // disambiguates duplicate versions and lets VKD3D resolve the
                // profile instead of falling through to a nonexistent APK asset.
                data.put("dxwrapperConfig", replaceConfigValue(config, key, tail(entry)));
            }
        }
        clearImportedPrefixState(data);
        restoreCustomPresets(context, manifest.optJSONObject("presets"), data);
        return data;
    }

    /** A new prefix must never inherit "already applied" cache flags from the exporter. */
    private static void clearImportedPrefixState(JSONObject data) {
        JSONObject extra = data.optJSONObject("extraData");
        if (extra == null) return;
        String[] transientKeys = {
                "appVersion", "imgVersion", "dxwrapper", "ddrawrapper", "wincomponents",
                "desktopTheme", "audioDriver", "startupSelectionApplied", "wfmFixVersion",
                "controllerFixVersion", "arm64ecInputDllsVersion", "lastInstalledMainWrapper",
                "lastInstalledMainWrapperRevision", "graphicsDriver", "fexcoreVersion",
                "box64Version", "commonPatchRevision"
        };
        for (String key : transientKeys) extra.remove(key);
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
                return sameContentVersion(normalized, DefaultVersion.BOX64);
            case "fexcore":
                return sameContentVersion(normalized, DefaultVersion.FEXCORE);
            case "dxvk":
                return sameContentVersion(normalized, DefaultVersion.DXVK);
            case "vkd3d":
                return sameContentVersion(normalized, DefaultVersion.VKD3D);
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

    private static List<ContentProfile> allInstalledProfiles(ContentsManager manager) {
        List<ContentProfile> profiles = new ArrayList<>();
        for (ContentProfile.ContentType type : ContentProfile.ContentType.values()) {
            List<ContentProfile> part = manager.getProfiles(type);
            if (part == null) continue;
            for (ContentProfile profile : part) {
                // Ignore one malformed local profile instead of aborting the
                // import of an otherwise valid configuration.
                if (profile == null || profile.type == null) continue;
                try {
                    if (manager.isInstalledProfile(profile)) profiles.add(profile);
                }
                catch (RuntimeException error) {
                    Log.w("CommunityConfig", "Ignoring invalid installed content profile", error);
                }
            }
        }
        return profiles;
    }

    private static boolean isCompatibleProfile(ContentProfile profile, String type, String role) {
        if (profile == null || profile.type == null) return false;
        if (!type.isEmpty()) {
            if (profile.type.toString().equalsIgnoreCase(type)) return true;
            return "wineRuntime".equals(role)
                    && (profile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE
                    || profile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON)
                    && ("Wine".equalsIgnoreCase(type) || "Proton".equalsIgnoreCase(type));
        }
        switch (role) {
            case "wineRuntime":
                return profile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE
                        || profile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON;
            case "box64":
                return profile.type == ContentProfile.ContentType.CONTENT_TYPE_BOX64;
            case "fexcore":
                return profile.type == ContentProfile.ContentType.CONTENT_TYPE_FEXCORE;
            case "dxvk":
                return profile.type == ContentProfile.ContentType.CONTENT_TYPE_DXVK;
            case "vkd3d":
                return profile.type == ContentProfile.ContentType.CONTENT_TYPE_VKD3D;
            default:
                return false;
        }
    }

    private static void installExactPackagesFromDownloads(Context context, ContentsManager manager,
                                                           JSONArray refs) {
        File downloads = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        if (!downloads.isDirectory()) return;
        List<File> packages = new ArrayList<>();
        collectContentPackages(downloads, 0, new int[]{0}, packages);
        if (packages.isEmpty()) return;

        for (int index = 0; index < refs.length(); index++) {
            JSONObject ref = refs.optJSONObject(index);
            if (ref == null) continue;
            String role = ref.optString("role", "");
            String requested = ref.optString("entryName", ref.optString("versionName", ""));
            if (isDisabledValue(requested) || isBundledReference(context, role, requested)
                    || findExactInstalled(manager, ref) != null) continue;

            File bestFile = null;
            int bestScore = -1;
            for (File candidate : packages) {
                int score = packageNameScore(ref, candidate.getName());
                if (score > bestScore) {
                    bestScore = score;
                    bestFile = candidate;
                }
            }
            // Avoid unpacking unrelated archives. Exact/reordered package names
            // normally score 100; 72 still tolerates a type prefix or build code.
            if (bestFile == null || bestScore < 72) continue;
            if (installMatchingPackage(context, manager, ref, bestFile)) {
                manager.syncContents();
                packages.remove(bestFile);
            }
        }
    }

    private static void collectContentPackages(File directory, int depth, int[] visited,
                                               List<File> output) {
        if (depth > 3 || visited[0] >= 800 || output.size() >= 200) return;
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (++visited[0] > 800 || output.size() >= 200) return;
            if (file.isDirectory()) {
                collectContentPackages(file, depth + 1, visited, output);
            }
            else if (isContentPackage(file.getName())) {
                output.add(file);
            }
        }
    }

    private static boolean isContentPackage(String name) {
        String lower = clean(name).toLowerCase(Locale.ENGLISH);
        return lower.endsWith(".wcp") || lower.endsWith(".wcp.xz")
                || lower.endsWith(".wcp.zst") || lower.endsWith(".tzst")
                || lower.endsWith(".tar.xz") || lower.endsWith(".tar.zst");
    }

    private static int packageNameScore(JSONObject ref, String fileName) {
        String requested = contentIdentity(ref.optString("entryName",
                ref.optString("versionName", "")));
        String candidate = contentIdentity(stripPackageExtension(fileName));
        if (requested.isEmpty() || candidate.isEmpty()) return -1;
        if (requested.equals(candidate)) return 100;
        if (requested.contains(candidate) || candidate.contains(requested)) return 92;
        return similarity(requested, candidate);
    }

    private static String stripPackageExtension(String name) {
        return clean(name).replaceFirst("(?i)\\.(?:wcp(?:\\.(?:xz|zst))?|t?zst|tar\\.(?:xz|zst))$", "");
    }

    private static String contentIdentity(String value) {
        String result = clean(value).toLowerCase(Locale.ENGLISH)
                .replaceFirst("(?i)\\.(?:wcp(?:\\.(?:xz|zst))?|t?zst|tar\\.(?:xz|zst)|zip)$", "")
                .replaceFirst("^(?:wine|proton|dxvk|vkd3d|box64|fexcore)[\\s._-]+", "")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        // ContentsManager entry names append a numeric versionCode that package
        // filenames and versionName fields do not necessarily contain.
        return result.replaceFirst("-[0-9]{1,10}$", "");
    }

    private static boolean sameContentVersion(String left, String right) {
        return clean(left).equalsIgnoreCase(clean(right))
                || contentIdentity(left).equals(contentIdentity(right));
    }

    private static ContentProfile findExactInstalled(ContentsManager manager, JSONObject ref) {
        for (ContentProfile profile : allInstalledProfiles(manager)) {
            if (!isCompatibleProfile(profile, ref.optString("type", ""),
                    ref.optString("role", ""))) continue;
            if (matchesReference(profile, ref)) return profile;
        }
        return null;
    }

    private static boolean matchesReference(ContentProfile profile, JSONObject ref) {
        String id = ref.optString("contentId", "");
        if (!id.isEmpty() && id.equals(profile.getContentId())) return true;
        String requested = contentIdentity(ref.optString("entryName",
                ref.optString("versionName", "")));
        return requested.equals(contentIdentity(ContentsManager.getEntryName(profile)))
                || requested.equals(contentIdentity(profile.verName));
    }

    private static boolean installMatchingPackage(Context context, ContentsManager manager,
                                                  JSONObject ref, File packageFile) {
        final boolean[] installed = {false};
        manager.extraContentFile(Uri.fromFile(packageFile),
                new ContentsManager.OnInstallFinishedCallback() {
                    private boolean staged = true;

                    @Override
                    public void onFailed(ContentsManager.InstallFailedReason reason, Exception error) {
                        if (reason != ContentsManager.InstallFailedReason.ERROR_EXIST) {
                            Log.w("CommunityConfig", "Could not recover " + packageFile
                                    + " from Downloads: " + reason, error);
                        }
                    }

                    @Override
                    public void onSucceed(ContentProfile profile) {
                        if (staged) {
                            staged = false;
                            if (!isCompatibleProfile(profile, ref.optString("type", ""),
                                    ref.optString("role", "")) || !matchesReference(profile, ref)) {
                                manager.discardStagedContent(profile);
                                return;
                            }
                            manager.finishInstallContent(profile, this);
                        }
                        else installed[0] = true;
                    }
                });
        return installed[0];
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
        return new String(readLimited(input, MAX_CONFIG_BYTES), StandardCharsets.UTF_8);
    }

    private static byte[] readLimited(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > maximum) throw new IOException("Archive entry is too large");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static boolean isImageName(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ENGLISH);
        return lower.endsWith(".png") || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg") || lower.endsWith(".webp")
                || lower.endsWith(".bmp");
    }

    private static boolean writeCover(File output, byte[] bytes) {
        try {
            File parent = output.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
            try (FileOutputStream stream = new FileOutputStream(output)) {
                stream.write(bytes);
            }
            return true;
        } catch (IOException ignored) {
            return false;
        }
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
    private static String semicolonConfigValue(String config, String key) {
        if (config == null) return "";
        for (String item : config.split(";")) {
            String[] pair = item.split("=", 2);
            if (pair.length == 2 && key.equals(pair[0].trim())) return pair[1].trim();
        }
        return "";
    }
    private static String replaceSemicolonConfigValue(String config, String key, String value) {
        StringBuilder out = new StringBuilder();
        boolean found = false;
        for (String item : (config == null ? "" : config).split(";")) {
            if (item.isEmpty()) continue;
            String[] pair = item.split("=", 2);
            if (out.length() > 0) out.append(';');
            if (pair.length == 2 && key.equals(pair[0].trim())) {
                out.append(key).append('=').append(value);
                found = true;
            }
            else out.append(item);
        }
        if (!found) {
            if (out.length() > 0) out.append(';');
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
