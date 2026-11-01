package com.winlator.cmod.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** Only translator-owned Unix libraries; never remove Wine's own modules. */
public final class FexUnixLibs {
    private static final String[] NAMES = {
            "libarm64ecfex.so", "libwow64fex.so", "libarm64ecfe.so", "libwow64fe.so"
    };
    private FexUnixLibs() {}

    private static Set<String> targets(Collection<File> targets) throws IOException {
        Set<String> paths = new HashSet<>();
        for (File target : targets) paths.add(target.getCanonicalPath());
        return paths;
    }

    public static boolean matches(File directory, Collection<File> expected) throws IOException {
        Set<String> paths = targets(expected);
        for (String name : NAMES) {
            File file = new File(directory, name);
            if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
                    && !paths.contains(file.getCanonicalPath())) return false;
        }
        return true;
    }

    public static void removeStale(File directory, Collection<File> expected) throws IOException {
        Set<String> paths = targets(expected);
        for (String name : NAMES) {
            File file = new File(directory, name);
            if (!paths.contains(file.getCanonicalPath())) Files.deleteIfExists(file.toPath());
        }
    }
}
