package com.winlator.cmod.contents;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;

public class Downloader {

    public interface ProgressCallback {
        void onProgress(int percent);
    }

    public static boolean downloadFile(String address, File file) {
        return downloadFile(address, file, null);
    }

    public static boolean downloadFile(String address, File file, ProgressCallback callback) {
        HttpURLConnection connection = null;
        File partial = null;
        try {
            File parent = file.getAbsoluteFile().getParentFile();
            if (parent == null || !parent.isDirectory() && !parent.mkdirs()) return false;
            URL url = new URL(address);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.connect();
            int response = connection.getResponseCode();
            // No Range request was made: a partial or empty HTTP success is not
            // a complete package and must never reach the installer.
            if (response != HttpURLConnection.HTTP_OK) return false;

            long contentLength = connection.getContentLengthLong();
            long downloaded = 0;
            int lastPercent = -1;
            partial = File.createTempFile("content-", ".part", parent);
            try (InputStream input = connection.getInputStream();
                 OutputStream output = new FileOutputStream(partial)) {
                byte[] data = new byte[64 * 1024];
                int count;
                while ((count = input.read(data)) != -1) {
                    output.write(data, 0, count);
                    downloaded += count;
                    if (callback != null && contentLength > 0) {
                        int percent = (int) Math.min(99, downloaded * 100L / contentLength);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            callback.onProgress(percent);
                        }
                    }
                }
                output.flush();
            }
            if (downloaded == 0 || contentLength >= 0 && downloaded != contentLength) return false;
            try {
                Files.move(partial.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            }
            catch (AtomicMoveNotSupportedException error) {
                Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            if (callback != null) callback.onProgress(100);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            if (connection != null) connection.disconnect();
            if (partial != null && partial.exists()) partial.delete();
        }
    }

    public static String downloadString(String address) {
        URLConnection connection = null;
        try {
            URL url = new URL(address);
            connection = url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", "WinXclipse-Android");
            connection.connect();

            if (connection instanceof HttpURLConnection) {
                int response = ((HttpURLConnection) connection).getResponseCode();
                if (response < 200 || response >= 300) return null;
            }
            try (InputStream input = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(input))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                return sb.toString();
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            if (connection instanceof HttpURLConnection) {
                ((HttpURLConnection) connection).disconnect();
            }
        }
    }
}
