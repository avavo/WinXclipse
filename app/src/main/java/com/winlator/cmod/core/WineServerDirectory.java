package com.winlator.cmod.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/** Wine rejects a server directory with any group/other permission bits. */
public final class WineServerDirectory {
    interface Permissions { void restrict(Path directory) throws IOException; }
    private WineServerDirectory() {}

    public static void prepare(File prefix) throws IOException {
        prepare(prefix, directory -> {
            try {
                java.util.Set<java.nio.file.attribute.PosixFilePermission> mode = PosixFilePermissions.fromString("rwx------");
                Files.setPosixFilePermissions(directory, mode);
                if (!Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS).equals(mode))
                    throw new IOException("Wine server directory permissions did not become 0700");
            } catch (UnsupportedOperationException unsupported) {
                throw new IOException("Wine server directory requires POSIX permissions", unsupported);
            }
        });
    }

    static void prepare(File prefix, Permissions permissions) throws IOException {
        Files.createDirectories(prefix.toPath());
        Path directory = new File(prefix, ".wineserver").toPath();
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Wine server directory is not a real directory: " + directory);
        if (!Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS)
                .equals(Files.getOwner(prefix.toPath())))
            throw new IOException("Wine server directory belongs to another user: " + directory);
        permissions.restrict(directory);
    }
}
