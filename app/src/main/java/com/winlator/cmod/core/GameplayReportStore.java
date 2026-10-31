package com.winlator.cmod.core;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/** Publish a complete TXT in Downloads. A failed write never leaves a success receipt. */
public final class GameplayReportStore {
    private GameplayReportStore() {}
    public static String save(Context context, String report) throws IOException {
        String name = "measurement-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date())
                + "-" + UUID.randomUUID().toString().substring(0, 8) + ".txt";
        byte[] bytes = report.getBytes(StandardCharsets.UTF_8);
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/WinXclipse");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("Cannot create Download/WinXclipse/" + name);
            try {
                try (OutputStream stream = context.getContentResolver().openOutputStream(uri, "w")) {
                    if (stream == null) throw new IOException("Cannot open report output");
                    stream.write(bytes);
                }
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.Downloads.IS_PENDING, 0);
                if (context.getContentResolver().update(uri, ready, null, null) != 1)
                    throw new IOException("Cannot publish report");
                return "Download/WinXclipse/" + name;
            } catch (Exception failure) {
                try { context.getContentResolver().delete(uri, null, null); } catch (RuntimeException ignored) {}
                throw failure instanceof IOException ? (IOException)failure : new IOException(failure);
            }
        }
        File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "WinXclipse");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create " + directory);
        File temporary = new File(directory, "." + name + ".tmp"), target = new File(directory, name);
        try {
            try (FileOutputStream stream = new FileOutputStream(temporary)) { stream.write(bytes); }
            java.nio.file.Files.move(temporary.toPath(), target.toPath());
            return target.getAbsolutePath();
        } finally { temporary.delete(); }
    }
}
