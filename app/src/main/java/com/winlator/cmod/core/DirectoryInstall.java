package com.winlator.cmod.core;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/** Commit an extracted package on the same filesystem, retaining rollback data. */
public final class DirectoryInstall {
    private DirectoryInstall() {}

    /** Returns a previous incomplete installation for cleanup after success. */
    public static synchronized File commit(File staging, File destination) throws IOException {
        if (staging == null || !staging.isDirectory()) throw new IOException("Missing staged package");
        File source = staging.getCanonicalFile();
        File target = destination.getCanonicalFile();
        if (source.equals(target) || target.toPath().startsWith(source.toPath())
                || source.toPath().startsWith(target.toPath())) {
            throw new IOException("Overlapping installation paths");
        }
        File parent = target.getParentFile();
        if (parent == null || !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create package directory");
        }
        File previous = null;
        if (target.exists()) {
            // Keep recovery data outside temporary extraction trees. A caller
            // cleaning staging after failure must not remove the previous package.
            previous = new File(parent, ".previous-" + target.getName() + "-" + UUID.randomUUID());
            if (!target.renameTo(previous)) throw new IOException("Cannot stage previous package");
        }
        if (!source.renameTo(target)) {
            if (previous != null && !previous.renameTo(target)) {
                throw new IOException("Package commit failed; previous package retained at " + previous);
            }
            throw new IOException("Cannot commit staged package");
        }
        return previous;
    }
}
