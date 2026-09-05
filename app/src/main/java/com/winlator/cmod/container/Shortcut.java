    package com.winlator.cmod.container;

    import android.graphics.Bitmap;
    import android.graphics.BitmapFactory;
    import android.util.Log;

    import com.winlator.cmod.core.FileUtils;
    import com.winlator.cmod.core.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
    import org.json.JSONException;
    import org.json.JSONObject;

    import java.io.File;
    import java.util.ArrayList;
    import java.util.Iterator;
    import java.util.List;
    import java.util.UUID;

    public class Shortcut {
        public final Container container;
        public final String name;
        public final String path;
        public Bitmap icon;
        public final File file;
        public File iconFile;
        public final String wmClass;
        private final JSONObject extraData = new JSONObject();
        private Bitmap coverArt; // Changed to private to use getter method
        private String customCoverArtPath; // Path to custom cover art

        private static final String COVER_ART_DIR = "app_data/cover_arts/"; // Removed leading "/" to keep it relative
        /** Max width (in px) for decoded cover art held in memory; height may reach 2x for portraits. */
        private static final int COVER_ART_MAX_DIMENSION = 512;

        public Shortcut(Container container, File file) {

            this.container = container;
            this.file      = file;

            String execArgs = "";
            Bitmap icon     = null;
            File   iconFile = null;
            String wmClass  = "";

            File[] iconDirs = { container.getIconsDir(64),
                    container.getIconsDir(48),
                    container.getIconsDir(32),
                    container.getIconsDir(16) };

            /* --- NEW: flag so we know we actually parsed the header --- */
            boolean seenDesktopEntry = false;

            String section = "";
            int index;

            for (String line : FileUtils.readLines(file)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                if (line.startsWith("[")) {
                    section = line.substring(1, line.indexOf(']'));
                    if ("Desktop Entry".equals(section)) seenDesktopEntry = true;   // <-- NEW
                    continue;
                }

                index = line.indexOf('=');
                if (index == -1) continue;
                String key   = line.substring(0, index);
                String value = line.substring(index + 1);

                if ("Desktop Entry".equals(section)) {
                    if (key.equals("Exec"))  execArgs = value;
                    if (key.equals("Icon")) {
                        for (File d : iconDirs) {
                            iconFile = new File(d, value + ".png");
                            if (iconFile.isFile()) { icon = BitmapFactory.decodeFile(iconFile.getPath()); break; }
                        }
                    }
                    if (key.equals("StartupWMClass")) wmClass = value;
                }
                else if ("Extra Data".equals(section)) {
                    try { extraData.put(key, value); } catch (JSONException ignored) {}
                }
            }

            /* --- NEW: quick bail-out if header was missing or no meaningful data --- */
            if (!seenDesktopEntry) {
                Log.w("Shortcut", "Ignoring malformed shortcut (no [Desktop Entry]): "
                        + file.getName());
                throw new IllegalArgumentException("Malformed .desktop file");
            }

            /* ------------------------------------------------------------------ */
            /*                 SAFE handling of Exec → path                       */
            /* ------------------------------------------------------------------ */
            int winePos = execArgs.lastIndexOf("wine ");
            if (winePos != -1) {
                // +5 because "wine " is five chars (w i n e ␠)
                this.path = StringUtils.unescape(execArgs.substring(winePos + 5).trim());
            } else {
                Log.w("Shortcut", "Exec line missing or has no \"wine \" prefix: "
                        + file.getName());
                this.path = "";        // or leave null / handle however you prefer
            }

            /* --- everything else unchanged ------------------------------------ */
            this.name     = FileUtils.getBasename(file.getPath());
            this.icon     = icon;
            this.iconFile = iconFile;
            this.wmClass  = wmClass;

            this.customCoverArtPath = getExtra("customCoverArtPath");
            loadCoverArt();

            Container.checkObsoleteOrMissingProperties(extraData);
        }

        private void loadCoverArt() {
            // Check for custom cover art first
            if (customCoverArtPath != null && !customCoverArtPath.isEmpty()) {
                File customCoverArtFile = new File(customCoverArtPath);
                if (customCoverArtFile.isFile()) {
                    this.coverArt = decodeSampledCoverArt(customCoverArtFile);
                    return; // Exit if custom cover art is loaded
                }
            }

            // Fallback to standard cover art location
            File defaultCoverArtFile = getGeneratedCoverArtFile();
            if (defaultCoverArtFile.isFile()) {
                this.coverArt = decodeSampledCoverArt(defaultCoverArtFile);
            }
        }

        /**
         * Decodes cover art with inSampleSize so list rows do not hold
         * full-size 600x900 bitmaps (roughly 2 MB each) in memory.
         */
        private Bitmap decodeSampledCoverArt(File file) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), bounds);

            int sampleSize = 1;
            while ((bounds.outWidth / sampleSize) > COVER_ART_MAX_DIMENSION
                    || (bounds.outHeight / sampleSize) > COVER_ART_MAX_DIMENSION * 2) {
                sampleSize *= 2;
            }

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Math.max(1, sampleSize);
            return BitmapFactory.decodeFile(file.getPath(), options);
        }

        // Getters and setters for coverArt and customCoverArtPath
        public Bitmap getCoverArt() {
            return coverArt;
        }

        public void setCoverArt(Bitmap coverArt) {
            this.coverArt = coverArt;
        }

        public String getCustomCoverArtPath() {
            return customCoverArtPath;
        }

        public void setCustomCoverArtPath(String customCoverArtPath) {
            this.customCoverArtPath = customCoverArtPath;
            putExtra("customCoverArtPath", customCoverArtPath); // Save the custom cover art path to extra data
            saveData(); // Save immediately to ensure persistence
            Log.d("Shortcut", "Set and saved custom cover art path: " + customCoverArtPath); // Add a log for debugging
        }

        public String getExtra(String name) {
            return getExtra(name, "");
        }

        public String getExtra(String name, String fallback) {
            try {
                return extraData.has(name) ? extraData.getString(name) : fallback;
            }
            catch (JSONException e) {
                return fallback;
            }
        }

        public void putExtra(String name, String value) {
            try {
                if (value != null) {
                    extraData.put(name, value);
                }
                else extraData.remove(name);
            }
            catch (JSONException e) {}
        }

        public void saveData() {
            String content = "[Desktop Entry]\n";
            for (String line : FileUtils.readLines(file)) {
                if (line.contains("[Extra Data]")) break;
                if (!line.contains("[Desktop Entry]") && !line.isEmpty()) content += line + "\n";
            }

            if (extraData.length() > 0) {
                content += "\n[Extra Data]\n";
                Iterator<String> keys = extraData.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    try {
                        content += key + "=" + extraData.getString(key) + "\n";
                    } catch (JSONException e) {}
                }
            }

            // Verify that the file reference is correct
            if (!file.getName().endsWith(".desktop")) {
                Log.e("Shortcut", "Incorrect file reference before saving: " + file.getPath());
                return; // Prevent saving to an incorrect file
            }

            FileUtils.writeString(file, content);
        }


        public void genUUID() {
            if (getExtra("uuid").equals("")) {
                putExtra("uuid", UUID.randomUUID().toString());
                saveData();
            }
        }

        // Save the custom cover art to the default cover art directory
        public void saveCustomCoverArt(Bitmap coverArt) {
            try {
                File coverArtDir = new File(container.getRootDir(), COVER_ART_DIR); // Ensure the path is relative to the container's root directory
                if (!coverArtDir.exists()) {
                    boolean created = coverArtDir.mkdirs();
                    if (!created) {
                        Log.e("Shortcut", "Failed to create cover art directory: " + coverArtDir.getAbsolutePath());
                    }
                }


                File coverFile = new File(coverArtDir, this.name + ".png");
                if (FileUtils.saveBitmapToFile(coverArt, coverFile)) {
                    this.coverArt = coverArt; // Update the cover art
                    setCustomCoverArtPath(coverFile.getPath()); // Update the path and save data
                    Log.d("Shortcut", "Custom cover art saved at: " + coverFile.getPath());
                } else {
                    Log.e("Shortcut", "Failed to save custom cover art.");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        public File getGeneratedCoverArtFile() {
            return new File(new File(container.getRootDir(), COVER_ART_DIR), this.name + ".png");
        }

        /** Sidecar opcional com dxvk.conf so deste atalho; precede o do container. */
        public File getDxvkConfFile() {
            if (file == null) return null;
            File dir = file.getParentFile();
            if (dir == null) return null;
            return new File(dir, FileUtils.getBasename(file.getName()) + ".dxvk.conf");
        }

        public boolean hasCustomDxvkConf() {
            File f = getDxvkConfFile();
            return f != null && f.isFile() && f.length() > 0;
        }

        public boolean saveGeneratedCoverArt(Bitmap generatedCoverArt) {
            if (generatedCoverArt == null) return false;
            File coverFile = getGeneratedCoverArtFile();
            File parent = coverFile.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
            if (!FileUtils.saveBitmapToFile(generatedCoverArt, coverFile)) return false;
            this.coverArt = generatedCoverArt;
            return true;
        }

        public void reloadCoverArt() {
            this.coverArt = null;
            loadCoverArt();
        }

        public File resolveExecutableFile() {
            if (path == null) return null;
            String windowsPath = path.trim().replace("\"", "");
            int exeEnd = windowsPath.toLowerCase().indexOf(".exe");
            if (exeEnd >= 0) windowsPath = windowsPath.substring(0, exeEnd + 4);
            if (!windowsPath.matches("^[A-Za-z]:.*")) return null;

            String drive = windowsPath.substring(0, 1).toUpperCase();
            File base;
            if ("C".equals(drive)) base = new File(container.getRootDir(), ".wine/drive_c");
            else if ("Z".equals(drive)) base = container.getRootDir().getParentFile().getParentFile();
            else {
                base = null;
                for (String[] item : container.drivesIterator()) {
                    if (drive.equalsIgnoreCase(item[0])) {
                        base = new File(item[1]);
                        break;
                    }
                }
            }
            if (base == null) return null;
            String relative = windowsPath.substring(2).replace('\\', File.separatorChar);
            while (relative.startsWith(File.separator)) relative = relative.substring(1);
            return new File(base, relative);
        }



        public void removeCustomCoverArt() {
            if (customCoverArtPath != null && !customCoverArtPath.isEmpty()) {
                File customCoverArtFile = new File(customCoverArtPath);

                // Log the path to be deleted
                Log.d("Shortcut", "Removing custom cover art file at: " + customCoverArtPath);

                // Delete the file if it exists
                if (customCoverArtFile.exists() && customCoverArtFile.delete()) {
                    Log.d("Shortcut", "Custom cover art file deleted successfully.");
                } else {
                    Log.e("Shortcut", "Failed to delete custom cover art file or it doesn't exist.");
                }
            }

            // Reset the custom cover art path and cover art object
            this.customCoverArtPath = null;
            this.coverArt = null;

            // Remove it from extra data and save the state
            putExtra("customCoverArtPath", null);
            saveData();

            // Log the state after removal
            Log.d("Shortcut", "Shortcut state saved after removing custom cover art. Current path: " + customCoverArtPath);
        }

        public boolean cloneToContainer(Container newContainer) {
            try {
                // Define the path for the new .desktop file in the new container
                File newShortcutFile = new File(newContainer.getDesktopDir(), this.file.getName());

                // Read the existing .desktop file
                ArrayList<String> lines = FileUtils.readLines(this.file);

                // Prepare the content for the new .desktop file with updated container_id
                StringBuilder updatedContent = new StringBuilder();
                boolean containerIdFound = false;

                for (String line : lines) {
                    if (line.startsWith("container_id:")) {
                        // Update the container_id to the new container
                        updatedContent.append("container_id:").append(newContainer.id).append("\n");
                        containerIdFound = true;
                    } else {
                        updatedContent.append(line).append("\n");
                    }
                }

                // If the container_id wasn't found in the original file, add it
                if (!containerIdFound) {
                    updatedContent.append("container_id:").append(newContainer.id).append("\n");
                }

                // Write the updated content to the new .desktop file
                FileUtils.writeString(newShortcutFile, updatedContent.toString());

                // Optionally copy the icon if it exists
                if (this.iconFile != null && this.iconFile.isFile()) {
                    File newIconFile = new File(newContainer.getIconsDir(64), this.iconFile.getName());
                    FileUtils.copy(this.iconFile, newIconFile);
                }

                // Carry the per-shortcut dxvk.conf sidecar, if any
                File conf = getDxvkConfFile();
                if (conf != null && conf.isFile()) {
                    File newConf = new File(newShortcutFile.getParentFile(),
                            FileUtils.getBasename(newShortcutFile.getName()) + ".dxvk.conf");
                    try {
                        FileUtils.copy(conf, newConf);
                    } catch (Exception e) {
                        Log.w("Shortcut", "Could not clone dxvk.conf sidecar", e);
                    }
                }

                return true;
            } catch (Exception e) {
                Log.e("Shortcut", "Failed to clone shortcut to new container", e);
                return false;
            }
        }

        public void setCustomIconPath(String path) {
            putExtra("customIconPath", path);
            saveData();

            if (path != null && !path.isEmpty()) {
                this.iconFile = new File(path);
                if (this.iconFile.exists()) {
                    this.icon = BitmapFactory.decodeFile(this.iconFile.getPath());
                }
            } else {
                this.iconFile = null;
                this.icon = null; // Or set to a default icon
            }
        }


        public int getContainerId() {
            return container.id;
        }
         
        public String getExecutable() {
            String exe = "";
            try {
                List<String> lines = Files.readAllLines(file.toPath());
                for (String line : lines) {
                    if (line.startsWith("Exec")) {
                        exe = line.substring(line.lastIndexOf("\\") + 1, line.length()).replaceAll("\\s+$", "");
                        break;
                    }
                }
            }
            catch (IOException e) {
                Log.e("Shortcut", "Failed to read shortcut file: " + file.getPath(), e);
                return exe;
            }

            // Strip surrounding quotes, arguments and query fragments so the value
            // matches the plain executable name expected by FEX per-app configs.
            int spaceIndex = exe.indexOf(' ');
            if (spaceIndex >= 0) exe = exe.substring(0, spaceIndex);
            int argIndex = exe.indexOf('%');
            if (argIndex >= 0) exe = exe.substring(0, argIndex);
            exe = exe.replace("\"", "").trim();

            return exe;
        }

        public boolean hasExtra(String name) {
            return this.extraData != null && this.extraData.has(name);
        }
    }
