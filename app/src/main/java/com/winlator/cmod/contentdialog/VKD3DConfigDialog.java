package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import com.winlator.cmod.R;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.KeyValueSet;
import com.winlator.cmod.core.VKD3DVersionItem;

import java.util.ArrayList;
import java.util.List;

public class VKD3DConfigDialog extends ContentDialog {
    public static final String DEFAULT_CONFIG = DXVKConfigDialog.DEFAULT_CONFIG +
            ",vkd3dVersion=" + DefaultVersion.VKD3D + ",vkd3dLevel=" + DefaultVersion.VKD3D_FEATURE_LEVEL;
    public static final String[] VKD3D_FEATURE_LEVEL = {"12_0", "12_1", "12_2", "11_1", "11_0", "10_1", "10_0", "9_3", "9_2", "9_1"};
    private final Context context;

    public VKD3DConfigDialog(View anchor) {
        super(anchor.getContext(), R.layout.vkd3d_config_dialog);
        context = anchor.getContext();
        setIcon(R.drawable.icon_settings);
        setTitle("VKD3D " + context.getString(R.string.configuration));

        final Spinner sVersion = findViewById(R.id.SVersion);
        final Spinner sFeatureLevel = findViewById(R.id.SFeatureLevel);

        ContentsManager contentsManager = new ContentsManager(context);
        contentsManager.syncContents();
        loadVkd3dVersionSpinner(contentsManager, sVersion);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, VKD3D_FEATURE_LEVEL);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sFeatureLevel.setAdapter(adapter);

        // Retrieve and apply previously saved config values
        KeyValueSet config = parseConfig(anchor.getTag());
        setSpinnerSelectionByIdentifier(sVersion, config.get("vkd3dVersion"));
        AppUtils.setSpinnerSelectionFromIdentifier(sFeatureLevel, config.get("vkd3dLevel"));

        // Save selection on confirmation
        setOnConfirmCallback(() -> {
            VKD3DVersionItem selectedItem = (VKD3DVersionItem) sVersion.getSelectedItem();
            config.put("vkd3dVersion", selectedItem.getIdentifier()); // Save identifier
            config.put("vkd3dLevel", sFeatureLevel.getSelectedItem().toString());
            anchor.setTag(config.toString());
        });
    }

    public static KeyValueSet parseConfig(Object config) {
        String data = config != null && !config.toString().isEmpty() ? config.toString() : DEFAULT_CONFIG;
        KeyValueSet result = new KeyValueSet(data);
        if (result.get("vkd3dLevel").isEmpty()) result.put("vkd3dLevel", DefaultVersion.VKD3D_FEATURE_LEVEL);
        return result;
    }

    public static void setEnvVars(Context context, KeyValueSet config, EnvVars envVars) {
        envVars.put("VKD3D_FEATURE_LEVEL", config.get("vkd3dLevel"));
    }

    // Method to load versions into the VKD3D version spinner
    private void loadVkd3dVersionSpinner(ContentsManager manager, Spinner spinner) {
        List<VKD3DVersionItem> itemList = new ArrayList<>();

        itemList.add(new VKD3DVersionItem("none", context.getString(R.string.none)));
        // Add predefined versions
        String[] originalItems = context.getResources().getStringArray(R.array.vkd3d_version_entries);
        for (String version : originalItems) {
            itemList.add(new VKD3DVersionItem(version, version)); // Built-ins use their asset identifier.
        }

        // Add installed content profiles
        for (ContentProfile profile : manager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_VKD3D)) {
            if (!manager.isInstalledProfile(profile)) continue;
            String displayName = profile.verName;  // Display name for the spinner
            int versionCode = profile.verCode;     // Unique version code if available
            itemList.add(new VKD3DVersionItem(displayName, versionCode));
        }

        ArrayAdapter<VKD3DVersionItem> adapter = new com.winlator.cmod.widget.ThemedSpinnerAdapter<>(spinner.getContext(), itemList);
        spinner.setAdapter(adapter);
    }

    // Method to set spinner selection by identifier
    static void setSpinnerSelectionByIdentifier(Spinner spinner, String identifier) {
        List<VKD3DVersionItem> items = new ArrayList<>();
        for (int i = 0; i < spinner.getCount(); i++) items.add((VKD3DVersionItem)spinner.getItemAtPosition(i));
        int selected = VKD3DVersionItem.retainSelection(items, identifier);
        if (items.size() != spinner.getCount())
            spinner.setAdapter(new com.winlator.cmod.widget.ThemedSpinnerAdapter<>(spinner.getContext(), items));
        spinner.setSelection(selected, false);
    }
}
