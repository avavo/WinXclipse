package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.graphics.Color;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

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
import java.util.Locale;
import java.util.Map;

public class GraphicsDriverConfigDialog extends ContentDialog {
    private static final String TAG = "GraphicsDriverConfig";

    private final Spinner versionSpinner;
    private final Spinner vulkanVersionSpinner;
    private final MultiSelectionComboBox extensionsSpinner;
    private final Spinner maxDeviceMemorySpinner;
    private final Spinner resourceTypeSpinner;
    private final CheckBox syncFrameCheckBox;
    private final CheckBox disablePresentWaitCheckBox;
    private final Spinner bcnEmulationSpinner;
    private final Spinner bcnTypeSpinner;
    private final Spinner bcnCacheSpinner;
    private final CheckBox astcTranscodeCheckBox;
    private final CheckBox etc2TranscodeCheckBox;
    private final CheckBox bcnSoftwareSwitchCheckBox;
    private final CheckBox astcAutoDefaultCheckBox;

    private final String initialVersion;
    private final String initialExtensionBlacklist;
    private final HashMap<String, String> initialConfig;
    private final boolean experimentalBcn;

    public GraphicsDriverConfigDialog(View anchor, String graphicsDriver, TextView graphicsDriverVersionView) {
        this(anchor, graphicsDriver, graphicsDriverVersionView, false);
    }

    public GraphicsDriverConfigDialog(View anchor, String graphicsDriver,
                                      TextView graphicsDriverVersionView,
                                      boolean experimentalBcn) {
        super(anchor.getContext(), R.layout.graphics_driver_config_dialog);
        this.experimentalBcn = experimentalBcn;
        setIcon(R.drawable.icon_settings);
        setTitle(R.string.graphics_driver_configuration);

        versionSpinner = findViewById(R.id.SGraphicsDriverVersion);
        vulkanVersionSpinner = findViewById(R.id.SGraphicsDriverVulkanVersion);
        extensionsSpinner = findViewById(R.id.MSCAvailableExtensions);
        maxDeviceMemorySpinner = findViewById(R.id.SGraphicsDriverMaxDeviceMemory);
        resourceTypeSpinner = findViewById(R.id.SGraphicsDriverResourceType);
        syncFrameCheckBox = findViewById(R.id.CBSyncFrame);
        disablePresentWaitCheckBox = findViewById(R.id.CBDisablePresentWait);
        bcnEmulationSpinner = findViewById(R.id.SGraphicsDriverBCnEmulation);
        bcnTypeSpinner = findViewById(R.id.SGraphicsDriverBCnEmulationType);
        bcnCacheSpinner = findViewById(R.id.SGraphicsDriverBCnEmulationCache);
        astcTranscodeCheckBox = findViewById(R.id.CBASTCTranscode);
        etc2TranscodeCheckBox = findViewById(R.id.CBETC2Transcode);
        bcnSoftwareSwitchCheckBox = findViewById(R.id.CBBCnSoftwareSwitch);
        astcAutoDefaultCheckBox = findViewById(R.id.CBAstcAutoDefault);
        findViewById(R.id.LLExperimentalBCNOptions).setVisibility(
                experimentalBcn ? View.VISIBLE : View.GONE);

        initialConfig = parseGraphicsDriverConfig(String.valueOf(anchor.getTag()));
        initialVersion = initialConfig.getOrDefault("version", DefaultVersion.WRAPPER);
        initialExtensionBlacklist = initialConfig.getOrDefault("blacklistedExtensions", "");

        applyTheme(anchor.getContext());
        loadDriverVersions(anchor.getContext(), graphicsDriver);
        restoreValues(initialConfig);
        configureListeners();
        applyWrapperBcnProfile(graphicsDriver, experimentalBcn);
        findViewById(R.id.BTASTCTranscodeHelp).setOnClickListener(v ->
                AppUtils.showHelpBox(getContext(), v, R.string.astc_transcode_help));
        findViewById(R.id.BTETC2TranscodeHelp).setOnClickListener(v ->
                AppUtils.showHelpBox(getContext(), v, R.string.gdc_help_etc2));
        findViewById(R.id.BTBCnSoftwareHelp).setOnClickListener(v ->
                AppUtils.showHelpBox(getContext(), v, R.string.gdc_help_bcn_software));
        findViewById(R.id.BTSyncFrameHelp).setOnClickListener(v ->
                AppUtils.showHelpBox(getContext(), v, R.string.gdc_help_sync_frame));
        findViewById(R.id.BTDisablePresentWaitHelp).setOnClickListener(v ->
                AppUtils.showHelpBox(getContext(), v, R.string.gdc_help_disable_present_wait));

        setOnConfirmCallback(() -> {
            String result = writeGraphicsDriverConfig();
            Log.i(TAG, "Saved graphics driver config: " + result);
            anchor.setTag(result);
            if (graphicsDriverVersionView != null) {
                // Driver selection lives outside this configuration popup.  Keep the
                // externally selected driver instead of replacing it with the hidden
                // compatibility spinner's fallback value when the dialog is saved.
                graphicsDriverVersionView.setText(initialVersion);
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

    /**
     * Normalizes any FSR mode value persisted by past builds to one of
     * "off", "on", "fidelity", "quality", "balanced", "performance" or
     * "ultraperformance". Older releases stored the spinner's display
     * string ("Off", "Quality (1.5x)", "Balanced (1.7x)",
     * "Performance (2.0x)"); the current format stores lowercase tokens.
     */
    public static String normalizeFsrValue(String raw) {
        if (raw == null) return "off";
        String value = raw.trim().toLowerCase(Locale.ENGLISH);
        if (value.startsWith("ultra")) return "ultraperformance";
        if (value.startsWith("fidelity")) return "fidelity";
        if (value.startsWith("quality")) return "quality";
        if (value.startsWith("balanced")) return "balanced";
        if (value.startsWith("performance")) return "performance";
        if (value.equals("on") || value.equals("1") || value.equals("true")) return "on";
        return "off";
    }

    /** Upscale factor of the internal FSR scene buffer for a mode token. */
    public static float fsrFactorForMode(String mode) {
        switch (mode) {
            case "fidelity": return 1.3f;
            case "quality": return 1.5f;
            case "performance": return 2.0f;
            case "ultraperformance": return 2.5f;
            default: return 1.7f;
        }
    }

    /** RCAS sharpness (stops) for a mode token: bigger upscale, stronger sharpening. */
    public static float fsrStopsForMode(String mode) {
        switch (mode) {
            case "fidelity": return 1.8f;
            case "quality": return 1.5f;
            case "performance": return 0.7f;
            case "ultraperformance": return 0.4f;
            default: return 1.0f;
        }
    }

    public static String getExtensionsBlacklist(String graphicsDriverConfig) {
        return parseGraphicsDriverConfig(graphicsDriverConfig).get("blacklistedExtensions");
    }

    public static void bindExternalDriverSpinner(Context context, Spinner spinner,
                                                  View configAnchor, String graphicsDriver) {
        ContentsManager manager = new ContentsManager(context);
        manager.syncContents();
        LinkedHashSet<String> versions = new LinkedHashSet<>(Arrays.asList(
                context.getResources().getStringArray(
                        R.array.wrapper_graphics_driver_version_entries)));
        versions.addAll(new XclipseDriverManager(context).enumerateInstalledDrivers());
        spinner.setAdapter(new ThemedSpinnerAdapter<>(context, new ArrayList<>(versions)));
        HashMap<String, String> config = parseGraphicsDriverConfig(
                String.valueOf(configAnchor.getTag()));
        String wanted = config.getOrDefault("version", DefaultVersion.WRAPPER);
        AppUtils.setSpinnerSelectionFromValue(spinner, wanted);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                 int position, long id) {
                HashMap<String, String> updated = parseGraphicsDriverConfig(
                        String.valueOf(configAnchor.getTag()));
                updated.put("version", selectedValue(spinner));
                configAnchor.setTag(toGraphicsDriverConfig(updated));
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
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

    private void restoreValues(HashMap<String, String> config) {
        AppUtils.setSpinnerSelectionFromValue(vulkanVersionSpinner,
                config.getOrDefault("vulkanVersion", "1.3"));
        AppUtils.setSpinnerSelectionFromNumber(maxDeviceMemorySpinner,
                config.getOrDefault("maxDeviceMemory", "0"));
        AppUtils.setSpinnerSelectionFromValue(resourceTypeSpinner,
                config.getOrDefault("resourceType", "auto"));
        syncFrameCheckBox.setChecked("1".equals(config.getOrDefault("syncFrame", "0"))
                || "Always".equals(config.get("frameSync")));
        disablePresentWaitCheckBox.setChecked("1".equals(config.getOrDefault("disablePresentWait", "0"))
                || "Never".equals(config.get("frameSync")));
        AppUtils.setSpinnerSelectionFromValue(bcnEmulationSpinner,
                config.getOrDefault("bcnEmulation", "auto"));
        AppUtils.setSpinnerSelectionFromValue(bcnTypeSpinner,
                config.getOrDefault("bcnEmulationType",
                        GPUInformation.defaultBcnEmulationType()));
        AppUtils.setSpinnerSelectionFromValue(bcnCacheSpinner,
                config.getOrDefault("bcnEmulationCache", experimentalBcn ? "1" : "0"));
        astcTranscodeCheckBox.setChecked("1".equals(config.getOrDefault("astcTranscode", "0")));
        etc2TranscodeCheckBox.setChecked("1".equals(config.getOrDefault("etc2Transcode", "0")));
        bcnSoftwareSwitchCheckBox.setChecked("1".equals(config.getOrDefault("bcnSoftwareSwitch", "0")));
        astcAutoDefaultCheckBox.setChecked("1".equals(config.getOrDefault("astcAutoDefault", "0")));
        refreshExtensions(initialVersion);
    }

    private void applyWrapperBcnProfile(String graphicsDriver, boolean experimentalBcn) {
        /* Kirimu ships its own working software BCN path: when Experimental
         * BCN is on, lock the shared-layer options to that profile. */
        if (!experimentalBcn || graphicsDriver == null
                || !graphicsDriver.toLowerCase(Locale.ENGLISH).contains("kirimu")) {
            return;
        }
        AppUtils.setSpinnerSelectionFromValue(bcnTypeSpinner, "software");
        bcnTypeSpinner.setEnabled(false);
        astcTranscodeCheckBox.setEnabled(false);
        astcTranscodeCheckBox.setChecked(false);
        etc2TranscodeCheckBox.setEnabled(false);
        etc2TranscodeCheckBox.setChecked(false);
        if (!initialConfig.containsKey("bcnEmulationCache")) {
            AppUtils.setSpinnerSelectionFromValue(bcnCacheSpinner, "1");
        }
    }

    private void configureListeners() {
        versionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                refreshExtensions(selectedValue(versionSpinner));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        astcTranscodeCheckBox.setOnCheckedChangeListener((button, checked) -> {
            if (checked) etc2TranscodeCheckBox.setChecked(false);
        });
        etc2TranscodeCheckBox.setOnCheckedChangeListener((button, checked) -> {
            if (checked) astcTranscodeCheckBox.setChecked(false);
        });
        bcnTypeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                 int position, long id) {
                boolean software = "software".equalsIgnoreCase(selectedValue(bcnTypeSpinner));
                astcTranscodeCheckBox.setEnabled(!software);
                etc2TranscodeCheckBox.setEnabled(!software);
                if (software) {
                    astcTranscodeCheckBox.setChecked(false);
                    etc2TranscodeCheckBox.setChecked(false);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
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

    private String writeGraphicsDriverConfig() {
        HashMap<String, String> result = new HashMap<>(initialConfig);
        result.put("vulkanVersion", selectedValue(vulkanVersionSpinner));
        // The driver is selected beside the wrapper in the container/shortcut screen.
        // This dialog only edits its options, so it must preserve that selection.
        result.put("version", initialVersion);
        result.put("blacklistedExtensions", extensionsSpinner.getUnSelectedItemsAsString());
        result.put("maxDeviceMemory", StringUtils.parseNumber(selectedValue(maxDeviceMemorySpinner)));
        result.put("syncFrame", boolValue(syncFrameCheckBox));
        result.put("disablePresentWait", boolValue(disablePresentWaitCheckBox));
        result.put("resourceType", selectedValue(resourceTypeSpinner));
        result.put("bcnEmulation", selectedValue(bcnEmulationSpinner));
        result.put("bcnEmulationType", selectedValue(bcnTypeSpinner));
        result.put("bcnEmulationCache", selectedValue(bcnCacheSpinner));
        result.put("astcTranscode", boolValue(astcTranscodeCheckBox));
        result.put("etc2Transcode", boolValue(etc2TranscodeCheckBox));
        result.put("bcnSoftwareSwitch", boolValue(bcnSoftwareSwitchCheckBox));
        result.put("astcAutoDefault", boolValue(astcAutoDefaultCheckBox));
        // GPU name and present mode belong to Video Configuration.  Keeping the
        // untouched keys here prevents either dialog from silently resetting the other.
        result.putIfAbsent("gpuName", "Device");
        result.putIfAbsent("presentMode", "mailbox");
        return toGraphicsDriverConfig(result);
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
        boolean dark = AppUtils.isDarkMode(context);
        int background = dark ? R.drawable.combo_box_dark : R.drawable.combo_box;
        int textColor = dark ? Color.WHITE : Color.BLACK;
        Spinner[] spinners = {versionSpinner, vulkanVersionSpinner,
                maxDeviceMemorySpinner, resourceTypeSpinner, bcnEmulationSpinner,
                bcnTypeSpinner, bcnCacheSpinner};
        for (Spinner spinner : spinners) {
            spinner.setBackgroundResource(background);
        }
        extensionsSpinner.setBackgroundResource(background);
        extensionsSpinner.setTextColor(textColor);
    }
}
