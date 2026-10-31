package com.winlator.cmod.widget;

// Ported from Winlator Mali:
// https://github.com/GunaCharanTeja/WinlatorMali
// Native Mango-style preset adapted from Bannerlator's Fusion HUD approach:
// https://github.com/The412Banner/Bannerlator

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.winlator.cmod.R;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.core.ExynosDevices;
import com.winlator.cmod.core.HardwareLabel;
import com.winlator.cmod.core.CpuTelemetry;
import com.winlator.cmod.core.TelemetryMath;
import com.winlator.cmod.renderer.FrameRateSample;
import com.winlator.cmod.renderer.FrameTimeHistory;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public class WinlatorHUD extends View {
    private static final String PREFS    = "winlator_hud";
    private static final String KEY_X    = "hud_x";
    private static final String KEY_Y    = "hud_y";
    private static final String KEY_VIS  = "hud_vis";
    private static final String KEY_SHOW = "hud_show";
    private static final String KEY_SCALE= "hud_scale";
    private static final String KEY_ALPHA= "hud_alpha_int";
    private static final String KEY_VERT = "hud_vertical";
    private static final String KEY_RAM_WARNING = "hud_ram_warning";
    private static final String KEY_FPS_DEFAULT_MIGRATED = "hud_fps_default_095_2";
    private static final String KEY_DEFAULTS_096 = "hud_defaults_096";
    private static final String KEY_SOC_FORMAT = "hud_soc_format";
    private static final String KEY_GPU_FORMAT = "hud_gpu_format";
    private static final String KEY_DEVICE_FAMILY = "hud_device_family";

    public static final int SHOW_FPS      = 1;
    public static final int SHOW_GPU      = 1<<1;
    public static final int SHOW_CPU      = 1<<2;
    public static final int SHOW_BATT     = 1<<3;
    public static final int SHOW_GRAPH    = 1<<4;
    public static final int SHOW_RENDERER = 1<<5;
    public static final int SHOW_RAM      = 1<<6;
    public static final int SHOW_BATT_PCT = 1<<7;
    public static final int SHOW_MONO     = 1<<8;
    public static final int SHOW_BORDER   = 1<<9;
    public static final int SHOW_COMPACT  = 1<<10;
    public static final int SHOW_WRAPPER  = 1<<11;
    public static final int SHOW_CPU_TEMP = 1<<12;
    public static final int SHOW_LOCKED   = 1<<13;
    public static final int SHOW_SOC      = 1<<14;
    public static final int SHOW_GPU_TEMP = 1<<15;
    public static final int SHOW_PHONE_GPU = 1<<16;
    public static final int SHOW_FG_LATENCY = 1<<17;
    public static final int SHOW_FG_STATUS = 1<<18;
    public static final int SHOW_DEVICE = 1<<19;
    public static final int SHOW_DRIVER = 1<<20;
    private static final int OLD_DEFAULT = SHOW_FPS | SHOW_MONO | SHOW_WRAPPER | SHOW_GPU | SHOW_CPU | SHOW_RAM | SHOW_BATT | SHOW_BORDER | SHOW_SOC;
    private static final int SHOW_DEFAULT = SHOW_FPS | SHOW_MONO | SHOW_GPU | SHOW_CPU | SHOW_RAM | SHOW_BATT
            | SHOW_BORDER | SHOW_BATT_PCT | SHOW_DEVICE;
    /**
     * A conservative one-line MangoHUD layout which stays readable at 720p.
     * Temperature is included by SHOW_BATT and is drawn beside power usage.
     */
    private static final int SHOW_MANGO_DEFAULT = SHOW_FPS | SHOW_CPU
            | SHOW_RAM | SHOW_BATT | SHOW_BORDER | SHOW_BATT_PCT | SHOW_DEVICE;
    private static final int SHOW_MANGO_ONE_DEFAULT = SHOW_FPS | SHOW_GPU | SHOW_CPU | SHOW_RAM | SHOW_GRAPH
            | SHOW_CPU_TEMP | SHOW_GPU_TEMP | SHOW_DEVICE | SHOW_BATT_PCT | SHOW_BORDER;

    private static final int C_WHITE = Color.WHITE;
    private static final int C_GPU  = Color.rgb(0xE0,0x40,0xFB);
    private static final int C_CPU  = Color.rgb(0x00,0xE5,0xFF);
    private static final int C_BATT = Color.rgb(0xFF,0x80,0x00);
    private static final int C_CHG  = Color.rgb(0x40,0xC4,0x40);
    private static final int C_TEMP = Color.rgb(0xEF,0x53,0x50);
    private static final int C_FPS  = Color.rgb(0x76,0xFF,0x03);
    private static final int C_REND = Color.rgb(0xFF,0xEA,0x00);
    private static final int C_RAM  = Color.rgb(0xB0,0xFF,0xB0);
    private static final int C_SEP  = Color.rgb(0x60,0x60,0x60);
    private static final int C_BORDER = Color.argb(150, 255, 255, 255);

    private float TS, TSR, PAD, GRAW, CORNER;

    private static final int TEXT_FLAGS = Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG | Paint.LINEAR_TEXT_FLAG;
    private final Paint pBg      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBorder  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pVal     = new Paint(TEXT_FLAGS);
    private final Paint pGpu     = new Paint(TEXT_FLAGS);
    private final Paint pCpu     = new Paint(TEXT_FLAGS);
    private final Paint pBat     = new Paint(TEXT_FLAGS);
    private final Paint pTmp     = new Paint(TEXT_FLAGS);
    private final Paint pFps     = new Paint(TEXT_FLAGS);
    private final Paint pRend    = new Paint(TEXT_FLAGS);
    private final Paint pRam     = new Paint(TEXT_FLAGS);
    private final Paint pRamAlert= new Paint(TEXT_FLAGS);
    private final Paint pSep     = new Paint(TEXT_FLAGS);
    private final Paint pChg     = new Paint(TEXT_FLAGS);
    private final Paint pGraph   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pGraphBg = new Paint();
    private final Paint pMango = new Paint(TEXT_FLAGS);
    private final Paint pMangoValue = new Paint(TEXT_FLAGS);
    private final Paint pMangoUnit = new Paint(TEXT_FLAGS);

    private final RectF bgRect = new RectF();
    private final RectF ramHelpHitRect = new RectF();

    private float wLabelGpu, wLabelCpu, wLabelRam, wLabelPwr, wLabelTmp, wLabelCTmp, wLabelGTmp, wLabelFps, wLabelApex, wSep;
    private float wVal100pct, wValFps, wValWatt, wValTemp, wValBInfo;

    private boolean layoutDirty = true;

    private String strGpu = "N/A", strCpu = "N/A", strRam = "N/A";
    private String strPwr = "N/A", strTmp = "", strCTmp = "", strGTmp = "", strFps = "0", strPct = "";
    private String strRend = "OpenGL", strWrapper = "WineD3D", strPhoneGpu = "Unknown GPU";
    private String strFgLatency = "N/A", strFgStatus = "FG Off";
    private final String rawSoc;
    private String rawPhoneGpu = "Unknown GPU";
    private String strSoc, strDevice, strDriver = "System";
    private int socFormat, gpuFormat;
    private boolean deviceFamily;
    private int ramFormat;
    private boolean coreFrequency, coreUsage, coreName, gpuFrequency, batteryCharge;
    private java.util.Set<String> selectedCores = new java.util.HashSet<>();
    private java.util.List<String> telemetryRows = java.util.Collections.emptyList();
    private boolean snapCharging = false;
    private final int ramBlinkThreshold;
    private final int ramWarningThreshold;
    private boolean ramAlertActive = false;
    private boolean ramWarningEnabled = true;
    private boolean ramHelpPressed = false;

    private final SharedPreferences prefs;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private HudDataSource dataSource;

    private final AtomicInteger frameAccum = new AtomicInteger(0);
    private long lastFpsNs = 0;
    private float snapFps = 0;
    private boolean apexActive = false;
    private float apexMultiplier = 2.0f;
    private float apexRealFps;
    private float apexOutputFps;
    private float apexGeneratedFps;
    private long apexSampleTimeNanos;
    private boolean apexFailure;

    private int snapGpu=-1, snapCpu=-1, snapMw=-1, snapTmp=-1, snapCTmp=-1, snapGTmp=-1, snapPct=-1, snapRam=-1;
    private String rendererLabel = "OpenGL";
    private boolean isNative = false;

    private static final int GBUF = 40;
    private final float[] graph = new float[GBUF];
    private int gHead = 0;
    private float gMax = 60f;

    private int showMask = SHOW_DEFAULT;
    private float hudAlpha = 1f;
    private volatile boolean userEnabled = false;
    private boolean vertical = false;
    private boolean mangoStyle = false;
    private int hudStyle;
    private volatile boolean sessionVisible = true;
    private final FrameTimeHistory frameTimes = new FrameTimeHistory(120);
    private final String panelId;

    private float touchX, touchY, startX, startY;
    private boolean dragging = false;
    private static final float DRAG_THRESH = 10f;
    private long touchDownMs = 0;

    private boolean redrawScheduled = false;
    private Path cachedPath = null;
    private int lastGHead = -1;
    private final Runnable redrawRunnable = () -> {
        redrawScheduled = false;
        try {
            snapshot();
            invalidate();
        } catch (Exception ignored) {
        }
        if (getVisibility() == VISIBLE) scheduleRedraw();
    };

    public WinlatorHUD(Context context) { this(context, null, "primary"); }

    public WinlatorHUD(Context context, AttributeSet attrs) {
        this(context, attrs, "primary");
    }
    public WinlatorHUD(Context context, String id) { this(context, null, id); }

    private WinlatorHUD(Context context, AttributeSet attrs, String id) {
        super(context, attrs);
        panelId = id;
        prefs = context.getSharedPreferences("primary".equals(id) ? PREFS : PREFS + "_" + id, Context.MODE_PRIVATE);
        hudStyle = Math.max(0, Math.min(2, prefs.getInt("style", 0)));
        mangoStyle = hudStyle != 0;
        // User request: blink at 90%, dialog at 93% (was 91/93 and 92/95)
        ramBlinkThreshold = 90;
        ramWarningThreshold = 93;
        float d = context.getResources().getDisplayMetrics().density;
        TS     = 12f * d;
        TSR    = 11f * d;
        PAD    = 6f * d;
        GRAW   = 70f * d;
        CORNER = 5f * d;
        initPaints();
        rawSoc = getSocName();
        loadPrefs();
        lastFpsNs = System.nanoTime();
        setPivotX(0); setPivotY(0);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    private void initPaints() {
        Typeface mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
        pBg.setStyle(Paint.Style.FILL);
        pBg.setColor(Color.argb(180, 0, 0, 0));
        pBorder.setStyle(Paint.Style.STROKE);
        pBorder.setStrokeWidth(1.5f);
        pBorder.setColor(C_BORDER);

        pVal.setTextSize(TS);       pVal.setTypeface(mono);  pVal.setColor(C_WHITE);
        pGpu.setTextSize(TS);       pGpu.setTypeface(mono);  pGpu.setColor(C_GPU);
        pCpu.setTextSize(TS);       pCpu.setTypeface(mono);  pCpu.setColor(C_CPU);
        pBat.setTextSize(TS);       pBat.setTypeface(mono);  pBat.setColor(C_BATT);
        pTmp.setTextSize(TS);       pTmp.setTypeface(mono);  pTmp.setColor(C_TEMP);
        pFps.setTextSize(TS * 1.25f);
        pFps.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        pFps.setColor(C_FPS);
        pRend.setTextSize(TSR);     pRend.setTypeface(mono); pRend.setColor(C_REND);
        pRam.setTextSize(TS);       pRam.setTypeface(mono);  pRam.setColor(C_RAM);
        pRamAlert.setTextSize(TS);  pRamAlert.setTypeface(mono); pRamAlert.setColor(C_TEMP);
        pSep.setTextSize(TS);       pSep.setTypeface(mono);  pSep.setColor(C_SEP);
        pChg.setTextSize(TS);       pChg.setTypeface(mono);  pChg.setColor(C_CHG);

        pVal.setShadowLayer(2f, 1f, 1f, Color.BLACK);
        pGpu.setShadowLayer(2f, 1f, 1f, Color.BLACK);
        pCpu.setShadowLayer(2f, 1f, 1f, Color.BLACK);
        pBat.setShadowLayer(2f, 1f, 1f, Color.BLACK);
        pTmp.setShadowLayer(2f, 1f, 1f, Color.BLACK);
        pFps.setShadowLayer(2f, 1f, 1f, Color.BLACK);
        pRend.setShadowLayer(2f, 1f, 1f, Color.BLACK);
        pRam.setShadowLayer(2f, 1f, 1f, Color.BLACK);
        pRamAlert.setShadowLayer(2f, 1f, 1f, Color.BLACK);
        pSep.setShadowLayer(2f, 1f, 1f, Color.BLACK);
        pChg.setShadowLayer(2f, 1f, 1f, Color.BLACK);

        pGraph.setStyle(Paint.Style.STROKE); pGraph.setStrokeWidth(1.5f); pGraph.setColor(C_FPS);
        pGraphBg.setStyle(Paint.Style.FILL); pGraphBg.setColor(Color.argb(80,20,20,20));

        wLabelGpu  = pGpu.measureText("GPU ");
        wLabelCpu  = pCpu.measureText("CPU ");
        wLabelRam  = pRam.measureText("RAM ");
        wLabelPwr  = pBat.measureText("PWR ");
        wLabelTmp  = pTmp.measureText("TMP ");
        wLabelCTmp = pTmp.measureText("CTMP ");
        wLabelGTmp = pTmp.measureText("GTMP ");
        wLabelFps  = pFps.measureText("FPS ");
        wLabelApex = pFps.measureText("Apex ");
        wSep       = pSep.measureText(" | ");
        wVal100pct = pVal.measureText("100%");
        wValFps    = pFps.measureText("999");
        wValWatt   = pVal.measureText("9.9W");
        wValTemp   = pVal.measureText("99°C");
        wValBInfo  = pVal.measureText("9.9W (100%)");
        pMango.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        pMangoValue.setTypeface(pMango.getTypeface());
        pMangoUnit.setTypeface(pMango.getTypeface());
        pMangoValue.setColor(Color.WHITE);
    }

    public void setDataSource(HudDataSource ds) { this.dataSource = ds; }

    /**
     * Applies Bannerlator's native-HUD strategy to the MangoHUD option. This is
     * intentionally an Android Canvas preset rather than a guest Vulkan layer:
     * it works for Vulkan and OpenGL renderers and cannot block Wine startup.
     * The preset is session-only, so it never overwrites the user's Winlator
     * HUD customization.
     */
    public void setMangoStyle(boolean enabled) {
        setHudStyle(enabled ? 2 : 0);
    }

    public int getHudStyle() { return hudStyle; }
    public String getPanelId() { return panelId; }
    public String getPanelName() { return prefs.getString("name", "primary".equals(panelId) ? "HUD 1" : "HUD"); }
    public void setPanelName(String name) { prefs.edit().putString("name", name).apply(); }
    public void clearPanelPreferences() { prefs.edit().clear().apply(); }
    public void setHudStyle(int style) {
        style = Math.max(0, Math.min(2, style));
        if (style != hudStyle || !prefs.getBoolean("style_initialized", false)) {
            if (style != hudStyle || prefs.getBoolean("style_initialized", false))
                prefs.edit().putInt("style_" + hudStyle + "_mask", showMask)
                        .putInt("style_" + hudStyle + "_ram", ramFormat)
                        .putInt("style_" + hudStyle + "_alpha", Math.round(hudAlpha * 100))
                        .putFloat("style_" + hudStyle + "_scale", getScaleX()).apply();
            hudStyle = style;
            int mask = style == 1 ? SHOW_MANGO_ONE_DEFAULT
                    : style == 2 ? SHOW_MANGO_DEFAULT : prefs.getInt(KEY_SHOW, SHOW_DEFAULT);
            showMask = prefs.getInt("style_" + style + "_mask", mask);
            ramFormat = prefs.getInt("style_" + style + "_ram", style == 1 ? 2 : 0);
            hudAlpha = prefs.getInt("style_" + style + "_alpha", style == 1 ? 85 : prefs.getInt(KEY_ALPHA, 55)) / 100f;
            float scale = prefs.getFloat("style_" + style + "_scale", prefs.getFloat(KEY_SCALE, 1f));
            setAlpha(hudAlpha); setScaleX(scale); setScaleY(scale);
            prefs.edit().putInt("style", style).putInt(KEY_SHOW, showMask).putInt("ram_format", ramFormat)
                    .putInt(KEY_ALPHA, Math.round(hudAlpha * 100)).putFloat(KEY_SCALE, scale)
                    .putBoolean("style_initialized", true).apply();
        }
        mangoStyle = style != 0;
        if (style == 1 && !prefs.contains(KEY_X)) post(() -> setPositionPreset(0));
        pBg.setColor(style == 1 ? Color.argb(210, 12, 12, 12) : style == 2 ? Color.argb(210, 8, 10, 14) : Color.argb(180, 0, 0, 0));
        pBorder.setColor(style == 1 ? Color.argb(50, 48, 48, 48) : style == 2 ? Color.argb(190, 126, 87, 194) : C_BORDER);
        if (style != 1) pGraph.setStrokeWidth(1.5f);
        layoutDirty = true;
        cachedPath = null;
        requestLayout();
        invalidate();
        post(this::fitPosition);
    }

    private static String getSocName() {
        ExynosDevices.Profile device = ExynosDevices.find(Build.MODEL);
        if (device != null) return device.soc;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            String model = Build.SOC_MODEL;
            if (model != null && !model.isEmpty() && !"unknown".equalsIgnoreCase(model)) {
                String mfg = Build.SOC_MANUFACTURER;
                String mapped = mapExynosModel(model);
                if (mapped == null && mfg != null && !mfg.isEmpty()) {
                    mapped = mapExynosModel(mfg + " " + model);
                }
                if (mapped != null) return mapped;
                if (mfg != null && !mfg.isEmpty() && !"unknown".equalsIgnoreCase(mfg)
                        && !model.regionMatches(true, 0, mfg, 0, mfg.length())) {
                    return mfg + " " + model;
                }
                return model;
            }
        }
        if (Build.HARDWARE != null && !Build.HARDWARE.isEmpty()) {
            String mapped = mapExynosModel(Build.HARDWARE);
            return mapped != null ? mapped : Build.HARDWARE;
        }
        return "SoC";
    }

    private static String mapExynosModel(String model) {
        String m = model.toLowerCase(Locale.US);
        if (m.contains("s5e9945") || m.contains("universal2400")) return "Exynos 2400";
        if (m.contains("s5e9925") || m.contains("universal2200")) return "Exynos 2200";
        if (m.contains("s5e9955")) return "Exynos 2500";
        if (m.contains("s5e9965")) return "Exynos 2600";
        if (m.contains("s5e8845") || m.contains("universal1480")) return "Exynos 1480";
        if (m.contains("s5e8855")) return "Exynos 1580";
        if (m.contains("s5e8865")) return "Exynos 1680";
        return null;
    }

    public void onFrame() {
        if (!userEnabled || !sessionVisible) return;
        frameAccum.incrementAndGet(); frameTimes.frame(System.nanoTime());
    }

    public void resetFrameBoundary() { frameTimes.boundary(); frameAccum.set(0); lastFpsNs = System.nanoTime(); }
    public void setSessionHudVisible(boolean visible) {
        if (visible && !sessionVisible) resetFrameBoundary();
        sessionVisible = visible;
        setVisibility(visible && userEnabled ? VISIBLE : GONE);
        if (visible && userEnabled) {
            if (dataSource != null) dataSource.start();
            scheduleRedraw();
        }
    }

    public void update() { onFrame(); }

    private void snapshot() {
        long now = System.nanoTime();
        if (lastFpsNs == 0) lastFpsNs = now;
        long dt = now - lastFpsNs;
        if (dt >= 950_000_000L) {
            int f = frameAccum.getAndSet(0);
            snapFps = f * 1_000_000_000f / dt;
            lastFpsNs = now;

            // FG can miss its requested factor or coalesce source Presents.
            // Only completed compositor draws belong in its headline/graph.
            float displayFps = getDisplayedFps(now);
            graph[gHead % GBUF] = displayFps;
            gHead++;
            cachedPath = null;
            float targetMax = Math.max(30f, displayFps * 1.2f);
            gMax = gMax + (targetMax - gMax) * 0.15f;
            
            strFps = String.format(Locale.US, "%.0f", displayFps);
        }
        if (dataSource != null) {
            int g  = dataSource.gpuLoad.get();
            int cp = dataSource.cpuLoad.get();
            int mw = dataSource.batteryMw.get();
            int tm = dataSource.batteryTempC.get();
            int ctm = dataSource.cpuTempC.get();
            int gtm = dataSource.gpuTempC.get();
            int pc = dataSource.batteryPct.get();
            int rm = dataSource.ramUsagePct.get();

            if (g != snapGpu)   { snapGpu = g;  strGpu = g  >= 0 ? g  + "%" : "N/A"; }
            if (cp != snapCpu)  { snapCpu = cp; strCpu = cp >= 0 ? cp + "%" : "N/A"; }
            snapRam = rm;
            String ram = TelemetryMath.ram(dataSource.ramTotalBytes, dataSource.ramAvailableBytes, ramFormat);
            if (!ram.equals(strRam)) { strRam = ram; requestLayout(); }
            updateRamAlert(rm);
            if (tm != snapTmp)  { snapTmp = tm; strTmp = tm > 0 ? tm + "°C" : ""; }
            if (ctm != snapCTmp) { snapCTmp = ctm; strCTmp = ctm > 0 ? ctm + "°C" : ""; }
            if (gtm != snapGTmp) { snapGTmp = gtm; strGTmp = gtm > 0 ? gtm + "°C" : ""; }
            if (pc != snapPct)  { snapPct = pc; strPct = pc >= 0 ? pc + "%" : ""; }
            if (mw != snapMw) {
                snapMw = mw;
                if (mw >= 0)       strPwr = String.format(Locale.US, "%.1fW", mw / 1000f);
                else                strPwr = "N/A";
            }
            snapCharging = dataSource.charging;
            java.util.List<String> rows = new java.util.ArrayList<>();
            if (hudStyle != 1 && (showMask & SHOW_BATT_PCT) != 0 && (showMask & SHOW_BATT) == 0)
                rows.add(pc >= 0 ? pc + "%" : "N/A %");
            if (gpuFrequency) rows.add("GPU " + (dataSource.gpuMhz.get() >= 0 ? dataSource.gpuMhz.get() + " MHz" : "N/A"));
            if (batteryCharge) rows.add((dataSource.batteryChargeEstimated ? "≈" : "")
                    + (Double.isNaN(dataSource.batteryAvailableMah) ? "N/A mAh"
                    : String.format(Locale.getDefault(), "%.0f mAh", dataSource.batteryAvailableMah)));
            for (CpuTelemetry.Core core : dataSource.cpu.cores) if (selectedCores.contains(String.valueOf(core.id))) {
                String value = core.label(coreName);
                if (coreFrequency) value += " " + (core.mhz >= 0 ? core.mhz + " MHz" : "N/A MHz");
                if (coreUsage) value += " " + (core.usage >= 0 ? core.usage + "%" : "N/A %");
                rows.add(value);
            }
            if (!rows.equals(telemetryRows)) { telemetryRows = rows; layoutDirty = true; requestLayout(); }
        }
    }

    private void updateRamAlert(int ramPercent) {
        // The HUD observes memory pressure. It must not PAGEOUT live Android
        // mappings as a game's working set grows: that stalls the compositor
        // without reclaiming the separate Wine process's allocations.
        if (!ramWarningEnabled) {
            if (ramAlertActive) {
                ramAlertActive = false;
                layoutDirty = true;
                requestLayout();
            }
            return;
        }
        boolean wasActive = ramAlertActive;
        ramAlertActive = ramPercent >= ramBlinkThreshold;
        if (wasActive != ramAlertActive) {
            layoutDirty = true;
            requestLayout();
        }
    }

    private void showRamWarning() {
        ContentDialog dialog = new ContentDialog(getContext());
        dialog.setTitle("High RAM Usage");
        dialog.setMessage("RAM usage is currently " + Math.max(0, snapRam) + "%. "
                + "At " + ramWarningThreshold + "% or higher, the game may run out of memory and crash.");
        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (getVisibility() != VISIBLE) return;
        try {
            ramHelpHitRect.setEmpty();
            if (hudStyle == 1) { drawMangoOne(c); return; }
            boolean mono = (showMask & SHOW_MONO) != 0;
            updatePaintColors(mono);

            bgRect.set(0, 0, getWidth(), getHeight());
            float corner = vertical ? CORNER : (bgRect.height() / 2f);
            
            pBg.setShadowLayer(4f, 0, 0, Color.BLACK);
            c.drawRoundRect(bgRect, corner, corner, pBg);
            pBg.clearShadowLayer();

            if ((showMask & SHOW_BORDER) != 0) c.drawRoundRect(bgRect, corner, corner, pBorder);

            if (vertical) drawVertical(c);
            else          drawHorizontal(c);
        } catch (Exception e) {
        }
    }

    private void updatePaintColors(boolean mono) {
        float fps = getDisplayedFps(System.nanoTime());
        int fpsColor = mono ? C_WHITE : (fps >= 55 ? C_FPS : (fps >= 25 ? C_REND : C_TEMP));
        pFps.setColor(fpsColor);
        pGraph.setColor(fpsColor);
        pGpu.setColor(mono ? C_WHITE : C_GPU);
        pCpu.setColor(mono ? C_WHITE : C_CPU);
        pBat.setColor(mono ? C_WHITE : C_BATT);
        pTmp.setColor(mono ? C_WHITE : C_TEMP);
        pRend.setColor(mono ? C_WHITE : C_REND);
        pRam.setColor(ramAlertActive ? C_TEMP : (mono ? C_WHITE : C_RAM));
    }

    private static final class MangoRow {
        final String label, value, extra;
        final int color;
        MangoRow(String label, String value, String extra, int color) {
            this.label = label; this.value = value; this.extra = extra; this.color = color;
        }
    }
    private float mangoLineHeight() { return TS * 1.9f; }
    private String mangoEngine() {
        String label = rendererLabel.toLowerCase(Locale.ROOT);
        if (label.contains("vkd3d")) return "VKD3D";
        if (label.contains("dxvk")) return "DXVK";
        if (label.contains("opengl") || label.contains("wined3d")) return "OpenGL";
        return label.contains("gdi") ? "GDI" : "Vulkan";
    }
    private String mangoFrameTime() {
        double milliseconds = frameTimes.latest(System.nanoTime());
        return Double.isFinite(milliseconds) ? String.format(Locale.getDefault(), "%.1f ms", milliseconds) : "N/A ms";
    }
    private java.util.List<MangoRow> mangoRows() {
        java.util.List<MangoRow> rows = new java.util.ArrayList<>();
        if ((showMask & SHOW_GPU) != 0) rows.add(new MangoRow("GPU", strGpu,
                (showMask & SHOW_GPU_TEMP) != 0 ? (strGTmp.isEmpty() ? "N/A" : strGTmp) : "", Color.rgb(45, 183, 104)));
        if ((showMask & SHOW_CPU) != 0) rows.add(new MangoRow("CPU", strCpu,
                (showMask & SHOW_CPU_TEMP) != 0 ? (strCTmp.isEmpty() ? "N/A" : strCTmp) : "", Color.rgb(44, 171, 217)));
        // Unified system RAM is not a measurement of GPU allocations. Keep the
        // requested VRAM row honest until a backend exposes an actual budget.
        if (getTelemetryOption("vram")) rows.add(new MangoRow("VRAM", "N/A", "", Color.rgb(192, 63, 203)));
        if ((showMask & SHOW_RAM) != 0) rows.add(new MangoRow("RAM", strRam,
                "", ramAlertActive ? C_TEMP : Color.rgb(210, 78, 200)));
        if ((showMask & SHOW_FPS) != 0) rows.add(new MangoRow(mangoEngine(),
                String.format(Locale.getDefault(), "%.0f FPS", snapFps), mangoFrameTime(), Color.rgb(240, 62, 56)));
        return rows;
    }
    private java.util.List<String> mangoFooter() {
        java.util.List<String> rows = new java.util.ArrayList<>();
        String identity = (showMask & SHOW_DEVICE) != 0 ? strDevice : "";
        if ((showMask & SHOW_BATT_PCT) != 0) identity += (identity.isEmpty() ? "" : " · ") + (strPct.isEmpty() ? "N/A %" : strPct);
        if (!identity.isEmpty()) rows.add(identity);
        if ((showMask & SHOW_RENDERER) != 0) rows.add(strRend);
        if ((showMask & SHOW_DRIVER) != 0) rows.add(strDriver);
        if ((showMask & SHOW_PHONE_GPU) != 0) rows.add(strPhoneGpu);
        if ((showMask & SHOW_SOC) != 0) rows.add(strSoc);
        if ((showMask & SHOW_WRAPPER) != 0) rows.add(strWrapper);
        if ((showMask & SHOW_BATT) != 0) rows.add(strPwr + (strTmp.isEmpty() ? "" : " · " + strTmp));
        if ((showMask & SHOW_FG_STATUS) != 0) rows.add(strFgStatus);
        if ((showMask & SHOW_FG_LATENCY) != 0) rows.add(strFgLatency);
        rows.addAll(telemetryRows);
        return rows;
    }
    private void mangoRight(Canvas canvas, String text, float edge, float baseline, Paint paint) {
        canvas.drawText(text, edge - paint.measureText(text), baseline, paint);
    }
    private void mangoValue(Canvas canvas, String text, float edge, float baseline) {
        String number = text, unit = "";
        for (String suffix : new String[]{" FPS", " GB", " ms", "°C", "%"}) if (text.endsWith(suffix) && !text.startsWith("N/A")) {
            number = text.substring(0, text.length() - suffix.length()); unit = suffix.trim(); break;
        }
        pMangoUnit.setTextSize(TS * .85f); pMangoUnit.setColor(pMangoValue.getColor());
        float unitWidth = pMangoUnit.measureText(unit), gap = unit.isEmpty() ? 0 : TS * .12f;
        canvas.drawText(number, edge - unitWidth - gap - pMangoValue.measureText(number), baseline, pMangoValue);
        canvas.drawText(unit, edge - unitWidth, baseline - TS * .52f, pMangoUnit);
    }
    private void drawMangoOne(Canvas canvas) {
        bgRect.set(0, 0, getWidth(), getHeight());
        canvas.drawRoundRect(bgRect, CORNER, CORNER, pBg);
        if ((showMask & SHOW_BORDER) != 0) canvas.drawRoundRect(bgRect, CORNER, CORNER, pBorder);
        float line = mangoLineHeight(), y = PAD;
        pMango.setTextSize(TS * 1.65f);
        pMangoValue.setTextSize(TS * 1.85f);
        boolean mono = (showMask & SHOW_MONO) != 0;
        for (MangoRow row : mangoRows()) {
            float baseline = getBaseline(pMango, y, line);
            pMango.setColor(mono ? Color.WHITE : row.color);
            canvas.drawText(row.label, PAD, baseline, pMango);
            pMangoValue.setColor(ramAlertActive && row.label.equals("RAM") ? C_TEMP : Color.WHITE);
            mangoValue(canvas, row.value, getWidth() * .61f, baseline);
            pMangoValue.setTextSize(TS * 1.65f);
            mangoValue(canvas, row.extra, getWidth() - PAD, baseline);
            pMangoValue.setTextSize(TS * 1.85f);
            if (ramAlertActive && row.label.equals("RAM")) ramHelpHitRect.set(PAD, y, getWidth() - PAD, y + line);
            y += line;
        }
        pMangoValue.setColor(Color.WHITE);
        pMangoValue.setTextSize(TS);
        for (String row : mangoFooter()) {
            canvas.save();
            canvas.clipRect(PAD, y, getWidth() - PAD, y + TS + PAD);
            canvas.drawText(row, PAD, getBaseline(pMangoValue, y, TS + PAD), pMangoValue);
            canvas.restore();
            y += TS + PAD;
        }
        if ((showMask & SHOW_GRAPH) == 0) return;
        pMango.setTextSize(TS); pMango.setColor(mono ? Color.WHITE : Color.rgb(240, 62, 56));
        canvas.drawText("Frametime", PAD, y + TS, pMango);
        mangoRight(canvas, mangoFrameTime(), getWidth() - PAD, y + TS * 2.4f, pMangoValue);
        double[] history = frameTimes.snapshot();
        if (history.length == 0) return;
        double maximum = 33.333;
        for (double value : history) maximum = Math.max(maximum, value * 1.12);
        float top = y + TS * 2.7f, bottom = getHeight() - PAD;
        float width = getWidth() - PAD * 2;
        Path path = new Path();
        for (int i = 0; i < history.length; i++) {
            float x = PAD + width * (120 - history.length + i) / 119f;
            float pointY = bottom - (float)(history[i] / maximum) * (bottom - top);
            if (i == 0) path.moveTo(x, pointY); else path.lineTo(x, pointY);
        }
        pGraph.setColor(mono ? Color.WHITE : Color.rgb(42, 232, 43));
        pGraph.setStrokeWidth(Math.max(1f, TS / 12));
        canvas.drawPath(path, pGraph);
    }

    private float getDisplayedFps(long nowNanos) {
        return apexActive ? FrameRateSample.freshOutput(apexOutputFps,
                apexSampleTimeNanos, nowNanos) : snapFps;
    }

    private Paint getRamValuePaint(boolean compact) {
        return ramAlertActive ? pRamAlert : (compact ? pRam : pVal);
    }

    private void drawHorizontal(Canvas c) {
        boolean compact = (showMask & SHOW_COMPACT) != 0;
        float rowH = getHeight();
        float radius = rowH / 2f;
        float x = radius;
        boolean first = true;

        if ((showMask & SHOW_RENDERER) != 0) {
            if (!first) x += drawSep(c, x, 0);
            float baseline = getBaseline(pRend, 0, rowH);
            c.drawText(strRend, x, baseline, pRend);
            x += pRend.measureText(strRend);
            first = false;
        }
        if ((showMask & SHOW_WRAPPER) != 0) {
            if (!first) x += drawSep(c, x, 0);
            float baseline = getBaseline(pRend, 0, rowH);
            c.drawText(strWrapper, x, baseline, pRend);
            x += pRend.measureText(strWrapper);
            first = false;
        }
        if ((showMask & SHOW_GPU) != 0) {
            if (!first) x += drawSep(c, x, 0);
            float baseline = getBaseline(compact ? pGpu : pVal, 0, rowH);
            if (!compact) { c.drawText("GPU ", x, baseline, pGpu); x += wLabelGpu; }
            float vw = Math.max(pVal.measureText(strGpu), wVal100pct);
            c.drawText(strGpu, x, baseline, compact ? pGpu : pVal);
            x += vw;
            first = false;
        }
        if ((showMask & SHOW_CPU) != 0) {
            if (!first) x += drawSep(c, x, 0);
            float baseline = getBaseline(compact ? pCpu : pVal, 0, rowH);
            if (!compact) { c.drawText("CPU ", x, baseline, pCpu); x += wLabelCpu; }
            float vw = Math.max(pVal.measureText(strCpu), wVal100pct);
            c.drawText(strCpu, x, baseline, compact ? pCpu : pVal);
            x += vw;
            first = false;
        }
        if ((showMask & SHOW_RAM) != 0) {
            if (!first) x += drawSep(c, x, 0);
            Paint ramValuePaint = getRamValuePaint(compact);
            float baseline = getBaseline(ramValuePaint, 0, rowH);
            if (!compact) { c.drawText("RAM ", x, baseline, pRam); x += wLabelRam; }
            float vw = Math.max(pVal.measureText(strRam), wVal100pct);
            c.drawText(strRam, x, baseline, ramValuePaint);
            x += vw;
            if (ramAlertActive) {
                float helpWidth = pRamAlert.measureText(" ?");
                c.drawText(" ?", x, baseline, pRamAlert);
                ramHelpHitRect.set(x - PAD / 2f, 0, x + helpWidth + PAD / 2f, rowH);
                x += helpWidth;
            }
            first = false;
        }
        if ((showMask & SHOW_BATT) != 0) {
            if (!first) x += drawSep(c, x, 0);
            float baseline = getBaseline(compact ? pBat : pVal, 0, rowH);
            if (!compact) { c.drawText("PWR ", x, baseline, pBat); x += wLabelPwr; }
            float vw = Math.max((compact ? pBat : pVal).measureText(strPwr), wValWatt);
            c.drawText(strPwr, x, baseline, snapCharging ? pChg : (compact ? pBat : pVal));
            x += vw;
            if ((showMask & SHOW_BATT_PCT) != 0) {
                x += drawSep(c, x, 0);
                float pw = Math.max((compact ? pBat : pVal).measureText(strPct), wVal100pct);
                c.drawText(strPct.isEmpty() ? "N/A" : strPct, x, baseline, compact ? pBat : pVal);
                x += pw;
            }
            if (!strTmp.isEmpty() || (showMask & SHOW_COMPACT) == 0) {
                x += drawSep(c, x, 0);
                float tw = Math.max((compact ? pTmp : pVal).measureText(strTmp), wValTemp);
                if (!compact) { c.drawText("TMP ", x, baseline, pTmp); x += wLabelTmp; }
                c.drawText(strTmp, x, baseline, compact ? pTmp : pVal);
                x += tw;
            }
            first = false;
        }
        if ((showMask & SHOW_CPU_TEMP) != 0) {
            if (!first) x += drawSep(c, x, 0);
            float baseline = getBaseline(compact ? pTmp : pVal, 0, rowH);
            if (!strCTmp.isEmpty() || !compact) {
                if (!compact) { c.drawText("CTMP ", x, baseline, pTmp); x += wLabelCTmp; }
                String shown = strCTmp.isEmpty() ? "N/A" : strCTmp;
                float vw = Math.max((compact ? pTmp : pVal).measureText(shown), wValTemp);
                c.drawText(shown, x, baseline, compact ? pTmp : pVal);
                x += vw;
            }
            first = false;
        }
        if ((showMask & SHOW_GPU_TEMP) != 0) {
            if (!first) x += drawSep(c, x, 0);
            float baseline = getBaseline(compact ? pTmp : pVal, 0, rowH);
            if (!strGTmp.isEmpty() || !compact) {
                if (!compact) { c.drawText("GTMP ", x, baseline, pTmp); x += wLabelGTmp; }
                String shown = strGTmp.isEmpty() ? "N/A" : strGTmp;
                float vw = Math.max((compact ? pTmp : pVal).measureText(shown), wValTemp);
                c.drawText(shown, x, baseline, compact ? pTmp : pVal);
                x += vw;
            }
            first = false;
        }
        if ((showMask & SHOW_DEVICE) != 0) {
            if (!first) x += drawSep(c, x, 0);
            c.drawText(strDevice, x, getBaseline(pRend, 0, rowH), pRend);
            x += pRend.measureText(strDevice);
            first = false;
        }
        if ((showMask & SHOW_DRIVER) != 0) {
            if (!first) x += drawSep(c, x, 0);
            c.drawText(strDriver, x, getBaseline(pRend, 0, rowH), pRend);
            x += pRend.measureText(strDriver);
            first = false;
        }
        if ((showMask & SHOW_PHONE_GPU) != 0) {
            if (!first) x += drawSep(c, x, 0);
            float baseline = getBaseline(pRend, 0, rowH);
            c.drawText(strPhoneGpu, x, baseline, pRend);
            x += pRend.measureText(strPhoneGpu);
            first = false;
        }
        if ((showMask & SHOW_SOC) != 0) {
            if (!first) x += drawSep(c, x, 0);
            float baseline = getBaseline(pRend, 0, rowH);
            c.drawText(strSoc, x, baseline, pRend);
            x += pRend.measureText(strSoc);
            first = false;
        }
        if ((showMask & SHOW_FPS) != 0) {
            if (!first) x += drawSep(c, x, 0);
            float fb = getBaseline(pFps, 0, rowH);
            String label = apexActive ? "Apex " : "FPS ";
            float labelW = apexActive ? wLabelApex : wLabelFps;
            if (!compact) { c.drawText(label, x, fb, pFps); x += labelW; }
            float fw = Math.max(pFps.measureText(strFps), wValFps);
            c.drawText(strFps, x, fb, pFps);
            x += fw;
            if ((showMask & SHOW_GRAPH) != 0) {
                x += PAD;
                drawInlineGraph(c, x, PAD, GRAW, rowH - PAD * 2);
                x += GRAW;
            }
            first = false;
        }
        if ((showMask & SHOW_FG_LATENCY) != 0) {
            if (!first) x += drawSep(c, x, 0);
            float baseline = getBaseline(pRend, 0, rowH);
            c.drawText(strFgLatency, x, baseline, pRend);
            x += pRend.measureText(strFgLatency);
            first = false;
        }
        if ((showMask & SHOW_FG_STATUS) != 0) {
            if (!first) x += drawSep(c, x, 0);
            Paint statusPaint = apexFailure ? pTmp : pRend;
            float baseline = getBaseline(statusPaint, 0, rowH);
            c.drawText(strFgStatus, x, baseline, statusPaint);
            x += statusPaint.measureText(strFgStatus);
            first = false;
        }
        for (String row : telemetryRows) {
            if (!first) x += drawSep(c, x, 0);
            c.drawText(row, x, getBaseline(pVal, 0, rowH), pVal);
            x += pVal.measureText(row);
            first = false;
        }
    }

    private float getBaseline(Paint p, float y, float height) {
        Paint.FontMetrics fm = p.getFontMetrics();
        return y + (height - (fm.ascent + fm.descent)) / 2f;
    }

    private void drawVertical(Canvas c) {
        boolean compact = (showMask & SHOW_COMPACT) != 0;
        float lineH = TS + PAD * 2;
        float y     = 0;
        if ((showMask & SHOW_RENDERER) != 0) {
            c.drawText(strRend, PAD, getBaseline(pRend, y, lineH), pRend);
            y += lineH;
        }
        if ((showMask & SHOW_WRAPPER) != 0) {
            c.drawText(strWrapper, PAD, getBaseline(pRend, y, lineH), pRend);
            y += lineH;
        }
        if ((showMask & SHOW_GPU) != 0) {
            float bl = getBaseline(compact ? pGpu : pVal, y, lineH);
            if (!compact) c.drawText("GPU ", PAD, bl, pGpu);
            c.drawText(strGpu, PAD + (compact ? 0 : wLabelGpu), bl, compact ? pGpu : pVal);
            y += lineH;
        }
        if ((showMask & SHOW_CPU) != 0) {
            float bl = getBaseline(compact ? pCpu : pVal, y, lineH);
            if (!compact) c.drawText("CPU ", PAD, bl, pCpu);
            c.drawText(strCpu, PAD + (compact ? 0 : wLabelCpu), bl, compact ? pCpu : pVal);
            y += lineH;
        }
        if ((showMask & SHOW_RAM) != 0) {
            Paint ramValuePaint = getRamValuePaint(compact);
            float bl = getBaseline(ramValuePaint, y, lineH);
            if (!compact) c.drawText("RAM ", PAD, bl, pRam);
            float valueX = PAD + (compact ? 0 : wLabelRam);
            c.drawText(strRam, valueX, bl, ramValuePaint);
            if (ramAlertActive) {
                float helpX = valueX + ramValuePaint.measureText(strRam);
                float helpWidth = pRamAlert.measureText(" ?");
                c.drawText(" ?", helpX, bl, pRamAlert);
                ramHelpHitRect.set(helpX - PAD / 2f, y, helpX + helpWidth + PAD / 2f, y + lineH);
            }
            y += lineH;
        }
        if ((showMask & SHOW_BATT) != 0) {
            float bl = getBaseline(compact ? pBat : pVal, y, lineH);
            if (!compact) c.drawText("PWR ", PAD, bl, pBat);
            String bInfo = strPwr + ((showMask & SHOW_BATT_PCT) != 0 ? " ("+(strPct.isEmpty() ? "N/A" : strPct)+")" : "");
            c.drawText(bInfo, PAD + (compact ? 0 : wLabelPwr), bl, snapCharging ? pChg : (compact ? pBat : pVal));
            y += lineH;
            if (!strTmp.isEmpty() || (showMask & SHOW_COMPACT) == 0) {
                float tbl = getBaseline(compact ? pTmp : pVal, y, lineH);
                if (!compact) c.drawText("TMP ", PAD, tbl, pTmp);
                c.drawText(strTmp, PAD + (compact ? 0 : wLabelTmp), tbl, compact ? pTmp : pVal);
                y += lineH;
            }
        }
        if ((showMask & SHOW_CPU_TEMP) != 0) {
            if (!strCTmp.isEmpty() || !compact) {
                float bl = getBaseline(compact ? pTmp : pVal, y, lineH);
                if (!compact) c.drawText("CTMP ", PAD, bl, pTmp);
                String shown = strCTmp.isEmpty() && !compact ? "N/A" : strCTmp;
                c.drawText(shown, PAD + (compact ? 0 : wLabelCTmp), bl, compact ? pTmp : pVal);
                y += lineH;
            }
        }
        if ((showMask & SHOW_GPU_TEMP) != 0) {
            if (!strGTmp.isEmpty() || !compact) {
                float bl = getBaseline(compact ? pTmp : pVal, y, lineH);
                if (!compact) c.drawText("GTMP ", PAD, bl, pTmp);
                String shown = strGTmp.isEmpty() && !compact ? "N/A" : strGTmp;
                c.drawText(shown, PAD + (compact ? 0 : wLabelGTmp), bl, compact ? pTmp : pVal);
                y += lineH;
            }
        }
        if ((showMask & SHOW_DEVICE) != 0) {
            c.drawText(strDevice, PAD, getBaseline(pRend, y, lineH), pRend);
            y += lineH;
        }
        if ((showMask & SHOW_DRIVER) != 0) {
            c.drawText(strDriver, PAD, getBaseline(pRend, y, lineH), pRend);
            y += lineH;
        }
        if ((showMask & SHOW_PHONE_GPU) != 0) {
            c.drawText(strPhoneGpu, PAD, getBaseline(pRend, y, lineH), pRend);
            y += lineH;
        }
        if ((showMask & SHOW_SOC) != 0) {
            c.drawText(strSoc, PAD, getBaseline(pRend, y, lineH), pRend);
            y += lineH;
        }
        if ((showMask & SHOW_FPS) != 0) {
            float bl = getBaseline(pFps, y, lineH);
            String label = apexActive ? "Apex " : "FPS ";
            float labelW = apexActive ? wLabelApex : wLabelFps;
            if (!compact) c.drawText(label, PAD, bl, pFps);
            c.drawText(strFps, PAD + (compact ? 0 : labelW), bl, pFps);
            y += lineH;
        }
        if ((showMask & SHOW_FG_LATENCY) != 0) {
            c.drawText(strFgLatency, PAD, getBaseline(pRend, y, lineH), pRend);
            y += lineH;
        }
        if ((showMask & SHOW_FG_STATUS) != 0) {
            Paint statusPaint = apexFailure ? pTmp : pRend;
            c.drawText(strFgStatus, PAD, getBaseline(statusPaint, y, lineH), statusPaint);
            y += lineH;
        }
        for (String row : telemetryRows) {
            c.drawText(row, PAD, getBaseline(pVal, y, lineH), pVal);
            y += lineH;
        }
    }

    private float drawSep(Canvas c, float x, float baseline) {
        if ((showMask & SHOW_COMPACT) != 0) return PAD / 2f;
        if (c != null) {
            float rowH = TS + PAD * 2;
            float bl = baseline > 0 ? baseline : getBaseline(pSep, 0, rowH);
            c.drawText(" | ", x, bl, pSep);
        }
        return wSep;
    }

    private void drawInlineGraph(Canvas c, float x, float y, float w, float h) {
        c.drawRect(x, y, x + w, y + h, pGraphBg);
        int count = Math.min(gHead, GBUF);
        if (count < 2) return;
        if (cachedPath == null || lastGHead != gHead) {
            cachedPath = new Path();
            float bw = w / (GBUF - 1);
            boolean first = true;
            for (int i = 0; i < count; i++) {
                float v  = graph[(gHead - count + i) % GBUF];
                float px = x + i * bw;
                float py = y + h - (v / gMax) * h;
                if (first) { cachedPath.moveTo(px, py); first = false; }
                else        { cachedPath.lineTo(px, py); }
            }
            lastGHead = gHead;
        }
        c.drawPath(cachedPath, pGraph);
    }

    private float measureHorizontal() {
        boolean compact = (showMask & SHOW_COMPACT) != 0;
        float rowH = TS + PAD * 2;
        float radius = rowH / 2f;
        float w = 0;
        boolean first = true;
        if ((showMask & SHOW_RENDERER) != 0) {
            if (!first) w += drawSep(null, 0, 0);
            w += pRend.measureText(strRend);
            first = false;
        }
        if ((showMask & SHOW_DEVICE) != 0) {
            if (!first) w += drawSep(null, 0, 0);
            w += pRend.measureText(strDevice);
            first = false;
        }
        if ((showMask & SHOW_DRIVER) != 0) {
            if (!first) w += drawSep(null, 0, 0);
            w += pRend.measureText(strDriver);
            first = false;
        }
        if ((showMask & SHOW_PHONE_GPU) != 0) {
            if (!first) w += drawSep(null, 0, 0);
            w += pRend.measureText(strPhoneGpu);
            first = false;
        }
        if ((showMask & SHOW_WRAPPER) != 0) {
            if (!first) w += drawSep(null, 0, 0);
            w += pRend.measureText(strWrapper);
            first = false;
        }
        if ((showMask & SHOW_GPU) != 0) {
            if (!first) w += drawSep(null, 0, 0);
            w += (compact ? 0 : wLabelGpu) + Math.max(pVal.measureText(strGpu), wVal100pct);
            first = false;
        }
        if ((showMask & SHOW_CPU) != 0) {
            if (!first) w += drawSep(null, 0, 0);
            w += (compact ? 0 : wLabelCpu) + Math.max(pVal.measureText(strCpu), wVal100pct);
            first = false;
        }
        if ((showMask & SHOW_RAM) != 0) {
            if (!first) w += drawSep(null, 0, 0);
            w += (compact ? 0 : wLabelRam) + Math.max(pVal.measureText(strRam), wVal100pct);
            if (ramAlertActive) w += pRamAlert.measureText(" ?");
            first = false;
        }
        if ((showMask & SHOW_BATT) != 0) {
            if (!first) w += drawSep(null, 0, 0);
            w += (compact ? 0 : wLabelPwr) + Math.max((compact ? pBat : pVal).measureText(strPwr), wValWatt);
            if ((showMask & SHOW_BATT_PCT) != 0) {
                w += drawSep(null, 0, 0) + Math.max((compact ? pBat : pVal).measureText(strPct), wVal100pct);
            }
            if (!strTmp.isEmpty() || (showMask & SHOW_COMPACT) == 0) {
                w += drawSep(null, 0, 0) + (compact ? 0 : wLabelTmp) + Math.max((compact ? pTmp : pVal).measureText(strTmp), wValTemp);
            }
            first = false;
        }
        if ((showMask & SHOW_CPU_TEMP) != 0) {
            if (!first) w += drawSep(null, 0, 0);
            w += (compact ? 0 : wLabelCTmp) + Math.max((compact ? pTmp : pVal).measureText(strCTmp), wValTemp);
            first = false;
        }
        if ((showMask & SHOW_GPU_TEMP) != 0) {
            if (!first) w += drawSep(null, 0, 0);
            w += (compact ? 0 : wLabelGTmp) + Math.max((compact ? pTmp : pVal).measureText(strGTmp), wValTemp);
            first = false;
        }
        if ((showMask & SHOW_SOC) != 0) {
            if (!first) w += drawSep(null, 0, 0);
            w += pRend.measureText(strSoc);
            first = false;
        }
        if ((showMask & SHOW_FPS) != 0) {
            if (!first) w += drawSep(null, 0, 0);
            float labelW = apexActive ? wLabelApex : wLabelFps;
            float minValW = apexActive ? pFps.measureText("0000 (4x)") : wValFps;
            w += (compact ? 0 : labelW) + Math.max(pFps.measureText(strFps), minValW);
            if ((showMask & SHOW_GRAPH) != 0) w += PAD + GRAW;
            first = false;
        }
        if ((showMask & SHOW_FG_LATENCY) != 0) {
            if (!first) w += drawSep(null, 0, 0);
            w += pRend.measureText(strFgLatency);
            first = false;
        }
        if ((showMask & SHOW_FG_STATUS) != 0) {
            if (!first) w += drawSep(null, 0, 0);
            w += (apexFailure ? pTmp : pRend).measureText(strFgStatus);
            first = false;
        }
        for (String row : telemetryRows) {
            if (!first) w += drawSep(null, 0, 0);
            w += pVal.measureText(row);
            first = false;
        }
        return w + radius * 2;
    }

    private float measureVertical() {
        boolean compact = (showMask & SHOW_COMPACT) != 0;
        float w = PAD * 2;
        if ((showMask & SHOW_RENDERER) != 0) w = Math.max(w, PAD * 2 + pRend.measureText(strRend));
        if ((showMask & SHOW_DEVICE) != 0) w = Math.max(w, PAD * 2 + pRend.measureText(strDevice));
        if ((showMask & SHOW_DRIVER) != 0) w = Math.max(w, PAD * 2 + pRend.measureText(strDriver));
        if ((showMask & SHOW_PHONE_GPU) != 0) w = Math.max(w, PAD * 2 + pRend.measureText(strPhoneGpu));
        if ((showMask & SHOW_WRAPPER)  != 0) w = Math.max(w, PAD * 2 + pRend.measureText(strWrapper));
        if ((showMask & SHOW_GPU)      != 0) w = Math.max(w, PAD * 2 + (compact ? 0 : wLabelGpu) + Math.max(pVal.measureText(strGpu), wVal100pct));
        if ((showMask & SHOW_CPU)      != 0) w = Math.max(w, PAD * 2 + (compact ? 0 : wLabelCpu) + Math.max(pVal.measureText(strCpu), wVal100pct));
        if ((showMask & SHOW_RAM)      != 0) {
            float helpWidth = ramAlertActive ? pRamAlert.measureText(" ?") : 0;
            w = Math.max(w, PAD * 2 + (compact ? 0 : wLabelRam)
                    + Math.max(pVal.measureText(strRam), wVal100pct) + helpWidth);
        }
        if ((showMask & SHOW_BATT)     != 0) {
            float bw = Math.max((compact ? pBat : pVal).measureText(strPwr + ( (showMask & SHOW_BATT_PCT) != 0 ? " (100%)" : "" )), wValBInfo);
            w = Math.max(w, PAD * 2 + (compact ? 0 : wLabelPwr) + bw);
            if (!strTmp.isEmpty() || (showMask & SHOW_COMPACT) == 0) {
                w = Math.max(w, PAD * 2 + (compact ? 0 : wLabelTmp) + Math.max((compact ? pTmp : pVal).measureText(strTmp), wValTemp));
            }
        }
        if ((showMask & SHOW_CPU_TEMP) != 0) {
            w = Math.max(w, PAD * 2 + (compact ? 0 : wLabelCTmp) + Math.max((compact ? pTmp : pVal).measureText(strCTmp), wValTemp));
        }
        if ((showMask & SHOW_GPU_TEMP) != 0) {
            w = Math.max(w, PAD * 2 + (compact ? 0 : wLabelGTmp) + Math.max((compact ? pTmp : pVal).measureText(strGTmp), wValTemp));
        }
        if ((showMask & SHOW_SOC)       != 0) w = Math.max(w, PAD * 2 + pRend.measureText(strSoc));
        if ((showMask & SHOW_FPS)      != 0) {
            float labelW = apexActive ? wLabelApex : wLabelFps;
            float minValW = apexActive ? pFps.measureText("0000 (4x)") : wValFps;
            w = Math.max(w, PAD * 2 + (compact ? 0 : labelW) + Math.max(pFps.measureText(strFps), minValW));
        }
        if ((showMask & SHOW_FG_LATENCY) != 0)
            w = Math.max(w, PAD * 2 + pRend.measureText(strFgLatency));
        if ((showMask & SHOW_FG_STATUS) != 0)
            w = Math.max(w, PAD * 2 + (apexFailure ? pTmp : pRend).measureText(strFgStatus));
        for (String row : telemetryRows) w = Math.max(w, PAD * 2 + pVal.measureText(row));
        return w;
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        if (hudStyle == 1) {
            float width = TS * 24 + PAD * 2;
            float height = mangoRows().size() * mangoLineHeight() + PAD * 2
                    + mangoFooter().size() * (TS + PAD) + ((showMask & SHOW_GRAPH) != 0 ? TS * 6 : 0);
            setMeasuredDimension((int)Math.ceil(width), (int)Math.ceil(Math.max(mangoLineHeight(), height)));
            return;
        }
        float lineH = TS + PAD * 2;
        float w = vertical ? measureVertical() : measureHorizontal();
        float h = vertical ? (countVerticalRows() * lineH) : lineH;
        setMeasuredDimension((int) Math.ceil(w), (int) Math.ceil(h));
    }

    private float countVerticalRows() {
        float r = 0;
        if ((showMask & SHOW_RENDERER) != 0) r++;
        if ((showMask & SHOW_DEVICE) != 0) r++;
        if ((showMask & SHOW_DRIVER) != 0) r++;
        if ((showMask & SHOW_PHONE_GPU) != 0) r++;
        if ((showMask & SHOW_WRAPPER)  != 0) r++;
        if ((showMask & SHOW_GPU)      != 0) r++;
        if ((showMask & SHOW_CPU)      != 0) r++;
        if ((showMask & SHOW_RAM)      != 0) r++;
        if ((showMask & SHOW_BATT)     != 0) { r++; if (!strTmp.isEmpty()) r++; }
        if ((showMask & SHOW_CPU_TEMP) != 0) r++;
        if ((showMask & SHOW_GPU_TEMP) != 0) r++;
        if ((showMask & SHOW_SOC)      != 0) r++;
        if ((showMask & SHOW_FPS)      != 0) r++;
        if ((showMask & SHOW_FG_LATENCY) != 0) r++;
        if ((showMask & SHOW_FG_STATUS) != 0) r++;
        return Math.max(1, r + telemetryRows.size());
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN
                && ramAlertActive && ramHelpHitRect.contains(e.getX(), e.getY())) {
            ramHelpPressed = true;
            return true;
        }
        if (ramHelpPressed) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                boolean showWarning = ramHelpHitRect.contains(e.getX(), e.getY());
                ramHelpPressed = false;
                if (showWarning) showRamWarning();
            } else if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                ramHelpPressed = false;
            }
            return true;
        }
        if ((showMask & SHOW_LOCKED) != 0) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (e.getPointerCount() > 1) return true;
                touchX = e.getRawX(); touchY = e.getRawY();
                startX = getX();      startY = getY();
                dragging = false;
                touchDownMs = System.currentTimeMillis();
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = e.getRawX() - touchX, dy = e.getRawY() - touchY;
                if (!dragging && Math.hypot(dx, dy) > DRAG_THRESH) dragging = true;
                if (dragging) { setX(startX + dx); setY(startY + dy); fitPosition(); }
                return true;
            case MotionEvent.ACTION_UP:
                if (e.getPointerCount() > 1) { dragging = false; return true; }
                if (dragging) { savePosition(); } 
                else if (hudStyle != 1 && touchDownMs > 0 && System.currentTimeMillis() - touchDownMs < 300) {
                    vertical = !vertical;
                    prefs.edit().putBoolean(KEY_VERT, vertical).apply();
                    try { requestLayout(); invalidate(); } catch (Exception ignored) {}
                    uiHandler.postDelayed(this::ensureVisible, 250);
                }
                dragging = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_UP:
                dragging = false;
                touchDownMs = 0;
                return true;
        }
        return false;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (sessionVisible && userEnabled && rendererActive) {
            uiHandler.removeCallbacks(redrawRunnable);
            redrawScheduled = false;
            setVisibility(VISIBLE);
            scheduleRedraw();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        uiHandler.removeCallbacks(redrawRunnable);
        redrawScheduled = false;
    }

    private void ensureVisible() {
        if (sessionVisible && userEnabled && rendererActive && isAttachedToWindow() && getWindowVisibility() == VISIBLE) {
            if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
            scheduleRedraw();
        }
    }

    private void savePosition() {
        prefs.edit().putFloat(KEY_X, getX()).putFloat(KEY_Y, getY()).apply();
    }

    private void scheduleRedraw() {
        if (!sessionVisible || !userEnabled || !isAttachedToWindow() || getWindowVisibility() != VISIBLE) return;
        if (!redrawScheduled) {
            redrawScheduled = true;
            uiHandler.postDelayed(redrawRunnable, hudStyle == 1 ? 250 : 1000);
        }
    }

    @Override
    protected void onVisibilityChanged(View v, int vis) {
        super.onVisibilityChanged(v, vis);
        if (vis == VISIBLE) scheduleRedraw();
        else {
            uiHandler.removeCallbacks(redrawRunnable);
            redrawScheduled = false;
            if (sessionVisible && userEnabled) uiHandler.postDelayed(this::ensureVisible, 300);
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE && sessionVisible && userEnabled) {
            uiHandler.removeCallbacks(redrawRunnable);
            redrawScheduled = false;
            uiHandler.postDelayed(this::ensureVisible, 150);
        } else {
            uiHandler.removeCallbacks(redrawRunnable); redrawScheduled = false;
        }
    }

    private void loadPrefs() {
        showMask = prefs.getInt(KEY_SHOW, SHOW_DEFAULT);
        if (!prefs.getBoolean(KEY_DEFAULTS_096, false)) {
            if (showMask == OLD_DEFAULT) showMask = SHOW_DEFAULT;
            prefs.edit().putInt(KEY_SHOW, showMask).putBoolean(KEY_DEFAULTS_096, true).apply();
        }
        socFormat = prefs.getInt(KEY_SOC_FORMAT, HardwareLabel.FULL);
        gpuFormat = prefs.getInt(KEY_GPU_FORMAT, HardwareLabel.FULL);
        deviceFamily = prefs.getBoolean(KEY_DEVICE_FAMILY, false);
        ramFormat = Math.max(0, Math.min(3, prefs.getInt("ram_format", 0)));
        coreFrequency = prefs.getBoolean("core_frequency", true);
        coreUsage = prefs.getBoolean("core_usage", true);
        coreName = prefs.getBoolean("core_name", false);
        gpuFrequency = prefs.getBoolean("gpu_frequency", false);
        batteryCharge = prefs.getBoolean("battery_charge", false);
        selectedCores = new java.util.HashSet<>(prefs.getStringSet("selected_cores", java.util.Collections.emptySet()));
        updateHardwareLabels();
        // 0.9.5 makes the FPS field part of the visible default for both new
        // and upgraded installs. Run this once so an older saved HUD mask does
        // not leave the checkbox off after updating; later user changes remain
        // untouched.
        if (!prefs.getBoolean(KEY_FPS_DEFAULT_MIGRATED, false)) {
            showMask |= SHOW_FPS;
            prefs.edit()
                    .putInt(KEY_SHOW, showMask)
                    .putBoolean(KEY_FPS_DEFAULT_MIGRATED, true)
                    .apply();
        }
        hudAlpha = prefs.getInt(KEY_ALPHA, 55) / 100f;
        setAlpha(hudAlpha);
        vertical = prefs.getBoolean(KEY_VERT, false);
        float scale = prefs.getFloat(KEY_SCALE, 1f);
        setScaleX(scale); setScaleY(scale);

        if (prefs.contains(KEY_X)) {
            setX(prefs.getFloat(KEY_X, 16f));
            setY(prefs.getFloat(KEY_Y, 16f));
        } else {
            post(() -> setPositionPreset(1));
        }

        userEnabled = prefs.getBoolean(KEY_VIS, false);
        setVisibility(userEnabled ? VISIBLE : GONE);
        ramWarningEnabled = prefs.getBoolean(KEY_RAM_WARNING, true);
    }

    public boolean isRamWarningEnabled() {
        return ramWarningEnabled;
    }

    public void setRamWarningEnabled(boolean enabled) {
        ramWarningEnabled = enabled;
        prefs.edit().putBoolean(KEY_RAM_WARNING, enabled).apply();
        if (!enabled && ramAlertActive) {
            ramAlertActive = false;
            layoutDirty = true;
            requestLayout();
        }
    }

    private boolean rendererActive = false;

    public boolean isUserEnabled() { return userEnabled; }
    public boolean hasSavedVisibility() { return prefs.contains(KEY_VIS); }

    public void enableByUser() {
        userEnabled = true;
        rendererActive = true;
        prefs.edit().putBoolean(KEY_VIS, true).apply();
        resetFrameBoundary();
        if (dataSource != null) dataSource.start();
        uiHandler.removeCallbacks(redrawRunnable);
        redrawScheduled = false;
        setVisibility(sessionVisible ? VISIBLE : GONE);
        scheduleRedraw();
        requestLayout();
    }

    public void disableByUser() {
        userEnabled = false;
        prefs.edit().putBoolean(KEY_VIS, false).apply();
        uiHandler.removeCallbacks(redrawRunnable);
        redrawScheduled = false;
        setVisibility(GONE);
    }

    public void onRendererDetected(String name) {
        uiHandler.post(() -> {
            rendererActive = true;
            boolean changed = false;
            if (name != null && !name.isEmpty() && !name.equals(rendererLabel)) {
                rendererLabel = name;
                strRend = (isNative ? "+" : "") + rendererLabel;
                layoutDirty = true;
                changed = true;
            }
            if (sessionVisible && userEnabled) {
                if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
                scheduleRedraw();
            }
            if (changed) requestLayout();
        });
    }

    public void onRendererGone() {
        uiHandler.post(() -> {
            rendererActive = false;
            if (!userEnabled) {
                uiHandler.removeCallbacks(redrawRunnable);
                redrawScheduled = false;
                setVisibility(GONE);
            }
            // If userEnabled, we keep redrawing to show last stats/0 FPS during swaps
        });
    }

    public void setFrameGenerationStats(boolean active, float realFps, float outputFps, float generatedFps,
            float multiplier, float latencyMs, String backend, String state, String failure,
            boolean lowLatencyMode) {
        uiHandler.post(() -> {
            apexRealFps = Math.max(0, realFps);
            apexOutputFps = Math.max(0, outputFps);
            apexGeneratedFps = Math.max(0, generatedFps);
            apexSampleTimeNanos = System.nanoTime();
            apexMultiplier = Math.max(1.0f, Math.min(4.0f, multiplier));
            String safeBackend = backend == null || backend.trim().isEmpty()
                    ? "Unknown" : backend.trim();
            String safeState = state == null || state.trim().isEmpty()
                    ? (active ? "Starting" : "Off") : state.trim();
            String safeFailure = failure == null ? "" : failure.trim();
            apexFailure = !safeFailure.isEmpty()
                    && ("Error".equalsIgnoreCase(safeState)
                    || "Fallback".equalsIgnoreCase(safeState));
            strFgLatency = active && latencyMs > 0
                    ? String.format(Locale.US, "FG LAT ~%.1f ms%s", latencyMs,
                            lowLatencyMode ? " (low)" : "")
                    : "FG LAT N/A";
            if (!safeFailure.isEmpty()) {
                if (safeFailure.length() > 72) safeFailure = safeFailure.substring(0, 72);
                strFgStatus = "FG " + safeState + ": " + safeFailure;
            }
            else if (active && "Active".equalsIgnoreCase(safeState)) {
                strFgStatus = "FG " + safeBackend + " · SRC " + Math.round(apexRealFps)
                        + " · GEN "
                        + Math.round(apexGeneratedFps) + " FPS"
                        + (apexGeneratedFps < 0.5f ? " (no output)" : "");
            }
            else strFgStatus = "FG " + safeBackend + " " + safeState;
            if (this.apexActive != active) {
                this.apexActive = active;
                layoutDirty = true;
                requestLayout();
            }
            else {
                layoutDirty = true;
                requestLayout();
            }
            invalidate();
        });
    }

    public void setRenderer(String name) {
        if (name != null && !name.isEmpty()) rendererLabel = name;
    }
    public void setWrapperName(String name) {
        if (name != null && !name.isEmpty()) {
            String identifier = StringUtils.parseIdentifier(name);
            String displayName = null;
            for (String entry : getResources().getStringArray(R.array.graphics_driver_entries)) {
                if (identifier.equals(StringUtils.parseIdentifier(entry))) {
                    displayName = entry;
                    break;
                }
            }
            if (displayName == null) displayName = name;
            if (displayName.regionMatches(true, 0, "Wrapper-", 0, 8))
                displayName = displayName.substring(8);
            else if ("wrapper".equalsIgnoreCase(displayName))
                displayName = "Wrapper";
            this.strWrapper = displayName;
            layoutDirty = true;
            invalidate();
        }
    }
    public void setGpuName(String name) {
        // Guest-reported names can be spoofed by the container. The dedicated
        // phone-GPU field is populated from Android's physical GLES renderer.
    }

    public void setPhoneGpuName(String name) {
        if (name == null || name.trim().isEmpty()) return;
        ExynosDevices.Profile device = ExynosDevices.find(Build.MODEL);
        rawPhoneGpu = device == null ? name : device.gpu;
        updateHardwareLabels();
    }

    public void setDriverName(String name) {
        strDriver = name == null || name.trim().isEmpty() ? "System" : name;
        layoutDirty = true;
        requestLayout();
        invalidate();
    }

    private void updateHardwareLabels() {
        strSoc = HardwareLabel.format(rawSoc, socFormat);
        strPhoneGpu = HardwareLabel.format(rawPhoneGpu, gpuFormat);
        ExynosDevices.Profile device = ExynosDevices.find(Build.MODEL);
        strDevice = device == null ? Build.MODEL : deviceFamily ? HardwareLabel.family(device.device) : device.shortLabel();
        if (strDevice == null) strDevice = "";
        layoutDirty = true;
        requestLayout();
        invalidate();
    }

    public int getSocFormat() { return socFormat; }
    public int getGpuFormat() { return gpuFormat; }
    public boolean isDeviceFamily() { return deviceFamily; }

    public int getRamFormat() { return ramFormat; }
    public boolean getTelemetryOption(String key) { return prefs.getBoolean(key, key.equals("core_frequency") || key.equals("core_usage") || key.equals("vram")); }
    public void setRamFormat(int format) {
        ramFormat = Math.max(0, Math.min(3, format));
        prefs.edit().putInt("ram_format", ramFormat).apply();
        snapshot(); invalidate(); requestLayout();
    }
    public void setTelemetryOption(String key, boolean enabled) {
        switch (key) {
            case "core_frequency": coreFrequency = enabled; break;
            case "core_usage": coreUsage = enabled; break;
            case "core_name": coreName = enabled; break;
            case "gpu_frequency": gpuFrequency = enabled; break;
            case "battery_charge": batteryCharge = enabled; break;
            case "vram": break;
            default: return;
        }
        prefs.edit().putBoolean(key, enabled).apply();
        snapshot(); requestLayout(); invalidate();
    }
    public java.util.Set<String> getSelectedCores() { return new java.util.HashSet<>(selectedCores); }
    public void setSelectedCores(java.util.Set<String> ids) {
        selectedCores = new java.util.HashSet<>(ids);
        prefs.edit().putStringSet("selected_cores", selectedCores).apply();
        snapshot(); requestLayout(); invalidate();
    }

    public void setHardwareLabelFormat(boolean gpu, int format) {
        int selected = Math.max(0, Math.min(2, format));
        if (gpu) gpuFormat = selected; else socFormat = selected;
        prefs.edit().putInt(gpu ? KEY_GPU_FORMAT : KEY_SOC_FORMAT, selected).apply();
        updateHardwareLabels();
    }

    public void setDeviceFamily(boolean family) {
        deviceFamily = family;
        prefs.edit().putBoolean(KEY_DEVICE_FAMILY, family).apply();
        updateHardwareLabels();
    }

    public boolean isElementEnabled(int index) { return (showMask & idxToMask(index)) != 0; }

    public void setVertical(boolean v) {
        vertical = v;
        prefs.edit().putBoolean(KEY_VERT, vertical).apply();
        layoutDirty = true;
        try { requestLayout(); invalidate(); } catch (Exception ignored) {}
    }

    public boolean isVertical() { return vertical; }

    public float getHudScale() { return getScaleX(); }
    public float getHudAlpha() { return hudAlpha; }

    public void toggleElement(int idx, boolean on) {
        int bit = idxToMask(idx);
        if (bit == 0) return;
        if (on) showMask |= bit; else showMask &= ~bit;
        prefs.edit().putInt(KEY_SHOW, showMask).apply();
        layoutDirty = true;
        try { requestLayout(); invalidate(); } catch (Exception ignored) {}
    }

    public void syncCheckboxes(android.widget.CheckBox cbFps, android.widget.CheckBox cbGpu,
            android.widget.CheckBox cbCpu, android.widget.CheckBox cbBattTemp,
            android.widget.CheckBox cbGraph, android.widget.CheckBox cbRenderer,
            android.widget.CheckBox cbRam, android.widget.CheckBox cbBattPct,
            android.widget.CheckBox cbMono, android.widget.CheckBox cbBorder,
            android.widget.CheckBox cbCompact, android.widget.CheckBox cbWrapper,
            android.widget.CheckBox cbLocked, android.widget.CheckBox cbCpuTemp,
            android.widget.CheckBox cbSoc, android.widget.CheckBox cbGpuTemp,
            android.widget.CheckBox cbPhoneGpu, android.widget.CheckBox cbFgLatency,
            android.widget.CheckBox cbFgStatus) {
        if (cbFps      != null) cbFps.setChecked((showMask & SHOW_FPS)       != 0);
        if (cbGpu      != null) cbGpu.setChecked((showMask & SHOW_GPU)       != 0);
        if (cbCpu      != null) cbCpu.setChecked((showMask & SHOW_CPU)       != 0);
        if (cbBattTemp != null) cbBattTemp.setChecked((showMask & SHOW_BATT) != 0);
        if (cbGraph    != null) cbGraph.setChecked((showMask & SHOW_GRAPH)   != 0);
        if (cbRenderer != null) cbRenderer.setChecked((showMask & SHOW_RENDERER) != 0);
        if (cbRam      != null) cbRam.setChecked((showMask & SHOW_RAM)      != 0);
        if (cbBattPct  != null) cbBattPct.setChecked((showMask & SHOW_BATT_PCT) != 0);
        if (cbMono     != null) cbMono.setChecked((showMask & SHOW_MONO)     != 0);
        if (cbBorder   != null) cbBorder.setChecked((showMask & SHOW_BORDER)   != 0);
        if (cbCompact  != null) cbCompact.setChecked((showMask & SHOW_COMPACT) != 0);
        if (cbWrapper  != null) cbWrapper.setChecked((showMask & SHOW_WRAPPER) != 0);
        if (cbLocked   != null) cbLocked.setChecked((showMask & SHOW_LOCKED)  != 0);
        if (cbCpuTemp  != null) cbCpuTemp.setChecked((showMask & SHOW_CPU_TEMP) != 0);
        if (cbSoc      != null) cbSoc.setChecked((showMask & SHOW_SOC)         != 0);
        if (cbGpuTemp  != null) cbGpuTemp.setChecked((showMask & SHOW_GPU_TEMP) != 0);
        if (cbPhoneGpu != null) cbPhoneGpu.setChecked((showMask & SHOW_PHONE_GPU) != 0);
        if (cbFgLatency != null) cbFgLatency.setChecked((showMask & SHOW_FG_LATENCY) != 0);
        if (cbFgStatus != null) cbFgStatus.setChecked((showMask & SHOW_FG_STATUS) != 0);
    }

    public void setHudScale(float scale) {
        setScaleX(scale); setScaleY(scale);
        prefs.edit().putFloat(KEY_SCALE, scale).apply();
        post(this::fitPosition);
    }

    private void fitPosition() {
        if (!(getParent() instanceof View)) return;
        View parent = (View)getParent();
        if (parent.getWidth() <= 0 || parent.getHeight() <= 0) return;
        setX(Math.max(0, Math.min(getX(), parent.getWidth() - getWidth() * getScaleX())));
        setY(Math.max(0, Math.min(getY(), parent.getHeight() - getHeight() * getScaleY())));
    }
    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        post(this::fitPosition);
    }

    public void setHudAlpha(float a) {
        hudAlpha = Math.max(0f, Math.min(1f, a));
        setAlpha(hudAlpha);
        prefs.edit().putInt(KEY_ALPHA, (int)(hudAlpha * 100)).apply();
        invalidate();
    }

    public void reset() {
        rendererLabel = "OpenGL"; resetFrameBoundary(); snapFps = 0; gHead = 0;
    }

    public void forceReset() {
        uiHandler.post(() -> {
            uiHandler.removeCallbacks(redrawRunnable);
            redrawScheduled = false;
            frameAccum.set(0);
            snapFps = 0; gHead = 0; resetFrameBoundary(); frameTimes.clear();
            cachedPath = null; lastGHead = -1;
            dragging = false; touchDownMs = 0;
            rendererActive = true;
            userEnabled = true;
            
            showMask = hudStyle == 1 ? SHOW_MANGO_ONE_DEFAULT : mangoStyle ? SHOW_MANGO_DEFAULT : SHOW_DEFAULT;
            socFormat = gpuFormat = HardwareLabel.FULL;
            deviceFamily = false;
            ramFormat = hudStyle == 1 ? 2 : 0;
            coreFrequency = coreUsage = true;
            coreName = gpuFrequency = batteryCharge = false;
            selectedCores.clear();
            telemetryRows = java.util.Collections.emptyList();
            updateHardwareLabels();
            vertical = false;
            layoutDirty = true;
            
            setScaleX(1.0f); setScaleY(1.0f);
            hudAlpha = hudStyle == 1 ? .85f : .55f;
            setAlpha(hudAlpha);

            SharedPreferences.Editor ed = prefs.edit();
            ed.putBoolean(KEY_VIS, true);
            ed.putInt(KEY_SHOW, showMask);
            ed.putInt(KEY_SOC_FORMAT, socFormat);
            ed.putInt(KEY_GPU_FORMAT, gpuFormat);
            ed.putBoolean(KEY_DEVICE_FAMILY, deviceFamily);
            ed.putInt("ram_format", ramFormat);
            ed.putBoolean("core_frequency", true).putBoolean("core_usage", true)
                    .putBoolean("core_name", false).putBoolean("gpu_frequency", false)
                    .putBoolean("battery_charge", false).putBoolean("vram", true).putStringSet("selected_cores", new java.util.HashSet<>());
            ed.putBoolean(KEY_VERT, vertical);
            ed.putFloat(KEY_SCALE, 1.0f);
            ed.putInt(KEY_ALPHA, Math.round(hudAlpha * 100));
            ed.remove(KEY_X);
            ed.remove(KEY_Y);
            ed.apply();

            post(() -> setPositionPreset(1));

            if (dataSource != null) dataSource.start();
            setVisibility(sessionVisible ? VISIBLE : GONE);
            scheduleRedraw();
            requestLayout();
        });
    }

    public int idxToMask(int idx) {
        switch (idx) {
            case 0: return SHOW_FPS;
            case 2: return SHOW_GPU;
            case 3: return SHOW_CPU;
            case 4: return SHOW_BATT;
            case 5: return SHOW_GRAPH;
            case 6: return SHOW_RENDERER;
            case 7: return SHOW_RAM;
            case 8: return SHOW_BATT_PCT;
            case 9: return SHOW_MONO;
            case 10: return SHOW_BORDER;
            case 11: return SHOW_COMPACT;
            case 12: return SHOW_WRAPPER;
            case 13: return SHOW_LOCKED;
            case 14: return SHOW_CPU_TEMP;
            case 15: return SHOW_SOC;
            case 16: return SHOW_GPU_TEMP;
            case 17: return SHOW_PHONE_GPU;
            case 18: return SHOW_FG_LATENCY;
            case 19: return SHOW_FG_STATUS;
            case 20: return SHOW_DEVICE;
            case 21: return SHOW_DRIVER;
            default: return 0;
        }
    }

    public void setPositionPreset(int preset) {
        Runnable r = () -> {
            if (getParent() == null) return;
            float parentW = ((View)getParent()).getWidth();
            float parentH = ((View)getParent()).getHeight();
            if (parentW <= 0 || parentH <= 0) {
                postDelayed(() -> setPositionPreset(preset), 100);
                return;
            }

            float w = getMeasuredWidth() * getScaleX();
            float h = getMeasuredHeight() * getScaleY();
            if (w <= 0 || h <= 0) {
                measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
                w = getMeasuredWidth() * getScaleX();
                h = getMeasuredHeight() * getScaleY();
            }

            float x = 16f, y = 16f;
            switch (preset) {
                case 1: x = (parentW - w) / 2f; break;
                case 2: x = parentW - w - 16f; break;
                case 3: y = (parentH - h) / 2f; break;
                case 4: x = (parentW - w) / 2f; y = (parentH - h) / 2f; break;
                case 5: x = parentW - w - 16f; y = (parentH - h) / 2f; break;
                case 6: y = parentH - h - 16f; break;
                case 7: x = (parentW - w) / 2f; y = parentH - h - 16f; break;
                case 8: x = parentW - w - 16f; y = parentH - h - 16f; break;
            }
            setX(x); setY(y);
            savePosition();
        };

        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else post(r);
    }
}

