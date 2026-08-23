package com.winlator.cmod.contentdialog;



import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import com.google.android.material.tabs.TabLayout;
import com.winlator.cmod.BuildConfig;
import com.winlator.cmod.ContainerDetailFragment;
import com.winlator.cmod.R;
import com.winlator.cmod.ShortcutsFragment;
import com.winlator.cmod.box86_64.Box86_64PresetManager;
import com.winlator.cmod.box86_64.rc.RCManager;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.contentdialog.ExperimentalPerformanceDialog;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.KeyValueSet;
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.fexcore.FEXCoreManager;
import com.winlator.cmod.fexcore.FEXCorePresetManager;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.InputControlsManager;
import com.winlator.cmod.midi.MidiManager;
import com.winlator.cmod.widget.CPUListView;
import com.winlator.cmod.widget.EnvVarsView;
import com.winlator.cmod.widget.ThemedSpinnerAdapter;
import com.winlator.cmod.winhandler.WinHandler;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import kotlin.random.Random;

public class ShortcutSettingsDialog extends ContentDialog {
    private final ShortcutsFragment fragment;
    private final Shortcut shortcut;
    private InputControlsManager inputControlsManager;
    private TextView tvGraphicsDriverVersion;
    private String box64Version;

    private static final String APP_DATA_DIR = "/data/data/" + BuildConfig.APPLICATION_ID;
    private static final String[] MEDIACONV_ENV_VARS = {
            "MEDIACONV_AUDIO_DUMP_FILE=" + APP_DATA_DIR + "/files/imagefs/home/xuser/audio.dmp",
            "MEDIACONV_VIDEO_DUMP_FILE=" + APP_DATA_DIR + "/files/imagefs/home/xuser/video.dmp",
            "MEDIACONV_VIDEO_TRANSCODED_FILE=" + APP_DATA_DIR + "/files/imagefs/home/xuser/transcoded.mkv",
            "MEDIACONV_AUDIO_TRANSCODED_FILE=" + APP_DATA_DIR + "/files/imagefs/home/xuser/transcoded.wav",
            "MEDIACONV_BLANK_AUDIO_FILE=" + APP_DATA_DIR + "/files/imagefs/home/xuser/blank.wav",
            "MEDIACONV_BLANK_VIDEO_FILE=" + APP_DATA_DIR + "/files/imagefs/home/xuser/blank.mkv",
    };


    public ShortcutSettingsDialog(ShortcutsFragment fragment, Shortcut shortcut) {
        super(fragment.getContext(), R.layout.shortcut_settings_dialog);
        this.fragment = fragment;
        this.shortcut = shortcut;
        setTitle(shortcut.name);
        setIcon(R.drawable.icon_settings);

        // Initialize the ContentsManager
        ContainerManager containerManager = shortcut.container.getManager();

//        if (containerManager != null) {
//            this.contentsManager = new ContentsManager(containerManager.getContext());
//        } else {
//            Toast.makeText(fragment.getContext(), "Failed to initialize container manager. Please try again.", Toast.LENGTH_SHORT).show();
//            return;
//        }

        createContentView();
    }

    private void createContentView() {
        // Use ContentDialog's themed context for adapters and popups as well as the
        // already-inflated layout.  Otherwise dropdown rows remain light-themed in
        // a dark shortcut dialog and their text becomes unreadable.
        final Context context = getContext();
        inputControlsManager = new InputControlsManager(context);
        LinearLayout llContent = findViewById(R.id.LLContent);
        llContent.getLayoutParams().width = AppUtils.getPreferredDialogWidth(context);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean isDarkMode = AppUtils.isDarkMode(context);

        applyDynamicStyles(findViewById(R.id.LLContent), isDarkMode);

        // Initialize the graphics-driver version TextView
        tvGraphicsDriverVersion = findViewById(R.id.TVGraphicsDriverVersion);

        // Get the shared preferences and check the legacy mode status
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
//        boolean isLegacyModeEnabled = preferences.getBoolean("legacy_mode_enabled", false);

        final EditText etName = findViewById(R.id.ETName);
        etName.setText(shortcut.name);

        final EditText etExecArgs = findViewById(R.id.ETExecArgs);
        etExecArgs.setText(shortcut.getExtra("execArgs"));

        ContainerDetailFragment containerDetailFragment = new ContainerDetailFragment(shortcut.container.id);
//        containerDetailFragment.loadScreenSizeSpinner(getContentView(), shortcut.getExtra("screenSize", shortcut.container.getScreenSize()));

        loadScreenSizeSpinner(getContentView(), shortcut.getExtra("screenSize", shortcut.container.getScreenSize()), isDarkMode);


        final Spinner sGraphicsDriver = findViewById(R.id.SGraphicsDriver);
        
        final Spinner sDXWrapper = findViewById(R.id.SDXWrapper);

        final Spinner sDDrawrapper = findViewById(R.id.SDDrawrapper);

        final Spinner sBox64Version = findViewById(R.id.SBox64Version);
        
        ContentsManager contentsManager = new ContentsManager(context);
        
        contentsManager.syncContents();

        final View vGraphicsDriverConfig = findViewById(R.id.BTGraphicsDriverConfig);
        vGraphicsDriverConfig.setTag(shortcut.getExtra("graphicsDriverConfig", shortcut.container.getGraphicsDriverConfig()));
        
        final View vDXWrapperConfig = findViewById(R.id.BTDXWrapperConfig);
        vDXWrapperConfig.setTag(shortcut.getExtra("dxwrapperConfig", shortcut.container.getDXWrapperConfig()));

        ContainerDetailFragment.setupDXWrapperSpinner(sDXWrapper, vDXWrapperConfig);
        ContainerDetailFragment.setupDDrawSpinner(sDDrawrapper, shortcut.getExtra("ddrawrapper", shortcut.container.getDDrawWrapper()));
        KeyValueSet initialDXConfig = DXVKConfigDialog.parseConfig(vDXWrapperConfig.getTag());
        if (initialDXConfig.get("ddrawrapper").isEmpty()) {
            initialDXConfig.put("ddrawrapper", shortcut.getExtra("ddrawrapper", shortcut.container.getDDrawWrapper()));
            vDXWrapperConfig.setTag(initialDXConfig.toString());
        }
        loadGraphicsDriverSpinner(sGraphicsDriver, sDXWrapper, vGraphicsDriverConfig,
                Container.normalizeGraphicsDriver(shortcut.getExtra("graphicsDriver", shortcut.container.getGraphicsDriver())),
            shortcut.getExtra("dxwrapper", shortcut.container.getDXWrapper()));

        final Spinner sRenderer = findViewById(R.id.SRenderer);
        sRenderer.setAdapter(new ThemedSpinnerAdapter<>(context,
                Arrays.asList(context.getString(R.string.gl),
                        context.getString(R.string.vulkan), context.getString(R.string.gdi))));
        final String containerRenderer = shortcut.container.getExtra("renderer", "vulkan");
        AppUtils.setSpinnerSelectionFromIdentifier(sRenderer,
                shortcut.getExtra("renderer", containerRenderer));

        int parsedContainerRendererFilterMode = 0;
        int parsedShortcutRendererFilterMode = 0;
        try {
            parsedContainerRendererFilterMode = Integer.parseInt(
                    shortcut.container.getExtra("rendererFilterMode", "0"));
            parsedShortcutRendererFilterMode = Integer.parseInt(shortcut.getExtra(
                    "rendererFilterMode", String.valueOf(parsedContainerRendererFilterMode)));
        }
        catch (NumberFormatException ignored) {
        }
        final int containerRendererFilterMode = parsedContainerRendererFilterMode;
        final int[] rendererFilterMode = {parsedShortcutRendererFilterMode};
        final boolean containerRendererSwapRB = "1".equals(
                shortcut.container.getExtra("rendererSwapRB", "0"));
        final boolean[] rendererSwapRB = {"1".equals(shortcut.getExtra(
                "rendererSwapRB", containerRendererSwapRB ? "1" : "0"))};

        findViewById(R.id.BTVideoConfig).setOnClickListener(button ->
                new VideoConfigDialog(context, new VideoConfigDialog.Config() {
                    @Override
                    public String getGpuName() {
                        HashMap<String, String> config = GraphicsDriverConfigDialog
                                .parseGraphicsDriverConfig(String.valueOf(vGraphicsDriverConfig.getTag()));
                        return config.getOrDefault("gpuName", "Device");
                    }

                    @Override
                    public String getPresentMode() {
                        HashMap<String, String> config = GraphicsDriverConfigDialog
                                .parseGraphicsDriverConfig(String.valueOf(vGraphicsDriverConfig.getTag()));
                        return config.getOrDefault("presentMode", "mailbox");
                    }

                    @Override
                    public int getTextureFilterMode() {
                        return rendererFilterMode[0];
                    }

                    @Override
                    public boolean isSwapRedBlue() {
                        return rendererSwapRB[0];
                    }

                    @Override
                    public String getFsrMode() {
                        return GraphicsDriverConfigDialog
                                .parseGraphicsDriverConfig(String.valueOf(vGraphicsDriverConfig.getTag()))
                                .getOrDefault("fsrMode", "off");
                    }

                    @Override
                    public String getFsrUpscale() {
                        return GraphicsDriverConfigDialog
                                .parseGraphicsDriverConfig(String.valueOf(vGraphicsDriverConfig.getTag()))
                                .getOrDefault("fsrUpscale", "0");
                    }

                    @Override
                    public String getFsrQuality() {
                        return GraphicsDriverConfigDialog
                                .parseGraphicsDriverConfig(String.valueOf(vGraphicsDriverConfig.getTag()))
                                .getOrDefault("fsrQuality", "balanced");
                    }

                    @Override
                    public boolean isVsyncOff() {
                        return "1".equals(GraphicsDriverConfigDialog
                                .parseGraphicsDriverConfig(String.valueOf(vGraphicsDriverConfig.getTag()))
                                .getOrDefault("vblankOff", "0"));
                    }

                    @Override
                    public boolean isUnlimitedImages() {
                        return "1".equals(GraphicsDriverConfigDialog
                                .parseGraphicsDriverConfig(String.valueOf(vGraphicsDriverConfig.getTag()))
                                .getOrDefault("unlimitedImages", "0"));
                    }

                    @Override
                    public void apply(String gpuName, String presentMode,
                                      int textureFilterMode, boolean swapRedBlue,
                                      String fsrMode, String fsrUpscale,
                                      String fsrQuality, boolean vsyncOff,
                                      boolean unlimitedImages) {
                        HashMap<String, String> config = GraphicsDriverConfigDialog
                                .parseGraphicsDriverConfig(String.valueOf(vGraphicsDriverConfig.getTag()));
                        config.put("gpuName", gpuName);
                        config.put("presentMode", presentMode);
                        config.put("fsrMode", fsrMode == null ? "off" : fsrMode);
                        config.put("fsrUpscale", fsrUpscale == null ? "0" : fsrUpscale);
                        config.put("fsrQuality", fsrQuality == null ? "balanced" : fsrQuality);
                        config.put("vblankOff", vsyncOff ? "1" : "0");
                        config.put("unlimitedImages", unlimitedImages ? "1" : "0");
                        vGraphicsDriverConfig.setTag(
                                GraphicsDriverConfigDialog.toGraphicsDriverConfig(config));
                        rendererFilterMode[0] = textureFilterMode;
                        rendererSwapRB[0] = swapRedBlue;
                    }
                }).show());

        findViewById(R.id.BTHelpDXWrapper).setOnClickListener((v) -> AppUtils.showHelpBox(context, v, R.string.dxwrapper_help_content));
        findViewById(R.id.BTHelpGraphicsDriver).setOnClickListener((v) -> AppUtils.showHelpBox(context, v, R.string.graphics_driver_help_content));

        final Spinner sAudioDriver = findViewById(R.id.SAudioDriver);
        AppUtils.setSpinnerSelectionFromIdentifier(sAudioDriver, shortcut.getExtra("audioDriver", shortcut.container.getAudioDriver()));
        final Spinner sEmulator = findViewById(R.id.SEmulator);
        AppUtils.setSpinnerSelectionFromIdentifier(sEmulator, shortcut.getExtra("emulator", shortcut.container.getEmulator()));
        final Spinner sEmulator64 = findViewById(R.id.SEmulator64);
        sEmulator64.setEnabled(false);
        final Spinner sMIDISoundFont = findViewById(R.id.SMIDISoundFont);
        MidiManager.loadSFSpinner(sMIDISoundFont);
        AppUtils.setSpinnerSelectionFromValue(sMIDISoundFont, shortcut.getExtra("midiSoundFont", shortcut.container.getMIDISoundFont()));

        int parsedContainerAudioVolume = 100;
        int parsedShortcutAudioVolume = 100;
        try {
            parsedContainerAudioVolume = Integer.parseInt(
                    shortcut.container.getExtra("audioVolume", "100"));
            parsedShortcutAudioVolume = Integer.parseInt(shortcut.getExtra(
                    "audioVolume", String.valueOf(parsedContainerAudioVolume)));
        }
        catch (NumberFormatException ignored) {
        }
        final int containerAudioVolume = Math.max(0, Math.min(100, parsedContainerAudioVolume));
        final int[] audioVolume = {Math.max(0, Math.min(100, parsedShortcutAudioVolume))};
        findViewById(R.id.BTAudioConfig).setOnClickListener(v ->
                new AudioConfigDialog(context, new AudioConfigDialog.Config() {
                    @Override
                    public String getAudioDriver() {
                        return StringUtils.parseIdentifier(sAudioDriver.getSelectedItem());
                    }

                    @Override
                    public String getMidiSoundFont() {
                        return sMIDISoundFont.getSelectedItemPosition() == 0
                                ? "" : sMIDISoundFont.getSelectedItem().toString();
                    }

                    @Override
                    public int getVolumePercent() {
                        return audioVolume[0];
                    }

                    @Override
                    public void apply(String driver, String soundFont, int volumePercent) {
                        AppUtils.setSpinnerSelectionFromIdentifier(sAudioDriver, driver);
                        AppUtils.setSpinnerSelectionFromValue(sMIDISoundFont, soundFont);
                        audioVolume[0] = volumePercent;
                    }
                }).show());

        FrameLayout fexcoreFL = findViewById(R.id.fexcoreFrame);
        String wineVersion = shortcut.container.getWineVersion();
        WineInfo wineInfo = WineInfo.fromIdentifier(context, contentsManager, wineVersion);
        if (wineInfo.isArm64EC()) {
            fexcoreFL.setVisibility(View.VISIBLE);
            sEmulator.setEnabled(true);
            sEmulator64.setSelection(0);
        }
        else {
            fexcoreFL.setVisibility(View.GONE);
            sEmulator.setEnabled(false);
            sEmulator.setSelection(1);
            sEmulator64.setSelection(1);
        }

        loadBox64VersionSpinner(context, contentsManager, sBox64Version, wineInfo.isArm64EC());

        // Add this part to set the initial spinner selection based on the shortcut
        String currentBox64Version = shortcut.getExtra("box64Version", shortcut.container.getBox64Version());
        if (currentBox64Version != null) {
            AppUtils.setSpinnerSelectionFromValue(sBox64Version, currentBox64Version);
        } else {
            // Default selection or use a preferred default version
            AppUtils.setSpinnerSelectionFromValue(sBox64Version, DefaultVersion.BOX64);
        }

        // Set OnItemSelectedListener for the Box64 version spinner
        sBox64Version.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedVersion = parent.getItemAtPosition(position).toString();
                box64Version = selectedVersion;  // Update the class-level variable
                // Update the shortcut extra immediately, or wait until saveData() is called
                shortcut.putExtra("box64Version", selectedVersion);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // This method must be implemented, even if it's empty.
                // Optional: You can handle the case where no item is selected, if needed.
            }
        });

        final CheckBox cbUseSecondaryExec = findViewById(R.id.CBUseSecondaryExec);
        final LinearLayout llSecondaryExecOptions = findViewById(R.id.LLSecondaryExecOptions);
        final EditText etSecondaryExec = findViewById(R.id.ETSecondaryExec);
        final EditText etExecDelay = findViewById(R.id.ETExecDelay);

        boolean useSecondaryExec = !shortcut.getExtra("secondaryExec", "").isEmpty();
        cbUseSecondaryExec.setChecked(useSecondaryExec);
        llSecondaryExecOptions.setVisibility(useSecondaryExec ? View.VISIBLE : View.GONE);
        etSecondaryExec.setText(shortcut.getExtra("secondaryExec"));
        etExecDelay.setText(shortcut.getExtra("execDelay", "0"));

        cbUseSecondaryExec.setOnCheckedChangeListener((buttonView, isChecked) -> {
            llSecondaryExecOptions.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });

        // Initialize the TextView for the legacy mode message
//        TextView tvLegacyInputMessage = findViewById(R.id.TVLegacyInputMessage);

        final CheckBox cbFullscreenStretched =  findViewById(R.id.CBFullscreenStretched);
        boolean fullscreenStretched = shortcut.getExtra("fullscreenStretched", "0").equals("1");
        cbFullscreenStretched.setChecked(fullscreenStretched);


        final Runnable showInputWarning = () -> ContentDialog.alert(context,
                R.string.enable_xinput_and_dinput_same_time, null);
        final CheckBox cbEnableXInput = findViewById(R.id.CBEnableXInput);
        final CheckBox cbEnableDInput = findViewById(R.id.CBEnableDInput);
        final CheckBox cbExclusiveXInput = findViewById(R.id.CBExclusiveXInput);
        int inputType = Integer.parseInt(shortcut.getExtra("inputType",
                String.valueOf(shortcut.container.getInputType())));
        cbEnableXInput.setChecked((inputType & WinHandler.FLAG_INPUT_TYPE_XINPUT) != 0);
        cbEnableDInput.setChecked((inputType & WinHandler.FLAG_INPUT_TYPE_DINPUT) != 0);
        cbExclusiveXInput.setChecked("1".equals(shortcut.getExtra("exclusiveXInput",
                shortcut.container.isExclusiveXInput() ? "1" : "0")));

        final boolean[] changingInputOptions = {false};
        Runnable updateExclusiveInput = () -> {
            changingInputOptions[0] = true;
            boolean exclusive = cbExclusiveXInput.isChecked();
            if (exclusive) {
                cbEnableXInput.setChecked(true);
                cbEnableDInput.setChecked(false);
            }
            cbEnableXInput.setEnabled(!exclusive);
            cbEnableDInput.setEnabled(!exclusive);
            changingInputOptions[0] = false;
        };
        cbEnableXInput.setOnCheckedChangeListener((buttonView, checked) -> {
            if (!changingInputOptions[0] && checked && cbEnableDInput.isChecked()) showInputWarning.run();
        });
        cbEnableDInput.setOnCheckedChangeListener((buttonView, checked) -> {
            if (!changingInputOptions[0] && checked && cbEnableXInput.isChecked()) showInputWarning.run();
        });
        cbExclusiveXInput.setOnCheckedChangeListener((buttonView, checked) -> updateExclusiveInput.run());
        findViewById(R.id.BTXInputHelp).setOnClickListener(v -> AppUtils.showHelpBox(context, v, R.string.help_xinput));
        findViewById(R.id.BTDInputHelp).setOnClickListener(v -> AppUtils.showHelpBox(context, v, R.string.help_dinput));
        findViewById(R.id.BTExclusiveInputHelp).setOnClickListener(v -> AppUtils.showHelpBox(context, v, R.string.help_exclusive_input));
        updateExclusiveInput.run();

        final CheckBox cbForceFullscreen = findViewById(R.id.CBForceFullscreen);
        cbForceFullscreen.setChecked(shortcut.getExtra("forceFullscreen", "0").equals("1"));


        final Spinner sBox64Preset = findViewById(R.id.SBox64Preset);
        Box86_64PresetManager.loadSpinner("box64", sBox64Preset, shortcut.getExtra("box64Preset", shortcut.container.getBox64Preset()));

        final Spinner sFEXCoreVersion = findViewById(R.id.SFEXCoreVersion);
        FEXCoreManager.loadFEXCoreVersion(context, contentsManager, sFEXCoreVersion, shortcut);
        final Spinner sFEXCorePreset = findViewById(R.id.SFEXCorePreset);
        FEXCorePresetManager.loadSpinner(sFEXCorePreset, shortcut.getExtra("fexcorePreset", shortcut.container.getFEXCorePreset()));

        final Spinner sRCFile = findViewById(R.id.SRCFile);
        final int[] rcfileIds = {0};
        RCManager manager = new RCManager(context);
        String rcfileId = shortcut.getExtra("rcfileId", String.valueOf(shortcut.container.getRCFileId()));
        RCManager.loadRCFileSpinner(manager, Integer.parseInt(rcfileId), sRCFile, id -> {
            rcfileIds[0] = id;
        });

        final Spinner sControlsProfile = findViewById(R.id.SControlsProfile);
        loadControlsProfileSpinner(sControlsProfile, shortcut.getExtra("controlsProfile", "0"));

        final CheckBox cbDisabledXInput = findViewById(R.id.CBDisabledXInput);
        // Set the initial value based on the shortcut extras
        boolean isXInputDisabled = shortcut.getExtra("disableXinput", "0").equals("1");
        cbDisabledXInput.setChecked(isXInputDisabled);

        final Runnable showGStreamerWorkaroundWarning = () -> ContentDialog.alert(context, R.string.enable_gstreamer_workaround_alert, null);

        final CheckBox cbGStreamerWorkaroundToggle = findViewById(R.id.CBGStreamerWorkaroundToggle);
        String isGStreamerWorkaroundEnabled = shortcut.getExtra("gstreamerWorkaround", "0");
        cbGStreamerWorkaroundToggle.setChecked(isGStreamerWorkaroundEnabled.equals("1") ? true : false);



        cbGStreamerWorkaroundToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && cbGStreamerWorkaroundToggle.isChecked())
                showGStreamerWorkaroundWarning.run();
        });

        final CheckBox cbExperimentalPerformance = findViewById(R.id.CBExperimentalPerformance);
        final boolean containerExperimentalPerformance = "1".equals(
                shortcut.container.getExtra("experimentalPerformance", "0"));
        cbExperimentalPerformance.setChecked("1".equals(shortcut.getExtra(
                "experimentalPerformance", containerExperimentalPerformance ? "1" : "0")));

        final String containerXPerfConfig = shortcut.container.getExtra("xperfConfig", "");
        final String[] pendingXPerfConfig = { shortcut.getExtra("xperfConfig", containerXPerfConfig) };

        final CheckBox cbExperimentalBCN = findViewById(R.id.CBExperimentalBCN);
        final boolean containerExperimentalBCN = "1".equals(
                shortcut.container.getExtra("experimentalBCN", "0"));
        cbExperimentalBCN.setChecked("1".equals(shortcut.getExtra(
                "experimentalBCN", containerExperimentalBCN ? "1" : "0")));

        findViewById(R.id.BTExperimentalPerformanceConfig).setOnClickListener(v -> {
            if (!cbExperimentalPerformance.isChecked()) {
                AppUtils.showToast(context, R.string.xperf_need_master);
                return;
            }
            new ExperimentalPerformanceDialog(context, pendingXPerfConfig[0],
                    cfg -> pendingXPerfConfig[0] = cfg == null ? "" : cfg).show();
        });
        findViewById(R.id.BTExperimentalPerformanceHelp).setOnClickListener(v ->
                AppUtils.showHelpBox(context, v, R.string.experimental_performance_description));
        findViewById(R.id.BTExperimentalBCNHelp).setOnClickListener(v ->
                AppUtils.showHelpBox(context, v, R.string.experimental_bcn_description));


//        final CheckBox cbRelativeMouseMovement = findViewById(R.id.CBRelativeMouseMovement);
//        String isRelativeMouseMovement = shortcut.getExtra("relativeMouseMovement", shortcut.container.isRelativeMouseMovement() ? "1" : "0");
//        cbRelativeMouseMovement.setChecked(isRelativeMouseMovement.equals("1") ? true : false);

        final CheckBox cbSimTouchScreen = findViewById(R.id.CBTouchscreenMode);
        String isTouchScreenMode = shortcut.getExtra("simTouchScreen");
        cbSimTouchScreen.setChecked(isTouchScreenMode.equals("1") ? true : false);

        ContainerDetailFragment.createWinComponentsTabFromShortcut(this, getContentView(),
                shortcut.getExtra("wincomponents", shortcut.container.getWinComponents()), isDarkMode);

        final EnvVarsView envVarsView = createEnvVarsTab();

        AppUtils.setupTabLayout(getContentView(), R.id.TabLayout, R.id.LLTabWinComponents, R.id.LLTabEnvVars, R.id.LLTabAdvanced);

        TabLayout tabLayout = findViewById(R.id.TabLayout);

        if (isDarkMode) {
            tabLayout.setBackgroundResource(R.drawable.tab_layout_background_dark);
        } else {
            tabLayout.setBackgroundResource(R.drawable.tab_layout_background);
        }

        findViewById(R.id.BTExtraArgsMenu).setOnClickListener((v) -> {
            PopupMenu popupMenu = new PopupMenu(context, v);
            popupMenu.inflate(R.menu.extra_args_popup_menu);
            popupMenu.setOnMenuItemClickListener((menuItem) -> {
                String value = String.valueOf(menuItem.getTitle());
                String execArgs = etExecArgs.getText().toString();
                if (!execArgs.contains(value)) etExecArgs.setText(!execArgs.isEmpty() ? execArgs + " " + value : value);
                return true;
            });
            popupMenu.show();
        });

        ContainerDetailFragment.updateGraphicsDriverSpinner(context, sGraphicsDriver);

        final Spinner sStartupSelection = findViewById(R.id.SStartupSelection);
        sStartupSelection.setSelection(Integer.parseInt(shortcut.getExtra("startupSelection", String.valueOf(shortcut.container.getStartupSelection()))));

        final Spinner sSharpnessEffect = findViewById(R.id.SSharpnessEffect);
        final SeekBar sbSharpnessLevel = findViewById(R.id.SBSharpnessLevel);
        final SeekBar sbSharpnessDenoise = findViewById(R.id.SBSharpnessDenoise);
        final TextView tvSharpnessLevel = findViewById(R.id.TVSharpnessLevel);
        final TextView tvSharpnessDenoise = findViewById(R.id.TVSharpnessDenoise);

        AppUtils.setSpinnerSelectionFromValue(sSharpnessEffect, shortcut.getExtra("sharpnessEffect", "None"));

        sbSharpnessLevel.setProgress(Integer.parseInt(shortcut.getExtra("sharpnessLevel", "100")));
        tvSharpnessLevel.setText(shortcut.getExtra("sharpnessLevel", "100") + "%");
        sbSharpnessLevel.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvSharpnessLevel.setText(progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });
        sbSharpnessDenoise.setProgress(Integer.parseInt(shortcut.getExtra("sharpnessDenoise", "100")));
        tvSharpnessDenoise.setText(shortcut.getExtra("sharpnessDenoise", "100") + "%");
        sbSharpnessDenoise.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvSharpnessDenoise.setText(progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });

        final CPUListView cpuListView = findViewById(R.id.CPUListView);
        cpuListView.setCheckedCPUList(shortcut.getExtra("cpuList", shortcut.container.getCPUList(true)));
        final CPUListView cpuListViewWoW64 = findViewById(R.id.CPUListViewWoW64);
        cpuListViewWoW64.setCheckedCPUList(shortcut.getExtra("cpuListWoW64", shortcut.container.getCPUListWoW64(true)));

        setOnConfirmCallback(() -> {
            String name = etName.getText().toString().trim();
            boolean nameChanged = !shortcut.name.equals(name) && !name.isEmpty();

            // First, handle renaming if the name has changed
            if (nameChanged) {
                renameShortcut(name);
            }


            // Determine if renaming is needed
            boolean renamingSuccess = !nameChanged || new File(shortcut.file.getParent(), name + ".desktop").exists();

            if (renamingSuccess) {
                String graphicsDriver = StringUtils.parseIdentifier(sGraphicsDriver.getSelectedItem());
                String graphicsDriverConfig = vGraphicsDriverConfig.getTag().toString();
                String renderer = StringUtils.parseIdentifier(sRenderer.getSelectedItem());
                String dxwrapper = ContainerDetailFragment.getDXWrapperIdentifier(sDXWrapper.getSelectedItem());
                String dxwrapperConfig = vDXWrapperConfig.getTag().toString();
                String ddrawrapper = DXVKConfigDialog.parseConfig(dxwrapperConfig).get("ddrawrapper");
                if (ddrawrapper.isEmpty()) ddrawrapper = StringUtils.parseIdentifier(sDDrawrapper.getSelectedItem());
                String audioDriver = StringUtils.parseIdentifier(sAudioDriver.getSelectedItem());
                String emulator = StringUtils.parseIdentifier(sEmulator.getSelectedItem());
                String midiSoundFont = sMIDISoundFont.getSelectedItemPosition() == 0 ? "" : sMIDISoundFont.getSelectedItem().toString();
                String screenSize = containerDetailFragment.getScreenSize(getContentView());

                int finalInputType = 0;
                finalInputType |= cbEnableXInput.isChecked() ? WinHandler.FLAG_INPUT_TYPE_XINPUT : 0;
                finalInputType |= cbEnableDInput.isChecked() ? WinHandler.FLAG_INPUT_TYPE_DINPUT : 0;
                shortcut.putExtra("inputType", finalInputType != shortcut.container.getInputType()
                        ? String.valueOf(finalInputType) : null);
                boolean exclusiveXInput = cbExclusiveXInput.isChecked();
                shortcut.putExtra("exclusiveXInput",
                        exclusiveXInput != shortcut.container.isExclusiveXInput()
                                ? (exclusiveXInput ? "1" : "0") : null);

                boolean disabledXInput = cbDisabledXInput.isChecked();
                shortcut.putExtra("disableXinput", disabledXInput ? "1" : null);

//                boolean relativeMouseMovement = cbRelativeMouseMovement.isChecked();
//                shortcut.putExtra("relativeMouseMovement", relativeMouseMovement ? "1" : "0");

                boolean gstreamerWorkaround = cbGStreamerWorkaroundToggle.isChecked();
                shortcut.putExtra("gstreamerWorkaround", gstreamerWorkaround ? "1" : "0");

                boolean experimentalPerformance = cbExperimentalPerformance.isChecked();
                shortcut.putExtra("experimentalPerformance",
                        experimentalPerformance == containerExperimentalPerformance
                                ? null : (experimentalPerformance ? "1" : "0"));
                String savedXPerf = pendingXPerfConfig[0] == null ? "" : pendingXPerfConfig[0];
                shortcut.putExtra("xperfConfig", savedXPerf.equals(containerXPerfConfig) ? null : savedXPerf);

                boolean experimentalBCN = cbExperimentalBCN.isChecked();
                shortcut.putExtra("experimentalBCN",
                        experimentalBCN == containerExperimentalBCN
                                ? null : (experimentalBCN ? "1" : "0"));

                boolean touchscreenMode = cbSimTouchScreen.isChecked();
                shortcut.putExtra("simTouchScreen", touchscreenMode ? "1" : "0");

                String execArgs = etExecArgs.getText().toString();
                shortcut.putExtra("execArgs", !execArgs.isEmpty() ? execArgs : null);
                shortcut.putExtra("screenSize", !screenSize.equals(shortcut.container.getScreenSize()) ? screenSize : null);
                shortcut.putExtra("graphicsDriver", !graphicsDriver.equals(shortcut.container.getGraphicsDriver()) ? graphicsDriver : null);
                shortcut.putExtra("graphicsDriverConfig", !graphicsDriverConfig.equals(shortcut.container.getGraphicsDriverConfig()) ? graphicsDriverConfig : null);
                shortcut.putExtra("renderer", !renderer.equals(containerRenderer) ? renderer : null);
                shortcut.putExtra("rendererFilterMode",
                        rendererFilterMode[0] != containerRendererFilterMode
                                ? String.valueOf(rendererFilterMode[0]) : null);
                shortcut.putExtra("rendererSwapRB",
                        rendererSwapRB[0] != containerRendererSwapRB
                                ? (rendererSwapRB[0] ? "1" : "0") : null);
                shortcut.putExtra("dxwrapper", !dxwrapper.equals(shortcut.container.getDXWrapper()) ? dxwrapper : null);
                shortcut.putExtra("ddrawrapper", !ddrawrapper.equals(shortcut.container.getDDrawWrapper()) ? ddrawrapper : null);
                shortcut.putExtra("dxwrapperConfig", !dxwrapperConfig.equals(shortcut.container.getDXWrapperConfig()) ? dxwrapperConfig : null);
                shortcut.putExtra("audioDriver", !audioDriver.equals(shortcut.container.getAudioDriver()) ? audioDriver : null);
                shortcut.putExtra("emulator", !emulator.equals(shortcut.container.getEmulator()) ? emulator : null);
                shortcut.putExtra("midiSoundFont", !midiSoundFont.equals(shortcut.container.getMIDISoundFont()) ? midiSoundFont : null);
                shortcut.putExtra("audioVolume", audioVolume[0] != containerAudioVolume
                        ? String.valueOf(audioVolume[0]) : null);
                shortcut.putExtra("forceFullscreen", cbForceFullscreen.isChecked() ? "1" : null);

                if (cbUseSecondaryExec.isChecked()) {
                    String secondaryExec = etSecondaryExec.getText().toString().trim();
                    String execDelay = etExecDelay.getText().toString().trim();
                    shortcut.putExtra("secondaryExec", !secondaryExec.isEmpty() ? secondaryExec : null);
                    shortcut.putExtra("execDelay", !execDelay.isEmpty() ? execDelay : null);
                } else {
                    shortcut.putExtra("secondaryExec", null);
                    shortcut.putExtra("execDelay", null);
                }

                shortcut.putExtra("fullscreenStretched", cbFullscreenStretched.isChecked() ? "1" : null);

                String wincomponents = containerDetailFragment.getWinComponents(getContentView());
                shortcut.putExtra("wincomponents", !wincomponents.equals(shortcut.container.getWinComponents()) ? wincomponents : null);



                String envVars = envVarsView.getEnvVars();

                shortcut.putExtra("envVars", !envVars.isEmpty() ? envVars : null);

                String box64Preset = Box86_64PresetManager.getSpinnerSelectedId(sBox64Preset);
                shortcut.putExtra("box64Preset", !box64Preset.equals(shortcut.container.getBox64Preset()) ? box64Preset : null);

                shortcut.putExtra("rcfileId", rcfileIds[0] != shortcut.container.getRCFileId() ? Integer.toString(rcfileIds[0]) : null);

                String fexcoreVersion = sFEXCoreVersion.getSelectedItem().toString();
                shortcut.putExtra("fexcoreVersion", !fexcoreVersion.equals(shortcut.container.getFEXCoreVersion()) ? fexcoreVersion : null);

                String fexcorePreset = FEXCorePresetManager.getSpinnerSelectedId(sFEXCorePreset);
                shortcut.putExtra("fexcorePreset",
                        !fexcorePreset.equals(shortcut.container.getFEXCorePreset()) ? fexcorePreset : null);

                byte startupSelection = (byte)sStartupSelection.getSelectedItemPosition();
                shortcut.putExtra("startupSelection", (startupSelection != shortcut.container.getStartupSelection()) ? String.valueOf(startupSelection) : null);

                String sharpeningEffect = sSharpnessEffect.getSelectedItem().toString();
                String sharpeningLevel = String.valueOf(sbSharpnessLevel.getProgress());
                String sharpeningDenoise = String.valueOf(sbSharpnessDenoise.getProgress());
                shortcut.putExtra("sharpnessEffect", sharpeningEffect);
                shortcut.putExtra("sharpnessLevel", sharpeningLevel);
                shortcut.putExtra("sharpnessDenoise", sharpeningDenoise);

                ArrayList<ControlsProfile> profiles = inputControlsManager.getProfiles(true);
                int controlsProfile = sControlsProfile.getSelectedItemPosition() > 0 ? profiles.get(sControlsProfile.getSelectedItemPosition() - 1).id : 0;
                shortcut.putExtra("controlsProfile", controlsProfile > 0 ? String.valueOf(controlsProfile) : null);

                String cpuList = cpuListView.getCheckedCPUListAsString();
                shortcut.putExtra("cpuList", !cpuList.equals(shortcut.container.getCPUList(true)) ? cpuList : null);

                String cpuListWoW64 = cpuListViewWoW64.getCheckedCPUListAsString();
                shortcut.putExtra("cpuListWoW64", !cpuListWoW64.equals(shortcut.container.getCPUListWoW64(true)) ? cpuListWoW64 : null);

                // Save all changes to the shortcut
                shortcut.saveData();
            }
        });
    }

    // Utility method to apply styles to dynamically added TextViews based on their content
    private void applyFieldSetLabelStylesDynamically(ViewGroup rootView, boolean isDarkMode) {
        for (int i = 0; i < rootView.getChildCount(); i++) {
            View child = rootView.getChildAt(i);
            if (child instanceof ViewGroup) {
                applyFieldSetLabelStylesDynamically((ViewGroup) child, isDarkMode); // Recursive call for nested ViewGroups
            } else if (child instanceof TextView) {
                TextView textView = (TextView) child;
                // Apply the style based on the content of the TextView
                if (isFieldSetLabel(textView.getText().toString())) {
                    applyFieldSetLabelStyle(textView, isDarkMode);
                }
            }
        }
    }

    // Method to check if the text content matches any fieldset label
    private boolean isFieldSetLabel(String text) {
        return text.equalsIgnoreCase("DirectX") ||
                text.equalsIgnoreCase("General") ||
                text.equalsIgnoreCase("Box86/Box64") ||
                text.equalsIgnoreCase("Input Controls") ||
                text.equalsIgnoreCase("Game Controller") ||
                text.equalsIgnoreCase("System");
    }

    public void onWinComponentsViewsAdded(boolean isDarkMode) {
        // Apply styles to all dynamically added TextViews
        ViewGroup llContent = findViewById(R.id.LLContent);
        applyFieldSetLabelStylesDynamically(llContent, isDarkMode);
    }


    public static void loadScreenSizeSpinner(View view, String selectedValue, boolean isDarkMode) {
        final Spinner sScreenSize = view.findViewById(R.id.SScreenSize);

        final LinearLayout llCustomScreenSize = view.findViewById(R.id.LLCustomScreenSize);

        applyDarkThemeToEditText(view.findViewById(R.id.ETScreenWidth), isDarkMode);
        applyDarkThemeToEditText(view.findViewById(R.id.ETScreenHeight), isDarkMode);


        sScreenSize.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String value = sScreenSize.getItemAtPosition(position).toString();
                llCustomScreenSize.setVisibility(value.equalsIgnoreCase("custom") ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        boolean found = AppUtils.setSpinnerSelectionFromIdentifier(sScreenSize, selectedValue);
        if (!found) {
            AppUtils.setSpinnerSelectionFromValue(sScreenSize, "custom");
            String[] screenSize = selectedValue.split("x");
            ((EditText)view.findViewById(R.id.ETScreenWidth)).setText(screenSize[0]);
            ((EditText)view.findViewById(R.id.ETScreenHeight)).setText(screenSize[1]);
        }
    }

    private void applyDynamicStyles(View view, boolean isDarkMode) {

        // Update edit text
        EditText etName = view.findViewById(R.id.ETName);
        applyDarkThemeToEditText(etName, isDarkMode);

        // Update Spinners
        Spinner sGraphicsDriver = view.findViewById(R.id.SGraphicsDriver);
        Spinner sDXWrapper = view.findViewById(R.id.SDXWrapper);
        Spinner sDDrawrapper = view.findViewById(R.id.SDDrawrapper);
        Spinner sAudioDriver = view.findViewById(R.id.SAudioDriver);
        Spinner sEmulatorSpinner = view.findViewById(R.id.SEmulator);
        Spinner sBox64Preset = view.findViewById(R.id.SBox64Preset);
        Spinner sControlsProfile = view.findViewById(R.id.SControlsProfile);
        Spinner sRCFile = view.findViewById(R.id.SRCFile);
//        Spinner sDInputType = view.findViewById(R.id.SDInputType);
        Spinner sMIDISoundFont = view.findViewById(R.id.SMIDISoundFont);
        Spinner sBox64Version = view.findViewById(R.id.SBox64Version);
        Spinner sFEXCoreVersion = view.findViewById(R.id.SFEXCoreVersion);
        Spinner sFEXCorePreset = findViewById(R.id.SFEXCorePreset);
        Spinner sStartupSelection = findViewById(R.id.SStartupSelection);
        

        // Set dark or light mode background for spinners
        sGraphicsDriver.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        sDXWrapper.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        sDDrawrapper.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        sAudioDriver.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        sEmulatorSpinner.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        sBox64Preset.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        sControlsProfile.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        sRCFile.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
//        sDInputType.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        sMIDISoundFont.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        sBox64Version.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        sFEXCorePreset.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        sFEXCoreVersion.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        sStartupSelection.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);

//        EditText etLC_ALL = view.findViewById(R.id.ETlcall);
        EditText etExecArgs = view.findViewById(R.id.ETExecArgs);

//        applyDarkThemeToEditText(etLC_ALL, isDarkMode);
        applyDarkThemeToEditText(etExecArgs, isDarkMode);

    }

    private void applyFieldSetLabelStyle(TextView textView, boolean isDarkMode) {
        if (isDarkMode) {
            textView.setTextColor(Color.WHITE);
            textView.setBackgroundColor(Color.BLACK);
        } else {
            textView.setTextColor(Color.BLACK);
            textView.setBackgroundResource(R.color.window_background_color);
        }
    }

    private static void applyDarkThemeToEditText(EditText editText, boolean isDarkMode) {
        if (isDarkMode) {
            editText.setTextColor(Color.WHITE);
            editText.setHintTextColor(Color.GRAY);
            editText.setBackgroundResource(R.drawable.edit_text_dark);
        } else {
            editText.setTextColor(Color.BLACK);
            editText.setHintTextColor(Color.GRAY);
            editText.setBackgroundResource(R.drawable.edit_text);
        }
    }

    private void updateExtra(String extraName, String containerValue, String newValue) {
        String extraValue = shortcut.getExtra(extraName);
        if (extraValue.isEmpty() && containerValue.equals(newValue))
            return;
        shortcut.putExtra(extraName, newValue);
    }

    private void renameShortcut(String newName) {
        File parent = shortcut.file.getParentFile();
        File oldDesktopFile = shortcut.file; // Reference to the old file
        File newDesktopFile = new File(parent, newName + ".desktop");

        // Rename the desktop file if the new one doesn't exist
        if (!newDesktopFile.isFile() && oldDesktopFile.renameTo(newDesktopFile)) {
            // Successfully renamed, update the shortcut's file reference
            updateShortcutFileReference(newDesktopFile); // New helper method

            // As a precaution, delete any remaining old file
            deleteOldFileIfExists(oldDesktopFile);
        }

        // Rename link file if applicable
        File linkFile = new File(parent, shortcut.name + ".lnk");
        if (linkFile.isFile()) {
            File newLinkFile = new File(parent, newName + ".lnk");
            if (!newLinkFile.isFile()) linkFile.renameTo(newLinkFile);
        }

        fragment.loadShortcutsList();
        fragment.updateShortcutOnScreen(newName, newName, shortcut.container.id, newDesktopFile.getAbsolutePath(),
                Icon.createWithBitmap(shortcut.icon), shortcut.getExtra("uuid"));
    }

    // Method to ensure no old file remains
    private void deleteOldFileIfExists(File oldFile) {
        if (oldFile.exists()) {
            if (!oldFile.delete()) {
                Log.e("ShortcutSettingsDialog", "Failed to delete old file: " + oldFile.getPath());
            }
        }
    }

    // Update the shortcut's file reference to ensure saveData() writes to the correct file
    private void updateShortcutFileReference(File newFile) {
        try {
            Field fileField = Shortcut.class.getDeclaredField("file");
            fileField.setAccessible(true);
            fileField.set(shortcut, newFile);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Log.e("ShortcutSettingsDialog", "Error updating shortcut file reference", e);
        }
    }


    private EnvVarsView createEnvVarsTab() {
        final View view = getContentView();
        final Context context = view.getContext();

        // Retrieve the existing EnvVarsView
        final EnvVarsView envVarsView = view.findViewById(R.id.EnvVarsView);

        // Update the dark mode setting of the existing instance
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean isDarkMode = AppUtils.isDarkMode(context);
        envVarsView.setDarkMode(isDarkMode);

        // Set the environment variables in the existing EnvVarsView
        envVarsView.setEnvVars(new EnvVars(shortcut.getExtra("envVars")));

        // Set the click listener for adding new environment variables
        view.findViewById(R.id.BTAddEnvVar).setOnClickListener((v) ->
                new AddEnvVarDialog(context, envVarsView).show()
        );

        return envVarsView;
    }

    private void loadControlsProfileSpinner(Spinner spinner, String selectedValue) {
        final Context context = fragment.getContext();
        final ArrayList<ControlsProfile> profiles = inputControlsManager.getProfiles(true);
        ArrayList<String> values = new ArrayList<>();
        values.add(context.getString(R.string.none));

        int selectedPosition = 0;
        int selectedId = Integer.parseInt(selectedValue);
        for (int i = 0; i < profiles.size(); i++) {
            ControlsProfile profile = profiles.get(i);
            if (profile.id == selectedId) selectedPosition = i + 1;
            values.add(profile.getName());
        }

        spinner.setAdapter(new ThemedSpinnerAdapter<>(context, values));
        spinner.setSelection(selectedPosition, false);
    }

    private void showInputWarning() {
        final Context context = fragment.getContext();
        ContentDialog.alert(context, R.string.enable_xinput_and_dinput_same_time, null);
    }

    public static void loadBox64VersionSpinner(Context context, ContentsManager manager, Spinner spinner, boolean isArm64EC) {
        List<String> itemList = new ArrayList<>(Arrays.asList(
                context.getResources().getStringArray(R.array.box64_version_entries)));
        for (ContentProfile profile : manager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_BOX64)) {
            String entryName = ContentsManager.getEntryName(profile);
            int firstDashIndex = entryName.indexOf('-');
            String version = entryName.substring(firstDashIndex + 1);
            if (!itemList.contains(version)) itemList.add(version);
        }
        spinner.setAdapter(new ThemedSpinnerAdapter<>(context, itemList));
    }
    
    public void loadGraphicsDriverSpinner(final Spinner sGraphicsDriver, final Spinner sDXWrapper, final View vGraphicsDriverConfig, String selectedGraphicsDriver, String selectedDXWrapper) {
        final Context context = sGraphicsDriver.getContext();
        
        ContainerDetailFragment.updateGraphicsDriverSpinner(context, sGraphicsDriver);
        
        final String[] dxwrapperEntries = context.getResources().getStringArray(R.array.dxwrapper_entries);
        
        Runnable update = () -> {
            Object selectedDriverItem = sGraphicsDriver.getSelectedItem();
            String graphicsDriver = StringUtils.parseIdentifier(selectedDriverItem);
            String graphicsDriverLabel = selectedDriverItem != null
                    ? selectedDriverItem.toString() : "";
            String graphicsDriverConfig = vGraphicsDriverConfig.getTag().toString();

            tvGraphicsDriverVersion.setText(graphicsDriverLabel + "  ·  "
                    + GraphicsDriverConfigDialog.getVersion(graphicsDriverConfig));

            vGraphicsDriverConfig.setOnClickListener((v) -> {
                CheckBox experimentalBcn = vGraphicsDriverConfig.getRootView()
                        .findViewById(R.id.CBExperimentalBCN);
                new GraphicsDriverConfigDialog(vGraphicsDriverConfig, graphicsDriver,
                        tvGraphicsDriverVersion,
                        experimentalBcn != null && experimentalBcn.isChecked()).show();
            });
            Spinner externalVersion = vGraphicsDriverConfig.getRootView()
                    .findViewById(R.id.SGraphicsDriverVersionExternal);
            if (externalVersion != null) {
                GraphicsDriverConfigDialog.bindExternalDriverSpinner(context, externalVersion,
                        vGraphicsDriverConfig, graphicsDriver);
            }

            ArrayList<String> items = new ArrayList<>();
            for (String value : dxwrapperEntries) {
                    items.add(value);
            }
            sDXWrapper.setAdapter(new ThemedSpinnerAdapter<>(context, items));
            ContainerDetailFragment.setDXWrapperSelection(sDXWrapper, selectedDXWrapper);
        };

        sGraphicsDriver.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                update.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        AppUtils.setSpinnerSelectionFromIdentifier(sGraphicsDriver, selectedGraphicsDriver);
        update.run();
    }
}
