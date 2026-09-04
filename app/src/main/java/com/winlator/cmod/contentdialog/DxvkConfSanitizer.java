package com.winlator.cmod.contentdialog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Ajusta dxvk.conf de terceiros (template Mali/OpJuegos) para GPU movel.
 *  Puro Java, sem dependencias Android, para poder testar isolado. */
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

    // Chaves que nao existem no DXVK (typo ou legado removido): ignoradas pelo
    // driver, mas poluem o log e confundem ("conf ta pegando?"). Comenta fora.
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
                        fixed = "# removido pelo WinXclipse (opcao inexistente no DXVK): " + stripped;
                        issues.add(key + " nao existe no DXVK (linha ignorada pelo driver)");
                        changed = true;
                    } else if (normKey.equals("d3d11.relaxedbarriers") && normVal.equals("true")) {
                        fixed = "d3d11.relaxedBarriers = False  # sanitizado: True buga textura/mapa no Xclipse/Mali";
                        issues.add("relaxedBarriers=True causa glitch/mapa bugado (RE2/GTA)");
                        changed = true;
                    } else if (normKey.equals("dxvk.userawssbo") && normVal.equals("true")) {
                        fixed = "dxvk.useRawSsbo = Auto  # sanitizado: True e inseguro em GPU movel";
                        issues.add("useRawSsbo=True pode corromper shader em GPU movel");
                        changed = true;
                    } else if ((normKey.equals("dxgi.maxdevicememory")
                            || normKey.equals("dxgi.maxsharedmemory")
                            || normKey.equals("d3d9.maxavailablememory"))
                            && parseMb(value) >= 0 && parseMb(value) < MIN_SAFE_VRAM_MB) {
                        fixed = key + " = " + SANITIZED_VRAM_MB
                                + "  # sanitizado: " + value + " MB quebra RE2/GTA (0x80070057, texture loss)";
                        issues.add(key + "=" + value + " MB e baixo demais (crash/texture loss)");
                        changed = true;
                    } else if (normKey.equals("dxvk.numcompilerthreads") && parseMb(value) > MAX_COMPILER_THREADS) {
                        fixed = "dxvk.numCompilerThreads = " + SANITIZED_COMPILER_THREADS
                                + "  # sanitizado: excesso rouba CPU do jogo no 8-core";
                        issues.add("numCompilerThreads=" + value + " alto demais p/ mobile");
                        changed = true;
                    } else if (normKey.equals("dxvk.hud") && !normVal.isEmpty()
                            && !normVal.equals("0") && !normVal.equals("none")
                            && !normVal.equals("false")) {
                        fixed = "# removido pelo WinXclipse (HUD rouba FPS): " + stripped;
                        issues.add("hud ativo rouba CPU/GPU e esquenta (throttle)");
                        changed = true;
                    } else if ((normKey.equals("dxgi.customdeviceid") || normKey.equals("dxgi.customvendorid"))
                            && (normVal.equals("0") || normVal.equals("0000"))) {
                        fixed = "# removido pelo WinXclipse (ID 0 invalido): " + stripped;
                        issues.add(key + "=0 invalido (cai em path generico de GPU)");
                        changed = true;
                    }

                    out.append(fixed != null ? fixed : line);
                }
            }
            if (i < lines.length - 1) out.append('\n');
        }

        String sanitized = out.toString();
        if (changed) {
            sanitized = "# Sanitizado pelo WinXclipse (" + issues.size()
                    + " ajustes p/ Xclipse/Mali)\n" + sanitized;
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
