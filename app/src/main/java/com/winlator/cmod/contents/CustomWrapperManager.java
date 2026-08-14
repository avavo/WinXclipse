package com.winlator.cmod.contents;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.core.TarCompressorUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Stores user supplied Vulkan wrappers without tying them to an APK build. */
public final class CustomWrapperManager {
    private static final String TAG = "CustomWrapperManager";
    private static final String PREFIX = "wrapper-";
    private final Context context;
    private final File wrapperDir;

    public CustomWrapperManager(Context context) {
        this.context = context.getApplicationContext();
        wrapperDir = new File(this.context.getFilesDir(), "contents/wrappers");
        if (!wrapperDir.isDirectory()) wrapperDir.mkdirs();
    }

    public List<String> getInstalledIds() {
        ArrayList<String> result = new ArrayList<>();
        File[] files = wrapperDir.listFiles();
        if (files == null) return result;
        for (File file : files) {
            String name = file.getName().toLowerCase(Locale.ENGLISH);
            if (!file.isFile() || !name.startsWith(PREFIX)) continue;
            if (!(name.endsWith(".tzst") || name.endsWith(".so"))) continue;
            result.add(stripExtension(name));
        }
        Collections.sort(result);
        return result;
    }

    public List<String> getInstalledLabels() {
        ArrayList<String> result = new ArrayList<>();
        for (String id : getInstalledIds()) result.add(toDisplayName(id));
        return result;
    }

    public String install(Uri source, String requestedName) {
        String id = toIdentifier(requestedName);
        if (id.isEmpty()) return null;

        String sourceName = source.toString().startsWith("/") || "file".equals(source.getScheme())
                ? new File(source.getPath() != null ? source.getPath() : source.toString()).getName()
                : FileUtils.getUriFileName(context, source);
        String extension = normalizeExtension(sourceName);
        if (extension == null) return null;

        File destination = new File(wrapperDir, id + extension);
        File temporary = new File(wrapperDir, ".installing-" + id + extension);
        FileUtils.delete(temporary);
        File directSource = source.getPath() == null ? null : new File(source.getPath());
        boolean copied = directSource != null && directSource.isFile()
                ? FileUtils.copy(directSource, temporary)
                : FileUtils.copy(context, source, temporary, null);
        if (!copied) return null;

        boolean valid;
        if (".so".equals(extension)) {
            valid = temporary.length() > 4096;
        }
        else {
            File validationDir = new File(context.getCacheDir(), "wrapper-validation-" + id);
            FileUtils.delete(validationDir);
            valid = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, temporary, validationDir)
                    && new File(validationDir, "usr/lib/libvulkan_wrapper.so").isFile();
            FileUtils.delete(validationDir);
        }

        if (!valid) {
            FileUtils.delete(temporary);
            return null;
        }

        remove(id);
        if (!temporary.renameTo(destination)) {
            valid = FileUtils.copy(temporary, destination);
            FileUtils.delete(temporary);
            if (!valid) return null;
        }
        return id;
    }

    public boolean apply(String wrapperId, File imageFsRoot) {
        String id = toIdentifier(wrapperId);
        File archive = new File(wrapperDir, id + ".tzst");
        if (archive.isFile()) {
            return TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, archive, imageFsRoot);
        }

        File library = new File(wrapperDir, id + ".so");
        if (library.isFile()) {
            File target = new File(imageFsRoot, "usr/lib/libvulkan_wrapper.so");
            boolean copied = FileUtils.copy(library, target);
            if (copied) FileUtils.chmod(target, 0755);
            return copied;
        }
        return false;
    }

    public void remove(String wrapperId) {
        String id = toIdentifier(wrapperId);
        FileUtils.delete(new File(wrapperDir, id + ".tzst"));
        FileUtils.delete(new File(wrapperDir, id + ".so"));
    }

    public static String toIdentifier(String value) {
        if (value == null) return "";
        String parsed = StringUtils.parseIdentifier(value).toLowerCase(Locale.ENGLISH)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        if (parsed.isEmpty()) return "";
        return parsed.startsWith(PREFIX) ? parsed : PREFIX + parsed;
    }

    public static String toDisplayName(String wrapperId) {
        String id = toIdentifier(wrapperId);
        if (id.isEmpty()) return "";
        String suffix = id.substring(PREFIX.length());
        StringBuilder name = new StringBuilder("Wrapper-");
        for (String part : suffix.split("-")) {
            if (part.isEmpty()) continue;
            if (part.equals("gamenative")) name.append("GameNative");
            else name.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            name.append('-');
        }
        if (name.charAt(name.length() - 1) == '-') name.setLength(name.length() - 1);
        return name.toString();
    }

    private static String normalizeExtension(String sourceName) {
        if (sourceName == null) return null;
        String lower = sourceName.toLowerCase(Locale.ENGLISH);
        if (lower.endsWith(".so")) return ".so";
        if (lower.endsWith(".tzst") || lower.endsWith(".tstz")
                || lower.endsWith(".tzts") || lower.endsWith(".zst")) return ".tzst";
        return null;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
