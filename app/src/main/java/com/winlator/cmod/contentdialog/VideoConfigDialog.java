package com.winlator.cmod.contentdialog;

import android.content.Context;
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
        String getFsrMode();
        boolean isVsyncOff();
        boolean isUnlimitedImages();
        void apply(String gpuName, String presentMode, int textureFilterMode,
                   boolean swapRedBlue, String fsrMode,
                   boolean vsyncOff, boolean unlimitedImages);
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
        Spinner fsrMode = findViewById(R.id.SVideoFsr);
        CheckBox vsyncOff = findViewById(R.id.CBVideoVsyncOff);
        CheckBox unlimitedImages = findViewById(R.id.CBVideoUnlimitedImages);

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
        AppUtils.setSpinnerSelectionFromValue(fsrMode, config.getFsrMode());
        fsrMode.setAdapter(new ThemedSpinnerAdapter<>(context,
                Arrays.asList("Off", "Quality (1.5x)", "Balanced (1.7x)", "Performance (2.0x)")));
        AppUtils.setSpinnerSelectionFromValue(fsrMode, config.getFsrMode());
        vsyncOff.setChecked(config.isVsyncOff());
        unlimitedImages.setChecked(config.isUnlimitedImages());
        applyTheme(context, gpuName, presentMode, textureFilter);

        setOnConfirmCallback(() -> config.apply(
                selectedValue(gpuName), selectedValue(presentMode),
                textureFilter.getSelectedItemPosition(), swapRedBlue,
                selectedValue(fsrMode).isEmpty() ? "off" : selectedValue(fsrMode),
                vsyncOff.isChecked(), unlimitedImages.isChecked()));
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
