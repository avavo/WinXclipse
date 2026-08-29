package com.winlator.cmod.contents;

import android.content.res.AssetManager;
import android.net.Uri;

import android.content.Context;
import android.util.Log;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.contentdialog.GraphicsDriverConfigDialog;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.xenvironment.ImageFs;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

public class XclipseDriverManager {

    private final File driverContentDir;
    private final Context context;

    public XclipseDriverManager(Context context) {
        this.context = context;
        File contentsDir = new File(context.getFilesDir(), "imagefs/contents");
        this.driverContentDir = new File(contentsDir, "xclipse-drivers");
        if (!driverContentDir.exists()) driverContentDir.mkdirs();
        migrateInstalledDriverPackages(contentsDir);
    }

    /**
     * Preserve compatible driver packages installed by an older application
     * build without depending on its directory or implementation name.
     */
    private void migrateInstalledDriverPackages(File contentsDir) {
        File[] roots = contentsDir.listFiles(File::isDirectory);
        if (roots == null) return;
        for (File root : roots) {
            if (root.equals(driverContentDir)) continue;
            File[] packages = root.listFiles(file -> file.isDirectory()
                    && new File(file, "meta.json").isFile());
            if (packages == null) continue;
            for (File packageDir : packages) {
                try {
                    JSONObject profile = new JSONObject(
                            FileUtils.readString(new File(packageDir, "meta.json")));
                    String libraryName = profile.optString("libraryName", "").trim();
                    if (libraryName.isEmpty() || !new File(packageDir, libraryName).isFile()) continue;
                    File destination = new File(driverContentDir, packageDir.getName());
                    if (!destination.exists() && !FileUtils.copy(packageDir, destination)) {
                        FileUtils.delete(destination);
                    }
                }
                catch (Exception e) {
                    Log.w("XclipseDriverManager",
                            "Ignoring incompatible legacy driver package " + packageDir.getName(), e);
                }
            }
        }
    }

    public String getLibraryName(String driverId) {
        String libraryName = "";
        File driverPath = new File(driverContentDir, driverId);
        try {
            File metaProfile = new File(driverPath, "meta.json");
            JSONObject jsonObject = new JSONObject(FileUtils.readString(metaProfile));
            libraryName = jsonObject.getString("libraryName");
        }
        catch (JSONException e) {
        }
        return libraryName;
    }

    public String getDriverName(String driverId) {
        String driverName = "";
        File driverPath = new File(driverContentDir, driverId);
        try {
            File metaProfile = new File(driverPath, "meta.json");
            JSONObject jsonObject = new JSONObject(FileUtils.readString(metaProfile));
            driverName = jsonObject.getString("name");
        }
        catch (JSONException e) {
        }
        return driverName;
    }

    public String getDriverVersion(String driverId) {
        String driverVersion = "";
        File driverPath = new File(driverContentDir, driverId);
        try {
            File metaProfile = new File(driverPath, "meta.json");
            JSONObject jsonObject = new JSONObject(FileUtils.readString(metaProfile));
            driverVersion = jsonObject.getString("driverVersion");
        }
        catch (JSONException e) {
        }
        return driverVersion;
    }

    private void reloadContainers(String driverId) {
        ContainerManager containerManager = new ContainerManager(context);
        String driverName = getDriverName(driverId);
        if (driverName == null || driverName.isEmpty()) driverName = driverId;
        for (Container container : containerManager.getContainers()) {
            HashMap<String, String> config = GraphicsDriverConfigDialog.parseGraphicsDriverConfig(container.getGraphicsDriverConfig());
            String selectedVersion = config.getOrDefault("version", "");
            Log.d("XclipseDriverManager", "Checking if container driver version " + selectedVersion + " matches " + driverName);
            if (selectedVersion.toLowerCase(Locale.ENGLISH).contains(driverName.toLowerCase(Locale.ENGLISH))) {
                Log.d("XclipseDriverManager", "Found a match for container " + container.getName());
                config.put("version", DefaultVersion.WRAPPER);
                container.setGraphicsDriverConfig(GraphicsDriverConfigDialog.toGraphicsDriverConfig(config));
                container.saveData();
            }
        }
        for (Shortcut shortcut : containerManager.loadShortcuts()) {
            HashMap<String, String> config = GraphicsDriverConfigDialog.parseGraphicsDriverConfig(shortcut.getExtra("graphicsDriverConfig", shortcut.container.getGraphicsDriverConfig()));
            String selectedVersion = config.getOrDefault("version", "");
            Log.d("XclipseDriverManager", "Checking if shortcut driver version " + selectedVersion + " matches " + driverName);
            if (selectedVersion.toLowerCase(Locale.ENGLISH).contains(driverName.toLowerCase(Locale.ENGLISH))) {
                Log.d("XclipseDriverManager", "Found a match for shortcut " + shortcut.name);
                config.put("version", DefaultVersion.WRAPPER);
                shortcut.putExtra("graphicsDriverConfig", GraphicsDriverConfigDialog.toGraphicsDriverConfig(config));
                shortcut.saveData();
            }
        }
    }

    public void removeDriver(String driverId) {
        Log.d("XclipseDriverManager", "Removing driver " + driverId);
        File driverPath = new File(driverContentDir, driverId);
        reloadContainers(driverId);
        FileUtils.delete(driverPath);
    }

    public ArrayList<String> enumerateInstalledDrivers() {
        ArrayList<String> driversList = new ArrayList<>();

        File[] files = driverContentDir.listFiles();
        if (files == null) return driversList;
        for (File f : files) {
            boolean fromResources = isFromResources("graphics_driver/xclipse-" + f.getName() + ".tzst");
            if (!"tmp".equals(f.getName()) && !fromResources && new File(f, "meta.json").exists())
                driversList.add(f.getName());
        }
        return driversList;
    }

    /**
     * Resolves the portable name stored in a community config to the local
     * directory id generated by the driver installer. Older exports used a
     * mixture of labels ("Xclipse 920 Old"), archive names
     * ("XclipseOld920") and ids, so compare all of them without depending on
     * word order.
     */
    public String findInstalledDriverId(String requested) {
        if (requested == null || requested.trim().isEmpty()
                || DefaultVersion.WRAPPER.equalsIgnoreCase(requested.trim())) return "";
        String bestId = "";
        int bestScore = -1;
        for (String id : enumerateInstalledDrivers()) {
            int score = Math.max(driverMatchScore(requested, id),
                    Math.max(driverMatchScore(requested, getDriverName(id)),
                            driverMatchScore(requested,
                                    getDriverName(id) + " " + getDriverVersion(id))));
            if (score > bestScore) {
                bestScore = score;
                bestId = id;
            }
        }
        return bestScore >= 82 ? bestId : "";
    }

    public static int driverMatchScore(String requested, String candidate) {
        String left = driverIdentity(requested);
        String right = driverIdentity(candidate);
        if (left.isEmpty() || right.isEmpty()) return -1;
        if (left.equals(right)) return 100;
        if (left.contains(right) || right.contains(left)) return 92;

        int max = Math.max(left.length(), right.length());
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1];
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1,
                        previous[j] + 1), previous[j - 1] + cost);
            }
            previous = current;
        }
        return Math.max(0, 100 - previous[right.length()] * 100 / max);
    }

    private static String driverIdentity(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ENGLISH)
                .replaceFirst("(?i)\\.(?:zip|tzst|zst|xz)$", "")
                .replaceAll("(?:xclipse|vulkan|driver|samsung|mali)", " ")
                .replaceAll("([a-z])([0-9])", "$1 $2")
                .replaceAll("([0-9])([a-z])", "$1 $2")
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        if (normalized.isEmpty()) return "";
        String[] tokens = normalized.split("\\s+");
        Arrays.sort(tokens);
        return String.join("", tokens);
    }

    private boolean isFromResources(String driver) {
        AssetManager am = context.getResources().getAssets();
        InputStream is = null;
        boolean isFromResources = true;

        try {
            is = am.open(driver);
            is.close();
        }
        catch (IOException e) {
            isFromResources = false;
        }

        return isFromResources;
    }

    private boolean extractDriverFromResources(String driverId) {
        String src = "graphics_driver/xclipse-" + driverId + ".tzst";
        boolean hasExtracted;

        File dst = new File(driverContentDir, driverId);
        if (dst.exists())
            return true;

        dst.mkdirs();
        Log.d("XclipseDriverManager", "Extracting " + src + " to " + dst.getAbsolutePath());
        hasExtracted = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, src, dst);

        if (!hasExtracted)
            dst.delete();

        return hasExtracted;
    }

    public String installDriver(Uri driverUri) {
        return installDriver(driverUri, null);
    }

    public String installDriver(Uri driverUri, Downloader.ProgressCallback progressCallback) {
        File tmpDir = new File(driverContentDir, "tmp");
        FileUtils.delete(tmpDir);
        tmpDir.mkdirs();
        File archive = new File(driverContentDir, ".driver-install.zip");
        FileUtils.delete(archive);

        try {
            reportProgress(progressCallback, 5);
            File directSource = driverUri.getPath() == null ? null : new File(driverUri.getPath());
            boolean copied = directSource != null && directSource.isFile()
                    ? FileUtils.copy(directSource, archive)
                    : FileUtils.copy(context, driverUri, archive, null);
            if (!copied) return "";
            reportProgress(progressCallback, 25);
            if (!TarCompressorUtils.extractZip(archive, tmpDir)) return "";
            reportProgress(progressCallback, 70);

            File packageDir = findPackageRoot(tmpDir);
            File meta = new File(packageDir, "meta.json");
            if (!meta.isFile()) return "";
            JSONObject profile = new JSONObject(FileUtils.readString(meta));
            String displayName = profile.optString("name", "").trim();
            String libraryName = profile.optString("libraryName", "").trim();
            if (displayName.isEmpty() || libraryName.isEmpty()
                    || !new File(packageDir, libraryName).isFile()) return "";
            reportProgress(progressCallback, 82);

            String id = displayName.toLowerCase(Locale.ENGLISH)
                    .replaceAll("[^a-z0-9._-]+", "-")
                    .replaceAll("-+", "-")
                    .replaceAll("^-|-$", "");
            if (id.isEmpty()) return "";
            File destination = new File(driverContentDir, id);
            // Re-importing an already installed package is success. Returning
            // an empty id here made automatic Community Config recovery report
            // failure even though the exact driver was ready on disk.
            if (destination.isDirectory()
                    && new File(destination, "meta.json").isFile()
                    && new File(destination, libraryName).isFile()) return id;
            if (destination.exists()) FileUtils.delete(destination);

            if (!FileUtils.copy(packageDir, destination)) {
                FileUtils.delete(destination);
                return "";
            }
            reportProgress(progressCallback, 100);
            return id;
        }
        catch (Exception e) {
            Log.e("XclipseDriverManager", "Unable to install Xclipse driver package", e);
            return "";
        }
        finally {
            FileUtils.delete(archive);
            FileUtils.delete(tmpDir);
        }
    }

    private static void reportProgress(Downloader.ProgressCallback callback, int progress) {
        if (callback != null) callback.onProgress(progress);
    }

    private File findPackageRoot(File root) {
        if (new File(root, "meta.json").isFile()) return root;
        File[] children = root.listFiles(File::isDirectory);
        if (children != null && children.length == 1 && new File(children[0], "meta.json").isFile())
            return children[0];
        return root;
    }

    public void setDriverById(EnvVars envVars, ImageFs imageFs, String driverId) {
        if (extractDriverFromResources(driverId) || enumerateInstalledDrivers().contains(driverId)) {
            String driverPath = driverContentDir.getAbsolutePath() + "/" + driverId + "/";
            String libraryName = getLibraryName(driverId);
            if (!libraryName.isEmpty()) {
                /*
                 * These environment variable names are the private ABI consumed by
                 * libvulkan_wrapper.so and its hook libraries.  They are intentionally
                 * kept for binary compatibility even though the public UI and manager
                 * are Xclipse-specific.  Renaming them in v0.8.6 made every external
                 * driver invisible to the wrapper and could leave container startup
                 * waiting forever.
                 */
                envVars.put("ADRENOTOOLS_DRIVER_PATH", driverPath);
                envVars.put("ADRENOTOOLS_HOOKS_PATH", imageFs.getLibDir());
                envVars.put("ADRENOTOOLS_DRIVER_NAME", libraryName);
            }
        }
    }
 }
