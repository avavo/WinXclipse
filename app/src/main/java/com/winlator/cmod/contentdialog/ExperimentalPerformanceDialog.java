package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.TextView;

import com.winlator.cmod.R;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.KeyValueSet;
import com.winlator.cmod.widget.ThemedSpinnerAdapter;

/**
 * Per-container tuning for the Experimental Performance master switch.
 * The serialized form is a KeyValueSet stored as the container/shortcut
 * extra "xperfConfig"; every tuning key defaults to disabled so enabling the
 * master switch never silently changes a newly created container.
 */
public class ExperimentalPerformanceDialog extends ContentDialog {
    public static final String DEFAULT_CONFIG =
            "vramCap=0,vramCapMode=auto,perfcache=0,mdiex=0,ramAggro=0,wow64Pin=0,translationTurbo=0";

    public interface OnConfirmCallback {
        void onConfirm(String config);
    }

    public static KeyValueSet parseConfig(String config) {
        KeyValueSet merged = new KeyValueSet(DEFAULT_CONFIG);
        if (config != null && !config.isEmpty()) {
            for (String[] entry : new KeyValueSet(config)) merged.put(entry[0], entry[1]);
        }
        return merged;
    }

    public ExperimentalPerformanceDialog(Context context, String initialConfig, OnConfirmCallback callback) {
        super(context, R.layout.experimental_performance_dialog);
        setIcon(R.drawable.icon_settings);
        setTitle(R.string.experimental_performance);

        KeyValueSet config = parseConfig(initialConfig);

        CheckBox cbVramCap = findViewById(R.id.CBXPerfVramCap);
        Spinner sVramCapMode = findViewById(R.id.SXPerfVramCapMode);
        TextView tvVramCapMode = findViewById(R.id.TVXPerfVramCapMode);
        CheckBox cbPerfcache = findViewById(R.id.CBXPerfPerfcache);
        CheckBox cbMdiex = findViewById(R.id.CBXPerfMdiex);
        CheckBox cbNramv = findViewById(R.id.CBXPerfNramv);

        sVramCapMode.setAdapter(new ThemedSpinnerAdapter<>(context,
                new String[]{"Auto", "2048 MB", "3072 MB", "4096 MB", "6144 MB",
                        "8192 MB", "10240 MB", "12288 MB"}));
        cbVramCap.setChecked("1".equals(config.get("vramCap")));
        // Stored values are bare tokens ("2048"); spinner entries carry the
        // unit suffix ("2048 MB"), so translate before matching by value.
        String capMode = config.get("vramCapMode");
        if ("2048".equals(capMode)) capMode = "2048 MB";
        else if ("3072".equals(capMode)) capMode = "3072 MB";
        else if ("4092".equals(capMode) || "4096".equals(capMode)) capMode = "4096 MB";
        else if ("6144".equals(capMode)) capMode = "6144 MB";
        else if ("8192".equals(capMode)) capMode = "8192 MB";
        else if ("10240".equals(capMode)) capMode = "10240 MB";
        else if ("12288".equals(capMode)) capMode = "12288 MB";
        else if ("auto".equalsIgnoreCase(capMode) || capMode == null || capMode.isEmpty()) capMode = "Auto";
        AppUtils.setSpinnerSelectionFromValue(sVramCapMode, capMode);
        cbPerfcache.setChecked("1".equals(config.get("perfcache")));
        cbMdiex.setChecked("1".equals(config.get("mdiex")));
        cbNramv.setChecked("1".equals(config.get("ramAggro")));

        Runnable updateVramCapUi = () -> {
            float alpha = cbVramCap.isChecked() ? 1f : 0.45f;
            sVramCapMode.setEnabled(cbVramCap.isChecked());
            tvVramCapMode.setAlpha(alpha);
            sVramCapMode.setAlpha(alpha);
        };
        updateVramCapUi.run();
        cbVramCap.setOnCheckedChangeListener((b, checked) -> updateVramCapUi.run());

        findViewById(R.id.BTXPerfVramCapHelp).setOnClickListener(v ->
                AppUtils.showHelpBox(context, v, R.string.xperf_help_vram_cap));
        findViewById(R.id.BTXPerfVramCapModeHelp).setOnClickListener(v ->
                AppUtils.showHelpBox(context, v, R.string.xperf_help_vram_cap_mode));
        findViewById(R.id.BTXPerfPerfcacheHelp).setOnClickListener(v ->
                AppUtils.showHelpBox(context, v, R.string.xperf_help_perfcache));
        findViewById(R.id.BTXPerfMdiexHelp).setOnClickListener(v ->
                AppUtils.showHelpBox(context, v, R.string.xperf_help_mdiex));
        findViewById(R.id.BTXPerfNramvHelp).setOnClickListener(v ->
                AppUtils.showHelpBox(context, v, R.string.xperf_help_nramv));

        setOnConfirmCallback(() -> {
            config.put("vramCap", cbVramCap.isChecked() ? "1" : "0");
            Object mode = sVramCapMode.getSelectedItem();
            String modeValue = mode == null ? "auto" : mode.toString();
            if (modeValue.startsWith("2048")) modeValue = "2048";
            else if (modeValue.startsWith("3072")) modeValue = "3072";
            else if (modeValue.startsWith("4096")) modeValue = "4096";
            else if (modeValue.startsWith("6144")) modeValue = "6144";
            else if (modeValue.startsWith("8192")) modeValue = "8192";
            else if (modeValue.startsWith("10240")) modeValue = "10240";
            else if (modeValue.startsWith("12288")) modeValue = "12288";
            else modeValue = "auto";
            config.put("vramCapMode", modeValue);
            config.put("perfcache", cbPerfcache.isChecked() ? "1" : "0");
            config.put("mdiex", cbMdiex.isChecked() ? "1" : "0");
            config.put("ramAggro", cbNramv.isChecked() ? "1" : "0");
            callback.onConfirm(config.toString());
        });
    }
}


