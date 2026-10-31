package com.winlator.cmod.core;

import java.io.File;
import java.io.IOException;

/** Filesystem receipts must describe usable payloads, not just profile.json. */
public final class RuntimeFiles {
    private RuntimeFiles() {}

    public static boolean hasPayload(File root, String relative) {
        if (root == null || relative == null || relative.isEmpty()) return false;
        try {
            File base = root.getCanonicalFile();
            File file = new File(base, relative).getCanonicalFile();
            if (!file.getPath().startsWith(base.getPath() + File.separator)) return false;
            if (file.isFile()) return file.length() > 0;
            String[] children = file.isDirectory() ? file.list() : null;
            return children != null && children.length > 0;
        }
        catch (IOException error) {
            return false;
        }
    }

    public static boolean hasWine(File root, String binPath) {
        if (root == null || binPath == null || binPath.isEmpty()) return false;
        File wine = new File(root, binPath + "/wine");
        File wine64 = new File(root, binPath + "/wine64");
        return wine.isFile() && hasPayload(root, binPath + "/wine")
                || wine64.isFile() && hasPayload(root, binPath + "/wine64");
    }

    public static boolean samePayload(File source, File applied) {
        if (source == null || applied == null || !source.isFile() || !applied.isFile()
                || source.length() == 0 || source.length() != applied.length()) return false;
        try { return PackageIdentity.sha256(source).equals(PackageIdentity.sha256(applied)); }
        catch (IOException failure) { return false; }
    }
}
