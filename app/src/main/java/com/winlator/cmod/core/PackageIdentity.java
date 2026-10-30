package com.winlator.cmod.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Exact portable identities for packages that do not have a WCP manifest. */
public final class PackageIdentity {
    private PackageIdentity() {}

    public static String canonical(String value) {
        return (value == null ? "" : value.trim()).toLowerCase(Locale.ENGLISH)
                .replaceFirst("\\.(?:zip|tzst|tstz|tzts|zst|so)$", "")
                .replaceAll("[^a-z0-9]", "");
    }

    public static boolean same(String requested, String candidate) {
        String left = canonical(requested);
        return !left.isEmpty() && left.equals(canonical(candidate));
    }

    /** Only the documented old Xclipse 920 label reordered the word Old. */
    public static boolean sameDriver(String requested, String candidate) {
        if (same(requested, candidate)) return true;
        String left = canonical(requested);
        String right = canonical(candidate);
        return left.matches("xclipse(?:old920|920old)")
                && right.matches("xclipse(?:old920|920old)");
    }

    public static String sha256(File file) throws IOException {
        if (file == null || !file.isFile()) throw new IOException("Missing package payload");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value & 255));
            return hex.toString();
        }
        catch (NoSuchAlgorithmException error) { throw new AssertionError(error); }
    }

    /** An empty expected hash denotes an old export without a digest. */
    public static boolean matchesSha256(File file, String expected) {
        if (expected == null || expected.isEmpty()) return true;
        if (!expected.matches("(?i)[a-f0-9]{64}")) return false;
        try { return expected.equalsIgnoreCase(sha256(file)); }
        catch (IOException error) { return false; }
    }

    /** Android wrappers must be little-endian ELF64 shared objects for AArch64. */
    public static boolean isArm64SharedLibrary(File file) {
        if (file == null || !file.isFile() || file.length() < 64) return false;
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] header = new byte[20];
            if (input.read(header) != header.length) return false;
            return header[0] == 0x7f && header[1] == 'E' && header[2] == 'L'
                    && header[3] == 'F' && header[4] == 2 && header[5] == 1
                    && header[16] == 3 && header[17] == 0
                    && (header[18] & 255) == 183 && header[19] == 0;
        }
        catch (IOException error) { return false; }
    }
}
