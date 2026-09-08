package com.winlator.cmod.core;

import android.os.SystemClock;
import android.util.Log;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Resync minimal do prefix com o runtime ativo, só com file ops (nunca wineboot).
 *
 *  <p>Mecanismo do "parou de abrir do nada": nada re-sincroniza o prefix na
 *  troca/reinstalação de runtime (a flag {@code wineprefixNeedsUpdate} é morta
 *  desde o upload inicial — só é escrita, nunca lida). As DLLs de
 *  {@code system32/syswow64} dessincronizam do runtime ativo e o jogo morre no
 *  boot sem dizer nada; reinstalar o runtime "resolve" porque re-seeda os
 *  arquivos. Este resync fecha o buraco no boot, por container:
 *
 *  <ul>
 *    <li>Sempre restaura DLLs <b>ausentes</b> a partir do runtime ativo
 *        (aditivo — risco zero pra prefix funcionando).</li>
 *    <li>Quando o runtime mudou (id/mtime versionado por container), também
 *        atualiza DLLs presentes com tamanho diferente — <b>só</b> se não
 *        houver override nativo no {@code user.reg}.</li>
 *    <li>Nunca encosta em DLL com fluxo versionado próprio (DXVK/VKD3D,
 *        xidi, ddraw): cada um tem seu dono.</li>
 *    <li>Fail-open total: orçamento de 20s, qualquer erro aborta em silêncio
 *        e o boot segue. Sem carimbo em falha pra retentar no próximo boot.</li>
 *  </ul>
 *
 *  <p>Save-safe por construção: só lê/escreve DLLs de
 *  {@code .wine/drive_c/windows}; saves moram fora dali.
 */
public final class PrefixDllResync {
    private static final String TAG = "PrefixDllResync";
    private static final String EXTRA_RUNTIME = "lastPrefixRuntimeId";
    private static final long BUDGET_MS = 20000L;
    private static final int BUDGET_CHECK_EVERY = 25;
    private static final String DLLOVERRIDES_KEY = "Software\\Wine\\DllOverrides";

    /** DLLs com dono versionado: DXVK/VKD3D (DXWrapper), xidi (controller fix), ddraw. */
    private static final Set<String> MANAGED_DLLS = new HashSet<>();
    static {
        String[] names = {
                "d3d8.dll", "d3d9.dll", "d3d10.dll", "d3d10_1.dll", "d3d10core.dll",
                "d3d11.dll", "d3d12.dll", "d3d12core.dll", "dxgi.dll", "ddraw.dll",
                "dinput.dll", "dinput8.dll"
        };
        for (String n : names) MANAGED_DLLS.add(n);
    }

    private PrefixDllResync() {}

    public static void resync(Container container, WineInfo wineInfo, ImageFs imageFs) {
        long startMs = SystemClock.elapsedRealtime();
        try {
            resyncThrowing(container, wineInfo, imageFs, startMs);
        } catch (Throwable t) {
            Log.w(TAG, "Prefix resync aborted (boot continues normally)", t);
        }
    }

    private static void resyncThrowing(Container container, WineInfo wineInfo, ImageFs imageFs, long startMs) {
        if (container == null || wineInfo == null || imageFs == null) return;
        File containerRoot = container.getRootDir();
        if (containerRoot == null) return;
        File prefixWindows = new File(containerRoot, ".wine/drive_c/windows");
        File system32Dir = new File(prefixWindows, "system32");
        // Sem prefix ainda: o caminho de criação já faz o seeding completo.
        if (!system32Dir.isDirectory()) return;

        String winePath = imageFs.getWinePath();
        if (winePath == null || winePath.isEmpty() || !new File(winePath).isDirectory()) {
            Log.e(TAG, "Active runtime path is missing (" + winePath
                    + "); reinstall the runtime. Prefix left untouched.");
            return;
        }
        File wineLibDir = new File(winePath, "lib/wine");
        File nested = new File(wineLibDir, "wine");
        if (!new File(wineLibDir, "i386-windows").isDirectory()
                && new File(nested, "i386-windows").isDirectory()) wineLibDir = nested;

        // Mapeamento de arch igual ao seeding de criação (ContainerManager).
        String sysArch;
        String wowArch = null;
        if (wineInfo.isArm64EC()) {
            sysArch = "aarch64-windows";
            wowArch = "i386-windows";
        } else if (!wineInfo.isWin64()) {
            sysArch = "i386-windows";
        } else {
            sysArch = "x86_64-windows";
            wowArch = "i386-windows";
        }
        File sysSrcDir = new File(wineLibDir, sysArch);
        File wowSrcDir = wowArch != null ? new File(wineLibDir, wowArch) : null;

        // Integridade do runtime: fonte ausente/vazia = runtime quebrado.
        // Não encosta no prefix; o log diz exatamente o que reinstalar.
        if (!hasFiles(sysSrcDir) || (wowArch != null && !hasFiles(wowSrcDir))) {
            Log.e(TAG, "Active runtime '" + wineInfo.identifier() + "' has no usable "
                    + sysArch + (wowArch != null ? "/" + wowArch : "")
                    + " payload under " + wineLibDir.getAbsolutePath()
                    + "; reinstall the runtime. Prefix left untouched.");
            return;
        }

        String fingerprint = wineInfo.identifier() + "|"
                + sysSrcDir.lastModified() + "|"
                + (wowSrcDir != null ? wowSrcDir.lastModified() : 0);
        boolean runtimeChanged = !fingerprint.equals(container.getExtra(EXTRA_RUNTIME));

        // Overrides nativos (wincomponents native etc.): essas DLLs são do
        // usuário, nunca overwrite. Editor aberto uma vez; consulta preguiçosa
        // com cache (só acontece no caminho runtimeChanged + tamanho mudou).
        // Se o user.reg estiver ilegível, overrides=null e o modo é só-aditivo.
        WineRegistryEditor overrides = openOverrides(new File(containerRoot, ".wine/user.reg"));
        java.util.Map<String, Boolean> nativeCache = new java.util.HashMap<>();
        try {
            int scanned = 0;
            int restored = 0;
            int refreshed = 0;
            int copyErrors = 0;
            boolean budgetDead = false;

            File[][] pairs = wowSrcDir != null
                    ? new File[][]{{sysSrcDir, system32Dir}, {wowSrcDir, new File(prefixWindows, "syswow64")}}
                    : new File[][]{{sysSrcDir, system32Dir}};
            for (File[] pair : pairs) {
                File srcDir = pair[0];
                File dstDir = pair[1];
                if (!dstDir.isDirectory() && !dstDir.mkdirs()) continue;
                File[] srcFiles = srcDir.listFiles();
                if (srcFiles == null) continue;
                for (File src : srcFiles) {
                    if (!src.isFile()) continue;
                    String name = src.getName();
                    if (!name.toLowerCase(Locale.ENGLISH).endsWith(".dll")) continue;
                    if (MANAGED_DLLS.contains(name.toLowerCase(Locale.ENGLISH))) continue;
                    scanned++;
                    if (scanned % BUDGET_CHECK_EVERY == 0
                            && SystemClock.elapsedRealtime() - startMs > BUDGET_MS) {
                        budgetDead = true;
                        break;
                    }
                    File dst = new File(dstDir, name);
                    try {
                        if (!dst.isFile()) {
                            if (FileUtils.copy(src, dst)) restored++;
                            else if (++copyErrors <= 5) Log.w(TAG, "Could not restore " + dst.getAbsolutePath());
                        } else if (runtimeChanged && overrides != null
                                && src.length() != dst.length()
                                && !isNativeFirst(overrides, nativeCache, name)) {
                            if (FileUtils.copy(src, dst)) refreshed++;
                            else if (++copyErrors <= 5) Log.w(TAG, "Could not refresh " + dst.getAbsolutePath());
                        }
                    } catch (Exception e) {
                        if (++copyErrors <= 5) Log.w(TAG, "Could not sync " + dst.getAbsolutePath(), e);
                    }
                }
                if (budgetDead) break;
            }

            long tookMs = SystemClock.elapsedRealtime() - startMs;
            if (budgetDead) {
                // Sem carimbo: continua de onde parou no próximo boot.
                Log.w(TAG, "Budget exceeded (" + tookMs + "ms, scanned=" + scanned
                        + " restored=" + restored + " refreshed=" + refreshed
                        + "); will resume next boot. Boot continues normally.");
                return;
            }
            container.putExtra(EXTRA_RUNTIME, fingerprint);
            container.saveData();
            Log.i(TAG, "Prefix resync done: runtime=" + wineInfo.identifier()
                    + " changed=" + runtimeChanged
                    + " scanned=" + scanned + " restored=" + restored
                    + " refreshed=" + refreshed + " (" + tookMs + "ms)");
        } finally {
            closeQuietly(overrides);
        }
    }

    private static boolean hasFiles(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        String[] names = dir.list();
        return names != null && names.length > 0;
    }

    /** Abre o user.reg para leitura (snapshot). Null = ilegível (modo só-aditivo). */
    private static WineRegistryEditor openOverrides(File userReg) {
        try {
            if (userReg != null && userReg.isFile()) return new WineRegistryEditor(userReg);
        } catch (Exception ignored) {}
        return null;
    }

    private static void closeQuietly(WineRegistryEditor editor) {
        try {
            if (editor != null) editor.close();
        } catch (Exception ignored) {}
    }

    /** true se o override da DLL começa com native (DLL do usuário: nunca overwrite). */
    private static boolean isNativeFirst(WineRegistryEditor overrides,
                                         java.util.Map<String, Boolean> cache, String dllFileName) {
        String base = dllFileName.toLowerCase(Locale.ENGLISH);
        if (base.endsWith(".dll")) base = base.substring(0, base.length() - 4);
        Boolean hit = cache.get(base);
        if (hit != null) return hit;
        boolean nativeFirst = false;
        try {
            String v = overrides.getStringValue(DLLOVERRIDES_KEY, base, null);
            // Wine aceita "*nome" como default da DLL; cobre os dois.
            if (v == null) v = overrides.getStringValue(DLLOVERRIDES_KEY, "*" + base, null);
            nativeFirst = v != null && v.trim().toLowerCase(Locale.ENGLISH).startsWith("native");
        } catch (Exception ignored) {}
        cache.put(base, nativeFirst);
        return nativeFirst;
    }
}
