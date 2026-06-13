package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;

import com.winlator.cmod.R;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.UnitUtils;
import com.winlator.cmod.widget.LogView;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;

public class DebugDialog extends ContentDialog implements Callback<String> {
    private final LogView logView;
    private static boolean paused = false;
    private BufferedWriter writer;
    private File logFile;

    public DebugDialog(@NonNull Context context) {
        super(context, R.layout.debug_dialog);
        setIcon(R.drawable.icon_debug);
        setTitle(context.getString(R.string.logs));

        logView = findViewById(R.id.LogView);
        logView.getLayoutParams().width = (int) UnitUtils.dpToPx(UnitUtils.pxToDp(AppUtils.getScreenWidth()) * 0.7f);

        findViewById(R.id.BTCancel).setVisibility(View.GONE);

        LinearLayout llBottomBarPanel = findViewById(R.id.LLBottomBarPanel);
        llBottomBarPanel.setVisibility(View.VISIBLE);

        View toolbarView = LayoutInflater.from(context).inflate(R.layout.debug_toolbar, llBottomBarPanel, false);

        toolbarView.findViewById(R.id.BTClear).setOnClickListener((v) -> logView.clear());

        toolbarView.findViewById(R.id.BTPause).setOnClickListener((v) -> {
            setPaused(!paused);
            ((ImageButton) v).setImageResource(getPaused() ? R.drawable.icon_play : R.drawable.icon_pause);
        });

        View btShare = toolbarView.findViewById(R.id.BTShareLog);
        if (btShare != null) {
            btShare.setOnClickListener((v) -> shareLogFile(context));
        }

        llBottomBarPanel.addView(toolbarView);

        try {
            logFile = new File(context.getCacheDir(), "winxclipse_backend_logs.txt");
            writer = new BufferedWriter(new FileWriter(logFile, false));
        }
        catch (IOException e) {
            writer = null;
            logView.append("Log file disabled: " + e.getMessage() + "\n");
        }

        call("WinXclipse Backend Logs - logcat snapshot");
        loadInitialLogcat();
    }

    private void loadInitialLogcat() {
        new Thread(() -> {
            final int[] count = {0};
            try {
                Process process = new ProcessBuilder("logcat", "-d", "-t", "300").redirectErrorStream(true).start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        final String out = line;
                        count[0]++;
                        logView.post(() -> call(out));
                    }
                }
                process.destroy();

                if (count[0] == 0) {
                    logView.post(() -> call("logcat returned no visible lines for this app."));
                }
            }
            catch (Exception e) {
                logView.post(() -> call("Failed to read logcat: " + e.getMessage()));
            }
        }, "WinXclipseLogcatLoader").start();
    }

    @Override
    public void call(final String line) {
        if (!getPaused()) {
            logView.append(line + "\n");
        }

        if (writer != null) {
            try {
                writer.write(line + "\n");
                writer.flush();
            }
            catch (IOException e) {
                writer = null;
                logView.append("Log file write disabled: " + e.getMessage() + "\n");
            }
        }
    }

    private void shareLogFile(Context context) {
        try {
            if (writer != null) writer.flush();

            if (logFile == null || !logFile.exists() || logFile.length() == 0) {
                call("No log file to share yet.");
                Toast.makeText(context, "No log file to share yet", Toast.LENGTH_SHORT).show();
                return;
            }

            Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", logFile);

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
        try {
            if (writer != null) writer.close();
        }
        catch (IOException ignored) {
        }
        writer = null;
        super.dismiss();
    }

    public static void setPaused(boolean cond) {
        paused = cond;
    }

    public static boolean getPaused() {
        return paused;
    }
}
