package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;
import com.winlator.cmod.contents.XclipseDriverManager;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.GPUInformation;
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.widget.MultiSelectionComboBox;
import com.winlator.cmod.widget.ThemedSpinnerAdapter;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;

public class GraphicsDriverConfigDialog extends ContentDialog {
    private static final String TAG = "GraphicsDriverConfig";

    private final Spinner versionSpinner;
    private final Spinner vulkanVersionSpinner;
    private final MultiSelectionComboBox extensionsSpinner;
    private final Spinner gpuNameSpinner;
    private final Spinner maxDeviceMemorySpinner;
    private final Spinner presentModeSpinner;
    private final Spinner resourceTypeSpinner;
    private final Spinner bcnEmulationSpinner;
    private final Spinner bcnEmulationTypeSpinner;
    private final Spinner bcnEmulationCacheSpinner;
    private final CheckBox syncFrameCheckBox;
    private final CheckBox disablePresentWaitCheckBox;
    private final CheckBox astcTranscodeCheckBox;
    private final CheckBox etc2TranscodeCheckBox;

    private final String initialVersion;
    private final String initialExtensionBlacklist;
    private boolean updatingTranscodeState;

    public GraphicsDriverConfigDialog(View anchor, String graphicsDriver, TextView graphicsDriverVersionView) {
        super(anchor.getContext(), R.layout.graphics_driver_config_dialog);
        setIcon(R.drawable.icon_settings);
        setTitle(R.string.graphics_driver_configuration);

        versionSpinner = findViewById(R.id.SGraphicsDriverVersion);
        vulkanVersionSpinner = findViewById(R.id.SGraphicsDriverVulkanVersion);
        extensionsSpinner = findViewById(R.id.MSCAvailableExtensions);
        gpuNameSpinner = findViewById(R.id.SGraphicsDriverGPUName);
        maxDeviceMemorySpinner = findViewById(R.id.SGraphicsDriverMaxDeviceMemory);
        presentModeSpinner = findViewById(R.id.SGraphicsDriverPresentMode);
        resourceTypeSpinner = findViewById(R.id.SGraphicsDriverResourceType);
        bcnEmulationSpinner = findViewById(R.id.SGraphicsDriverBCnEmulation);
        bcnEmulationTypeSpinner = findViewById(R.id.SGraphicsDriverBCnEmulationType);
        bcnEmulationCacheSpinner = findViewById(R.id.SGraphicsDriverBCnEmulationCache);
        syncFrameCheckBox = findViewById(R.id.CBSyncFrame);
        disablePresentWaitCheckBox = findViewById(R.id.CBDisablePresentWait);
        astcTranscodeCheckBox = findViewById(R.id.CBASTCTranscode);
        etc2TranscodeCheckBox = findViewById(R.id.CBETC2Transcode);

        HashMap<String, String> config = parseGraphicsDriverConfig(
                applyDriverSafetyDefaults(graphicsDriver, String.valueOf(anchor.getTag())));
        initialVersion = config.getOrDefault("version", DefaultVersion.WRAPPER);
        initialExtensionBlacklist = config.getOrDefault("blacklistedExtensions", "");

        applyTheme(anchor.getContext());
        loadDriverVersions(anchor.getContext(), graphicsDriver);
        loadGpuNames(anchor.getContext());
        restoreValues(config);
        configureListeners(anchor.getContext());

        findViewById(R.id.BTHelpTextureTranscoding).setOnClickListener(
                view -> AppUtils.showHelpBox(view.getContext(), view, R.string.texture_transcoding_help));

        setOnConfirmCallback(() -> {
            String result = applyDriverSafetyDefaults(graphicsDriver, writeGraphicsDriverConfig());
            Log.i(TAG, "Saved graphics driver config: " + result);
            anchor.setTag(result);
            if (graphicsDriverVersionView != null) {
                graphicsDriverVersionView.setText(selectedValue(versionSpinner));
            }
        });
    }

    public static HashMap<String, String> parseGraphicsDriverConfig(String graphicsDriverConfig) {
        HashMap<String, String> config = new HashMap<>();
        if (graphicsDriverConfig == null) return config;
        for (String element : graphicsDriverConfig.split(";")) {
            if (element.isEmpty()) continue;
            String[] pair = element.split("=", 2);
            config.put(pair[0], pair.length == 2 ? pair[1] : "");
        }
        return config;
    }

    public static String toGraphicsDriverConfig(HashMap<String, String> config) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : config.entrySet()) {
            if (result.length() > 0) result.append(';');
            result.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return result.toString();
    }

    public static String getVersion(String graphicsDriverConfig) {
        return parseGraphicsDriverConfig(graphicsDriverConfig).get("version");
    }

    public static String getExtensionsBlacklist(String graphicsDriverConfig) {
        return parseGraphicsDriverConfig(graphicsDriverConfig).get("blacklistedExtensions");
    }

    public static String applyDriverSafetyDefaults(String graphicsDriver, String graphicsDriverConfig) {
        String driverId = StringUtils.parseIdentifier(graphicsDriver);
        HashMap<String, String> config = parseGraphicsDriverConfig(graphicsDriverConfig);
        boolean changed = false;
        if ("wrapper-kirimu".equals(driverId)
                && !"software".equals(config.getOrDefault("bcnEmulationType", "compute"))) {
            // Kirimu's working native BCN path is the software implementation. ASTC/ETC2
            // remain independent and are handled by the Mali-compatible layer.
            config.put("bcnEmulationType", "software");
            changed = true;
        }
        else if (!supportsNativeBcn(driverId)
                && "software".equals(config.getOrDefault("bcnEmulationType", "compute"))) {
            // Wrappers without their own BCN decoder use the shared Leegao compute layer.
            config.put("bcnEmulationType", "compute");
            changed = true;
        }
        if (!changed) return graphicsDriverConfig;
        return toGraphicsDriverConfig(config);
    }

    public static boolean supportsNativeBcn(String graphicsDriver) {
        String driverId = StringUtils.parseIdentifier(graphicsDriver);
        return "wrapper-bcn".equals(driverId)
                || "wrapper-gamenative".equals(driverId)
                || "wrapper-kirimu".equals(driverId)
                || "wrapper-ref4ik-v6".equals(driverId);
    }

    private void loadDriverVersions(Context context, String graphicsDriver) {
        ContentsManager contentsManager = new ContentsManager(context);
        contentsManager.syncContents();

        LinkedHashSet<String> versions = new LinkedHashSet<>(Arrays.asList(
                context.getResources().getStringArray(R.array.wrapper_graphics_driver_version_entries)));
        versions.addAll(new XclipseDriverManager(context).enumerateInstalledDrivers());
        versionSpinner.setAdapter(new ThemedSpinnerAdapter<>(context, new ArrayList<>(versions)));
        setSpinnerSelectionWithFallback(versionSpinner, initialVersion, graphicsDriver);
    }

    private void loadGpuNames(Context context) {
        ArrayList<String> names = new ArrayList<>();
        names.add("Device");
        try {
            JSONArray cards = new JSONArray(FileUtils.readString(context, "gpu_cards.json"));
            for (int i = 0; i < cards.length(); i++) {
                String name = cards.getJSONObject(i).optString("name", "");
                if (!name.isEmpty() && !names.contains(name)) names.add(name);
            }
        }
        catch (JSONException e) {
            Log.w(TAG, "Unable to load GPU name catalog", e);
        }
        gpuNameSpinner.setAdapter(new ThemedSpinnerAdapter<>(context, names));
    }

    private void restoreValues(HashMap<String, String> config) {
        AppUtils.setSpinnerSelectionFromValue(vulkanVersionSpinner,
                config.getOrDefault("vulkanVersion", "1.3"));
        AppUtils.setSpinnerSelectionFromValue(gpuNameSpinner,
                config.getOrDefault("gpuName", "Device"));
        AppUtils.setSpinnerSelectionFromNumber(maxDeviceMemorySpinner,
                config.getOrDefault("maxDeviceMemory", "0"));
        AppUtils.setSpinnerSelectionFromValue(presentModeSpinner,
                config.getOrDefault("presentMode", "mailbox"));
        AppUtils.setSpinnerSelectionFromValue(resourceTypeSpinner,
                config.getOrDefault("resourceType", "auto"));
        AppUtils.setSpinnerSelectionFromValue(bcnEmulationSpinner,
                config.getOrDefault("bcnEmulation", "auto"));
        AppUtils.setSpinnerSelectionFromValue(bcnEmulationTypeSpinner,
                config.getOrDefault("bcnEmulationType", "compute"));
        AppUtils.setSpinnerSelectionFromValue(bcnEmulationCacheSpinner,
                config.getOrDefault("bcnEmulationCache", "0"));

        syncFrameCheckBox.setChecked("1".equals(config.getOrDefault("syncFrame", "0"))
                || "Always".equals(config.get("frameSync")));
        disablePresentWaitCheckBox.setChecked("1".equals(config.getOrDefault("disablePresentWait", "0"))
                || "Never".equals(config.get("frameSync")));
        astcTranscodeCheckBox.setChecked("1".equals(config.getOrDefault("astcTranscode", "0")));
        etc2TranscodeCheckBox.setChecked("1".equals(config.getOrDefault("etc2Transcode", "0")));
        refreshExtensions(initialVersion);
        updateTranscodeCheckboxes(true);
    }

    private void configureListeners(Context context) {
        versionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                refreshExtensions(selectedValue(versionSpinner));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        bcnEmulationTypeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateTranscodeCheckboxes(true);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        astcTranscodeCheckBox.setOnCheckedChangeListener((buttonView, checked) -> {
            if (updatingTranscodeState || !checked) return;
            if (isSoftwareBcn()) {
                updatingTranscodeState = true;
                astcTranscodeCheckBox.setChecked(false);
                updatingTranscodeState = false;
                AppUtils.showToast(context, R.string.transcode_requires_compute);
            }
            else if (etc2TranscodeCheckBox.isChecked()) {
                updatingTranscodeState = true;
                etc2TranscodeCheckBox.setChecked(false);
                updatingTranscodeState = false;
                AppUtils.showToast(context, R.string.transcode_mutually_exclusive);
            }
            updateTranscodeCheckboxes(false);
        });

        etc2TranscodeCheckBox.setOnCheckedChangeListener((buttonView, checked) -> {
            if (updatingTranscodeState || !checked) return;
            if (isSoftwareBcn()) {
                updatingTranscodeState = true;
                etc2TranscodeCheckBox.setChecked(false);
                updatingTranscodeState = false;
                AppUtils.showToast(context, R.string.transcode_requires_compute);
            }
            else if (astcTranscodeCheckBox.isChecked()) {
                updatingTranscodeState = true;
                astcTranscodeCheckBox.setChecked(false);
                updatingTranscodeState = false;
                AppUtils.showToast(context, R.string.transcode_mutually_exclusive);
            }
            updateTranscodeCheckboxes(false);
        });
    }

    private void refreshExtensions(String selectedVersion) {
        String[] extensions = GPUInformation.enumerateExtensions();
        if (extensions == null) extensions = new String[0];
        extensionsSpinner.setItems(extensions, "Extensions");
        extensionsSpinner.setSelectedItems(extensions);
        if (initialVersion.equalsIgnoreCase(selectedVersion)) {
            for (String extension : initialExtensionBlacklist.split(",")) {
                if (!extension.isEmpty()) extensionsSpinner.unsetSelectedItem(extension);
            }
        }
    }

    private void updateTranscodeCheckboxes(boolean clearWhenSoftware) {
        boolean software = isSoftwareBcn();
        updatingTranscodeState = true;
        if (software && clearWhenSoftware) {
            astcTranscodeCheckBox.setChecked(false);
            etc2TranscodeCheckBox.setChecked(false);
        }
        if (astcTranscodeCheckBox.isChecked() && etc2TranscodeCheckBox.isChecked()) {
            etc2TranscodeCheckBox.setChecked(false);
        }
        astcTranscodeCheckBox.setEnabled(!software && !etc2TranscodeCheckBox.isChecked());
        etc2TranscodeCheckBox.setEnabled(!software && !astcTranscodeCheckBox.isChecked());
        astcTranscodeCheckBox.setAlpha(astcTranscodeCheckBox.isEnabled() ? 1.0f : 0.5f);
        etc2TranscodeCheckBox.setAlpha(etc2TranscodeCheckBox.isEnabled() ? 1.0f : 0.5f);
        updatingTranscodeState = false;
    }

    private boolean isSoftwareBcn() {
        return "software".equals(selectedValue(bcnEmulationTypeSpinner));
    }

    private String writeGraphicsDriverConfig() {
        return "vulkanVersion=" + selectedValue(vulkanVersionSpinner)
                + ";version=" + selectedValue(versionSpinner)
                + ";blacklistedExtensions=" + extensionsSpinner.getUnSelectedItemsAsString()
                + ";maxDeviceMemory=" + StringUtils.parseNumber(selectedValue(maxDeviceMemorySpinner))
                + ";presentMode=" + selectedValue(presentModeSpinner)
                + ";syncFrame=" + boolValue(syncFrameCheckBox)
                + ";disablePresentWait=" + boolValue(disablePresentWaitCheckBox)
                + ";astcTranscode=" + boolValue(astcTranscodeCheckBox)
                + ";etc2Transcode=" + boolValue(etc2TranscodeCheckBox)
                + ";resourceType=" + selectedValue(resourceTypeSpinner)
                + ";bcnEmulation=" + selectedValue(bcnEmulationSpinner)
                + ";bcnEmulationType=" + selectedValue(bcnEmulationTypeSpinner)
                + ";bcnEmulationCache=" + selectedValue(bcnEmulationCacheSpinner)
                + ";gpuName=" + selectedValue(gpuNameSpinner);
    }

    private static String boolValue(CheckBox checkBox) {
        return checkBox.isChecked() ? "1" : "0";
    }

    private static String selectedValue(Spinner spinner) {
        Object selected = spinner.getSelectedItem();
        return selected == null ? "" : selected.toString();
    }

    private void setSpinnerSelectionWithFallback(Spinner spinner, @Nullable String value, String graphicsDriver) {
        if (value != null) {
            for (int i = 0; i < spinner.getCount(); i++) {
                if (spinner.getItemAtPosition(i).toString().equalsIgnoreCase(value)) {
                    spinner.setSelection(i);
                    return;
                }
            }
        }
        AppUtils.setSpinnerSelectionFromValue(spinner, DefaultVersion.WRAPPER);
        Log.w(TAG, "Driver version unavailable for " + graphicsDriver + ": " + value);
    }

    private void applyTheme(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        boolean dark = preferences.getBoolean("dark_mode", false);
        int background = dark ? R.drawable.combo_box_dark : R.drawable.combo_box;
        int textColor = dark ? Color.WHITE : Color.BLACK;
        Spinner[] spinners = {versionSpinner, vulkanVersionSpinner, gpuNameSpinner,
                maxDeviceMemorySpinner, presentModeSpinner, resourceTypeSpinner,
                bcnEmulationSpinner, bcnEmulationTypeSpinner, bcnEmulationCacheSpinner};
        for (Spinner spinner : spinners) {
            spinner.setBackgroundResource(background);
        }
        extensionsSpinner.setBackgroundResource(background);
        extensionsSpinner.setTextColor(textColor);
    }
}
