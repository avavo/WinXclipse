package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tunes third-party dxvk.conf files (Mali/OpJuegos template) for mobile GPUs.
 *  Pure Java, no Android dependencies, so it can be tested standalone. */
public final class DxvkConfSanitizer {

    public static final class Result {
        public final List<String> issues;
        public final String sanitized;
        public final boolean changed;

        Result(List<String> issues, String sanitized, boolean changed) {
            this.issues = issues;
            this.sanitized = sanitized;
            this.changed = changed;
        }
    }

    // Keys that do not exist in DXVK (typo or removed legacy): ignored by the
    // driver, but they pollute the log and confuse ("is the conf applying?"). Comment out.
    private static final Set<String> INVALID_KEYS = new HashSet<>(Arrays.asList(
            "dxvk.memorytrack",
            "dxvk.presentthrottle",
            "dxvk.debuglayer",
            "dxvk.maxframelatency",
            "dxvk.defersurfacecreation",
            "d3d9.maxdevicememory",
            "d3d9.allowdirectbuffermapping",
            "d3d11.allowmapflagnowait"));

    private static final Pattern ASSIGN = Pattern.compile("^([A-Za-z0-9_.]+)\\s*=\\s*(.*?)\\s*$");
    private static final long MIN_SAFE_VRAM_MB = 1024;
    private static final long SANITIZED_VRAM_MB = 2048;
    private static final int MAX_COMPILER_THREADS = 4;
    private static final int SANITIZED_COMPILER_THREADS = 3;

    private DxvkConfSanitizer() {}

    /** Le o texto de um SAF Uri com limite de 64 KB, preservando quebras de linha. */
    public static String readUriText(Context context, Uri uri) throws Exception {
        if (context == null || uri == null) throw new IllegalArgumentException("no uri");
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
                if (sb.length() > DXVKConfigDialog.MAX_CUSTOM_CONF_BYTES) break;
            }
            return sb.toString();
        }
    }

    /** Checagem barata: parece dxvk.conf (chave=valor de secao conhecida). */
    public static boolean isPlausible(String content) {
        if (content == null) return false;
        String t = content.trim();
        if (t.isEmpty() || t.length() > DXVKConfigDialog.MAX_CUSTOM_CONF_BYTES) return false;
        if (t.indexOf('=') < 0) return false;
        if (t.indexOf('\0') >= 0) return false;
        return t.contains("dxgi.") || t.contains("dxvk.") || t.contains("d3d11.")
                || t.contains("d3d9.") || t.contains("d3d8.");
    }

    public static Result sanitize(String content) {
        List<String> issues = new ArrayList<>();
        if (content == null) content = "";
        String[] lines = content.split("\n", -1);
        StringBuilder out = new StringBuilder();
        boolean changed = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String stripped = line.replace("\r", "");
            String trim = stripped.trim();
            // Preserva vazio e comentario.
            if (trim.isEmpty() || trim.startsWith("#") || trim.startsWith(";")) {
                out.append(line);
            } else {
                Matcher m = ASSIGN.matcher(trim);
                if (!m.matches()) {
                    out.append(line);
                } else {
                    String key = m.group(1);
                    String value = m.group(2);
                    String normKey = key.toLowerCase(Locale.ENGLISH);
                    String normVal = value.toLowerCase(Locale.ENGLISH);
                    String fixed = null;

                    if (INVALID_KEYS.contains(normKey)) {
                        fixed = "# removed by WinXclipse (option does not exist in DXVK): " + stripped;
                        issues.add(key + " does not exist in DXVK (line ignored by the driver)");
                        changed = true;
                    } else if (normKey.equals("d3d11.relaxedbarriers") && normVal.equals("true")) {
                        fixed = "d3d11.relaxedBarriers = False  # sanitized: True breaks textures/map on Xclipse/Mali";
                        issues.add("relaxedBarriers=True causes glitches/broken map (RE2/GTA)");
                        changed = true;
                    } else if (normKey.equals("dxvk.userawssbo") && normVal.equals("true")) {
                        fixed = "dxvk.useRawSsbo = Auto  # sanitized: True is unsafe on mobile GPUs";
                        issues.add("useRawSsbo=True can corrupt shaders on mobile GPUs");
                        changed = true;
                    } else if ((normKey.equals("dxgi.maxdevicememory")
                            || normKey.equals("dxgi.maxsharedmemory")
                            || normKey.equals("d3d9.maxavailablememory"))
                            && parseMb(value) >= 0 && parseMb(value) < MIN_SAFE_VRAM_MB) {
                        fixed = key + " = " + SANITIZED_VRAM_MB
                                + "  # sanitized: " + value + " MB breaks RE2/GTA (0x80070057, texture loss)";
                        issues.add(key + "=" + value + " MB is too low (crash/texture loss)");
                        changed = true;
                    } else if (normKey.equals("dxvk.numcompilerthreads") && parseMb(value) > MAX_COMPILER_THREADS) {
                        fixed = "dxvk.numCompilerThreads = " + SANITIZED_COMPILER_THREADS
                                + "  # sanitized: too many steals game CPU on mobile";
                        issues.add("numCompilerThreads=" + value + " too high for mobile");
                        changed = true;
                    } else if (normKey.equals("dxvk.hud") && !normVal.isEmpty()
                            && !normVal.equals("0") && !normVal.equals("none")
                            && !normVal.equals("false")) {
                        fixed = "# removed by WinXclipse (HUD costs FPS): " + stripped;
                        issues.add("active hud wastes CPU/GPU and heats up (throttle)");
                        changed = true;
                    } else if ((normKey.equals("dxgi.customdeviceid") || normKey.equals("dxgi.customvendorid"))
                            && (normVal.equals("0") || normVal.equals("0000"))) {
                        fixed = "# removed by WinXclipse (ID 0 is invalid): " + stripped;
                        issues.add(key + "=0 is invalid (falls back to generic GPU path)");
                        changed = true;
                    }

                    out.append(fixed != null ? fixed : line);
                }
            }
            if (i < lines.length - 1) out.append('\n');
        }

        String sanitized = out.toString();
        if (changed) {
            sanitized = "# Sanitized by WinXclipse (" + issues.size()
                    + " fixes for Xclipse/Mali)\n" + sanitized;
        }
        return new Result(issues, sanitized, changed);
    }

    private static long parseMb(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
