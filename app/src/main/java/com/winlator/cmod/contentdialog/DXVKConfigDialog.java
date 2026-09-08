package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ToggleButton;

import com.winlator.cmod.R;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.KeyValueSet;
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.core.VKD3DVersionItem;
import com.winlator.cmod.xenvironment.ImageFs;
import com.winlator.cmod.widget.ThemedSpinnerAdapter;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;

public class DXVKConfigDialog extends ContentDialog {
    public static final String DEFAULT_CONFIG = "version="+DefaultVersion.DXVK+",framerate=0,async=1,asyncCache=0,vkd3dVersion="+DefaultVersion.VKD3D+",vkd3dLevel=12_1,ddrawrapper=,noTimeline=1,vk3d66=1,ramFix=1,ramFixPool=0";
    public static final String CUSTOM_CONF_FILENAME = "dxvk.conf";
    public static final long MAX_CUSTOM_CONF_BYTES = 64 * 1024;
    // Neutraliza as chaves que quebram RE Engine/RAGE em GPU movel quando um
    // dxvk.conf embarcado no repack (OpJuegos/ADM/Mali) vaza para a sessao.
    // Inclui o cap de VRAM reportada: sem ele o jogo dimensiona os pools de
    // streaming pelo heap inteiro e morre de OOM andando de carro (GTA V).
    // explicitCapMb e o menor hard cap ativo (driver/xperf): o reportado nunca
    // passa do real, senao o jogo planeja alem do que existe e perde textura.
    // Chaves de seguranca: sempre valem (neutralizam repack quebrado). Chaves de
    // memoria cedem para um dxvk.conf ao lado do .exe (CR/repack): o arquivo
    // governa a memoria dele, como nos outros Winlator — o env nao pisa nele.
    private static final String SAFETY_KEYS =
            "d3d11.relaxedBarriers = False; dxvk.useRawSsbo = Auto";
    private static final String[] MEMORY_KEYS =
            {"dxgi.maxdevicememory", "dxgi.maxsharedmemory", "d3d9.maxavailablememory"};

    /** Chaves (lowercase) definidas num dxvk.conf ao lado do exe, se existir. */
    public static java.util.Set<String> gameDirConfKeys(File gameDirDxvkConf) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        if (gameDirDxvkConf == null || !gameDirDxvkConf.isFile()) return keys;
        try {
            String content = new String(java.nio.file.Files.readAllBytes(gameDirDxvkConf.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            for (String line : content.split("\r?\n")) {
                String t = line.trim();
                int hash = t.indexOf('#');
                if (hash >= 0) t = t.substring(0, hash).trim();
                int eq = t.indexOf('=');
                if (eq > 0) keys.add(t.substring(0, eq).trim().toLowerCase(java.util.Locale.ENGLISH));
            }
        } catch (Exception ignored) {}
        return keys;
    }

    /** Monta o env final: seguranca sempre + memoria so nas chaves que o arquivo nao definiu. */
    public static String mergeGameDirConf(String memoryPart, java.util.Set<String> fileKeys) {
        StringBuilder out = new StringBuilder(SAFETY_KEYS);
        if (memoryPart != null) {
            for (String entry : memoryPart.split(";")) {
                String t = entry.trim();
                if (t.isEmpty()) continue;
                int eq = t.indexOf('=');
                String key = eq > 0 ? t.substring(0, eq).trim().toLowerCase(java.util.Locale.ENGLISH) : "";
                boolean isMemory = false;
                for (String mk : MEMORY_KEYS) if (mk.equals(key)) { isMemory = true; break; }
                if (isMemory && fileKeys.contains(key)) continue;
                out.append("; ").append(t);
            }
        }
        return out.toString();
    }

    /** Pool configurado pelo usuario no dialogo (0 = Auto). So vale com RAM Fix ligado. */
    public static int resolveRamFixPoolMb(KeyValueSet config) {
        if (config == null) return 0;
        try {
            int v = Integer.parseInt(config.get("ramFixPool", "0").trim());
            if (v <= 0) return 0;
            return Math.min(Math.max(v, 256), 12288);
        } catch (Exception e) {
            return 0;
        }
    }

    /** Valores {device, shared, d3d9} do fallback por tier. */
    private static int[] tierMemoryValues(Context context, int explicitCapMb, boolean ramFixOn) {
        int dev;
        int shared;
        int d3d9;
        if (explicitCapMb > 0) {
            dev = explicitCapMb;
            shared = explicitCapMb;
            // Reportado nunca passa do hard cap real: senao o jogo planeja
            // alem do que existe e perde textura. Teto de 4096 no d3d9.
            d3d9 = Math.min(explicitCapMb, 4096);
        } else if (ramFixOn) {
            // RAM Fix geral (todos os jogos, nao so RAGE): teto conservador para
            // memoria unificada nao empurrar a RAM total para 85-90% (faixa do
            // lmkd) nem derrubar o celular inteiro em aparelho curto. Como nos
            // outros Winlator, o dxvk.conf ao lado do .exe governa a memoria
            // dele (merge por chave no setEnvVars) — aqui e so o fallback.
            if (getTotalMemMb(context) <= 6144) {
                dev = 1024;
                shared = 1024;
                d3d9 = 2048;
            } else {
                dev = 1536;
                shared = 1024;
                d3d9 = 2048;
            }
        } else {
            if (getTotalMemMb(context) <= 6144) {
                dev = 2048;
                shared = 2048;
            } else {
                dev = 3072;
                shared = 2048;
            }
            d3d9 = 4096;
        }
        return new int[]{dev, shared, d3d9};
    }

    private static String formatMemoryPart(int dev, int shared, int d3d9) {
        return "dxgi.maxDeviceMemory = " + dev + "; dxgi.maxSharedMemory = " + shared
                + "; d3d9.maxAvailableMemory = " + d3d9;
    }

    private static String tierMemoryPart(Context context, int explicitCapMb, boolean ramFixOn) {
        int[] v = tierMemoryValues(context, explicitCapMb, ramFixOn);
        return formatMemoryPart(v[0], v[1], v[2]);
    }

    private static String tierMemoryPart(Context context, int explicitCapMb) {
        return tierMemoryPart(context, explicitCapMb, true);
    }

    /** Aplica o override do seletor de pool: troca so o device, shared/d3d9 seguem o auto. */
    private static String applyPoolOverride(String memoryPart, int poolMb, int explicitCapMb) {
        if (poolMb <= 0) return memoryPart;
        int dev = explicitCapMb > 0 ? Math.min(poolMb, explicitCapMb) : poolMb;
        try {
            String rest = memoryPart.replaceFirst("(?i)dxgi\\.maxDeviceMemory\\s*=\\s*\\d+",
                    "dxgi.maxDeviceMemory = " + dev);
            if (!rest.equals(memoryPart)) return rest;
        } catch (Exception ignored) {}
        return "dxgi.maxDeviceMemory = " + dev + "; " + memoryPart;
    }

    private static long getTotalMemMb(Context context) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    context.getSystemService(Context.ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return mi.totalMem / (1024 * 1024);
        } catch (Exception e) {
            return 0;
        }
    }
    // Pool pequeno para RAGE (GTA V): a comunidade confirmou que reportar pouca
    // VRAM (512 MB device) mantem a RAM total em ~80% em vez de 85-90% (faixa do lmkd),
    // junto das flags de streaming do RAM Fix. Shared/d3d9 tambem baixos: o total
    // reportado (512+1024) segura o sistema — 512+2048+4096 ainda derrubava o
    // celular inteiro em teste. So faz sentido com ramFix ligado e sem conf
    // custom/hard cap do usuario, que sempre tem precedencia.
    public static final int RAGE_SMALL_POOL_MB = 512;
    public static final int RAGE_SMALL_SHARED_MB = 1024;
    public static final int RAGE_SMALL_D3D9_MB = 2048;
    private static String rageMemoryPart() {
        return "dxgi.maxDeviceMemory = " + RAGE_SMALL_POOL_MB + "; dxgi.maxSharedMemory = " + RAGE_SMALL_SHARED_MB
                + "; d3d9.maxAvailableMemory = " + RAGE_SMALL_D3D9_MB;
    }
    public static String buildRageFallbackConfig() {
        return SAFETY_KEYS + "; " + rageMemoryPart();
    }
    public static String buildSafeFallbackConfig(Context context, int explicitCapMb) {
        return SAFETY_KEYS + "; " + tierMemoryPart(context, explicitCapMb, true);
    }
    public static String buildSafeFallbackConfig(Context context, int explicitCapMb, boolean ramFixOn) {
        return SAFETY_KEYS + "; " + tierMemoryPart(context, explicitCapMb, ramFixOn);
    }
    public static final String[] VKD3D_FEATURE_LEVELS = {"12_0", "12_1", "12_2", "11_1", "11_0", "10_1", "10_0", "9_3", "9_2", "9_1"};
    public static final int DXVK_TYPE_NONE = 0;
    public static final int DXVK_TYPE_ASYNC = 1;
    public static final int DXVK_TYPE_GPLASYNC = 2;
    private final ToggleButton swAsync;
    private final ToggleButton swAsyncCache;
    private final ToggleButton swRamFix;
    private final ToggleButton swGtaOpt;
    private final View llAsync;
    private final View llAsyncCache;
    private final Context context;
    private final boolean arm64EC;
    private List<String> dxvkVersions;
    private final CustomConf customConf;
    private TextView tvDxvkConfStatus;
    private View btRemoveDxvkConf;

    /** Estado do dxvk.conf custom, compartilhado com a tela que abriu o dialogo.
     *  O dialogo nunca toca em arquivo: só monta stage; quem salva é o host. */
    public static class CustomConf {
        public final File targetFile;
        public String stagedContent;
        public boolean stagedRemoved;
        public final Runnable requestImport;

        public CustomConf(File targetFile, Runnable requestImport) {
            this.targetFile = targetFile;
            this.requestImport = requestImport;
        }
    }

    public DXVKConfigDialog(View anchor, boolean arm64EC) {
        this(anchor, arm64EC, null);
    }

    public DXVKConfigDialog(View anchor, boolean arm64EC, CustomConf customConf) {
        super(anchor.getContext(), R.layout.dxvk_config_dialog);
        context = anchor.getContext();
        this.arm64EC = arm64EC;
        this.customConf = customConf;
        setIcon(R.drawable.icon_settings);
        setTitle("DXVK + VKD3D "+context.getString(R.string.configuration));

        final Spinner sVersion = findViewById(R.id.SVersion);
        final Spinner sFramerate = findViewById(R.id.SFramerate);
        final Spinner sDDrawWrapper = findViewById(R.id.SDDrawWrapper);
        final Spinner sVkd3dVersion = findViewById(R.id.SVKD3DVersion);
        final Spinner sVkd3dFeatureLevel = findViewById(R.id.SVKD3DFeatureLevel);
        CheckBox cbNoTimeline = findViewById(R.id.CBXDvkNoTimeline);
        CheckBox cbVk3d66 = findViewById(R.id.CBXDvk66);
        findViewById(R.id.BTNoTimelineHelp).setOnClickListener(v ->
                AppUtils.showHelpBox(getContext(), v, R.string.dxvk_help_no_timeline));
        findViewById(R.id.BTVk3d66Help).setOnClickListener(v ->
                AppUtils.showHelpBox(getContext(), v, R.string.dxvk_help_vk3d66));
        swAsync = findViewById(R.id.SWAsync);
        swAsyncCache = findViewById(R.id.SWAsyncCache);
        swRamFix = findViewById(R.id.SWRamFix);
        swGtaOpt = findViewById(R.id.SWGtaOpt);
        final Spinner sRamFixPool = findViewById(R.id.SRamFixPool);
        findViewById(R.id.BTRamFixHelp).setOnClickListener(v ->
                AppUtils.showHelpBox(getContext(), v, R.string.ram_fix_help));
        findViewById(R.id.BTGtaOptHelp).setOnClickListener(v ->
                AppUtils.showHelpBox(getContext(), v, R.string.gta_optimization_help));
        llAsync = findViewById(R.id.LLAsync);
        llAsyncCache = findViewById(R.id.LLAsyncCache);

        View llDxvkConf = findViewById(R.id.LLDxvkConf);
        if (customConf == null) {
            llDxvkConf.setVisibility(View.GONE);
        } else {
            tvDxvkConfStatus = findViewById(R.id.TVDxvkConfStatus);
            btRemoveDxvkConf = findViewById(R.id.BTRemoveDxvkConf);
            findViewById(R.id.BTImportDxvkConf).setOnClickListener(v -> {
                if (customConf.requestImport != null) customConf.requestImport.run();
            });
            btRemoveDxvkConf.setOnClickListener(v -> {
                customConf.stagedContent = null;
                customConf.stagedRemoved = true;
                refreshCustomConf();
                AppUtils.showToast(context, R.string.dxvk_conf_removed);
            });
            refreshCustomConf();
        }

        ContentsManager contentsManager = new ContentsManager(context);
        contentsManager.syncContents();
        loadDxvkVersionSpinner(contentsManager,sVersion);
        loadVkd3dVersionSpinner(contentsManager, sVkd3dVersion);
        ArrayAdapter<String> featureAdapter = new ThemedSpinnerAdapter<>(context, VKD3D_FEATURE_LEVELS);
        sVkd3dFeatureLevel.setAdapter(featureAdapter);

        KeyValueSet config = parseConfig(anchor.getTag());
        if (!arm64EC && config.get("version").toLowerCase(Locale.ENGLISH).contains("arm64ec")) {
            config.put("version", DefaultVersion.DXVK_X86);
        }
        AppUtils.setSpinnerSelectionFromIdentifier(sVersion, config.get("version"));
        AppUtils.setSpinnerSelectionFromIdentifier(sFramerate, config.get("framerate"));
        AppUtils.setSpinnerSelectionFromIdentifier(sDDrawWrapper, config.get("ddrawrapper"));
        setVkd3dSelectionByIdentifier(sVkd3dVersion, config.get("vkd3dVersion"));
        updateVkd3dControls(sVkd3dVersion, sVkd3dFeatureLevel);
        AppUtils.setSpinnerSelectionFromIdentifier(sVkd3dFeatureLevel, config.get("vkd3dLevel"));
        swAsync.setChecked(config.get("async").equals("1"));
        swAsyncCache.setChecked(config.get("asyncCache").equals("1"));
        swRamFix.setChecked(config.getBoolean("ramFix", true));
        swGtaOpt.setChecked(config.get("gtaOpt").equals("1"));
        AppUtils.setSpinnerSelectionFromNumber(sRamFixPool, config.get("ramFixPool", "0"));
        sRamFixPool.setEnabled(swRamFix.isChecked());
        swRamFix.setOnCheckedChangeListener((v, checked) -> sRamFixPool.setEnabled(checked));
        cbNoTimeline.setChecked(config.get("noTimeline").equals("1"));
        cbVk3d66.setChecked(config.get("vk3d66").equals("1"));

        updateConfigVisibility(getDXVKType(sVersion.getSelectedItemPosition()));

        sVersion.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateConfigVisibility(getDXVKType(position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

        sVkd3dVersion.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateVkd3dControls(sVkd3dVersion, sVkd3dFeatureLevel);
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        setOnConfirmCallback(() -> {
            config.put("version", sVersion.getSelectedItem().toString());
            config.put("framerate", StringUtils.parseNumber(sFramerate.getSelectedItem()));
            config.put("ddrawrapper", StringUtils.parseIdentifier(sDDrawWrapper.getSelectedItem()));
            config.put("async", ((swAsync.isChecked())&&(llAsync.getVisibility()==View.VISIBLE))?"1":"0");
            config.put("asyncCache", ((swAsyncCache.isChecked())&&(llAsyncCache.getVisibility()==View.VISIBLE))?"1":"0");
            config.put("vkd3dVersion", ((VKD3DVersionItem) sVkd3dVersion.getSelectedItem()).getIdentifier());
            config.put("vkd3dLevel", sVkd3dFeatureLevel.getSelectedItem().toString());
            config.put("noTimeline", cbNoTimeline.isChecked() ? "1" : "0");
            config.put("vk3d66", cbVk3d66.isChecked() ? "1" : "0");
            config.put("ramFix", swRamFix.isChecked() ? "1" : "0");
            config.put("ramFixPool", StringUtils.parseNumber(sRamFixPool.getSelectedItem()));
            config.put("gtaOpt", swGtaOpt.isChecked() ? "1" : "0");
            anchor.setTag(config.toString());
        });
    }

    /** Atualiza status/botao da secao dxvk.conf (chamado pelo host apos importar). */
    public void refreshCustomConf() {
        if (customConf == null || tvDxvkConfStatus == null) return;
        if (customConf.stagedRemoved) {
            tvDxvkConfStatus.setText(context.getString(R.string.dxvk_custom_conf_none));
            btRemoveDxvkConf.setEnabled(false);
            return;
        }
        if (customConf.stagedContent != null) {
            int bytes;
            try {
                bytes = customConf.stagedContent.getBytes("UTF-8").length;
            } catch (Exception e) {
                bytes = customConf.stagedContent.length();
            }
            tvDxvkConfStatus.setText(context.getString(R.string.dxvk_custom_conf_pending, bytes));
            btRemoveDxvkConf.setEnabled(true);
            return;
        }
        File active = customConf.targetFile;
        if (active != null && active.isFile() && active.length() > 0) {
            tvDxvkConfStatus.setText(context.getString(R.string.dxvk_custom_conf_active, (int) active.length()));
            btRemoveDxvkConf.setEnabled(true);
        } else {
            tvDxvkConfStatus.setText(context.getString(R.string.dxvk_custom_conf_none));
            btRemoveDxvkConf.setEnabled(false);
        }
    }

    private void updateConfigVisibility(int dxvkType) {
        if (dxvkType == DXVK_TYPE_ASYNC) {
            llAsync.setVisibility(View.VISIBLE);
            llAsyncCache.setVisibility(View.GONE);
        } else if (dxvkType == DXVK_TYPE_GPLASYNC) {
            llAsync.setVisibility(View.VISIBLE);
            llAsyncCache.setVisibility(View.VISIBLE);
        } else {
            llAsync.setVisibility(View.GONE);
            llAsyncCache.setVisibility(View.GONE);
        }
    }

    private int getDXVKType(int pos) {
        final String v = dxvkVersions.get(pos);
        int dxvkType = DXVK_TYPE_NONE;
        if (v.contains("gplasync"))
            dxvkType = DXVK_TYPE_GPLASYNC;
        else if (v.contains("async"))
            dxvkType = DXVK_TYPE_ASYNC;
        return dxvkType;
    }

    public static KeyValueSet parseConfig(Object config) {
        KeyValueSet merged = new KeyValueSet(DEFAULT_CONFIG);
        if (config != null && !config.toString().isEmpty()) {
            for (String[] entry : new KeyValueSet(config.toString())) {
                merged.put(entry[0], entry[1]);
            }
        }
        return merged;
    }

    public static void setEnvVars(Context context, KeyValueSet config, EnvVars envVars) {
        setEnvVars(context, config, envVars, null, 0);
    }

    public static File getContainerDxvkConfFile(File containerRoot) {
        return containerRoot != null ? new File(containerRoot, CUSTOM_CONF_FILENAME) : null;
    }

    public static boolean isValidCustomConfFile(File f) {
        if (f == null || !f.isFile()) return false;
        long len = f.length();
        return len > 0 && len <= MAX_CUSTOM_CONF_BYTES;
    }

    public static void setEnvVars(Context context, KeyValueSet config, EnvVars envVars, File containerRoot) {
        setEnvVars(context, config, envVars, containerRoot, 0);
    }

    public static void setEnvVars(Context context, KeyValueSet config, EnvVars envVars, File containerRoot,
                                  int explicitCapMb) {
        setEnvVars(context, config, envVars, containerRoot, explicitCapMb, null);
    }

    /** Precedencia do conf custom: atalho > container > global legado. */
    public static void setEnvVars(Context context, KeyValueSet config, EnvVars envVars, File containerRoot,
                                  int explicitCapMb, File shortcutConf) {
        setEnvVars(context, config, envVars, containerRoot, explicitCapMb, shortcutConf, null);
    }

    /** Applies DXVK/VKD3D settings and optionally routes graphics diagnostics to a container log directory. */
    public static void setEnvVars(Context context, KeyValueSet config, EnvVars envVars, File containerRoot,
                                  int explicitCapMb, File shortcutConf, File diagnosticLogDirectory) {
        setEnvVars(context, config, envVars, containerRoot, explicitCapMb, shortcutConf, diagnosticLogDirectory, false);
    }

    /** Mesmo que acima, com pool pequeno reportado para alvos RAGE (GTA V). */
    public static void setEnvVars(Context context, KeyValueSet config, EnvVars envVars, File containerRoot,
                                  int explicitCapMb, File shortcutConf, File diagnosticLogDirectory, boolean rageSmallPool) {
        setEnvVars(context, config, envVars, containerRoot, explicitCapMb, shortcutConf,
                diagnosticLogDirectory, rageSmallPool, null);
    }

    /**
     * Versao completa: gameDirDxvkConf e o dxvk.conf ao lado do .exe, se existir.
     * As chaves de memoria dele sobrevivem (o arquivo governa a memoria dele);
     * as chaves de seguranca do env sempre valem.
     */
    public static void setEnvVars(Context context, KeyValueSet config, EnvVars envVars, File containerRoot,
                                  int explicitCapMb, File shortcutConf, File diagnosticLogDirectory,
                                  boolean rageSmallPool, File gameDirDxvkConf) {
        // Keep every D3D shader cache on fast internal storage. DXVK 1.x uses
        // STATE_CACHE_PATH while modern DXVK and VKD3D-Proton use their shader
        // cache variables, so set all three for both ARM64EC and x86 runtimes.
        File cacheDir = new File(ImageFs.find(context).getRootDir(),
                ImageFs.CACHE_PATH + "/d3d");
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
            Log.w("DXVKConfigDialog", "Could not create D3D cache directory: " + cacheDir);
        }
        String cachePath = cacheDir.getAbsolutePath();
        envVars.put("DXVK_STATE_CACHE_PATH", cachePath);
        envVars.put("DXVK_SHADER_CACHE_PATH", cachePath);
        envVars.put("VKD3D_SHADER_CACHE_PATH", cachePath);
        if (diagnosticLogDirectory != null) {
            if (!diagnosticLogDirectory.isDirectory()) diagnosticLogDirectory.mkdirs();
            envVars.put("DXVK_LOG_LEVEL", "error");
            envVars.put("DXVK_LOG_PATH", diagnosticLogDirectory.getAbsolutePath());
            envVars.put("VKD3D_DEBUG", "err");
            envVars.put("VKD3D_SHADER_DEBUG", "err");
        }
        else {
            envVars.put("DXVK_LOG_LEVEL", "none");
            envVars.put("DXVK_LOG_PATH", "none");
            envVars.put("VKD3D_DEBUG", "none");
            envVars.put("VKD3D_SHADER_DEBUG", "none");
        }
        if ("1".equals(config.get("noTimeline")))
            envVars.put("DXVK_DISABLE_TIMELINE_SEMAPHORES", "1");
        // SM 6.6 so faz sentido com VKD3D ativo; com "none" o jogo e D3D11 e a
        // variavel so poluiria o ambiente de titulos D3D12 com VKD3D antigo.
        if ("1".equals(config.get("vk3d66"))
                && !"none".equalsIgnoreCase(config.get("vkd3dVersion")))
            envVars.put("VKD3D_SHADER_MODEL", "6_6");

        File rootDir = ImageFs.find(context).getRootDir();
        File globalConf = new File(rootDir, ImageFs.CONFIG_PATH+"/dxvk.conf");
        File containerConf = getContainerDxvkConfFile(containerRoot);
        File activeConf = isValidCustomConfFile(shortcutConf) ? shortcutConf
                : (isValidCustomConfFile(containerConf) ? containerConf
                : (isValidCustomConfFile(globalConf) ? globalConf : null));
        if (activeConf != null) {
            envVars.put("DXVK_CONFIG_FILE", activeConf.getAbsolutePath());
            envVars.remove("DXVK_CONFIG");
            Log.i("DXVKConfigDialog", "Using custom dxvk.conf: " + activeConf.getAbsolutePath());
        } else {
            // No managed conf: keep a manual DXVK_CONFIG_FILE override if it
            // still points at a real file, otherwise drop stale state so an
            // old import can't leak into a container that removed it.
            String manual = envVars.get("DXVK_CONFIG_FILE");
            boolean keepManual = manual != null && !manual.isEmpty() && new File(manual).isFile();
            if (!keepManual) {
                try { if (globalConf.isFile()) globalConf.delete(); } catch (Exception ignored) {}
                envVars.remove("DXVK_CONFIG_FILE");
                // Sem conf importado, fixa os defaults seguros por cima de um
                // eventual dxvk.conf que o repack colocou ao lado do .exe.
                // O env tem precedencia no DXVK, entao o merge e por chave: as
                // de seguranca sempre valem; as de memoria do arquivo sobrevivem
                // (igual ao Ludashi/vanilla: o arquivo governa a memoria dele,
                // o env nunca pisa). Respeita um DXVK_CONFIG manual
                // da aba EnvVars, se houver.
                if (!envVars.has("DXVK_CONFIG")) {
                    boolean ramFixOn = config != null && config.getBoolean("ramFix", true);
                    int poolMb = ramFixOn ? resolveRamFixPoolMb(config) : 0;
                    String memoryPart = rageSmallPool ? rageMemoryPart()
                            : tierMemoryPart(context, explicitCapMb, ramFixOn);
                    if (poolMb > 0) memoryPart = applyPoolOverride(memoryPart, poolMb, explicitCapMb);
                    envVars.put("DXVK_CONFIG", mergeGameDirConf(
                            memoryPart, gameDirConfKeys(gameDirDxvkConf)));
                    if (rageSmallPool) {
                        Log.i("DXVKConfigDialog", "RAGE small-pool overrides: device="
                                + (poolMb > 0 ? poolMb : RAGE_SMALL_POOL_MB) + " shared=" + RAGE_SMALL_SHARED_MB
                                + " (game-dir memory keys kept)");
                    } else if (ramFixOn) {
                        Log.i("DXVKConfigDialog", "RAM Fix conservative pool overrides"
                                + " (game-dir memory keys kept): " + memoryPart);
                    } else {
                        Log.i("DXVKConfigDialog", "No custom dxvk.conf, applying safe fallback overrides");
                    }
                }
            }
        }

        String framerate = config.get("framerate");
        if (!framerate.isEmpty() && !framerate.equals("0")) {
            envVars.put("DXVK_FRAME_RATE", framerate);
        }

        String async = config.get("async");
        if (!async.isEmpty() && !async.equals("0"))
            envVars.put("DXVK_ASYNC", "1");

        String asyncCache = config.get("asyncCache");
        if (!asyncCache.isEmpty() && !asyncCache.equals("0"))
            envVars.put("DXVK_GPLASYNCCACHE", "1");

        if (!"none".equalsIgnoreCase(config.get("vkd3dVersion")))
            envVars.put("VKD3D_FEATURE_LEVEL", config.get("vkd3dLevel"));
    }

    private void loadDxvkVersionSpinner(ContentsManager manager, Spinner spinner) {
        String[] originalItems = context.getResources().getStringArray(R.array.dxvk_version_entries);
        List<String> itemList = new ArrayList<>();
        for (String version : originalItems) {
            if (arm64EC || !version.toLowerCase(Locale.ENGLISH).contains("arm64ec")) itemList.add(version);
        }

        /* Content profiles join the static list under their clean version name
         * (profile.verName, e.g. "1.7.2" or "2.6.2-arm64ec-gplasync") instead of
         * the raw "name-<verCode>" entry form, and duplicates are suppressed so
         * a bundled .wcp never shows up twice next to its array entry. */
        for (ContentProfile profile : manager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_DXVK)) {
            String verName = profile.verName;
            if (verName != null && !verName.isEmpty()
                    && (arm64EC || !verName.toLowerCase(Locale.ENGLISH).contains("arm64ec"))
                    && !itemList.contains(verName))
                itemList.add(verName);
        }

        spinner.setAdapter(new ThemedSpinnerAdapter<>(context, itemList));
        dxvkVersions = itemList;
    }

    private void loadVkd3dVersionSpinner(ContentsManager manager, Spinner spinner) {
        List<VKD3DVersionItem> items = new ArrayList<>();
        items.add(new VKD3DVersionItem("none", "None"));
        for (String version : context.getResources().getStringArray(R.array.vkd3d_version_entries)) {
            items.add(new VKD3DVersionItem(version, 0));
        }
        for (ContentProfile profile : manager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_VKD3D)) {
            items.add(new VKD3DVersionItem(profile.verName, profile.verCode));
        }
        spinner.setAdapter(new ThemedSpinnerAdapter<>(context, items));
    }

    private void updateVkd3dControls(Spinner versionSpinner, Spinner featureLevelSpinner) {
        Object selected = versionSpinner.getSelectedItem();
        boolean enabled = !(selected instanceof VKD3DVersionItem)
                || !"none".equalsIgnoreCase(((VKD3DVersionItem) selected).getIdentifier());
        featureLevelSpinner.setEnabled(enabled);
        featureLevelSpinner.setAlpha(enabled ? 1f : 0.45f);
    }

    private void setVkd3dSelectionByIdentifier(Spinner spinner, String identifier) {
        for (int i = 0; i < spinner.getCount(); i++) {
            VKD3DVersionItem item = (VKD3DVersionItem) spinner.getItemAtPosition(i);
            if (item.getIdentifier().equals(identifier)) {
                spinner.setSelection(i);
                return;
            }
        }
    }
}
