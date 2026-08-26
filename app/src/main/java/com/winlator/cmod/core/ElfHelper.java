package com.winlator.cmod.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

public abstract class ElfHelper {
    private static final byte ELF_CLASS_32 = 1;
    private static final byte ELF_CLASS_64 = 2;

    private static int getEIClass(File binFile) {
        try (InputStream inStream = new FileInputStream(binFile)) {
            byte[] header = new byte[52];
            int read = 0;
            while (read < header.length) {
                int n = inStream.read(header, read, header.length - read);
                if (n == -1) break;
                read += n;
            }
            if (read >= 5 && header[0] == 0x7F && header[1] == 'E' && header[2] == 'L' && header[3] == 'F') {
                return header[4];
            }
        }
        catch (IOException e) {}
        return 0;
    }

    public static boolean is32Bit(File binFile) {
        return getEIClass(binFile) == ELF_CLASS_32;
    }

    public static boolean is64Bit(File binFile) {
        return getEIClass(binFile) == ELF_CLASS_64;
    }
}