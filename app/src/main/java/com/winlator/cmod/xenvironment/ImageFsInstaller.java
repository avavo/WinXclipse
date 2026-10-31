package com.winlator.cmod.xenvironment;

import android.content.Context;
import android.util.Log;
import android.view.WindowManager;

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
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.core.ImageFsMaintenance;
import com.winlator.cmod.core.VulkanRuntimePayload;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.concurrent.atomic.AtomicLong;

public abstract class ImageFsInstaller {
    public static final byte LATEST_VERSION = 25;

    private static void resetContainerImgVersions(Context context) {
        ContainerManager manager = new ContainerManager(context);
        for (Container container : manager.getContainers()) {
            String imgVersion = container.getExtra("imgVersion");
            String wineVersion = container.getWineVersion();
            if (!imgVersion.isEmpty() && WineInfo.isMainWineVersion(wineVersion)) {
                int previousVersion;
                try { previousVersion = Integer.parseInt(imgVersion); }
                catch (NumberFormatException invalidVersion) { previousVersion = 0; }
                if (previousVersion <= 5) container.putExtra("wineprefixNeedsUpdate", "t");
            }

            container.putExtra("imgVersion", null);
            container.saveData();
        }
    }

    private static final ExecutorService INSTALLER =
            Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "ImageFsPreparation"));

    public static void installWineFromAssets(final MainActivity activity) {
        installIfNeeded(activity);
    }

    public static void installFromAssets(final MainActivity activity) {
        installFromAssets(activity, null);
    }

    public static void installFromAssets(final MainActivity activity, final Runnable onCompletion) {
        prepare(activity, true, onCompletion);
    }

    public static void installIfNeeded(final MainActivity activity) {
        installIfNeeded(activity, null);
    }

    public static void installIfNeeded(final MainActivity activity, final Runnable onCompletion) {
        prepare(activity, false, onCompletion);
    }

    private static void prepare(MainActivity activity, boolean forceImage, Runnable onCompletion) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        Context context = activity.getApplicationContext();
        ImageFs imageFs = ImageFs.find(context);
        File root = imageFs.getRootDir();
        DownloadProgressDialog dialog = new DownloadProgressDialog(activity);
        AtomicBoolean dialogRequested = new AtomicBoolean();
        AtomicBoolean keepScreenOwned = new AtomicBoolean();
        Runnable showInstalling = () -> {
            if (!dialogRequested.compareAndSet(false, true)) return;
            activity.runOnUiThread(() -> {
                if (!activity.isFinishing() && !activity.isDestroyed()) {
                    keepScreenOwned.set((activity.getWindow().getAttributes().flags
                            & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) == 0);
                    AppUtils.keepScreenOn(activity);
                    dialog.show(R.string.installing_system_files);
                }
            });
        };
        AtomicInteger lastProgress = new AtomicInteger(-1);
        IntConsumer progress = value -> {
            if (lastProgress.getAndSet(value) == value) return;
            activity.runOnUiThread(() -> {
                if (!activity.isFinishing() && !activity.isDestroyed()) dialog.setProgress(value);
            });
        };
        // Show before clearing an outdated base; the coordinator still decides again
        // inside its lock. Missing Wine detection/repair stays off the UI thread.
        if (forceImage || !imageFs.isValid() || imageFs.getVersion() < LATEST_VERSION) showInstalling.run();
        INSTALLER.execute(() -> {
            boolean success = false;
            try {
                String[] wineVersions = context.getResources().getStringArray(R.array.wine_entries);
                long wineSize = 0;
                for (String wine : wineVersions) {
                    long size = FileUtils.getSize(context, wine + ".tzst");
                    if (size == 0) size = FileUtils.getSize(context, wine + ".txz");
                    wineSize += size * 4L;
                }
                final long wineEstimate = Math.max(1L, wineSize);
                AtomicLong wineExtracted = new AtomicLong();
                final boolean[] imageUpdate = {false};
                ImageFsMaintenance.prepare(root, LATEST_VERSION, wineVersions, forceImage,
                        new ImageFsMaintenance.Backend() {
                    @Override public boolean extractImage(File destination) {
                        showInstalling.run();
                        imageUpdate[0] = true;
                        long estimate = Math.max(1L, FileUtils.getSize(context, "imagefs.tzst") * 4L);
                        AtomicLong extracted = new AtomicLong();
                        boolean ready = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD,
                                context, "imagefs.tzst", destination, (file, size) -> {
                                    if (size > 0) progress.accept((int)Math.min(78,
                                            extracted.addAndGet(size) * 78L / estimate));
                                    return file;
                                });
                        if (ready) progress.accept(78);
                        return ready;
                    }
                    @Override public boolean extractWine(String version, File staging) {
                        showInstalling.run();
                        OnExtractFileListener listener = (file, size) -> {
                            if (size > 0) {
                                int start = imageUpdate[0] ? 78 : 0;
                                progress.accept(start + (int)Math.min(93 - start,
                                        wineExtracted.addAndGet(size) * (93 - start) / wineEstimate));
                            }
                            return file;
                        };
                        boolean ready = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD,
                                context, version + ".tzst", staging, listener);
                        if (!ready) ready = TarCompressorUtils.extract(TarCompressorUtils.Type.XZ,
                                context, version + ".txz", staging, listener);
                        return ready;
                    }
                    @Override public boolean hasReplacementWine() {
                        ContentsManager manager = new ContentsManager(context);
                        manager.syncContents();
                        for (ContentProfile.ContentType type : new ContentProfile.ContentType[]{
                                ContentProfile.ContentType.CONTENT_TYPE_WINE,
                                ContentProfile.ContentType.CONTENT_TYPE_PROTON}) {
                            for (ContentProfile profile : manager.getProfiles(type)) {
                                if (!profile.bakedInRuntime && manager.isInstalledProfile(profile)) return true;
                            }
                        }
                        return false;
                    }
                    @Override public void prepareDependencies() throws IOException {
                        progress.accept(93);
                        VulkanRuntimePayload.prepare(new File(context.getFilesDir(), "dependencies"),
                                () -> context.getAssets().open(VulkanRuntimePayload.ASSET));
                    }
                    @Override public void imageUpdated() {
                        SettingsFragment.resetEmulatorsVersion(activity);
                        resetContainerImgVersions(context);
                    }
                    @Override public boolean delete(File path) {
                        // Android's native deletion avoids per-file Java overhead in large trees.
                        try {
                            Process process = new ProcessBuilder("rm", "-rf", path.getAbsolutePath()).start();
                            process.waitFor();
                            if (!Files.exists(path.toPath(), LinkOption.NOFOLLOW_LINKS)) return true;
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return false;
                        } catch (IOException ignored) {}
                        boolean deleted = FileUtils.delete(path);
                        if (!deleted) Log.w("ImageFsInstaller", "Could not remove " + path);
                        return deleted;
                    }
                });
                success = true;
            } catch (Exception error) {
                Log.e("ImageFsInstaller", "ImageFS/dependency preparation did not finish", error);
            }
            final boolean completed = success;
            activity.runOnUiThread(() -> {
                dialog.close();
                if (keepScreenOwned.get()) activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (completed) {
                    if (onCompletion != null) onCompletion.run();
                } else AppUtils.showToast(activity, R.string.unable_to_install_system_files);
            });
        });
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
