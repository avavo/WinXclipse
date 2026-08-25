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
        String[] versions = activity.getResources().getStringArray(R.array.wine_entries);
        File rootDir = ImageFs.find(activity).getRootDir();
        for (String version : versions) {
            File outFile = new File(rootDir, "/opt/" + version);
            outFile.mkdirs();
            if (!TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, activity, version + ".txz", outFile))
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, activity, version + ".tzst", outFile);
        }
    }

    public static void installFromAssets(final MainActivity activity) {
        AppUtils.keepScreenOn(activity);
        ImageFs imageFs = ImageFs.find(activity);
        File rootDir = imageFs.getRootDir();

        SettingsFragment.resetEmulatorsVersion(activity);

        final DownloadProgressDialog dialog = new DownloadProgressDialog(activity);
        dialog.show(R.string.installing_system_files);
        Executors.newSingleThreadExecutor().execute(() -> {
            clearRootDir(rootDir);
            final float compressionRatio = 4.0f;
            final long contentLength = (long)(FileUtils.getSize(activity, "imagefs.tzst") * compressionRatio);
            AtomicLong totalSizeRef = new AtomicLong();

            // 0..78% imagefs, 78..93% wine, 93..100% finalization – bar moves continuously.
            boolean success = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, activity, "imagefs.tzst", rootDir, (file, size) -> {
                if (size > 0) {
                    long totalSize = totalSizeRef.addAndGet(size);
                    final int progress = Math.min(78, (int)(((float)totalSize / contentLength) * 78));
                    activity.runOnUiThread(() -> dialog.setProgress(progress));
                }
                return file;
            });

            if (success) {
                activity.runOnUiThread(() -> dialog.setProgress(78));
                // Wine: prefer ZSTD (faster) and stream 78..93% per file so 85% no longer appears frozen.
                String[] versions = activity.getResources().getStringArray(R.array.wine_entries);
                long totalWineEst = 0;
                for (String v : versions) {
                    long s = FileUtils.getSize(activity, v + ".tzst");
                    if (s == 0) s = FileUtils.getSize(activity, v + ".txz");
                    totalWineEst += s * 4L;
                }
                totalWineEst = Math.max(1, totalWineEst);
                AtomicLong wineDone = new AtomicLong(0);
                final long totalWineFinal = totalWineEst;
                for (String version : versions) {
                    File outFile = new File(rootDir, "/opt/" + version);
                    outFile.mkdirs();
                    OnExtractFileListener wineListener = (file, size) -> {
                        if (size > 0) {
                            long d = wineDone.addAndGet(size);
                            int prog = 78 + (int)Math.min(15, d * 15 / totalWineFinal);
                            activity.runOnUiThread(() -> dialog.setProgress(Math.min(93, prog)));
                        }
                        return file;
                    };
                    boolean ok = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, activity, version + ".tzst", outFile, wineListener);
                    if (!ok) TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, activity, version + ".txz", outFile, wineListener);
                }
                activity.runOnUiThread(() -> dialog.setProgress(93));
                // Finalization (create version file + reset containers) now owns 93..100% so 100% never hangs.
                imageFs.createImgVersionFile(LATEST_VERSION);
                activity.runOnUiThread(() -> dialog.setProgress(96));
                resetContainerImgVersions(activity);
                activity.runOnUiThread(() -> dialog.setProgress(100));
            }
            else AppUtils.showToast(activity, R.string.unable_to_install_system_files);

            dialog.closeOnUiThread();
        });
    }

    public static void installFromAssets(final MainActivity activity, final Runnable onCompletion) {
        AppUtils.keepScreenOn(activity);
        ImageFs imageFs = ImageFs.find(activity);
        File rootDir = imageFs.getRootDir();

        SettingsFragment.resetEmulatorsVersion(activity);

        final DownloadProgressDialog dialog = new DownloadProgressDialog(activity);
        dialog.show(R.string.installing_system_files);
        Executors.newSingleThreadExecutor().execute(() -> {
            clearRootDir(rootDir);
            final float compressionRatio = 4.0f;
            final long contentLength = (long)(FileUtils.getSize(activity, "imagefs.tzst") * compressionRatio);
            AtomicLong totalSizeRef = new AtomicLong();

            boolean success = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, activity, "imagefs.tzst", rootDir, (file, size) -> {
                if (size > 0) {
                    long totalSize = totalSizeRef.addAndGet(size);
                    final int progress = Math.min(78, (int)(((float)totalSize / contentLength) * 78));
                    activity.runOnUiThread(() -> dialog.setProgress(progress));
                }
                return file;
            });

            if (success) {
                activity.runOnUiThread(() -> dialog.setProgress(78));
                String[] versions = activity.getResources().getStringArray(R.array.wine_entries);
                long totalWineEst2 = 0;
                for (String v : versions) { long s = FileUtils.getSize(activity, v + ".tzst"); if (s==0) s = FileUtils.getSize(activity, v + ".txz"); totalWineEst2 += s * 4L; }
                totalWineEst2 = Math.max(1, totalWineEst2);
                AtomicLong wineDone2 = new AtomicLong(0);
                final long totalWineFinal2 = totalWineEst2;
                for (String version : versions) {
                    File outFile = new File(rootDir, "/opt/" + version);
                    outFile.mkdirs();
                    OnExtractFileListener wl = (file, size) -> {
                        if (size > 0) { long d = wineDone2.addAndGet(size); int prog = 78 + (int)Math.min(15, d * 15 / totalWineFinal2); activity.runOnUiThread(() -> dialog.setProgress(Math.min(93, prog))); }
                        return file;
                    };
                    boolean ok = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, activity, version + ".tzst", outFile, wl);
                    if (!ok) TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, activity, version + ".txz", outFile, wl);
                }
                activity.runOnUiThread(() -> dialog.setProgress(93));
                imageFs.createImgVersionFile(LATEST_VERSION);
                activity.runOnUiThread(() -> dialog.setProgress(96));
                resetContainerImgVersions(activity);
                activity.runOnUiThread(() -> dialog.setProgress(100));
            }
            else AppUtils.showToast(activity, R.string.unable_to_install_system_files);

            dialog.closeOnUiThread();
            if (onCompletion != null) {
                activity.runOnUiThread(onCompletion);
            }
        });
    }

    public static void installIfNeeded(final MainActivity activity) {
        ImageFs imageFs = ImageFs.find(activity);
        if (!imageFs.isValid() || imageFs.getVersion() < LATEST_VERSION) installFromAssets(activity);
    }

    public static void installIfNeeded(final MainActivity activity, final Runnable onCompletion) {
        ImageFs imageFs = ImageFs.find(activity);
        if (!imageFs.isValid() || imageFs.getVersion() < LATEST_VERSION) {
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
