package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;

import com.winlator.cmod.BuildConfig;
import com.winlator.cmod.R;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.ProcessHelper;
import com.winlator.cmod.core.UnitUtils;
import com.winlator.cmod.widget.LogView;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DebugDialog extends ContentDialog implements Callback<String> {
    private static final int FLUSH_INTERVAL_LINES = 25;
    private static boolean paused;

    private final LogView logView;
    private final Object writerLock = new Object();
    private BufferedWriter writer;
    private File logFile;
    private int pendingLines;

    public DebugDialog(@NonNull Context context) {
        super(context, R.layout.debug_dialog);
        setIcon(R.drawable.icon_debug);
        setTitle(context.getString(R.string.logs));
        if (getWindow() != null && AppUtils.isDarkMode(context)) {
            getWindow().setBackgroundDrawableResource(R.drawable.artwork_dialog_background);
        }
        // Purple toolbar icons in both modes (dark icons vanish on black).
        int accent = resolveAccentColor(context);

        logView = findViewById(R.id.LogView);
        logView.getLayoutParams().width = (int) UnitUtils.dpToPx(
                UnitUtils.pxToDp(AppUtils.getScreenWidth()) * 0.7f);
        findViewById(R.id.BTCancel).setVisibility(View.GONE);

        LinearLayout bottomBar = findViewById(R.id.LLBottomBarPanel);
        bottomBar.setVisibility(View.VISIBLE);
        View toolbar = LayoutInflater.from(context).inflate(R.layout.debug_toolbar, bottomBar, false);
        tintToolbarButton(toolbar, R.id.BTClear, accent);
        tintToolbarButton(toolbar, R.id.BTPause, accent);
        toolbar.findViewById(R.id.BTClear).setOnClickListener(v -> logView.clear());
        toolbar.findViewById(R.id.BTPause).setOnClickListener(v -> {
            setPaused(!paused);
            ((ImageButton) v).setImageResource(paused ? R.drawable.icon_play : R.drawable.icon_pause);
        });
        View shareButton = toolbar.findViewById(R.id.BTShareLog);
        if (shareButton != null) {
            ((ImageButton) shareButton).setColorFilter(accent);
            shareButton.setOnClickListener(v -> shareLogFile(context));
        }
        View downloadButton = toolbar.findViewById(R.id.BTDownloadLog);
        if (downloadButton != null) {
            ((ImageButton) downloadButton).setColorFilter(accent);
            downloadButton.setOnClickListener(v -> saveLogToDownloads(context));
        }
        bottomBar.addView(toolbar);

        openLogWriter(false);
        call("WinXclipse backend log session");
        call("app=" + BuildConfig.APPLICATION_ID + " version=" + BuildConfig.VERSION_NAME
                + " android=" + Build.VERSION.SDK_INT + " device=" + Build.MANUFACTURER + " " + Build.MODEL);

        List<String> history = ProcessHelper.getRecentDebugLines();
        if (!history.isEmpty()) {
            call("Recent process output (" + history.size() + " lines)");
            for (String line : history) call("[history] " + line);
        }
        loadInitialLogcat();
    }

    @Override
    public void show() {
        if (writer == null) openLogWriter(true);
        ProcessHelper.addDebugCallback(this);
        super.show();
    }

    private void loadInitialLogcat() {
        new Thread(() -> {
            int count = 0;
            try {
                java.lang.Process process = new ProcessBuilder("logcat", "-d", "-t", "300",
                        "--pid=" + android.os.Process.myPid()).redirectErrorStream(true).start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        count++;
                        call("[logcat] " + line);
                    }
                }
                process.waitFor();
                if (count == 0) call("[logcat] no visible application lines");
            }
            catch (Exception e) {
                call("[logcat] read failed: " + e.getMessage());
            }
        }, "WinXclipseLogcatLoader").start();
    }

    @Override
    public void call(final String line) {
        if (!paused) logView.post(() -> logView.append(line));

        synchronized (writerLock) {
            if (writer == null) return;
            try {
                writer.write("[" + timestamp() + "] " + line);
                writer.newLine();
                if (++pendingLines >= FLUSH_INTERVAL_LINES) {
                    writer.flush();
                    pendingLines = 0;
                }
            }
            catch (IOException e) {
                closeWriter();
                logView.post(() -> logView.append("Log file write disabled: " + e.getMessage()));
            }
        }
    }

    private void openLogWriter(boolean append) {
        synchronized (writerLock) {
            try {
                if (logFile == null) {
                    String name = "winxclipse_backend_" + new SimpleDateFormat(
                            "yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".txt";
                    logFile = new File(getContext().getCacheDir(), name);
                }
                writer = new BufferedWriter(new FileWriter(logFile, append));
                pendingLines = 0;
            }
            catch (IOException e) {
                writer = null;
                logView.post(() -> logView.append("Log file disabled: " + e.getMessage()));
            }
        }
    }

    private static int resolveAccentColor(Context context) {
        android.util.TypedValue typed = new android.util.TypedValue();
        if (context.getTheme().resolveAttribute(R.attr.colorAccent, typed, true)) {
            return typed.data;
        }
        return 0xFF7C4DFF;
    }

    private static void tintToolbarButton(View toolbar, int viewId, int color) {
        View button = toolbar.findViewById(viewId);
        if (button instanceof ImageButton) ((ImageButton) button).setColorFilter(color);
    }

    private void saveLogToDownloads(Context context) {
        try {
            synchronized (writerLock) {
                if (writer != null) writer.flush();
                pendingLines = 0;
            }
            if (logFile == null || !logFile.exists() || logFile.length() == 0) {
                Toast.makeText(context, "No log file to save yet", Toast.LENGTH_SHORT).show();
                return;
            }
            File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), "WinXclipse/logs");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                Toast.makeText(context, "Could not create Downloads folder", Toast.LENGTH_SHORT).show();
                return;
            }
            java.io.FileInputStream in = null;
            java.io.FileOutputStream out = null;
            try {
                in = new java.io.FileInputStream(logFile);
                out = new java.io.FileOutputStream(new File(dir, logFile.getName()));
                byte[] buffer = new byte[65536];
                int count;
                while ((count = in.read(buffer)) > 0) out.write(buffer, 0, count);
            }
            finally {
                if (in != null) try { in.close(); } catch (IOException ignored) {}
                if (out != null) try { out.close(); } catch (IOException ignored) {}
            }
            Toast.makeText(context, "Log saved to Downloads/WinXclipse/logs", Toast.LENGTH_LONG).show();
        }
        catch (Exception e) {
            call("Save failed: " + e.getMessage());
            Toast.makeText(context, "Failed to save log: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void shareLogFile(Context context) {
        try {
            synchronized (writerLock) {
                if (writer != null) writer.flush();
                pendingLines = 0;
            }
            if (logFile == null || !logFile.exists() || logFile.length() == 0) {
                Toast.makeText(context, "No log file to share yet", Toast.LENGTH_SHORT).show();
                return;
            }

            Uri uri = FileProvider.getUriForFile(context,
                    context.getPackageName() + ".fileprovider", logFile);
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "WinXclipse Backend Log");
            shareIntent.putExtra(Intent.EXTRA_TEXT, "Backend log from WinXclipse");
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(Intent.createChooser(shareIntent, "Share log"));
        }
        catch (Exception e) {
            call("Share failed: " + e.getMessage());
            Toast.makeText(context, "Failed to share log: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void dismiss() {
        ProcessHelper.removeDebugCallback(this);
        synchronized (writerLock) {
            closeWriter();
        }
        super.dismiss();
    }

    private void closeWriter() {
        if (writer == null) return;
        try {
            writer.flush();
            writer.close();
        }
        catch (IOException ignored) {
        }
        writer = null;
        pendingLines = 0;
    }

    private static String timestamp() {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    public static void setPaused(boolean value) {
        paused = value;
    }

    public static boolean getPaused() {
        return paused;
    }
}
