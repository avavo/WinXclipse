package com.winlator.cmod.core;

import android.util.Log;

import java.io.File;
import java.util.Locale;

/** Flags de streaming seguras p/ GTA V (RAGE) em GPU movel com pouca RAM.
 *  Testado na pratica: -maxStreamedGrid 10 -minStreamedGrid 5 -hdr 0
 *  -percentvidmem 100 segura 1h+ de gameplay com missoes pesadas (Blitz Play)
 *  onde antes congelava e fechava andando de carro pela cidade (OOM).
 *  Flags que o usuario ja definiu (exec args ou commandline.txt) prevalecem. */
public final class RageLaunchArgs {

    private static final String[][] MEMORY_SAFE_FLAGS = {
            {"-maxStreamedGrid", "10"},
            {"-minStreamedGrid", "5"},
            {"-hdr", "0"},
            {"-percentvidmem", "100"},
    };

    /** Preset agressivo estilo Mali (opt-in): renderer DX10, tudo no minimo,
     *  densidade/LOD cortados, restricoes de memoria removidas. So aplica se
     *  o usuario nao definiu -nomemrestrict (sentinela "gerencio eu mesmo"). */
    public static final String POTATO_ARGS =
            " -fullscreen -DX10 -novsync -forcehighpriority -noprecache -noShaderCache"
            + " -nopostfx -nomemrestrict -norestrictions"
            + " -anisotropicQualityLevel 0 -shaderQuality 0 -postFX 0 -reflectionQuality 0"
            + " -grassQuality 0 -particleQuality 0 -noInGameDOF"
            + " -cityDensity 0.2 -lodScale 0.0 -pedLodBias 0.0 -vehicleLodBias 0.0";

    private RageLaunchArgs() {}

    public static boolean isGtaV(String exeName) {
        if (exeName == null) return false;
        String n = exeName.toLowerCase(Locale.ENGLISH).trim();
        if (n.startsWith("\"") && n.endsWith("\"") && n.length() > 1)
            n = n.substring(1, n.length() - 1);
        return n.equals("playgtav.exe") || n.equals("gta5.exe");
    }

    /** Devolve " -flag valor..." so com as flags ausentes em existing (case-insensitive). */
    public static String missingArgs(String existing) {
        String lower = existing != null ? existing.toLowerCase(Locale.ENGLISH) : "";
        StringBuilder out = new StringBuilder();
        for (String[] flag : MEMORY_SAFE_FLAGS) {
            if (!containsFlag(lower, flag[0])) out.append(' ').append(flag[0]).append(' ').append(flag[1]);
        }
        return out.toString();
    }

    private static boolean containsFlag(String lowerHaystack, String flag) {
        String needle = flag.toLowerCase(Locale.ENGLISH);
        int i = lowerHaystack.indexOf(needle);
        while (i >= 0) {
            boolean startOk = i == 0 || !Character.isLetterOrDigit(lowerHaystack.charAt(i - 1));
            int end = i + needle.length();
            boolean endOk = end >= lowerHaystack.length() || !Character.isLetterOrDigit(lowerHaystack.charAt(end));
            if (startOk && endOk) return true;
            i = lowerHaystack.indexOf(needle, i + 1);
        }
        return false;
    }

    /** Garante as flags no commandline.txt ao lado do exe (mecanismo canonico do
     *  RAGE; cobre launch via PlayGTAV.exe). Mescla sem apagar linhas do usuario.
     *  Retorna true se escreveu algo. Nunca joga excecao. */
    public static boolean ensureCommandLineTxt(File gameDir) {
        if (gameDir == null || !gameDir.isDirectory()) return false;
        try {
            File cmdline = new File(gameDir, "commandline.txt");
            String existing = cmdline.isFile() ? FileUtils.readString(cmdline) : "";
            String missing = missingArgs(existing);
            if (missing.isEmpty()) return false;
            StringBuilder content = new StringBuilder(existing);
            if (content.length() > 0 && content.charAt(content.length() - 1) != '\n') content.append('\n');
            content.append(missing.trim()).append('\n');
            if (FileUtils.writeString(cmdline, content.toString())) {
                Log.i("RageLaunchArgs", "commandline.txt updated in " + gameDir.getAbsolutePath());
                return true;
            }
        } catch (Exception e) {
            Log.w("RageLaunchArgs", "Could not update commandline.txt", e);
        }
        return false;
    }
}
