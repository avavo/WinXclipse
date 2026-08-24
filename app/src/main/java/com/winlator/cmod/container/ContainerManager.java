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
import java.util.concurrent.Executors;

public class ContainerManager {
    private final ArrayList<Container> containers = new ArrayList<>();
    private int maxContainerId = 0;
    private final File homeDir;
    private final Context context;

    private boolean isInitialized = false; // New flag to track initialization

    public ContainerManager(Context context) {
        this.context = context;
        File rootDir = ImageFs.find(context).getRootDir();
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
        final Handler handler = new Handler();
        Executors.newSingleThreadExecutor().execute(() -> {
            removeContainer(container);
            handler.post(callback);
        });
    }

    private Container createContainer(JSONObject data, ContentsManager contentsManager) {
        try {
            int id = maxContainerId + 1;
            File containerDir = new File(homeDir, ImageFs.USER + "-" + id);

            while (containerDir.exists()) {
                id++;
                containerDir = new File(homeDir, ImageFs.USER + "-" + id);
            }

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


    private void removeContainer(Container container) {
        if (FileUtils.delete(container.getRootDir())) containers.remove(container);
    }

    public ArrayList<Shortcut> loadShortcuts() {
        ArrayList<Shortcut> shortcuts = new ArrayList<>();

        for (Container container : containers) {
            File desktopDir = container.getDesktopDir();
            File[] list = (desktopDir.exists() ? desktopDir.listFiles() : null);
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
            if (dstFile.exists()) { skipped++; continue; }

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

        if (!result) {
            File containerPatternFile = runtimeProfile != null
                    ? ContentsManager.getSourceFile(context, runtimeProfile, runtimeProfile.winePrefixPack)
                    : new File(runtimeRoot, "prefixPack.txz");
            TarCompressorUtils.Type compression = TarCompressorUtils.detectType(containerPatternFile);
            result = compression != null
                    && TarCompressorUtils.extract(compression, containerPatternFile, containerDir);
        }

        if (result) {
            try {
                Log.i("ContainerManager", "extractContainerPatternFile: populating DLL dirs (arm64EC=" + wineInfo.isArm64EC() + ", wineLibDir=" + wineLibDir + ")");
                if (wineInfo.isArm64EC())
                    extractCommonDlls(wineInfo, wineLibDir, "aarch64-windows", "system32", containerDir, onExtractFileListener); // arm64ec only
                else
                    extractCommonDlls(wineInfo, wineLibDir, "x86_64-windows", "system32", containerDir, onExtractFileListener);

                extractCommonDlls(wineInfo, wineLibDir, "i386-windows", "syswow64", containerDir, onExtractFileListener);
            }
            catch (JSONException e) {
                return false;
            }
        }
   
        return result;
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

                // Create the new container object and save its data
                Container newContainer = new Container(newContainerId, this);
                newContainer.setRootDir(newContainerDir);
                newContainer.setName(importDir.getName());
                newContainer.saveData();
                containers.add(newContainer);
                maxContainerId++;

                Log.d("ContainerManager", "Container imported successfully to: " + newContainerDir.getPath());
                // Make sure to run the callback after successful import
                if (callback != null) {
                    callback.run();
                }
            } catch (Exception e) {
                Log.e("ContainerManager", "Failed to import container from: " + importDir.getPath(), e);
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

                Log.d("ContainerManager", "Container exported successfully to: " + destinationDir.getPath());
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
