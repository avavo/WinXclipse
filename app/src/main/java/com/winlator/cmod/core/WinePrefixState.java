package com.winlator.cmod.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Prefix metadata is validated before any registry editor or guest process runs. */
public final class WinePrefixState {
    private static final String RECEIPT = ".winxclipse-runtime-v1";
    private static final String[] REGISTRIES = {"system.reg", "user.reg", "userdef.reg"};
    private static final Pattern HEADER = Pattern.compile("^WINE REGISTRY Version \\d+[\\t ]*\\r?$", Pattern.MULTILINE);
    private static final Pattern ARCH = Pattern.compile("^#arch=(win32|win64)[\\t ]*\\r?$", Pattern.MULTILINE);

    private WinePrefixState() {}

    public static void ensureRegistries(File prefix, boolean win64) throws IOException {
        if (!prefix.isDirectory() && !prefix.mkdirs()) throw new IOException("Cannot create Wine prefix: " + prefix);
        String desired = win64 ? "win64" : "win32";
        String[] contents = new String[REGISTRIES.length];
        // Check every existing registry before writing anything. An architecture
        // marker cannot convert a prefix, and a bad header must not erase saves/settings.
        for (int i = 0; i < REGISTRIES.length; i++) {
            File file = new File(prefix, REGISTRIES[i]);
            if (file.exists() && !file.isFile()) throw new IOException("Invalid Wine registry: " + file);
            String content = file.isFile() ? read(file) : "";
            contents[i] = content;
            if (content.isEmpty()) continue;
            Matcher header = HEADER.matcher(content);
            if (!header.find() || header.start() != 0) throw new IOException("Invalid Wine registry header: " + file);
            Matcher arch = ARCH.matcher(content);
            while (arch.find()) {
                if (!desired.equals(arch.group(1))) throw new IOException(
                        "Wine prefix architecture is " + arch.group(1) + ", runtime requires " + desired + ": " + file);
            }
        }
        for (int i = 0; i < REGISTRIES.length; i++) {
            String content = contents[i];
            if (content.isEmpty()) content = "WINE REGISTRY Version 2\n#arch=" + desired + "\n";
            else if (!ARCH.matcher(content).find()) {
                int end = content.indexOf('\n');
                content = end >= 0 ? content.substring(0, end + 1) + "#arch=" + desired + "\n" + content.substring(end + 1)
                        : content + "\n#arch=" + desired + "\n";
            }
            if (!content.equals(contents[i])) {
                invalidate(prefix);
                write(new File(prefix, REGISTRIES[i]), content);
            }
        }
    }

    /** Drive mappings come from the container, never from an archived machine's links. */
    public static boolean isDriveMapping(File extractionRoot, File entry) {
        java.nio.file.Path relative = extractionRoot.toPath().toAbsolutePath().normalize()
                .relativize(entry.toPath().toAbsolutePath().normalize());
        return (relative.getNameCount() >= 3 && ".wine".equals(relative.getName(0).toString())
                && "dosdevices".equals(relative.getName(1).toString())
                || relative.getNameCount() >= 2 && "dosdevices".equals(relative.getName(0).toString()))
                && !relative.startsWith("..");
    }

    public static File wineExecutable(File bin) throws IOException {
        for (String name : new String[]{"wine", "wine64"}) {
            File file = new File(bin, name);
            if (file.isFile() && file.length() > 0) return file;
        }
        throw new IOException("Wine executable is missing or empty: " + bin);
    }

    public static String fingerprint(String identity, File wine, File wineInf) throws IOException {
        String value = identity + "\n" + wine.getCanonicalPath() + "\n" + wine.length() + "\n" + wine.lastModified()
                + "\n" + wineInf.getCanonicalPath() + "\n" + wineInf.length() + "\n" + wineInf.lastModified();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(value.getBytes(StandardCharsets.UTF_8));
            byte[] buffer = new byte[16 * 1024];
            for (File file : new File[]{wine, wineInf}) {
                digest.update((byte)0);
                try (FileInputStream input = new FileInputStream(file)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder result = new StringBuilder();
            for (byte item : hash) result.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    public static boolean isPrepared(File prefix, String fingerprint) throws IOException {
        File receipt = new File(prefix, RECEIPT);
        return hasCoreFiles(prefix) && receipt.isFile() && fingerprint.equals(read(receipt).trim());
    }

    public static boolean hasCoreFiles(File prefix) {
        for (String name : new String[]{"ntdll.dll", "kernel32.dll", "wineboot.exe"}) {
            File file = new File(prefix, "drive_c/windows/system32/" + name);
            if (!file.isFile() || file.length() == 0) return false;
        }
        return true;
    }

    public static void markPrepared(File prefix, String fingerprint) throws IOException {
        if (!hasCoreFiles(prefix)) throw new IOException("Wine prefix has missing or empty system modules: " + prefix);
        write(new File(prefix, RECEIPT), fingerprint + "\n");
    }

    public static void invalidate(File prefix) throws IOException {
        Files.deleteIfExists(new File(prefix, RECEIPT).toPath());
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static void write(File file, String value) throws IOException {
        File temporary = File.createTempFile(".wine-reg-", ".tmp", file.getParentFile());
        try {
            Files.write(temporary.toPath(), value.getBytes(StandardCharsets.UTF_8));
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary.toPath()); }
    }
}
