package com.winlator.cmod.core;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

public final class LauncherIconManager {
    public static final String PREF_USE_ALTERNATIVE_ICON = "use_alternative_app_icon";

    private static final String DEFAULT_ALIAS = "com.winlator.cmod.LauncherDefault";
    private static final String ALTERNATIVE_ALIAS = "com.winlator.cmod.LauncherAlternative";
    private static final String TAG = "LauncherIconManager";

    private LauncherIconManager() {}

    public static boolean setAlternativeIcon(Context context, boolean useAlternative) {
        PackageManager packageManager = context.getPackageManager();
        ComponentName enabled = new ComponentName(context,
                useAlternative ? ALTERNATIVE_ALIAS : DEFAULT_ALIAS);
        ComponentName disabled = new ComponentName(context,
                useAlternative ? DEFAULT_ALIAS : ALTERNATIVE_ALIAS);
        try {
            // Enable the replacement first so the app always retains one
            // launcher entry while the package manager refreshes its cache.
            packageManager.setComponentEnabledSetting(enabled,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
            packageManager.setComponentEnabledSetting(disabled,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            return true;
        }
        catch (RuntimeException error) {
            Log.e(TAG, "Unable to switch launcher icon", error);
            return false;
        }
    }
}
