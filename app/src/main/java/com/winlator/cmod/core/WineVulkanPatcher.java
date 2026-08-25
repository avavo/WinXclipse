package com.winlator.cmod.core;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class WineVulkanPatcher {
    private static final String TAG = "WineVulkanPatcher";
    private static final String TARGET_EXPORT = "vkDestroySurfaceKHR";

    private WineVulkanPatcher() {}

    public static void neutralizeDestroySurface(File dll) {
        try {
            if (dll == null || !dll.isFile()) return;
            byte[] pe = readFile(dll);

            int peOffset = u32(pe, 0x3C);
            if (peOffset <= 0 || peOffset + 24 > pe.length) return;
            if (pe[peOffset] != 'P' || pe[peOffset + 1] != 'E') return;

            int machine = u16(pe, peOffset + 4);
            if (machine != 0x014C) return;

            int sizeOfOptionalHeader = u16(pe, peOffset + 20);
            int optionalHeader = peOffset + 24;
            int exportDirRva = u32(pe, optionalHeader + 112);
            if (exportDirRva == 0) return;

            int sectionsOffset = optionalHeader + sizeOfOptionalHeader;
            int textVa = -1, textRaw = -1, textSize = 0;
            for (int i = 0; i < u16(pe, peOffset + 6); i++) {
                int s = sectionsOffset + i * 40;
                int va = u32(pe, s + 12);
                int rawPtr = u32(pe, s + 20);
                int rawSize = u32(pe, s + 16);
                int virtualSize = u32(pe, s + 8);
                String name = new String(pe, s, 8, StandardCharsets.US_ASCII);
                if (name.startsWith(".text")) {
                    textVa = va;
                    textRaw = rawPtr;
                    textSize = Math.max(virtualSize, rawSize);
                }
            }
            if (textVa < 0) return;

            int retFileOffset = -1;
            for (int i = 0; i < textSize && retFileOffset < 0; i++) {
                if (pe[textRaw + i] == (byte) 0xC3) retFileOffset = textRaw + i;
            }
            if (retFileOffset < 0) return;
            int retRva = textVa + (retFileOffset - textRaw);

            int exportOff = rvaToOffset(pe, sectionsOffset, u16(pe, peOffset + 6), exportDirRva);
            if (exportOff < 0) return;
            int numberOfNames = u32(pe, exportOff + 24);
            int functionsRva = u32(pe, exportOff + 28);
            int namesRva = u32(pe, exportOff + 32);
            int ordinalsRva = u32(pe, exportOff + 36);

            for (int i = 0; i < numberOfNames; i++) {
                int namesEntryOff = rvaToOffset(pe, sectionsOffset, u16(pe, peOffset + 6), namesRva + 4 * i);
                if (namesEntryOff < 0 || namesEntryOff + 4 > pe.length) continue;
                int nameRva = u32(pe, namesEntryOff);
                int nameOff = rvaToOffset(pe, sectionsOffset, u16(pe, peOffset + 6), nameRva);
                if (nameOff < 0) continue;
                if (!startsWith(pe, nameOff, TARGET_EXPORT)) continue;

                int ordinalsEntryOff = rvaToOffset(pe, sectionsOffset, u16(pe, peOffset + 6), ordinalsRva + 2 * i);
                if (ordinalsEntryOff < 0 || ordinalsEntryOff + 2 > pe.length) continue;
                int ordinalIndex = u16(pe, ordinalsEntryOff);
                int funcEntryOff = rvaToOffset(pe, sectionsOffset, u16(pe, peOffset + 6), functionsRva + 4 * ordinalIndex);
                if (funcEntryOff < 0 || funcEntryOff + 4 > pe.length) return;

                int currentRva = u32(pe, funcEntryOff);
                if (currentRva == retRva) {
                    Log.i(TAG, "vkDestroySurfaceKHR already neutralized in " + dll.getName());
                    return;
                }

                pe[funcEntryOff] = (byte) retRva;
                pe[funcEntryOff + 1] = (byte) (retRva >> 8);
                pe[funcEntryOff + 2] = (byte) (retRva >> 16);
                pe[funcEntryOff + 3] = (byte) (retRva >> 24);
                writeFile(dll, pe);
                Log.i(TAG, "vkDestroySurfaceKHR -> RET (rva 0x" + Integer.toHexString(retRva)
                        + ") in " + dll.getName());
                return;
            }
            Log.w(TAG, "export " + TARGET_EXPORT + " not found in " + dll.getName());
        } catch (Exception e) {
            Log.w(TAG, "patch failed for " + dll, e);
        }
    }

    private static int rvaToOffset(byte[] pe, int sectionsOffset, int numberOfSections, int rva) {
        for (int i = 0; i < numberOfSections; i++) {
            int s = sectionsOffset + i * 40;
            int va = u32(pe, s + 12);
            int rawPtr = u32(pe, s + 20);
            int rawSize = u32(pe, s + 16);
            int virtualSize = u32(pe, s + 8);
            int span = Math.max(virtualSize, rawSize);
            if (rva >= va && rva < va + span) {
                int off = rawPtr + (rva - va);
                return off < pe.length ? off : -1;
            }
        }
        return -1;
    }

    private static boolean startsWith(byte[] data, int off, String prefix) {
        byte[] p = prefix.getBytes(StandardCharsets.US_ASCII);
        if (off < 0 || off + p.length + 1 > data.length) return false;
        for (int i = 0; i < p.length; i++) {
            if (data[off + i] != p[i]) return false;
        }
        return data[off + p.length] == 0;
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int u32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static byte[] readFile(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    private static void writeFile(File f, byte[] data) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        out.write(data);
        out.flush();
        out.close();
    }
}
