package com.winlator.cmod.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/** Inspect runtime files without executing guest binaries or guessing from a default. */
public final class WineRuntimeLayout {
    private WineRuntimeLayout() {}

    public static File modules(File libraryRoot, String architecture) {
        File direct = new File(libraryRoot, architecture);
        File nested = new File(libraryRoot, "wine/" + architecture);
        return direct.isDirectory() ? direct : nested.isDirectory() ? nested : direct;
    }

    public static String architecture(String version, File bin, File libraryRoot) {
        String declared = ContentVersion.architecture(version);
        if (!declared.isEmpty()) return declared;
        String host = hostArchitecture(bin);
        if (!host.isEmpty()) return host;
        if (modules(libraryRoot, "aarch64-windows").isDirectory()) return "arm64";
        if (modules(libraryRoot, "x86_64-windows").isDirectory()) return "x86_64";
        if (modules(libraryRoot, "i386-windows").isDirectory()) return "x86";
        return "";
    }

    public static String hostArchitecture(File bin) {
        for (String name : new String[]{"wine", "wine64", "wine-preloader", "wine64-preloader"}) {
            File file = new File(bin, name);
            if (!file.isFile()) continue;
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] header = new byte[20];
                int count = 0, read;
                while (count < header.length && (read = input.read(header, count, header.length - count)) > 0) count += read;
                if (count != header.length || header[0] != 0x7f || header[1] != 'E'
                        || header[2] != 'L' || header[3] != 'F') continue;
                int first = header[18] & 255, second = header[19] & 255;
                int machine = header[5] == 1 ? first | second << 8 : header[5] == 2 ? first << 8 | second : -1;
                if (header[4] == 2 && machine == 183) return "arm64";
                if (header[4] == 2 && machine == 62) return "x86_64";
                if (header[4] == 1 && machine == 3) return "x86";
            } catch (IOException ignored) {}
        }
        return "";
    }

    public static boolean isNativeArm64(String architecture) {
        return "arm64".equals(architecture) || "arm64ec".equals(architecture);
    }

    public static boolean supportsArchitecture(String architecture) {
        return isNativeArm64(architecture) || "x86_64".equals(architecture) || "x86".equals(architecture);
    }

    public static void validateHost(String architecture, File bin) throws IOException {
        if (!supportsArchitecture(architecture)) throw new IOException("Cannot identify Wine architecture: " + bin);
        String host = hostArchitecture(bin);
        if (!host.isEmpty() && isNativeArm64(architecture) != isNativeArm64(host))
            throw new IOException("Wine architecture " + architecture + " disagrees with its " + host + " launcher: " + bin);
    }
}
