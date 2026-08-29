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

public class Downloader {

    public interface ProgressCallback {
        void onProgress(int percent);
    }

    public static boolean downloadFile(String address, File file) {
        return downloadFile(address, file, null);
    }

    public static boolean downloadFile(String address, File file, ProgressCallback callback) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(address);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);
            connection.connect();
            int response = connection.getResponseCode();
            if (response < 200 || response >= 300) return false;

            long contentLength = connection.getContentLengthLong();
            long downloaded = 0;
            int lastPercent = -1;
            try (InputStream input = connection.getInputStream();
                 OutputStream output = new FileOutputStream(file)) {
                byte[] data = new byte[64 * 1024];
                int count;
                while ((count = input.read(data)) != -1) {
                    output.write(data, 0, count);
                    downloaded += count;
                    if (callback != null && contentLength > 0) {
                        int percent = (int) Math.min(100, downloaded * 100L / contentLength);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            callback.onProgress(percent);
                        }
                    }
                }
                output.flush();
            }
            if (callback != null) callback.onProgress(100);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            if (connection != null) connection.disconnect();
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
