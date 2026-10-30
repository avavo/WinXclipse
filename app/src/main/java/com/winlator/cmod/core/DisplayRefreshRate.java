package com.winlator.cmod.core;

import android.app.Activity;
import android.os.Build;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

public final class DisplayRefreshRate {
    private DisplayRefreshRate() {}

    public static float request(Activity activity, String preference) {
        try {
            Display display = activity.getWindowManager().getDefaultDisplay();
            if (display == null) return 0;
            Display.Mode current = display.getMode();
            Display.Mode[] available = display.getSupportedModes();
            RefreshRatePolicy.Mode[] modes = new RefreshRatePolicy.Mode[available.length];
            for (int i = 0; i < available.length; i++) modes[i] = mode(available[i]);
            RefreshRatePolicy.Mode chosen = RefreshRatePolicy.choose(mode(current), modes, preference);
            WindowManager.LayoutParams params = activity.getWindow().getAttributes();
            params.preferredDisplayModeId = chosen.id;
            params.preferredRefreshRate = chosen.rate;
            activity.getWindow().setAttributes(params);
            return chosen.rate;
        }
        catch (RuntimeException error) {
            Log.w("DisplayRefreshRate", "Unable to request display mode", error);
            return 0;
        }
    }

    private static RefreshRatePolicy.Mode mode(Display.Mode mode) {
        return new RefreshRatePolicy.Mode(mode.getModeId(), mode.getPhysicalWidth(),
                mode.getPhysicalHeight(), mode.getRefreshRate());
    }

    public static void requestSurface(Surface surface, float rate) {
        if (Build.VERSION.SDK_INT < 30 || surface == null || !surface.isValid() || rate <= 0) return;
        try {
            if (Build.VERSION.SDK_INT >= 31)
                surface.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, Surface.CHANGE_FRAME_RATE_ALWAYS);
            else surface.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
        }
        catch (RuntimeException error) { Log.w("DisplayRefreshRate", "Unable to request surface rate", error); }
    }
}
