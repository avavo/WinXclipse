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
 * extra "xperfConfig"; every key defaults to the long-standing hard-coded
 * behaviour so existing containers keep working untouched.
 */
public class ExperimentalPerformanceDialog extends ContentDialog {
    public static final String DEFAULT_CONFIG =
            "vramCap=1,vramCapMode=auto,perfcache=1,mdiex=1,ramAggro=0,wow64Pin=1";

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
        CheckBox cbWow64Pin = findViewById(R.id.CBXPerfWow64Pin);

        sVramCapMode.setAdapter(new ThemedSpinnerAdapter<>(context,
                new String[]{"Auto", "2048 MB", "3072 MB", "4092 MB"}));
        cbVramCap.setChecked("1".equals(config.get("vramCap")));
        AppUtils.setSpinnerSelectionFromValue(sVramCapMode, config.get("vramCapMode"));
        cbPerfcache.setChecked("1".equals(config.get("perfcache")));
        cbMdiex.setChecked("1".equals(config.get("mdiex")));
        cbNramv.setChecked("1".equals(config.get("ramAggro")));
        cbWow64Pin.setChecked(!"0".equals(config.get("wow64Pin")));

        Runnable updateVramCapUi = () -> {
            float alpha = cbVramCap.isChecked() ? 1f : 0.45f;
            sVramCapMode.setEnabled(cbVramCap.isChecked());
            tvVramCapMode.setAlpha(alpha);
            sVramCapMode.setAlpha(alpha);
        };
        updateVramCapUi.run();
        cbVramCap.setOnCheckedChangeListener((b, checked) -> updateVramCapUi.run());

        setOnConfirmCallback(() -> {
            config.put("vramCap", cbVramCap.isChecked() ? "1" : "0");
            Object mode = sVramCapMode.getSelectedItem();
            String modeValue = mode == null ? "auto" : mode.toString();
            if (modeValue.startsWith("2048")) modeValue = "2048";
            else if (modeValue.startsWith("3072")) modeValue = "3072";
            else if (modeValue.startsWith("4092")) modeValue = "4092";
            else modeValue = "auto";
            config.put("vramCapMode", modeValue);
            config.put("perfcache", cbPerfcache.isChecked() ? "1" : "0");
            config.put("mdiex", cbMdiex.isChecked() ? "1" : "0");
            config.put("ramAggro", cbNramv.isChecked() ? "1" : "0");
            config.put("wow64Pin", cbWow64Pin.isChecked() ? "1" : "0");
            callback.onConfirm(config.toString());
        });
    }
}


