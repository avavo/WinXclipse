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
    public interface Config {
        String getGpuName();
        String getPresentMode();
        int getTextureFilterMode();
        boolean isSwapRedBlue();
        /** "off", "on" or a legacy mode value (quality/balanced/performance). */
        String getFsrMode();
        /** "0" or "1": whether the FSR EASU upscaler runs (modes only apply then). */
        String getFsrUpscale();
        /** "quality", "balanced" or "performance". */
        String getFsrQuality();
        boolean isVsyncOff();
        boolean isUnlimitedImages();
        void apply(String gpuName, String presentMode, int textureFilterMode,
                   boolean swapRedBlue, String fsrMode, String fsrUpscale,
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
        Spinner fsrMode = findViewById(R.id.SVideoFsr);
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

        if (!AppUtils.setSpinnerSelectionFromValue(gpuName, config.getGpuName())) {
            AppUtils.setSpinnerSelectionFromValue(gpuName, "Device");
        }
        AppUtils.setSpinnerSelectionFromValue(presentMode, config.getPresentMode());
        textureFilter.setSelection(Math.max(0, Math.min(config.getTextureFilterMode(), 2)));
        swapRedBlue.setChecked(config.isSwapRedBlue());

        fsrMode.setAdapter(new ThemedSpinnerAdapter<>(context, Arrays.asList("Off", "On")));
        fsrUpscale.setAdapter(new ThemedSpinnerAdapter<>(context, Arrays.asList("Off", "On")));
        fsrQuality.setAdapter(new ThemedSpinnerAdapter<>(context, Arrays.asList(
                "Quality (1.5x)", "Balanced (1.7x)", "Performance (2.0x)")));

        // Resolve legacy configs where the mode spinner stored quality/balanced/performance.
        String fsr = config.getFsrMode() == null ? "off" : config.getFsrMode();
        String upscale = config.getFsrUpscale() == null ? "0" : config.getFsrUpscale();
        String quality = config.getFsrQuality() == null ? "balanced" : config.getFsrQuality();
        if (fsr.equals("quality") || fsr.equals("balanced") || fsr.equals("performance")) {
            quality = fsr;
            upscale = "1";
            fsr = "on";
        }
        if (!quality.equals("quality") && !quality.equals("performance")) quality = "balanced";
        AppUtils.setSpinnerSelectionFromValue(fsrMode, fsr.equals("on") ? "On" : "Off");
        AppUtils.setSpinnerSelectionFromValue(fsrUpscale, upscale.equals("1") ? "On" : "Off");
        String qualityFinal = quality;
        AppUtils.setSpinnerSelectionFromValue(fsrQuality, qualityFinal.equals("quality")
                ? "Quality (1.5x)" : qualityFinal.equals("performance")
                ? "Performance (2.0x)" : "Balanced (1.7x)");

        // Upscale row only exists while FSR is on; mode row only while upscale is on.
        Runnable updateVisibility = () -> {
            boolean fsrOn = "On".equals(selectedValue(fsrMode));
            boolean upscaleOn = fsrOn && "On".equals(selectedValue(fsrUpscale));
            fsrUpscaleRow.setVisibility(fsrOn ? View.VISIBLE : View.GONE);
            fsrQualityRow.setVisibility(upscaleOn ? View.VISIBLE : View.GONE);
        };
        updateVisibility.run();
        fsrMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateVisibility.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        fsrUpscale.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateVisibility.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        vsyncOff.setChecked(config.isVsyncOff());
        unlimitedImages.setChecked(config.isUnlimitedImages());
        applyTheme(context, gpuName, presentMode, textureFilter, fsrMode, fsrUpscale, fsrQuality);

        setOnConfirmCallback(() -> {
            String fsrValue = "On".equals(selectedValue(fsrMode)) ? "on" : "off";
            String upscaleValue = "On".equals(selectedValue(fsrUpscale)) ? "1" : "0";
            String qualitySelected = selectedValue(fsrQuality);
            String qualityValue = qualitySelected.startsWith("Quality") ? "quality"
                    : qualitySelected.startsWith("Performance") ? "performance" : "balanced";
            config.apply(
                    selectedValue(gpuName), selectedValue(presentMode),
                    textureFilter.getSelectedItemPosition(), swapRedBlue.isChecked(),
                    fsrValue, upscaleValue, qualityValue,
                    vsyncOff.isChecked(), unlimitedImages.isChecked());
        });
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
