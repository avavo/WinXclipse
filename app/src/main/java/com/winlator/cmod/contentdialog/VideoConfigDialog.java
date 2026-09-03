package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.os.Build;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import com.winlator.cmod.R;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.widget.ThemedSpinnerAdapter;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.TreeSet;

/** Video options shared by the Wine renderer and the Android compositor. */
public class VideoConfigDialog extends ContentDialog {
    /** Display labels of the FSR mode spinner (index maps to normalizeFsrValue tokens). */
    private static final String[] FSR_MODE_LABELS = {
            "Fidelity (1.3x)", "Quality (1.5x)", "Balanced (1.7x)",
            "Performance (2.0x)", "Ultra Performance (2.5x)"
    };
    private static final String[] FSR_MODE_VALUES = {
            "fidelity", "quality", "balanced", "performance", "ultraperformance"
    };

    public interface Config {
        String getGpuName();
        String getPresentMode();
        /** 0 = bilinear, 1 = nearest, 2 = FSR, 3 = none; -1 = not set. */
        int getTextureFilterMode();
        boolean isSwapRedBlue();
        /** Legacy FSR mode key, only used to migrate old configs. */
        String getFsrMode();
        /** "0" or "1": whether the FSR EASU upscaler runs (only when FSR is selected). */
        String getFsrUpscale();
        /** "fidelity", "quality", "balanced", "performance" or "ultraperformance". */
        String getFsrQuality();
        /** "100", "50" or "off". */
        String getVsyncMode();
        boolean isUnlimitedImages();
        /** "auto" or a refresh rate in Hz ("60", "90", "120", "144"). */
        String getRefreshRate();
        String getSharpnessEffect();
        String getSharpnessLevel();
        String getSharpnessDenoise();
        boolean isFrameGenerationCompatible();
        String getFrameGenerationEnabled();
        String getFrameGenerationProfile();
        String getFrameGenerationMultiplier();
        String getFrameGenerationTargetFPS();
        String getFrameGenerationBackend();
        String getFrameGenerationLowLatency();
        void apply(String gpuName, String presentMode, int textureFilterMode,
                   boolean swapRedBlue, String fsrUpscale,
                   String fsrQuality, String vsyncMode, boolean unlimitedImages,
                   String refreshRate, String sharpnessEffect,
                   String sharpnessLevel, String sharpnessDenoise,
                   boolean frameGenerationEnabled, String frameGenerationProfile,
                   String frameGenerationMultiplier, String frameGenerationTargetFPS,
                   String frameGenerationBackend, boolean frameGenerationLowLatency);
    }

    public VideoConfigDialog(Context context, Config config) {
        super(context, R.layout.video_config_dialog);
        setIcon(R.drawable.icon_screen_effect);
        setTitle(R.string.video_configuration);

        Spinner gpuName = findViewById(R.id.SVideoGPUName);
        Spinner presentMode = findViewById(R.id.SVideoPresentMode);
        Spinner refreshRate = findViewById(R.id.SVideoRefreshRate);
        Spinner textureFilter = findViewById(R.id.SVideoTextureFilter);
        CheckBox swapRedBlue = findViewById(R.id.CBVideoSwapRedBlue);
        Spinner fsrUpscale = findViewById(R.id.SVideoFsrUpscale);
        Spinner fsrQuality = findViewById(R.id.SVideoFsrMode);
        View fsrUpscaleRow = findViewById(R.id.LLVideoFsrUpscale);
        View fsrQualityRow = findViewById(R.id.LLVideoFsrMode);
        Spinner vsyncLimit = findViewById(R.id.SVideoVsyncLimit);
        CheckBox unlimitedImages = findViewById(R.id.CBVideoUnlimitedImages);
        findViewById(R.id.BTVideoPresentModeHelp).setOnClickListener(v ->
                AppUtils.showHelpBox(context, v, R.string.video_help_present_modes));
        findViewById(R.id.BTVideoVsyncOffHelp).setOnClickListener(v ->
                AppUtils.showHelpBox(context, v, R.string.video_help_vsync_off));
        findViewById(R.id.BTVideoUnlimitedImagesHelp).setOnClickListener(v ->
                AppUtils.showHelpBox(context, v, R.string.video_help_unlimited_images));
        findViewById(R.id.BTVideoFsrUpscaleHelp).setOnClickListener(v ->
                AppUtils.showHelpBox(context, v, R.string.video_help_fsr_upscale));
        findViewById(R.id.BTVideoVkBasaltHelp).setOnClickListener(v ->
                AppUtils.showHelpBox(context, v, R.string.video_help_vkbasalt));

        gpuName.setAdapter(new ThemedSpinnerAdapter<>(context, loadGpuNames(context)));
        presentMode.setAdapter(new ThemedSpinnerAdapter<>(context,
                Arrays.asList(context.getResources().getStringArray(R.array.present_mode_entries))));
        ArrayList<String> refreshLabels = new ArrayList<>();
        ArrayList<String> refreshValues = new ArrayList<>();
        refreshLabels.add("Auto");
        refreshValues.add("auto");
        TreeSet<Integer> supportedRates = new TreeSet<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                Display display = wm != null ? wm.getDefaultDisplay() : null;
                if (display != null) {
                    for (Display.Mode mode : display.getSupportedModes())
                        supportedRates.add(Math.max(1, Math.round(mode.getRefreshRate())));
                }
            }
            catch (RuntimeException ignored) {
            }
        }
        if (supportedRates.isEmpty()) supportedRates.addAll(Arrays.asList(60, 90, 120, 144));
        for (int hz : supportedRates) {
            refreshLabels.add(hz + " Hz");
            refreshValues.add(String.valueOf(hz));
        }
        refreshRate.setAdapter(new ThemedSpinnerAdapter<>(context, refreshLabels));
        textureFilter.setAdapter(new ThemedSpinnerAdapter<>(context,
                Arrays.asList(context.getString(R.string.bilinear),
                        context.getString(R.string.nearest_neighbor),
                        "FSR", "None")));
        fsrUpscale.setAdapter(new ThemedSpinnerAdapter<>(context, Arrays.asList("Off", "On")));
        fsrQuality.setAdapter(new ThemedSpinnerAdapter<>(context, Arrays.asList(FSR_MODE_LABELS)));
        vsyncLimit.setAdapter(new ThemedSpinnerAdapter<>(context,
                Arrays.asList(context.getResources().getStringArray(R.array.video_vsync_limit_entries))));

        // Normalize values persisted by any build (display strings from old
        // releases, legacy mode tokens, current on/off tokens). A stored FSR
        // mode other than "off" implies the FSR texture filter selection.
        int rawFilterMode = config.getTextureFilterMode();
        boolean filterExplicit = rawFilterMode >= 0;
        int filterMode = Math.max(0, Math.min(filterExplicit ? rawFilterMode : 0, 3));
        // FSR is the default texture filter, but resolution upscaling is
        // opt-in.  Fresh containers keep native resolution by default.
        String upscale = config.getFsrUpscale() == null ? "0" : config.getFsrUpscale();
        String quality = GraphicsDriverConfigDialog.normalizeFsrValue(config.getFsrQuality());
        String legacyFsr = GraphicsDriverConfigDialog.normalizeFsrValue(config.getFsrMode());
        if (!legacyFsr.equals("off") && !filterExplicit) {
            // Any legacy FSR setting (including sharpen-only "on") maps to the
            // FSR texture filter entry; modes also imply upscale. Skipped when
            // an explicit texture-filter selection was already persisted, so a
            // newer Bilinear/None choice is not silently reverted to FSR.
            upscale = legacyFsr.equals("on") ? "0" : "1";
            quality = legacyFsr.equals("on") ? quality : legacyFsr;
            filterMode = 2;
        }
        if (indexOfFsrMode(quality) == -1) quality = "balanced";

        if (!AppUtils.setSpinnerSelectionFromValue(gpuName, config.getGpuName())) {
            AppUtils.setSpinnerSelectionFromValue(gpuName, "Device");
        }
        AppUtils.setSpinnerSelectionFromValue(presentMode, config.getPresentMode());
        int refreshIndex = refreshValues.indexOf(config.getRefreshRate());
        refreshRate.setSelection(Math.max(0, refreshIndex));
        textureFilter.setSelection(filterMode);
        swapRedBlue.setChecked(config.isSwapRedBlue());
        AppUtils.setSpinnerSelectionFromValue(fsrUpscale, upscale.equals("1") ? "On" : "Off");
        fsrQuality.setSelection(Math.max(0, indexOfFsrMode(quality)));

        // Upscale row only exists while FSR is selected; mode row only while upscale is on.
        Runnable updateVisibility = () -> {
            boolean fsrSelected = textureFilter.getSelectedItemPosition() == 2;
            boolean upscaleOn = fsrSelected && "On".equals(selectedValue(fsrUpscale));
            fsrUpscaleRow.setVisibility(fsrSelected ? View.VISIBLE : View.GONE);
            fsrQualityRow.setVisibility(upscaleOn ? View.VISIBLE : View.GONE);
        };
        updateVisibility.run();
        AdapterView.OnItemSelectedListener visibilityListener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateVisibility.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
        textureFilter.setOnItemSelectedListener(visibilityListener);
        fsrUpscale.setOnItemSelectedListener(visibilityListener);

        String currentVsync = config.getVsyncMode();
        vsyncLimit.setSelection("off".equals(currentVsync) ? 2 : "50".equals(currentVsync) ? 1 : 0);
        unlimitedImages.setChecked(config.isUnlimitedImages());

        CheckBox frameGeneration = findViewById(R.id.CBVideoFrameGeneration);
        View frameGenerationSettings = findViewById(R.id.LLVideoFrameGenerationSettings);
        Spinner frameGenerationProfile = findViewById(R.id.SVideoFrameGenerationProfile);
        CheckBox frameGenerationLowLatency = findViewById(
                R.id.CBVideoFrameGenerationLowLatency);
        Spinner frameGenerationMultiplier = findViewById(R.id.SVideoFrameGenerationMultiplier);
        View frameGenerationAutoFps = findViewById(R.id.LLVideoFrameGenerationAutoFPS);
        EditText frameGenerationTargetFps = findViewById(R.id.ETVideoFrameGenerationAutoFPS);
        frameGenerationProfile.setAdapter(new ThemedSpinnerAdapter<>(context, Arrays.asList(
                context.getResources().getStringArray(R.array.frame_generation_profile_entries))));
        frameGenerationMultiplier.setAdapter(new ThemedSpinnerAdapter<>(context, Arrays.asList(
                context.getResources().getStringArray(R.array.frame_generation_multiplier_entries))));
        frameGenerationProfile.setSelection(frameGenerationProfileIndex(
                config.getFrameGenerationProfile()));
        frameGenerationLowLatency.setChecked("1".equals(
                config.getFrameGenerationLowLatency()));
        frameGenerationMultiplier.setSelection(frameGenerationMultiplierIndex(
                config.getFrameGenerationMultiplier()));
        frameGenerationTargetFps.setText(String.valueOf(frameGenerationTarget(
                config.getFrameGenerationTargetFPS())));
        boolean frameGenerationCompatible = config.isFrameGenerationCompatible();
        frameGeneration.setChecked(frameGenerationCompatible
                && "1".equals(config.getFrameGenerationEnabled()));
        frameGeneration.setEnabled(frameGenerationCompatible);
        frameGeneration.setAlpha(frameGenerationCompatible ? 1.0f : 0.55f);
        findViewById(R.id.BTVideoFrameGenerationHelp).setOnClickListener(v ->
                AppUtils.showHelpBox(context, v, frameGenerationCompatible
                        ? R.string.frame_generation_help : R.string.frame_generation_vulkan_only));
        findViewById(R.id.BTVideoFrameGenerationProfileHelp).setOnClickListener(v ->
                AppUtils.showHelpBox(context, v, R.string.frame_generation_profile_help));
        findViewById(R.id.BTVideoFrameGenerationLowLatencyHelp).setOnClickListener(v ->
                AppUtils.showHelpBox(context, v, R.string.frame_generation_low_latency_help));
        Runnable updateFrameGeneration = () -> {
            boolean enabled = frameGenerationCompatible && frameGeneration.isChecked();
            frameGenerationSettings.setVisibility(enabled ? View.VISIBLE : View.GONE);
            frameGenerationProfile.setEnabled(enabled);
            frameGenerationLowLatency.setEnabled(enabled);
            frameGenerationMultiplier.setEnabled(enabled);
            frameGenerationTargetFps.setEnabled(enabled);
            frameGenerationAutoFps.setVisibility(enabled
                    && frameGenerationMultiplier.getSelectedItemPosition() == 0
                    ? View.VISIBLE : View.GONE);
        };
        frameGeneration.setOnCheckedChangeListener((button, checked) ->
                updateFrameGeneration.run());
        frameGenerationMultiplier.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override public void onItemSelected(AdapterView<?> parent, View view,
                            int position, long id) { updateFrameGeneration.run(); }
                    @Override public void onNothingSelected(AdapterView<?> parent) {}
                });
        updateFrameGeneration.run();

        // vkBasalt (CAS/DLS) - now in Video tab for both Container and Shortcut
        Spinner vkBasaltEffect = findViewById(R.id.SVideoVkBasaltEffect);
        SeekBar sbSharpnessLevel = findViewById(R.id.SBVideoSharpnessLevel);
        SeekBar sbSharpnessDenoise = findViewById(R.id.SBVideoSharpnessDenoise);
        TextView tvSharpnessLevel = findViewById(R.id.TVVideoSharpnessLevel);
        TextView tvSharpnessDenoise = findViewById(R.id.TVVideoSharpnessDenoise);
        vkBasaltEffect.setAdapter(new ThemedSpinnerAdapter<>(context,
                Arrays.asList(context.getResources().getStringArray(R.array.vkbasalt_sharpness_entries))));
        String sharpEff = config.getSharpnessEffect();
        if (sharpEff == null || sharpEff.isEmpty()) sharpEff = "None";
        AppUtils.setSpinnerSelectionFromValue(vkBasaltEffect, sharpEff);
        int level = 100;
        int denoise = 100;
        try { level = Integer.parseInt(config.getSharpnessLevel()); } catch (Exception ignored) {}
        try { denoise = Integer.parseInt(config.getSharpnessDenoise()); } catch (Exception ignored) {}
        level = Math.max(0, Math.min(100, level));
        denoise = Math.max(0, Math.min(100, denoise));
        sbSharpnessLevel.setProgress(level);
        sbSharpnessDenoise.setProgress(denoise);
        tvSharpnessLevel.setText(level + "%");
        tvSharpnessDenoise.setText(denoise + "%");
        sbSharpnessLevel.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean f) { tvSharpnessLevel.setText(p + "%"); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        sbSharpnessDenoise.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean f) { tvSharpnessDenoise.setText(p + "%"); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        applyTheme(context, gpuName, presentMode, textureFilter, fsrUpscale, fsrQuality,
                refreshRate, vsyncLimit, vkBasaltEffect, frameGenerationProfile,
                frameGenerationMultiplier);

        setOnConfirmCallback(() -> {
            String upscaleValue = "On".equals(selectedValue(fsrUpscale)) ? "1" : "0";
            int qualityIndex = Math.max(0, Math.min(fsrQuality.getSelectedItemPosition(),
                    FSR_MODE_VALUES.length - 1));
            config.apply(
                    selectedValue(gpuName), selectedValue(presentMode),
                    textureFilter.getSelectedItemPosition(), swapRedBlue.isChecked(),
                    upscaleValue, FSR_MODE_VALUES[qualityIndex],
                    vsyncLimit.getSelectedItemPosition() == 2 ? "off"
                            : vsyncLimit.getSelectedItemPosition() == 1 ? "50" : "100",
                    unlimitedImages.isChecked(),
                    refreshValues.get(Math.max(0, Math.min(
                            refreshRate.getSelectedItemPosition(),
                            refreshValues.size() - 1))),
                    selectedValue(vkBasaltEffect),
                    String.valueOf(sbSharpnessLevel.getProgress()),
                    String.valueOf(sbSharpnessDenoise.getProgress()),
                    frameGenerationCompatible && frameGeneration.isChecked(),
                    frameGenerationProfileValue(frameGenerationProfile.getSelectedItemPosition()),
                    frameGenerationMultiplierValue(frameGenerationMultiplier.getSelectedItemPosition()),
                    String.valueOf(frameGenerationTarget(
                            frameGenerationTargetFps.getText().toString().trim())),
                    "gles",
                    frameGenerationLowLatency.isChecked());
        });
    }

    private static int frameGenerationProfileIndex(String value) {
        if (value == null) return 1;
        switch (value.trim().toLowerCase(java.util.Locale.US)) {
            case "fast": return 0;
            case "quality":
            case "stable": return 2;
            case "ultra":
            case "ultra_quality": return 2;
            case "balanced": return 1;
            default:
                try {
                    int legacy = Integer.parseInt(value);
                    return legacy <= 1 ? 0 : legacy <= 3 ? 1 : 2;
                }
                catch (Exception ignored) { return 1; }
        }
    }

    private static String frameGenerationProfileValue(int index) {
        return index <= 0 ? "fast" : index == 1 ? "balanced" : "quality";
    }

    private static int frameGenerationMultiplierIndex(String value) {
        if (value == null || "auto".equalsIgnoreCase(value)) return 0;
        try {
            return Math.max(1, Math.min(8,
                    Math.round((Float.parseFloat(value) - 1.0f) * 2.0f)));
        }
        catch (Exception ignored) { return 0; }
    }

    private static String frameGenerationMultiplierValue(int index) {
        if (index <= 0) return "auto";
        float value = 1.0f + Math.max(1, Math.min(8, index)) * 0.5f;
        return value == Math.round(value) ? String.valueOf(Math.round(value))
                : String.format(java.util.Locale.US, "%.1f", value);
    }

    private static int frameGenerationTarget(String value) {
        try { return Math.max(15, Math.min(240, Integer.parseInt(value))); }
        catch (Exception ignored) { return 60; }
    }

    private static int indexOfFsrMode(String token) {
        for (int i = 0; i < FSR_MODE_VALUES.length; i++) {
            if (FSR_MODE_VALUES[i].equals(token)) return i;
        }
        return -1;
    }

    private static ArrayList<String> loadGpuNames(Context context) {
        ArrayList<String> names = new ArrayList<>();
        names.add("Device");
        try {
            JSONArray cards = new JSONArray(FileUtils.readString(context, "gpu_cards.json"));
            for (int i = 0; i < cards.length(); i++) {
                String name = cards.getJSONObject(i).optString("name", "");
                if (!name.isEmpty() && !names.contains(name)) names.add(name);
            }
        }
        catch (JSONException ignored) {
        }
        return names;
    }

    private static String selectedValue(Spinner spinner) {
        Object value = spinner.getSelectedItem();
        return value == null ? "" : value.toString();
    }

    private static void applyTheme(Context context, Spinner... spinners) {
        boolean dark = AppUtils.isDarkMode(context);
        int background = dark ? R.drawable.combo_box_dark : R.drawable.combo_box;
        for (Spinner spinner : spinners) spinner.setBackgroundResource(background);
    }
}
