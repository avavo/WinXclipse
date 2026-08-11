package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
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
import java.util.Arrays;
import java.util.List;

public class DXVKConfigDialog extends ContentDialog {
    public static final String DEFAULT_CONFIG = "version="+DefaultVersion.DXVK+",framerate=0,maxDeviceMemory=0,async=0,asyncCache=0,vkd3dVersion="+DefaultVersion.VKD3D+",vkd3dLevel=12_1";
    public static final String[] VKD3D_FEATURE_LEVELS = {"12_0", "12_1", "12_2", "11_1", "11_0", "10_1", "10_0", "9_3", "9_2", "9_1"};
    public static final int DXVK_TYPE_NONE = 0;
    public static final int DXVK_TYPE_ASYNC = 1;
    public static final int DXVK_TYPE_GPLASYNC = 2;
    private final ToggleButton swAsync;
    private final ToggleButton swAsyncCache;
    private final View llAsync;
    private final View llAsyncCache;
    private final Context context;
    private List<String> dxvkVersions;

    public DXVKConfigDialog(View anchor) {
        super(anchor.getContext(), R.layout.dxvk_config_dialog);
        context = anchor.getContext();
        setIcon(R.drawable.icon_settings);
        setTitle("DXVK + VKD3D "+context.getString(R.string.configuration));

        final Spinner sVersion = findViewById(R.id.SVersion);
        final Spinner sFramerate = findViewById(R.id.SFramerate);
        final Spinner sMaxDeviceMemory = findViewById(R.id.SMaxDeviceMemory);
        final Spinner sVkd3dVersion = findViewById(R.id.SVKD3DVersion);
        final Spinner sVkd3dFeatureLevel = findViewById(R.id.SVKD3DFeatureLevel);
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
        AppUtils.setSpinnerSelectionFromIdentifier(sVersion, config.get("version"));
        AppUtils.setSpinnerSelectionFromIdentifier(sFramerate, config.get("framerate"));
        AppUtils.setSpinnerSelectionFromNumber(sMaxDeviceMemory, config.get("maxDeviceMemory"));
        setVkd3dSelectionByIdentifier(sVkd3dVersion, config.get("vkd3dVersion"));
        updateVkd3dControls(sVkd3dVersion, sVkd3dFeatureLevel);
        AppUtils.setSpinnerSelectionFromIdentifier(sVkd3dFeatureLevel, config.get("vkd3dLevel"));
        swAsync.setChecked(config.get("async").equals("1"));
        swAsyncCache.setChecked(config.get("asyncCache").equals("1"));

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
            config.put("maxDeviceMemory", StringUtils.parseNumber(sMaxDeviceMemory.getSelectedItem()));
            config.put("async", ((swAsync.isChecked())&&(llAsync.getVisibility()==View.VISIBLE))?"1":"0");
            config.put("asyncCache", ((swAsyncCache.isChecked())&&(llAsyncCache.getVisibility()==View.VISIBLE))?"1":"0");
            config.put("vkd3dVersion", ((VKD3DVersionItem) sVkd3dVersion.getSelectedItem()).getIdentifier());
            config.put("vkd3dLevel", sVkd3dFeatureLevel.getSelectedItem().toString());
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
        envVars.put("DXVK_STATE_CACHE_PATH", context.getFilesDir() + "/imagefs/" + ImageFs.CACHE_PATH);
        envVars.put("DXVK_LOG_LEVEL", "none");

        File rootDir = ImageFs.find(context).getRootDir();
        File dxvkConfigFile = new File(rootDir, ImageFs.CONFIG_PATH+"/dxvk.conf");

        String content = "\"";
        String maxDeviceMemory = config.get("maxDeviceMemory");
        if (!maxDeviceMemory.isEmpty() && !maxDeviceMemory.equals("0")) {
            content += "dxgi.maxDeviceMemory = "+maxDeviceMemory+';';
            content += "dxgi.maxSharedMemory = "+maxDeviceMemory+';';
        }

        String framerate = config.get("framerate");
        if (!framerate.isEmpty() && !framerate.equals("0")) {
//            content += "dxgi.maxFrameRate = "+framerate+';';
//            content += "d3d9.maxFrameRate = "+framerate+';';
            envVars.put("DXVK_FRAME_RATE", framerate);
        }

        String async = config.get("async");
        if (!async.isEmpty() && !async.equals("0"))
//            content += "dxvk.enableAsync = True;";
            envVars.put("DXVK_ASYNC", "1");

        String asyncCache = config.get("asyncCache");
        if (!asyncCache.isEmpty() && !asyncCache.equals("0"))
//            content += "dxvk.gplAsyncCache = True;";
            envVars.put("DXVK_GPLASYNCCACHE", "1");
        content = content + '\"';

//        FileUtils.delete(dxvkConfigFile);
//        if (!content.isEmpty() && FileUtils.writeString(dxvkConfigFile, content)) {
//            envVars.put("DXVK_CONFIG_FILE", rootDir + ImageFs.CONFIG_PATH+"/dxvk.conf");
//        }
        envVars.put("DXVK_CONFIG_FILE", rootDir + ImageFs.CONFIG_PATH+"/dxvk.conf");
        envVars.put("DXVK_CONFIG", content);
        if (!"none".equalsIgnoreCase(config.get("vkd3dVersion")))
            envVars.put("VKD3D_FEATURE_LEVEL", config.get("vkd3dLevel"));
    }

    private void loadDxvkVersionSpinner(ContentsManager manager, Spinner spinner) {
        String[] originalItems = context.getResources().getStringArray(R.array.dxvk_version_entries);
        List<String> itemList = new ArrayList<>(Arrays.asList(originalItems));

        for (ContentProfile profile : manager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_DXVK)) {
            String entryName = ContentsManager.getEntryName(profile);
            int firstDashIndex = entryName.indexOf('-');
            itemList.add(entryName.substring(firstDashIndex + 1));
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
