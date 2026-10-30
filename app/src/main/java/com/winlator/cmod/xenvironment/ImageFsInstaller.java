package com.winlator.cmod.xenvironment;

import android.app.AlertDialog;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.text.Html;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import com.winlator.cmod.MainActivity;
import com.winlator.cmod.R;
import com.winlator.cmod.SettingsFragment;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.DownloadProgressDialog;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.OnExtractFileListener;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.core.RuntimeFiles;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public abstract class ImageFsInstaller {
    public static final byte LATEST_VERSION = 25;

    private static void resetContainerImgVersions(Context context) {
        ContainerManager manager = new ContainerManager(context);
        for (Container container : manager.getContainers()) {
            String imgVersion = container.getExtra("imgVersion");
            String wineVersion = container.getWineVersion();
            if (!imgVersion.isEmpty() && WineInfo.isMainWineVersion(wineVersion) && Short.parseShort(imgVersion) <= 5) {
                container.putExtra("wineprefixNeedsUpdate", "t");
            }

            container.putExtra("imgVersion", null);
            container.saveData();
        }
    }

    public static void installWineFromAssets(final MainActivity activity) {
        if (!installRequiredWine(activity, ImageFs.find(activity).getRootDir(), null)) {
            AppUtils.showToast(activity, R.string.unable_to_install_system_files);
        }
    }

    public static void installFromAssets(final MainActivity activity) {
        installFromAssets(activity, null);
    }

    /** Publish the ImageFS receipt and continue startup only after every required
     * runtime has actually been extracted. A failed install is retried next boot. */
    public static void installFromAssets(final MainActivity activity, final Runnable onCompletion) {
        AppUtils.keepScreenOn(activity);
        ImageFs imageFs = ImageFs.find(activity);
        File rootDir = imageFs.getRootDir();
        final DownloadProgressDialog dialog = new DownloadProgressDialog(activity);
        dialog.show(R.string.installing_system_files);
        Executors.newSingleThreadExecutor().execute(() -> {
            boolean success = false;
            try {
                File receipt = imageFs.getImgVersionFile();
                if (receipt.exists() && !receipt.delete()) {
                    throw new IOException("Cannot invalidate the previous ImageFS receipt");
                }
                clearRootDir(rootDir);
                long estimate = Math.max(1L, FileUtils.getSize(activity, "imagefs.tzst") * 4L);
                AtomicLong extracted = new AtomicLong();
                boolean imageReady = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD,
                        activity, "imagefs.tzst", rootDir, (file, size) -> {
                            if (size > 0) {
                                int progress = (int)Math.min(78, extracted.addAndGet(size) * 78L / estimate);
                                activity.runOnUiThread(() -> dialog.setProgress(progress));
                            }
                            return file;
                        });
                if (!imageReady) throw new IOException("ImageFS extraction failed");
                activity.runOnUiThread(() -> dialog.setProgress(78));
                if (!installRequiredWine(activity, rootDir, dialog)) {
                    throw new IOException("Required Wine/Proton extraction failed");
                }
                activity.runOnUiThread(() -> dialog.setProgress(93));
                SettingsFragment.resetEmulatorsVersion(activity);
                resetContainerImgVersions(activity);
                imageFs.getConfigDir().mkdirs();
                if (!FileUtils.writeStringAtomic(imageFs.getImgVersionFile(),
                        String.valueOf(LATEST_VERSION))) {
                    throw new IOException("Could not commit the ImageFS receipt");
                }
                success = true;
            }
            catch (Exception error) {
                Log.e("ImageFsInstaller", "Initial installation did not finish", error);
                FileUtils.delete(imageFs.getImgVersionFile());
            }
            final boolean completed = success;
            activity.runOnUiThread(() -> {
                if (completed) dialog.setProgress(100);
                dialog.close();
                if (completed) {
                    if (onCompletion != null) onCompletion.run();
                }
                else AppUtils.showToast(activity, R.string.unable_to_install_system_files);
            });
        });
    }

    private static boolean installRequiredWine(MainActivity activity, File rootDir,
                                                DownloadProgressDialog dialog) {
        String[] versions = activity.getResources().getStringArray(R.array.wine_entries);
        long estimate = 0;
        for (String version : versions) {
            long size = FileUtils.getSize(activity, version + ".tzst");
            if (size == 0) size = FileUtils.getSize(activity, version + ".txz");
            estimate += size * 4L;
        }
        final long total = Math.max(1L, estimate);
        AtomicLong extracted = new AtomicLong();
        for (String version : versions) {
            File target = new File(rootDir, "opt/" + version);
            if (!target.isDirectory() && !target.mkdirs()) return false;
            OnExtractFileListener progress = (file, size) -> {
                if (size > 0 && dialog != null) {
                    int value = 78 + (int)Math.min(15, extracted.addAndGet(size) * 15L / total);
                    activity.runOnUiThread(() -> dialog.setProgress(value));
                }
                return file;
            };
            boolean ready = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD,
                    activity, version + ".tzst", target, progress);
            if (!ready) ready = TarCompressorUtils.extract(TarCompressorUtils.Type.XZ,
                    activity, version + ".txz", target, progress);
            if (!ready || !RuntimeFiles.hasWine(target, "bin")) {
                Log.e("ImageFsInstaller", "Incomplete bundled runtime: " + version);
                return false;
            }
        }
        return true;
    }

    private static boolean hasRequiredWine(MainActivity activity, File rootDir) {
        for (String version : activity.getResources().getStringArray(R.array.wine_entries)) {
            if (!RuntimeFiles.hasWine(new File(rootDir, "opt/" + version), "bin")) {
                // Removing the APK-baked Proton is supported once a replacement
                // is installed. Do not reinstall it on every app launch.
                ContentsManager manager = new ContentsManager(activity);
                manager.syncContents();
                for (ContentProfile.ContentType type : new ContentProfile.ContentType[]{
                        ContentProfile.ContentType.CONTENT_TYPE_WINE,
                        ContentProfile.ContentType.CONTENT_TYPE_PROTON}) {
                    for (ContentProfile profile : manager.getProfiles(type)) {
                        if (manager.isInstalledProfile(profile)) return true;
                    }
                }
                return false;
            }
        }
        return true;
    }

    public static void installIfNeeded(final MainActivity activity) {
        ImageFs imageFs = ImageFs.find(activity);
        if (!imageFs.isValid() || imageFs.getVersion() < LATEST_VERSION
                || !hasRequiredWine(activity, imageFs.getRootDir())) installFromAssets(activity);
    }

    public static void installIfNeeded(final MainActivity activity, final Runnable onCompletion) {
        ImageFs imageFs = ImageFs.find(activity);
        if (!imageFs.isValid() || imageFs.getVersion() < LATEST_VERSION
                || !hasRequiredWine(activity, imageFs.getRootDir())) {
            installFromAssets(activity, onCompletion);
        }
        else if (onCompletion != null) {
            onCompletion.run();
        }
    }

    private static void clearOptDir(File optDir) {
        File[] files = optDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().equals("installed-wine")) continue;
                FileUtils.delete(file);
            }
        }
    }

    private static void clearRootDir(File rootDir) {
        if (!rootDir.isDirectory()) { rootDir.mkdirs(); return; }
        File[] files = rootDir.listFiles();
        if (files == null) return;
        // Use native rm -rf for speed (50k+ files in imagefs) – falls back to Java delete.
        for (File file : files) {
            if (file.isDirectory() && file.getName().equals("home")) continue;
            try {
                Process p = new ProcessBuilder("rm", "-rf", file.getAbsolutePath()).start();
                p.waitFor();
                if (!file.exists()) continue;
            } catch (Exception ignored) {}
            FileUtils.delete(file);
        }
    }

    private static void installGuestLibs(Context ctx) {
        final String ASSET_TAR = "evshim.tzst";          // ➊  add this to assets/
        File imagefs = new File(ctx.getFilesDir(), "imagefs");
        // ➋  Unpack straight into imagefs, preserving relative paths.
        try (InputStream in  = ctx.getAssets().open(ASSET_TAR)) {
            TarCompressorUtils.extract(
                    TarCompressorUtils.Type.ZSTD,      // you said .tzst
                    in, imagefs);                      // helper already exists in the project
        } catch (IOException e) {
            Log.e("ImageFsInstaller", "evshim deploy failed", e);
            return;
        }

        // ➌  Make sure the new libs are world-readable / executable
        chmod(new File(imagefs, "lib/libevshim.so"));
        chmod(new File(imagefs, "lib/libSDL2.so"));
        chmod(new File(imagefs, "lib/libSDL2-2.0.so"));
        chmod(new File(imagefs, "lib/libSDL2-2.0.so.0"));
    }
    private static void chmod(File f) { if (f.exists()) FileUtils.chmod(f, 0755);}
}
