package com.winlator.cmod.container;

import android.content.Context;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.WineInfo;
import java.io.File;
import org.json.JSONObject;

/** A settings draft, outside ImageFS/home; never a runnable user container. */
public final class ContainerDefaults {
    public static final int EDIT_DEFAULTS = -1;
    private ContainerDefaults() {}

    public static Container load(Context context, boolean create) {
        File directory = new File(context.getFilesDir(), "container-defaults");
        Container defaults = new Container(0);
        defaults.setRootDir(directory);
        try {
            if (defaults.getConfigFile().isFile()) {
                defaults.loadData(new JSONObject(FileUtils.readString(defaults.getConfigFile())));
                return defaults;
            }
            if (!create) return null;
            if (!new File(directory, ".wine").isDirectory() && !new File(directory, ".wine").mkdirs())
                throw new java.io.IOException("Cannot create default settings directory");
            JSONObject factory = new JSONObject();
            factory.put("wineVersion", WineInfo.MAIN_WINE_VERSION.identifier());
            factory.put("dxwrapperConfig", Container.DEFAULT_DXWRAPPERCONFIG);
            factory.put("graphicsDriverConfig", Container.DEFAULT_GRAPHICSDRIVERCONFIG);
            factory.put("emulator", Container.DEFAULT_EMULATOR);
            factory.put("exclusiveXInput", false);
            java.util.Locale locale = java.util.Locale.getDefault();
            factory.put("lc_all", locale.getLanguage() + '_' + locale.getCountry() + ".UTF-8");
            factory.put("extraData", new JSONObject().put("rendererFilterMode", "2"));
            defaults.loadData(factory);
            return defaults;
        } catch (Exception error) {
            android.util.Log.e("ContainerDefaults", "Cannot read container defaults", error);
            return null;
        }
    }
}
