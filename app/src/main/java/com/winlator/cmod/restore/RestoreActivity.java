package com.winlator.cmod.restore;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import com.winlator.cmod.R;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.core.TarCompressorUtils;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Executors;

public class RestoreActivity extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(com.winlator.cmod.core.AppLanguage.attach(base));
    }

    private PreloaderDialog preloaderDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        preloaderDialog = new PreloaderDialog(this);
        preloaderDialog.showOnUiThread(R.string.restoring_data);

        Uri backupUri = getIntent().getData();
        if (backupUri != null) {
            startRestoreOperation(backupUri);
        } else {
            finish(); // Close the activity if no URI is passed
        }
    }

    private void startRestoreOperation(Uri backupUri) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                File dataDir = getFilesDir().getParentFile(); // App's data directory
                boolean success;
                try (RestoreArchive backup = openBackup(backupUri)) {
                    success = TarCompressorUtils.extractTar(backup.file, dataDir, (file, size) -> {
                        // Skip symbolic links pointing to critical directories
                        if (isCriticalSymbolicLink(file)) {
                            Log.d("RestoreOp", "Skipping symbolic link: " + file.getAbsolutePath());
                            return null; // Return null to skip extraction
                        }
                        Log.d("RestoreOp", "Extracting file: " + file.getAbsolutePath());
                        return file;
                    });

                }
                if (success) {
                    onRestoreSuccess();
                } else {
                    throw new IOException("Extraction failed");
                }
            } catch (Exception e) {
                Log.e("RestoreOp", "Restore failed", e);
                onRestoreFailed();
            }
        });
    }

    private RestoreArchive openBackup(Uri uri) throws IOException {
        if ("file".equals(uri.getScheme())) {
            String path = uri.getPath();
            if (path == null) throw new IOException("Backup file is unavailable");
            return RestoreArchive.borrow(new File(path));
        }
        return RestoreArchive.copy(getContentResolver().openInputStream(uri), getCacheDir());
    }

    private boolean isCriticalSymbolicLink(File file) {
        if (FileUtils.isSymlink(file)) {
            String targetPath = FileUtils.readSymlink(file);
            // Check if the symlink points to the Downloads or Storage directories
            if (targetPath.contains("Download") || targetPath.contains("storage")) {
                return true;
            }
        }
        return false;
    }


    private void onRestoreSuccess() {
        runOnUiThread(() -> {
            preloaderDialog.closeOnUiThread();
            AppUtils.showToast(this, R.string.restore_completed);
            finish();
            // Restart the main application after restore
            Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (intent != null) {
                startActivity(intent);
            }
        });
    }

    private void onRestoreFailed() {
        runOnUiThread(() -> {
            preloaderDialog.closeOnUiThread();
            AppUtils.showToast(this, R.string.restore_failed);
            finish();
        });
    }
}
