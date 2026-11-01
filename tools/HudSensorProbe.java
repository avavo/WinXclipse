import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Standalone app_process probe, run under the app UID; never edits settings. */
public final class HudSensorProbe {
    public static void main(String[] args) throws Exception {
        android.os.Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context system = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        Context context = system.createPackageContext("com.win.xclipse", 0);
        BatteryManager battery = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        for (int property : new int[]{BatteryManager.BATTERY_PROPERTY_CURRENT_NOW,
                BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE, BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER})
            System.out.println("batteryProperty[" + property + "]=" + battery.getLongProperty(property));
        long current = com.winlator.cmod.core.TelemetryMath.batteryCurrentMicroamps(
                battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
                android.os.Build.MANUFACTURER, android.os.Build.MODEL);
        System.out.println("normalizedCurrentMicroamps=" + current);
        Intent intent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (intent != null) for (String key : new String[]{"voltage", "current_now", "current_avg", "temperature"})
            System.out.println("batteryExtra[" + key + "]=" + (intent.hasExtra(key) ? intent.getExtras().get(key) : "missing"));
        for (String path : new String[]{"/sys/kernel/gpu/gpu_tmu",
                "/sys/devices/virtual/sec/exynos_tmu/curr_temp",
                "/sys/devices/virtual/sec/sec-ap-thermistor/temperature"}) {
            try { System.out.println(path + "=" + new String(Files.readAllBytes(Paths.get(path))).trim()); }
            catch (Exception denied) { System.out.println(path + "=" + denied.getClass().getSimpleName()); }
        }
    }
}
