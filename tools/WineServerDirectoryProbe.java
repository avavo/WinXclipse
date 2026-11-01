package com.winlator.cmod.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.io.IOException;

/** Standalone ART check of the production Java helper; no APK or app data. */
public class WineServerDirectoryProbe {
    public static void main(String[] args) throws Exception {
        Path root=Paths.get(args[0]);Files.createDirectory(root);
        Path prefix=Files.createDirectory(root.resolve("prefix"));
        Path server=Files.createDirectory(prefix.resolve(".wineserver"));
        Path lock=Files.write(server.resolve("lock"),new byte[]{1,2,3});
        Files.setPosixFilePermissions(server,PosixFilePermissions.fromString("rwxrwx--x"));
        WineServerDirectory.prepare(prefix.toFile());WineServerDirectory.prepare(prefix.toFile());
        if(!PosixFilePermissions.toString(Files.getPosixFilePermissions(server)).equals("rwx------")
                ||Files.readAllBytes(lock)[2]!=3)throw new AssertionError("permission repair/data preservation");
        Path bad=Files.createDirectory(root.resolve("bad"));Path file=Files.write(bad.resolve(".wineserver"),new byte[]{7});
        try{WineServerDirectory.prepare(bad.toFile());throw new AssertionError("accepted a file");}catch(IOException expected){}
        Path linked=Files.createDirectory(root.resolve("linked"));Path link=Files.createSymbolicLink(linked.resolve(".wineserver"),server);
        try{WineServerDirectory.prepare(linked.toFile());throw new AssertionError("accepted symlink");}catch(IOException expected){}
        Files.delete(link);Files.delete(linked);Files.delete(file);Files.delete(bad);Files.delete(lock);Files.delete(server);Files.delete(prefix);Files.delete(root);
        System.out.println("PASS ART: 0771 -> 0700, repeated launch, lock preserved, file/symlink rejected");
    }
}
