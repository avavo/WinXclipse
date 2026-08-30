package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.ToggleButton;

import com.winlator.cmod.R;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.KeyValueSet;
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.core.VKD3DVersionItem;
import com.winlator.cmod.xenvironment.ImageFs;
import com.winlator.cmod.widget.ThemedSpinnerAdapter;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;

public class DXVKConfigDialog extends ContentDialog {
    public static final String DEFAULT_CONFIG = "version="+DefaultVersion.DXVK+",framerate=0,async=1,asyncCache=0,vkd3dVersion="+DefaultVersion.VKD3D+",vkd3dLevel=12_1,ddrawrapper=,noTimeline=1,vk3d66=1";
    public static final String[] VKD3D_FEATURE_LEVELS = {"12_0", "12_1", "12_2", "11_1", "11_0", "10_1", "10_0", "9_3", "9_2", "9_1"};
    public static final int DXVK_TYPE_NONE = 0;
    public static final int DXVK_TYPE_ASYNC = 1;
    public static final int DXVK_TYPE_GPLASYNC = 2;
    private final ToggleButton swAsync;
    private final ToggleButton swAsyncCache;
    private final View llAsync;
    private final View llAsyncCache;
    private final Context context;
    private final boolean arm64EC;
    private List<String> dxvkVersions;

    public DXVKConfigDialog(View anchor, boolean arm64EC) {
        super(anchor.getContext(), R.layout.dxvk_config_dialog);
        context = anchor.getContext();
        this.arm64EC = arm64EC;
        setIcon(R.drawable.icon_settings);
        setTitle("DXVK + VKD3D "+context.getString(R.string.configuration));

        final Spinner sVersion = findViewById(R.id.SVersion);
        final Spinner sFramerate = findViewById(R.id.SFramerate);
        final Spinner sDDrawWrapper = findViewById(R.id.SDDrawWrapper);
        final Spinner sVkd3dVersion = findViewById(R.id.SVKD3DVersion);
        final Spinner sVkd3dFeatureLevel = findViewById(R.id.SVKD3DFeatureLevel);
        CheckBox cbNoTimeline = findViewById(R.id.CBXDvkNoTimeline);
        CheckBox cbVk3d66 = findViewById(R.id.CBXDvk66);
        findViewById(R.id.BTNoTimelineHelp).setOnClickListener(v ->
                AppUtils.showHelpBox(getContext(), v, R.string.dxvk_help_no_timeline));
        findViewById(R.id.BTVk3d66Help).setOnClickListener(v ->
                AppUtils.showHelpBox(getContext(), v, R.string.dxvk_help_vk3d66));
        swAsync = findViewById(R.id.SWAsync);
        swAsyncCache = findViewById(R.id.SWAsyncCache);
        llAsync = findViewById(R.id.LLAsync);
        llAsyncCache = findViewById(R.id.LLAsyncCache);

        ContentsManager contentsManager = new ContentsManager(context);
        contentsManager.syncContents();
        loadDxvkVersionSpinner(contentsManager,sVersion);
        loadVkd3dVersionSpinner(contentsManager, sVkd3dVersion);
        ArrayAdapter<String> featureAdapter = new ThemedSpinnerAdapter<>(context, VKD3D_FEATURE_LEVELS);
        sVkd3dFeatureLevel.setAdapter(featureAdapter);

        KeyValueSet config = parseConfig(anchor.getTag());
        if (!arm64EC && config.get("version").toLowerCase(Locale.ENGLISH).contains("arm64ec")) {
            config.put("version", DefaultVersion.DXVK_X86);
        }
        AppUtils.setSpinnerSelectionFromIdentifier(sVersion, config.get("version"));
        AppUtils.setSpinnerSelectionFromIdentifier(sFramerate, config.get("framerate"));
        AppUtils.setSpinnerSelectionFromIdentifier(sDDrawWrapper, config.get("ddrawrapper"));
        setVkd3dSelectionByIdentifier(sVkd3dVersion, config.get("vkd3dVersion"));
        updateVkd3dControls(sVkd3dVersion, sVkd3dFeatureLevel);
        AppUtils.setSpinnerSelectionFromIdentifier(sVkd3dFeatureLevel, config.get("vkd3dLevel"));
        swAsync.setChecked(config.get("async").equals("1"));
        swAsyncCache.setChecked(config.get("asyncCache").equals("1"));
        cbNoTimeline.setChecked(config.get("noTimeline").equals("1"));
        cbVk3d66.setChecked(config.get("vk3d66").equals("1"));

        updateConfigVisibility(getDXVKType(sVersion.getSelectedItemPosition()));

        sVersion.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateConfigVisibility(getDXVKType(position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

        sVkd3dVersion.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateVkd3dControls(sVkd3dVersion, sVkd3dFeatureLevel);
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        setOnConfirmCallback(() -> {
            config.put("version", sVersion.getSelectedItem().toString());
            config.put("framerate", StringUtils.parseNumber(sFramerate.getSelectedItem()));
            config.put("ddrawrapper", StringUtils.parseIdentifier(sDDrawWrapper.getSelectedItem()));
            config.put("async", ((swAsync.isChecked())&&(llAsync.getVisibility()==View.VISIBLE))?"1":"0");
            config.put("asyncCache", ((swAsyncCache.isChecked())&&(llAsyncCache.getVisibility()==View.VISIBLE))?"1":"0");
            config.put("vkd3dVersion", ((VKD3DVersionItem) sVkd3dVersion.getSelectedItem()).getIdentifier());
            config.put("vkd3dLevel", sVkd3dFeatureLevel.getSelectedItem().toString());
            config.put("noTimeline", cbNoTimeline.isChecked() ? "1" : "0");
            config.put("vk3d66", cbVk3d66.isChecked() ? "1" : "0");
            anchor.setTag(config.toString());
        });
    }

    private void updateConfigVisibility(int dxvkType) {
        if (dxvkType == DXVK_TYPE_ASYNC) {
            llAsync.setVisibility(View.VISIBLE);
            llAsyncCache.setVisibility(View.GONE);
        } else if (dxvkType == DXVK_TYPE_GPLASYNC) {
            llAsync.setVisibility(View.VISIBLE);
            llAsyncCache.setVisibility(View.VISIBLE);
        } else {
            llAsync.setVisibility(View.GONE);
            llAsyncCache.setVisibility(View.GONE);
        }
    }

    private int getDXVKType(int pos) {
        final String v = dxvkVersions.get(pos);
        int dxvkType = DXVK_TYPE_NONE;
        if (v.contains("gplasync"))
            dxvkType = DXVK_TYPE_GPLASYNC;
        else if (v.contains("async"))
            dxvkType = DXVK_TYPE_ASYNC;
        return dxvkType;
    }

    public static KeyValueSet parseConfig(Object config) {
        KeyValueSet merged = new KeyValueSet(DEFAULT_CONFIG);
        if (config != null && !config.toString().isEmpty()) {
            for (String[] entry : new KeyValueSet(config.toString())) {
                merged.put(entry[0], entry[1]);
            }
        }
        return merged;
    }

    public static void setEnvVars(Context context, KeyValueSet config, EnvVars envVars) {
        // Keep every D3D shader cache on fast internal storage. DXVK 1.x uses
        // STATE_CACHE_PATH while modern DXVK and VKD3D-Proton use their shader
        // cache variables, so set all three for both ARM64EC and x86 runtimes.
        File cacheDir = new File(ImageFs.find(context).getRootDir(),
                ImageFs.CACHE_PATH + "/d3d");
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
            Log.w("DXVKConfigDialog", "Could not create D3D cache directory: " + cacheDir);
        }
        String cachePath = cacheDir.getAbsolutePath();
        envVars.put("DXVK_STATE_CACHE_PATH", cachePath);
        envVars.put("DXVK_SHADER_CACHE_PATH", cachePath);
        envVars.put("VKD3D_SHADER_CACHE_PATH", cachePath);
        envVars.put("DXVK_LOG_LEVEL", "none");
        envVars.put("DXVK_LOG_PATH", "none");
        envVars.put("VKD3D_DEBUG", "none");
        envVars.put("VKD3D_SHADER_DEBUG", "none");
        if ("1".equals(config.get("noTimeline")))
            envVars.put("DXVK_DISABLE_TIMELINE_SEMAPHORES", "1");
        if ("1".equals(config.get("vk3d66")))
            envVars.put("VKD3D_SHADER_MODEL", "6_6");

        File rootDir = ImageFs.find(context).getRootDir();
        File dxvkConfigFile = new File(rootDir, ImageFs.CONFIG_PATH+"/dxvk.conf");

        String framerate = config.get("framerate");
        if (!framerate.isEmpty() && !framerate.equals("0")) {
            envVars.put("DXVK_FRAME_RATE", framerate);
        }

        String async = config.get("async");
        if (!async.isEmpty() && !async.equals("0"))
            envVars.put("DXVK_ASYNC", "1");

        String asyncCache = config.get("asyncCache");
        if (!asyncCache.isEmpty() && !asyncCache.equals("0"))
            envVars.put("DXVK_GPLASYNCCACHE", "1");

        // dxvk.conf is no longer written by this path; any stale file from
        // older builds must not leak into the current session.
        try { if (dxvkConfigFile.isFile()) dxvkConfigFile.delete(); } catch (Exception ignored) {}
        envVars.remove("DXVK_CONFIG_FILE");
        envVars.remove("DXVK_CONFIG");
        if (!"none".equalsIgnoreCase(config.get("vkd3dVersion")))
            envVars.put("VKD3D_FEATURE_LEVEL", config.get("vkd3dLevel"));
    }

    private void loadDxvkVersionSpinner(ContentsManager manager, Spinner spinner) {
        String[] originalItems = context.getResources().getStringArray(R.array.dxvk_version_entries);
        List<String> itemList = new ArrayList<>();
        for (String version : originalItems) {
            if (arm64EC || !version.toLowerCase(Locale.ENGLISH).contains("arm64ec")) itemList.add(version);
        }

        /* Content profiles join the static list under their clean version name
         * (profile.verName, e.g. "1.7.2" or "2.6.2-arm64ec-gplasync") instead of
         * the raw "name-<verCode>" entry form, and duplicates are suppressed so
         * a bundled .wcp never shows up twice next to its array entry. */
        for (ContentProfile profile : manager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_DXVK)) {
            String verName = profile.verName;
            if (verName != null && !verName.isEmpty()
                    && (arm64EC || !verName.toLowerCase(Locale.ENGLISH).contains("arm64ec"))
                    && !itemList.contains(verName))
                itemList.add(verName);
        }

        spinner.setAdapter(new ThemedSpinnerAdapter<>(context, itemList));
        dxvkVersions = itemList;
    }

    private void loadVkd3dVersionSpinner(ContentsManager manager, Spinner spinner) {
        List<VKD3DVersionItem> items = new ArrayList<>();
        items.add(new VKD3DVersionItem("none", "None"));
        for (String version : context.getResources().getStringArray(R.array.vkd3d_version_entries)) {
            items.add(new VKD3DVersionItem(version, 0));
        }
        for (ContentProfile profile : manager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_VKD3D)) {
            items.add(new VKD3DVersionItem(profile.verName, profile.verCode));
        }
        spinner.setAdapter(new ThemedSpinnerAdapter<>(context, items));
    }

    private void updateVkd3dControls(Spinner versionSpinner, Spinner featureLevelSpinner) {
        Object selected = versionSpinner.getSelectedItem();
        boolean enabled = !(selected instanceof VKD3DVersionItem)
                || !"none".equalsIgnoreCase(((VKD3DVersionItem) selected).getIdentifier());
        featureLevelSpinner.setEnabled(enabled);
        featureLevelSpinner.setAlpha(enabled ? 1f : 0.45f);
    }

    private void setVkd3dSelectionByIdentifier(Spinner spinner, String identifier) {
        for (int i = 0; i < spinner.getCount(); i++) {
            VKD3DVersionItem item = (VKD3DVersionItem) spinner.getItemAtPosition(i);
            if (item.getIdentifier().equals(identifier)) {
                spinner.setSelection(i);
                return;
            }
        }
    }
}
