package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.view.View;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.Spinner;

import com.winlator.cmod.R;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.widget.ThemedSpinnerAdapter;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Arrays;

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
        /** 0 = bilinear, 1 = nearest, 2 = FSR. */
        int getTextureFilterMode();
        boolean isSwapRedBlue();
        /** Legacy FSR mode key, only used to migrate old configs. */
        String getFsrMode();
        /** "0" or "1": whether the FSR EASU upscaler runs (only when FSR is selected). */
        String getFsrUpscale();
        /** "fidelity", "quality", "balanced", "performance" or "ultraperformance". */
        String getFsrQuality();
        boolean isVsyncOff();
        boolean isUnlimitedImages();
        void apply(String gpuName, String presentMode, int textureFilterMode,
                   boolean swapRedBlue, String fsrUpscale,
                   String fsrQuality, boolean vsyncOff, boolean unlimitedImages);
    }

    public VideoConfigDialog(Context context, Config config) {
        super(context, R.layout.video_config_dialog);
        setIcon(R.drawable.icon_screen_effect);
        setTitle(R.string.video_configuration);

        Spinner gpuName = findViewById(R.id.SVideoGPUName);
        Spinner presentMode = findViewById(R.id.SVideoPresentMode);
        Spinner textureFilter = findViewById(R.id.SVideoTextureFilter);
        CheckBox swapRedBlue = findViewById(R.id.CBVideoSwapRedBlue);
        Spinner fsrUpscale = findViewById(R.id.SVideoFsrUpscale);
        Spinner fsrQuality = findViewById(R.id.SVideoFsrMode);
        View fsrUpscaleRow = findViewById(R.id.LLVideoFsrUpscale);
        View fsrQualityRow = findViewById(R.id.LLVideoFsrMode);
        CheckBox vsyncOff = findViewById(R.id.CBVideoVsyncOff);
        CheckBox unlimitedImages = findViewById(R.id.CBVideoUnlimitedImages);
        findViewById(R.id.BTVideoVsyncOffHelp).setOnClickListener(v ->
                AppUtils.showHelpBox(context, v, R.string.video_help_vsync_off));
        findViewById(R.id.BTVideoUnlimitedImagesHelp).setOnClickListener(v ->
                AppUtils.showHelpBox(context, v, R.string.video_help_unlimited_images));

        gpuName.setAdapter(new ThemedSpinnerAdapter<>(context, loadGpuNames(context)));
        presentMode.setAdapter(new ThemedSpinnerAdapter<>(context,
                Arrays.asList(context.getResources().getStringArray(R.array.present_mode_entries))));
        textureFilter.setAdapter(new ThemedSpinnerAdapter<>(context,
                Arrays.asList(context.getString(R.string.bilinear),
                        context.getString(R.string.nearest_neighbor),
                        "FSR")));
        fsrUpscale.setAdapter(new ThemedSpinnerAdapter<>(context, Arrays.asList("Off", "On")));
        fsrQuality.setAdapter(new ThemedSpinnerAdapter<>(context, Arrays.asList(FSR_MODE_LABELS)));

        // Normalize values persisted by any build (display strings from old
        // releases, legacy mode tokens, current on/off tokens). A stored FSR
        // mode other than "off" implies the FSR texture filter selection.
        int filterMode = Math.max(0, Math.min(config.getTextureFilterMode(), 2));
        String upscale = config.getFsrUpscale() == null ? "0" : config.getFsrUpscale();
        String quality = GraphicsDriverConfigDialog.normalizeFsrValue(config.getFsrQuality());
        String legacyFsr = GraphicsDriverConfigDialog.normalizeFsrValue(config.getFsrMode());
        if (!legacyFsr.equals("off")) {
            // Any legacy FSR setting (including sharpen-only "on") maps to the
            // FSR texture filter entry; modes also imply upscale.
            upscale = legacyFsr.equals("on") ? "0" : "1";
            quality = legacyFsr.equals("on") ? quality : legacyFsr;
            filterMode = 2;
        }
        if (indexOfFsrMode(quality) == -1) quality = "balanced";

        if (!AppUtils.setSpinnerSelectionFromValue(gpuName, config.getGpuName())) {
            AppUtils.setSpinnerSelectionFromValue(gpuName, "Device");
        }
        AppUtils.setSpinnerSelectionFromValue(presentMode, config.getPresentMode());
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

        vsyncOff.setChecked(config.isVsyncOff());
        unlimitedImages.setChecked(config.isUnlimitedImages());
        applyTheme(context, gpuName, presentMode, textureFilter, fsrUpscale, fsrQuality);

        setOnConfirmCallback(() -> {
            String upscaleValue = "On".equals(selectedValue(fsrUpscale)) ? "1" : "0";
            int qualityIndex = Math.max(0, indexOfFsrMode(selectedValue(fsrQuality)));
            config.apply(
                    selectedValue(gpuName), selectedValue(presentMode),
                    textureFilter.getSelectedItemPosition(), swapRedBlue.isChecked(),
                    upscaleValue, FSR_MODE_VALUES[qualityIndex],
                    vsyncOff.isChecked(), unlimitedImages.isChecked());
        });
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
