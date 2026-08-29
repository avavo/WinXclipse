package com.winlator.cmod.container;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.winlator.cmod.R;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.MSLink;
import com.winlator.cmod.core.OnExtractFileListener;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.FilenameFilter;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;

public class ContainerManager {
    private final ArrayList<Container> containers = new ArrayList<>();
    private int maxContainerId = 0;
    private final File homeDir;
    private final Context context;

    private boolean isInitialized = false; // New flag to track initialization

    public ContainerManager(Context context) {
        this.context = context.getApplicationContext();
        File rootDir = ImageFs.find(this.context).getRootDir();
        homeDir = new File(rootDir, "home");
        loadContainers();
        isInitialized = true;
    }

    // Check if the ContainerManager is fully initialized
    public boolean isInitialized() {
        return isInitialized;
    }

    public ArrayList<Container> getContainers() {
        return containers;
    }

    // Load containers from the home directory
    private void loadContainers() {
        containers.clear();
        maxContainerId = 0;

        File[] files = homeDir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (!file.isDirectory()) continue;
            if (!file.getName().startsWith(ImageFs.USER + "-")) continue;

            int id;
            try {
                id = Integer.parseInt(file.getName().replace(ImageFs.USER + "-", ""));
            }
            catch (NumberFormatException e) {
                Log.w("ContainerManager", "Skipping malformed container directory: " + file.getName(), e);
                continue;
            }

            maxContainerId = Math.max(maxContainerId, id);

            Container container = new Container(id, this);
            container.setRootDir(file);

            File configFile = container.getConfigFile();
            if (!configFile.isFile()) {
                Log.w("ContainerManager", "Skipping container directory without config: " + file.getAbsolutePath());
                continue;
            }

            String configString = FileUtils.readString(configFile);
            if (configString == null || configString.trim().isEmpty()) {
                Log.w("ContainerManager", "Skipping container with empty config: " + file.getAbsolutePath());
                continue;
            }

            try {
                JSONObject data = new JSONObject(configString);
                container.loadData(data);
                Log.i("WineStartup","loadContainers id=" + id + " extra_appVersion=" + container.getExtra("appVersion") + " extra_dxwrapper=" + container.getExtra("dxwrapper") + " rawHasApp=" + configString.contains("appVersion") + " rawLen=" + configString.length() + " snippet=" + configString.substring(0, Math.min(250, configString.length())));
                containers.add(container);
            }
            catch (JSONException e) {
                Log.e("ContainerManager", "Skipping malformed container config: " + file.getAbsolutePath(), e);
            }
        }
    }


    public Context getContext() {
        return context;
    }


    public void activateContainer(Container container) {
        container.setRootDir(new File(homeDir, ImageFs.USER+"-"+container.id));
        File file = new File(homeDir, ImageFs.USER);
        file.delete();
        FileUtils.symlink("./"+ImageFs.USER+"-"+container.id, file.getPath());
    }

    public void createContainerAsync(final JSONObject data, ContentsManager contentsManager, Callback<Container> callback) {
        final Handler handler = new Handler();
        Executors.newSingleThreadExecutor().execute(() -> {
            final Container container = createContainer(data, contentsManager);
            handler.post(() -> callback.call(container));
        });
    }

    public void duplicateContainerAsync(Container container, Runnable callback) {
        final Handler handler = new Handler();
        Executors.newSingleThreadExecutor().execute(() -> {
            duplicateContainer(container);
            handler.post(callback);
        });
    }

    public void removeContainerAsync(Container container, Runnable callback) {
        removeContainerAsync(container, null, callback);
    }

    public void removeContainerAsync(Container container, Runnable backgroundCleanup, Runnable callback) {
        final Handler handler = new Handler(Looper.getMainLooper());
        Executors.newSingleThreadExecutor().execute(() -> {
            if (backgroundCleanup != null) {
                try {
                    backgroundCleanup.run();
                }
                catch (Throwable error) {
                    Log.w("ContainerManager", "Container cleanup failed; continuing removal", error);
                }
            }
            boolean removed = removeContainer(container);
            handler.post(() -> {
                // RecyclerView reads this same list. Mutate it only on the main
                // thread so deletion cannot race a layout/bind pass.
                if (removed) containers.remove(container);
                if (callback != null) callback.run();
            });
        });
    }

    private Container createContainer(JSONObject data, ContentsManager contentsManager) {
        try {
            // Don't rely on instance maxId which can be stale (0) when this
            // manager was created before xuser-1 existed. Scan filesystem
            // directly and also consider already-loaded containers.
            int before = maxContainerId;
            // Keep in-memory max
            int scannedMax = maxContainerId;
            for (Container c : containers) scannedMax = Math.max(scannedMax, c.id);
            // Filesystem scan using list() to avoid isDirectory symlink quirks
            String[] names = homeDir.list();
            StringBuilder scanDbg = new StringBuilder();
            if (names != null) {
                for (String n : names) {
                    scanDbg.append(n).append(",");
                    if (n.startsWith(ImageFs.USER + "-")) {
                        try { int exId = Integer.parseInt(n.substring((ImageFs.USER + "-").length())); scannedMax = Math.max(scannedMax, exId); } catch (Exception ignored) {}
                    }
                }
            }
            maxContainerId = Math.max(maxContainerId, scannedMax);
            Log.i("WineStartup","createContainer scan home="+homeDir.getAbsolutePath()+" list="+scanDbg+" beforeMax="+before+" scannedMax="+scannedMax+" finalMax="+maxContainerId+" containersMem="+containers.size());
            Log.i("WineStartup","createContainer maxId="+maxContainerId+" dataId="+data.optInt("id",-1)+" wine="+data.optString("wineVersion","")+" home="+homeDir.getAbsolutePath());
            int id = maxContainerId + 1;
            File containerDir = new File(homeDir, ImageFs.USER + "-" + id);

            while (containerDir.exists()) {
                Log.i("WineStartup","createContainer dir exists "+containerDir.getAbsolutePath()+" bump id "+id+" -> "+(id+1));
                id++;
                containerDir = new File(homeDir, ImageFs.USER + "-" + id);
            }
            Log.i("WineStartup","createContainer final id="+id+" dir="+containerDir.getAbsolutePath()+" exists="+containerDir.exists());

            data.put("id", id);

            if (!containerDir.mkdirs()) return null;

            Container container = new Container(id, this);
            container.setRootDir(containerDir);
            container.loadData(data);

            container.setWineVersion(data.getString("wineVersion"));

            if (!extractContainerPatternFile(container, container.getWineVersion(), contentsManager, containerDir, null)) {
                FileUtils.delete(containerDir);
                return null;
            }

//            // Extract the selected graphics driver files
//            String driverVersion = container.getGraphicsDriverVersion();
//            if (!extractGraphicsDriverFiles(driverVersion, containerDir, null)) {
//                FileUtils.delete(containerDir);
//                return null;
//            }

            container.saveData();
            maxContainerId++;
            containers.add(container);
            return container;
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return null;
    }


    private void duplicateContainer(Container srcContainer) {
        int id = maxContainerId + 1;

        File dstDir = new File(homeDir, ImageFs.USER + "-" + id);
        if (!dstDir.mkdirs()) return;

        // Use the refactored copy method that doesn't require a Context for File operations
        if (!FileUtils.copy(srcContainer.getRootDir(), dstDir, file -> FileUtils.chmod(file, 0771))) {
            FileUtils.delete(dstDir);
            return;
        }

        Container dstContainer = new Container(id, this);
        dstContainer.setRootDir(dstDir);
        try {
            dstContainer.loadData(new JSONObject(FileUtils.readString(srcContainer.getConfigFile())));
        } catch (JSONException e) {
            FileUtils.delete(dstDir);
            return;
        }
        dstContainer.setName(srcContainer.getName() + " (" + context.getString(R.string._copy) + ")");
        dstContainer.saveData();

        maxContainerId++;
        containers.add(dstContainer);
    }


    private boolean removeContainer(Container container) {
        return FileUtils.delete(container.getRootDir());
    }

    public ArrayList<Shortcut> loadShortcuts() {
        ArrayList<Shortcut> shortcuts = new ArrayList<>();

        for (Container container : containers) {
            File desktopDir = container.getDesktopDir();
            File[] list = (desktopDir.exists() ? desktopDir.listFiles() : null);

            // Wine/WFM creates Windows .lnk files. Convert them whenever the
            // central shortcut list is loaded. WFM writes "Create Shortcut"
            // beside the selected EXE (often D:\\Downloads), rather than on the
            // Wine Desktop, so include configured drive folders as well.
            ArrayList<File> wineLinks = new ArrayList<>();
            Set<String> visitedRoots = new HashSet<>();
            collectWineLinks(desktopDir, 0, new int[]{0}, wineLinks, visitedRoots);
            for (String[] drive : container.drivesIterator()) {
                File driveRoot = new File(drive[1]);
                collectWineLinks(driveRoot, 0, new int[]{0}, wineLinks, visitedRoots);
            }
            for (File file : wineLinks) {
                File desktop = new File(desktopDir,
                        FileUtils.getBasename(file.getName()) + ".desktop");
                if (desktop.isFile()) continue;
                try {
                    MSLink.createDesktopFile(file, context, container);
                } catch (IOException ex) {
                    Log.w("ContainerManager", "Unable to convert Wine shortcut: "
                            + file.getAbsolutePath(), ex);
                }
            }

            list = desktopDir.listFiles();
            if (list == null) continue;
            for (File file : list) {
                if (!file.getName().toLowerCase().endsWith(".desktop")) continue;
                try {
                    shortcuts.add(new Shortcut(container, file));
                } catch (Exception ex) {
                    Log.w("ContainerManager",
                            "Skipping malformed shortcut: " + file.getAbsolutePath(), ex);
                    // TODO: move the bad file to a “quarantine” folder or delete it
                }
            }
        }

        shortcuts.sort(Comparator.comparing(a -> a.name, String::compareToIgnoreCase));
        return shortcuts;
    }

    /** Loads only shortcuts already belonging to one container. This intentionally
     * avoids the recursive mounted-drive scan used by the global shortcut screen,
     * making pre-removal cleanup cheap and predictable. */
    public ArrayList<Shortcut> loadShortcutsForContainer(Container container) {
        ArrayList<Shortcut> shortcuts = new ArrayList<>();
        if (container == null) return shortcuts;
        File desktopDir = container.getDesktopDir();
        File[] files = desktopDir.isDirectory() ? desktopDir.listFiles() : null;
        if (files == null) return shortcuts;
        for (File file : files) {
            if (!file.isFile() || !file.getName().toLowerCase(java.util.Locale.ENGLISH)
                    .endsWith(".desktop")) continue;
            try {
                shortcuts.add(new Shortcut(container, file));
            }
            catch (Exception error) {
                Log.w("ContainerManager", "Skipping malformed shortcut during removal: "
                        + file.getAbsolutePath(), error);
            }
        }
        return shortcuts;
    }

    private static void collectWineLinks(File directory, int depth, int[] visited,
                                         ArrayList<File> output, Set<String> visitedRoots) {
        if (directory == null || depth > 4 || visited[0] >= 1500 || output.size() >= 128
                || !directory.isDirectory() || !directory.canRead()) return;
        String absolute = directory.getAbsolutePath();
        if (depth == 0 && !visitedRoots.add(absolute)) return;
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (++visited[0] > 1500 || output.size() >= 128) return;
            String lower = file.getName().toLowerCase(java.util.Locale.ENGLISH);
            if (file.isFile() && lower.endsWith(".lnk")) {
                output.add(file);
            }
            else if (file.isDirectory() && depth < 4 && !lower.startsWith(".")
                    && !"android".equals(lower) && !"obb".equals(lower)
                    && !"data".equals(lower)) {
                collectWineLinks(file, depth + 1, visited, output, visitedRoots);
            }
        }
    }


    public int getNextContainerId() {
        return maxContainerId + 1;
    }

    public void reloadContainers() {
        loadContainers();
    }

    public Container getContainerById(int id) {
        for (Container container : containers) if (container.id == id) return container;
        return null;
    }

    private void extractCommonDlls(WineInfo wineInfo, File wineLibDir, String srcName, String dstName, File containerDir, OnExtractFileListener onExtractFileListener) throws JSONException {
        extractCommonDlls(wineInfo, wineLibDir, srcName, dstName, containerDir,
                onExtractFileListener, false);
    }

    private void extractCommonDlls(WineInfo wineInfo, File wineLibDir, String srcName,
                                   String dstName, File containerDir,
                                   OnExtractFileListener onExtractFileListener,
                                   boolean overwriteExisting) throws JSONException {
        if (wineInfo == null || wineLibDir == null) {
            Log.w("ContainerManager", "Skipping common DLL extraction: missing WineInfo for " + srcName);
            return;
        }

        File srcDir = new File(wineLibDir, srcName);
        if (!srcDir.isDirectory()) {
            Log.w("ContainerManager", "Skipping common DLL extraction: missing source dir " + srcDir.getAbsolutePath());
            return;
        }

        File[] srcfiles = srcDir.listFiles(file -> file.isFile());
        if (srcfiles == null || srcfiles.length == 0) {
            Log.w("ContainerManager", "Skipping common DLL extraction: no files in " + srcDir.getAbsolutePath());
            return;
        }

        File dstDir = new File(containerDir, ".wine/drive_c/windows/" + dstName);
        if (!dstDir.exists() && !dstDir.mkdirs()) {
            Log.w("ContainerManager", "Skipping common DLL extraction: unable to create destination dir " + dstDir.getAbsolutePath());
            return;
        }

        int copied = 0;
        int skipped = 0;
        for (File file : srcfiles) {
            String dllName = file.getName();
            if (dllName.equals("iexplore.exe") && wineInfo.isArm64EC() && srcName.equals("aarch64-windows")) {
                File fallbackFile = new File(wineLibDir, "i386-windows/iexplore.exe");
                if (fallbackFile.isFile()) file = fallbackFile;
            }

            File dstFile = new File(dstDir, dllName);
            if (dstFile.exists() && !overwriteExisting) { skipped++; continue; }

            if (onExtractFileListener != null) {
                dstFile = onExtractFileListener.onExtractFile(dstFile, 0);
                if (dstFile == null) { skipped++; continue; }
            }

            if (FileUtils.copy(file, dstFile)) copied++; else skipped++;
        }
        Log.i("ContainerManager", "extractCommonDlls " + srcName + " -> " + dstName
                + ": source=" + srcfiles.length + " copied=" + copied + " skipped=" + skipped);
    }

    public boolean extractContainerPatternFile(Container container, String wineVersion, ContentsManager contentsManager, File containerDir, OnExtractFileListener onExtractFileListener) {
        WineInfo wineInfo = WineInfo.fromIdentifier(context, contentsManager, wineVersion);
        ContentProfile runtimeProfile = contentsManager.getProfileByEntryName(wineVersion);
        File runtimeRoot = wineInfo.path != null ? new File(wineInfo.path) : null;
        File wineLibDir = runtimeProfile != null
                ? ContentsManager.getSourceFile(context, runtimeProfile, runtimeProfile.wineLibPath)
                : runtimeRoot != null ? new File(runtimeRoot, "lib/wine") : null;
        if (wineLibDir != null) {
            File directArchDir = new File(wineLibDir, "i386-windows");
            if (!directArchDir.isDirectory()) {
                File nestedWineDir = new File(wineLibDir, "wine");
                if (new File(nestedWineDir, "i386-windows").isDirectory()) wineLibDir = nestedWineDir;
            }
        }
        String containerPattern = wineVersion + "_container_pattern.tzst";
        boolean result = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, containerPattern, containerDir, onExtractFileListener);

        if (!result && runtimeProfile != null && runtimeProfile.winePrefixPack != null) {
            // Only use the runtime's declared pack when it actually is an archive;
            // synthesized profiles point the field at the wine binary itself.
            File packFile = ContentsManager.getSourceFile(context, runtimeProfile, runtimeProfile.winePrefixPack);
            TarCompressorUtils.Type compression = packFile != null && packFile.isFile()
                    ? TarCompressorUtils.detectType(packFile) : null;
            if (compression != null) {
                result = TarCompressorUtils.extract(compression, packFile, containerDir);
            }
            else Log.i("ContainerManager", "Runtime has no usable prefix pack (" + runtimeProfile.winePrefixPack + ")");
        }

        if (!result) {
            // Raw Wine/Proton .tzst downloads ship without any prefix pack.
            // Preferred fallback is the bundled Proton prefix pattern, but that
            // archive has failed commons-compress parsing on some builds, so the
            // final resort clones the shared imagefs prefix - which is guaranteed
            // to be a working win64 prefix - into the new container.
            result = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context,
                    WineInfo.MAIN_WINE_VERSION.identifier() + "_container_pattern.tzst",
                    containerDir, onExtractFileListener);
            if (result) {
                Log.i("ContainerManager", "Created container using the bundled Proton prefix pattern for " + wineVersion);
            }
        }

        if (!result) {
            // The container root doubles as the user home (it holds .wine directly),
            // so clone every entry of the shared imagefs home into it.
            File sharedHome = new File(ImageFs.find(context).getRootDir(), "home/xuser");
            File[] entries = sharedHome.listFiles();
            if (entries != null && entries.length > 0) {
                result = true;
                for (File entry : entries) {
                    if (!FileUtils.copy(entry, new File(containerDir, entry.getName()))) {
                        Log.e("ContainerManager", "Failed to clone prefix entry: " + entry.getPath());
                        result = false;
                        break;
                    }
                }
                if (result) {
                    ensureValidPrefixRegistries(new File(containerDir, ".wine"),
                            wineInfo.isWin64());
                    Log.i("ContainerManager", "Created container by cloning the shared imagefs prefix for "
                            + wineVersion);
                }
                else FileUtils.delete(containerDir);
            }
        }

        if (result) {
            ensureValidPrefixRegistries(new File(containerDir, ".wine"), wineInfo.isWin64());
            try {
                Log.i("ContainerManager", "extractContainerPatternFile: populating DLL dirs (arch="
                        + wineInfo.getArch() + ", isArm64EC=" + wineInfo.isArm64EC()
                        + ", isWin64=" + wineInfo.isWin64() + ", wineLibDir=" + wineLibDir + ")");
                if (wineInfo.isArm64EC()) {
                    extractCommonDlls(wineInfo, wineLibDir, "aarch64-windows", "system32",
                            containerDir, onExtractFileListener);
                    extractCommonDlls(wineInfo, wineLibDir, "i386-windows", "syswow64",
                            containerDir, onExtractFileListener);
                }
                else if (!wineInfo.isWin64()) {
                    // Pure 32-bit (x86) prefix: system32 gets the i386 payload, no syswow64.
                    // The fallback prefix is bundled as win64/ARM64EC. Replace
                    // same-named system DLLs instead of skipping them, otherwise
                    // a nominal win32 prefix still contains 64-bit system32.
                    extractCommonDlls(wineInfo, wineLibDir, "i386-windows", "system32",
                            containerDir, onExtractFileListener, true);
                    File spuriousWow64 = new File(containerDir,
                            ".wine/drive_c/windows/syswow64");
                    if (spuriousWow64.isDirectory()) {
                        FileUtils.delete(spuriousWow64);
                        Log.i("ContainerManager", "Removed spurious syswow64 for win32 prefix");
                    }
                }
                else {
                    extractCommonDlls(wineInfo, wineLibDir, "x86_64-windows", "system32",
                            containerDir, onExtractFileListener);
                    extractCommonDlls(wineInfo, wineLibDir, "i386-windows", "syswow64",
                            containerDir, onExtractFileListener);
                }
            }
            catch (JSONException e) {
                return false;
            }
        }

        return result;
    }

    /** Wine rejects a prefix whose registry files lack the "WINE REGISTRY"
     *  header or whose #arch marker disagrees with the selected runtime. Keep
     *  valid registry contents and repair only the header/architecture marker. */
    public static void ensureValidPrefixRegistries(File prefixDir) {
        ensureValidPrefixRegistries(prefixDir, true);
    }

    public static void ensureValidPrefixRegistries(File prefixDir, boolean isWin64) {
        if (!prefixDir.isDirectory()) return;
        String[] registries = {"system.reg", "user.reg", "userdef.reg"};
        java.util.regex.Pattern header = java.util.regex.Pattern.compile(
                "^WINE REGISTRY Version \\d+\\s*$");
        java.util.regex.Pattern arch = java.util.regex.Pattern.compile(
                "(?m)^#arch=win(?:32|64)[\\t ]*\\r?$");
        String desiredArch = isWin64 ? "#arch=win64" : "#arch=win32";
        for (String name : registries) {
            File file = new File(prefixDir, name);
            String content = file.isFile() ? FileUtils.readString(file) : "";
            int firstBreak = content.indexOf('\n');
            String firstLine = firstBreak >= 0 ? content.substring(0, firstBreak) : content;
            boolean validHeader = header.matcher(firstLine).matches();
            String repaired = content;

            if (!validHeader) {
                repaired = "WINE REGISTRY Version 2\n" + desiredArch + "\n";
            }
            else {
                java.util.regex.Matcher archMatcher = arch.matcher(content);
                if (archMatcher.find()) {
                    if (!desiredArch.equals(archMatcher.group().trim())) {
                        repaired = archMatcher.replaceFirst(
                                java.util.regex.Matcher.quoteReplacement(desiredArch));
                    }
                }
                else {
                    int insertAt = firstBreak >= 0 ? firstBreak + 1 : content.length();
                    repaired = content.substring(0, insertAt) + desiredArch + "\n"
                            + content.substring(insertAt);
                }
            }

            if (!repaired.equals(content)) {
                Log.w("ContainerManager", "Repairing " + name + " as "
                        + (isWin64 ? "win64" : "win32"));
                FileUtils.writeString(file, repaired);
            }
        }
    }

    public Container getContainerForShortcut(Shortcut shortcut) {
        // Search for the container by its ID
        for (Container container : containers) {
            if (container.id == shortcut.getContainerId()) {
                return container;
            }
        }
        return null;  // Return null if no matching container is found
    }

    public void importContainer(File importDir, Runnable callback) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                if (!importDir.exists() || !importDir.isDirectory()) {
                    Log.e("ContainerManager", "Invalid container directory for import: " + importDir.getPath());
                    return;
                }

                // Get the next container ID and set the new container name
                int newContainerId = getNextContainerId();
                String newContainerName = ImageFs.USER + "-" + newContainerId;
                File newContainerDir = new File(homeDir, newContainerName);

                if (newContainerDir.exists()) {
                    Log.e("ContainerManager", "Container directory already exists: " + newContainerDir.getPath());
                    return;
                }

                if (!newContainerDir.mkdirs()) {
                    Log.e("ContainerManager", "Failed to create directory: " + newContainerDir.getPath());
                    return;
                }

                // Copy the files from the import directory to the new container directory
                if (!FileUtils.copy(importDir, newContainerDir, file -> FileUtils.chmod(file, 0771))) {
                    FileUtils.delete(newContainerDir);
                    Log.e("ContainerManager", "Failed to copy container files to: " + newContainerDir.getPath());
                    return;
                }

                // Create the new container object and load the configuration copied with it
                Container newContainer = new Container(newContainerId, this);
                newContainer.setRootDir(newContainerDir);
                try {
                    newContainer.loadData(new JSONObject(FileUtils.readString(newContainer.getConfigFile())));
                }
                catch (JSONException e) {
                    FileUtils.delete(newContainerDir);
                    Log.e("ContainerManager", "Failed to read container config from: " + newContainer.getConfigFile().getPath(), e);
                    return;
                }
                newContainer.setName(importDir.getName());
                newContainer.saveData();
                containers.add(newContainer);
                maxContainerId++;

                Log.d("ContainerManager", "Container imported successfully to: " + newContainerDir.getPath());
            } catch (Exception e) {
                Log.e("ContainerManager", "Failed to import container from: " + importDir.getPath(), e);
            }
            finally {
                // Always release the caller's preloader dialog, success or not
                if (callback != null) callback.run();
            }
        });
    }



    public void exportContainer(Container container, Runnable callback) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                // Create the export directory path
                File exportDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Winlator/Backups/Containers");

                if (!exportDir.exists() && !exportDir.mkdirs()) {
                    Log.e("ContainerManager", "Failed to create export directory: " + exportDir.getPath());
                    runOnUiThread(() -> callback.run()); // Close the preloader dialog
                    return;
                }

                File containerDir = container.getRootDir();
                File destinationDir = new File(exportDir, containerDir.getName());

                if (destinationDir.exists()) {
                    Log.e("ContainerManager", "Export directory already exists: " + destinationDir.getPath());
                    runOnUiThread(() -> callback.run()); // Close the preloader dialog
                    return;
                }

                if (!destinationDir.mkdirs()) {
                    Log.e("ContainerManager", "Failed to create directory: " + destinationDir.getPath());
                    runOnUiThread(() -> callback.run()); // Close the preloader dialog
                    return;
                }

                if (!FileUtils.copy(containerDir, destinationDir, file -> FileUtils.chmod(file, 0771))) {
                    Log.e("ContainerManager", "Failed to export some container files to: " + destinationDir.getPath());
                    FileUtils.delete(destinationDir); // Optional: Delete partially copied directory
                }
                else Log.d("ContainerManager", "Container exported successfully to: " + destinationDir.getPath());
            } catch (Exception e) {
                Log.e("ContainerManager", "Failed to export container: " + container.getName(), e);
            } finally {
                runOnUiThread(callback); // Ensure the callback runs and preloader dialog closes
            }
        });
    }

    // Utility method to run on UI thread
    private void runOnUiThread(Runnable action) {
        new Handler(Looper.getMainLooper()).post(action);
    }



}
