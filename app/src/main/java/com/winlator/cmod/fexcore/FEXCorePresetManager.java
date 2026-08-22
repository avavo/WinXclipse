package com.winlator.cmod.fexcore;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;
import com.winlator.cmod.core.EnvVars;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class FEXCorePresetManager {
    private static final String CUSTOM_PRESETS_KEY = "fexcore_custom_presets";
    private static final String NEXT_CUSTOM_PRESET_ID_KEY = "fexcore_next_custom_preset_id";
    private static final String COMPATIBILITY_DEFAULT_MIGRATED_KEY =
            "fexcore_compatibility_default_migrated_v086";

    private FEXCorePresetManager() {}

    public static String normalizePresetId(String id) {
        if (id == null || id.trim().isEmpty()) return FEXCorePreset.COMPATIBILITY;
        return id.trim().toUpperCase(Locale.ENGLISH);
    }

    public static String getConfiguredDefault(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String selected = normalizePresetId(preferences.getString(
                "fexcore_preset", FEXCorePreset.COMPATIBILITY));
        if (!preferences.getBoolean(COMPATIBILITY_DEFAULT_MIGRATED_KEY, false)) {
            if (FEXCorePreset.STABILITY.equals(selected)) selected = FEXCorePreset.COMPATIBILITY;
            preferences.edit()
                    .putString("fexcore_preset", selected)
                    .putBoolean(COMPATIBILITY_DEFAULT_MIGRATED_KEY, true)
                    .apply();
        }
        return selected;
    }

    public static EnvVars getEnvVars(Context context, String id) {
        String normalizedId = normalizePresetId(id);
        EnvVars envVars = new EnvVars();

        switch (normalizedId) {
            case FEXCorePreset.STABILITY:
                envVars.put("FEX_TSOENABLED", "1");
                envVars.put("FEX_VECTORTSOENABLED", "1");
                envVars.put("FEX_MEMCPYSETTSOENABLED", "1");
                envVars.put("FEX_HALFBARRIERTSOENABLED", "1");
                envVars.put("FEX_X87REDUCEDPRECISION", "0");
                envVars.put("FEX_MULTIBLOCK", "0");
                break;
            case FEXCorePreset.COMPATIBILITY:
                envVars.put("FEX_TSOENABLED", "1");
                envVars.put("FEX_VECTORTSOENABLED", "1");
                envVars.put("FEX_MEMCPYSETTSOENABLED", "1");
                envVars.put("FEX_HALFBARRIERTSOENABLED", "1");
                envVars.put("FEX_X87REDUCEDPRECISION", "0");
                envVars.put("FEX_MULTIBLOCK", "1");
                break;
            case FEXCorePreset.PERFORMANCE:
                envVars.put("FEX_TSOENABLED", "0");
                envVars.put("FEX_VECTORTSOENABLED", "0");
                envVars.put("FEX_MEMCPYSETTSOENABLED", "0");
                envVars.put("FEX_HALFBARRIERTSOENABLED", "0");
                envVars.put("FEX_X87REDUCEDPRECISION", "1");
                envVars.put("FEX_MULTIBLOCK", "1");
                envVars.put("FEX_DYNAMICL1CACHE", "1");
                envVars.put("FEX_DISABLEL2CACHE", "1");
                break;
            case FEXCorePreset.INTERMEDIATE:
                putIntermediateEnvVars(envVars);
                break;
            default:
                if (normalizedId.startsWith(FEXCorePreset.CUSTOM + "-") && context != null) {
                    for (String[] preset : getCustomPresets(context)) {
                        if (preset[0].equals(normalizedId)) {
                            try {
                                envVars.putAll(preset[2]);
                                return envVars;
                            }
                            catch (RuntimeException ignored) {
                                break;
                            }
                        }
                    }
                }
                putIntermediateEnvVars(envVars);
                break;
        }
        return envVars;
    }

    private static void putIntermediateEnvVars(EnvVars envVars) {
        envVars.put("FEX_TSOENABLED", "1");
        envVars.put("FEX_VECTORTSOENABLED", "0");
        envVars.put("FEX_MEMCPYSETTSOENABLED", "0");
        envVars.put("FEX_HALFBARRIERTSOENABLED", "1");
        envVars.put("FEX_X87REDUCEDPRECISION", "1");
        envVars.put("FEX_MULTIBLOCK", "1");
    }

    /** Kept for source compatibility with older callers. */
    public static void applyPreset(String id, EnvVars envVars) {
        envVars.putAll(getEnvVars(null, id));
    }

    public static ArrayList<FEXCorePreset> getPresets(Context context) {
        ArrayList<FEXCorePreset> presets = new ArrayList<>();
        presets.add(new FEXCorePreset(FEXCorePreset.STABILITY, "Stability"));
        presets.add(new FEXCorePreset(FEXCorePreset.COMPATIBILITY, "Compatibility"));
        presets.add(new FEXCorePreset(FEXCorePreset.INTERMEDIATE, "Intermediate"));
        presets.add(new FEXCorePreset(FEXCorePreset.PERFORMANCE, "Performance"));
        for (String[] preset : getCustomPresets(context)) {
            presets.add(new FEXCorePreset(preset[0], preset[1]));
        }
        return presets;
    }

    public static FEXCorePreset getPreset(Context context, String id) {
        String normalizedId = normalizePresetId(id);
        for (FEXCorePreset preset : getPresets(context)) {
            if (preset.id.equals(normalizedId)) return preset;
        }
        return null;
    }

    private static List<String[]> getCustomPresets(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String encoded = preferences.getString(CUSTOM_PRESETS_KEY, "");
        ArrayList<String[]> result = new ArrayList<>();
        if (encoded == null || encoded.isEmpty()) return result;

        for (String item : encoded.split(",")) {
            String[] fields = item.split("\\|", 3);
            if (fields.length != 3) continue;
            String id = normalizePresetId(fields[0]);
            if (!id.startsWith(FEXCorePreset.CUSTOM + "-") || fields[1].trim().isEmpty()) continue;
            result.add(new String[]{id, fields[1], fields[2]});
        }
        return result;
    }

    private static void saveCustomPresets(Context context, List<String[]> presets) {
        ArrayList<String> encoded = new ArrayList<>();
        for (String[] preset : presets) {
            encoded.add(preset[0] + "|" + preset[1] + "|" + preset[2]);
        }
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit().putString(CUSTOM_PRESETS_KEY, String.join(",", encoded)).apply();
    }

    public static int getNextPresetId(Context context) {
        int maxId = 0;
        for (String[] preset : getCustomPresets(context)) {
            try {
                maxId = Math.max(maxId, Integer.parseInt(
                        preset[0].substring((FEXCorePreset.CUSTOM + "-").length())));
            }
            catch (RuntimeException ignored) {}
        }
        int persistedNextId = PreferenceManager.getDefaultSharedPreferences(context)
                .getInt(NEXT_CUSTOM_PRESET_ID_KEY, 1);
        return Math.max(maxId + 1, persistedNextId);
    }

    public static String editPreset(Context context, String id, String name, EnvVars envVars) {
        String cleanName = sanitizeName(name);
        if (cleanName.isEmpty()) return null;
        String cleanEnvVars = sanitizeEnvVars(envVars != null ? envVars.toString() : "");
        String targetId = id == null
                ? FEXCorePreset.CUSTOM + "-" + getNextPresetId(context)
                : normalizePresetId(id);
        if (!targetId.startsWith(FEXCorePreset.CUSTOM + "-")) return null;

        List<String[]> presets = getCustomPresets(context);
        boolean updated = false;
        for (int i = 0; i < presets.size(); i++) {
            if (presets.get(i)[0].equals(targetId)) {
                presets.set(i, new String[]{targetId, cleanName, cleanEnvVars});
                updated = true;
                break;
            }
        }
        if (!updated) presets.add(new String[]{targetId, cleanName, cleanEnvVars});
        saveCustomPresets(context, presets);
        try {
            int numericId = Integer.parseInt(
                    targetId.substring((FEXCorePreset.CUSTOM + "-").length()));
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
            if (preferences.getInt(NEXT_CUSTOM_PRESET_ID_KEY, 1) <= numericId) {
                preferences.edit().putInt(NEXT_CUSTOM_PRESET_ID_KEY, numericId + 1).apply();
            }
        }
        catch (RuntimeException ignored) {}
        return targetId;
    }

    public static String duplicatePreset(Context context, String id) {
        FEXCorePreset origin = getPreset(context, id);
        if (origin == null) return null;
        ArrayList<FEXCorePreset> presets = getPresets(context);
        String newName;
        int suffix = 1;
        do {
            newName = origin.name + " (" + suffix++ + ")";
        } while (containsName(presets, newName));
        return editPreset(context, null, newName, getEnvVars(context, origin.id));
    }

    private static boolean containsName(List<FEXCorePreset> presets, String name) {
        for (FEXCorePreset preset : presets) if (preset.name.equals(name)) return true;
        return false;
    }

    public static boolean removePreset(Context context, String id) {
        String normalizedId = normalizePresetId(id);
        if (!normalizedId.startsWith(FEXCorePreset.CUSTOM + "-")) return false;
        // Migrate old installs to the monotonic counter before a high ID is
        // removed, otherwise that ID could later be reused by another preset.
        persistNextPresetId(context, getNextPresetId(context));
        List<String[]> presets = getCustomPresets(context);
        boolean removed = false;
        for (int i = presets.size() - 1; i >= 0; i--) {
            if (presets.get(i)[0].equals(normalizedId)) {
                presets.remove(i);
                removed = true;
            }
        }
        if (removed) saveCustomPresets(context, presets);
        return removed;
    }

    private static void persistNextPresetId(Context context, int nextId) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        if (preferences.getInt(NEXT_CUSTOM_PRESET_ID_KEY, 1) < nextId) {
            preferences.edit().putInt(NEXT_CUSTOM_PRESET_ID_KEY, nextId).apply();
        }
    }

    public static String getExportFileName(Context context, String id) {
        FEXCorePreset preset = getPreset(context, id);
        if (preset == null || !preset.isCustom()) return "fexcore_preset.wbp";
        String safeName = preset.name.replaceAll("[^A-Za-z0-9._-]+", "_");
        return "fexcore_" + (safeName.isEmpty() ? "preset" : safeName) + ".wbp";
    }

    public static boolean exportPreset(Context context, String id, OutputStream outputStream) {
        FEXCorePreset preset = getPreset(context, id);
        if (preset == null || !preset.isCustom() || outputStream == null) return false;
        EnvVars envVars = getEnvVars(context, preset.id);
        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8))) {
            writer.println("ID:" + preset.id);
            writer.println("Name:" + preset.name);
            writer.println("EnvVars:" + envVars);
            return !writer.checkError();
        }
    }

    public static String importPreset(Context context, InputStream inputStream) throws IOException {
        if (inputStream == null) throw new IOException("Preset file could not be opened");
        String name = null;
        String envVarsString = null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split(":", 2);
                if (fields.length != 2) continue;
                if (fields[0].equalsIgnoreCase("Name")) name = fields[1];
                else if (fields[0].equalsIgnoreCase("EnvVars")) envVarsString = fields[1];
            }
        }

        String cleanName = sanitizeName(name);
        if (cleanName.isEmpty() || !isValidEnvVars(envVarsString)) {
            throw new IOException("Invalid FEXCore preset");
        }
        return editPreset(context, null, cleanName, new EnvVars(envVarsString));
    }

    private static boolean isValidEnvVars(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        for (String item : value.trim().split(" +")) {
            int separator = item.indexOf('=');
            if (separator <= 0 || separator == item.length() - 1
                    || !item.substring(0, separator).matches("[A-Za-z_][A-Za-z0-9_]*")) {
                return false;
            }
        }
        return true;
    }

    private static String sanitizeName(String name) {
        return name == null ? "" : name.replaceAll("[,|\\r\\n]+", "").trim();
    }

    private static String sanitizeEnvVars(String envVars) {
        return envVars.replace("|", "").replace(",", "")
                .replace('\r', ' ').replace('\n', ' ').trim();
    }

    public static void loadSpinner(Spinner spinner, String selectedId) {
        if (spinner == null) return;
        Context context = spinner.getContext();
        ArrayList<FEXCorePreset> presets = getPresets(context);
        String normalizedId = normalizePresetId(selectedId);
        int selectedPosition = -1;
        int compatibilityPosition = 0;
        for (int i = 0; i < presets.size(); i++) {
            if (presets.get(i).id.equals(FEXCorePreset.COMPATIBILITY)) compatibilityPosition = i;
            if (presets.get(i).id.equals(normalizedId)) selectedPosition = i;
        }
        if (selectedPosition < 0) selectedPosition = compatibilityPosition;
        spinner.setAdapter(new com.winlator.cmod.widget.ThemedSpinnerAdapter<>(
                spinner.getContext(), presets, 18f));
        spinner.setSelection(selectedPosition);
    }

    public static String getSpinnerSelectedId(Spinner spinner) {
        if (spinner == null) return FEXCorePreset.COMPATIBILITY;
        SpinnerAdapter adapter = spinner.getAdapter();
        int position = spinner.getSelectedItemPosition();
        if (adapter != null && position >= 0 && position < adapter.getCount()) {
            Object item = adapter.getItem(position);
            if (item instanceof FEXCorePreset) return ((FEXCorePreset) item).id;
        }
        return FEXCorePreset.COMPATIBILITY;
    }
}
