package com.winlator.cmod.box86_64;

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
import java.util.Iterator;
import java.util.Locale;

public abstract class Box86_64PresetManager {
    public static EnvVars getEnvVars(String prefix, Context context, String id) {
        String ucPrefix = prefix.toUpperCase(Locale.ENGLISH);
        EnvVars envVars = new EnvVars();

        if (id.equals(Box86_64Preset.STABILITY)) {
            envVars.put(ucPrefix+"_DYNAREC_SAFEFLAGS", "2");
            envVars.put(ucPrefix+"_DYNAREC_FASTNAN", "0");
            envVars.put(ucPrefix+"_DYNAREC_FASTROUND", "0");
            envVars.put(ucPrefix+"_DYNAREC_X87DOUBLE", "1");
            envVars.put(ucPrefix+"_DYNAREC_BIGBLOCK", "0");
            envVars.put(ucPrefix+"_DYNAREC_STRONGMEM", "2");
            envVars.put(ucPrefix+"_DYNAREC_FORWARD", "128");
            envVars.put(ucPrefix+"_DYNAREC_CALLRET", "0");
            envVars.put(ucPrefix+"_DYNAREC_WAIT", "0");
            if (ucPrefix.equals("BOX64")) {
                envVars.put("BOX64_AVX", "0");
                envVars.put("BOX64_UNITYPLAYER", "1");
                envVars.put("BOX64_MMAP32", "0");
            }
        }
        else if (id.equals(Box86_64Preset.COMPATIBILITY)) {
            envVars.put(ucPrefix+"_DYNAREC_SAFEFLAGS", "2");
            envVars.put(ucPrefix+"_DYNAREC_FASTNAN", "0");
            envVars.put(ucPrefix+"_DYNAREC_FASTROUND", "0");
            envVars.put(ucPrefix+"_DYNAREC_X87DOUBLE", "1");
            envVars.put(ucPrefix+"_DYNAREC_BIGBLOCK", "0");
            envVars.put(ucPrefix+"_DYNAREC_STRONGMEM", "1");
            envVars.put(ucPrefix+"_DYNAREC_FORWARD", "128");
            envVars.put(ucPrefix+"_DYNAREC_CALLRET", "0");
            envVars.put(ucPrefix+"_DYNAREC_WAIT", "1");
            if (ucPrefix.equals("BOX64")) {
                envVars.put("BOX64_AVX", "0");
                envVars.put("BOX64_UNITYPLAYER", "1");
                envVars.put("BOX64_MMAP32", "0");
            }
        }
        else if (id.equals(Box86_64Preset.INTERMEDIATE)) {
            envVars.put(ucPrefix+"_DYNAREC_SAFEFLAGS", "2");
            envVars.put(ucPrefix+"_DYNAREC_FASTNAN", "1");
            envVars.put(ucPrefix+"_DYNAREC_FASTROUND", "0");
            envVars.put(ucPrefix+"_DYNAREC_X87DOUBLE", "1");
            envVars.put(ucPrefix+"_DYNAREC_BIGBLOCK", "1");
            envVars.put(ucPrefix+"_DYNAREC_STRONGMEM", "0");
            envVars.put(ucPrefix+"_DYNAREC_FORWARD", "128");
            envVars.put(ucPrefix+"_DYNAREC_CALLRET", "1");
            envVars.put(ucPrefix+"_DYNAREC_WAIT", "1");
            if (ucPrefix.equals("BOX64")) {
                envVars.put("BOX64_AVX", "0");
                envVars.put("BOX64_UNITYPLAYER", "0");
                envVars.put("BOX64_MMAP32", "1");
            }
        }
        else if (id.equals(Box86_64Preset.PERFORMANCE)) {
            envVars.put(ucPrefix+"_DYNAREC_SAFEFLAGS", "1");
            envVars.put(ucPrefix+"_DYNAREC_FASTNAN", "1");
            envVars.put(ucPrefix+"_DYNAREC_FASTROUND", "1");
            envVars.put(ucPrefix+"_DYNAREC_X87DOUBLE", "0");
            envVars.put(ucPrefix+"_DYNAREC_BIGBLOCK", "3");
            envVars.put(ucPrefix+"_DYNAREC_STRONGMEM", "0");
            envVars.put(ucPrefix+"_DYNAREC_FORWARD", "512");
            envVars.put(ucPrefix+"_DYNAREC_CALLRET", "1");
            envVars.put(ucPrefix+"_DYNAREC_WAIT", "1");
            if (ucPrefix.equals("BOX64")) {
                envVars.put("BOX64_AVX", "0");
                envVars.put("BOX64_UNITYPLAYER", "0");
                envVars.put("BOX64_MMAP32", "1");

            }
        }
        else if (id.startsWith(Box86_64Preset.CUSTOM)) {
            for (String[] preset : customPresetsIterator(prefix, context)) {
                if (preset[0].equals(id)) {
                    envVars.putAll(preset[2]);
                    break;
                }
            }
        }

        return envVars;
    }

    public static ArrayList<Box86_64Preset> getPresets(String prefix, Context context) {
        ArrayList<Box86_64Preset> presets = new ArrayList<>();
        presets.add(new Box86_64Preset(Box86_64Preset.STABILITY, context.getString(R.string.stability)));
        presets.add(new Box86_64Preset(Box86_64Preset.COMPATIBILITY, context.getString(R.string.compatibility)));
        presets.add(new Box86_64Preset(Box86_64Preset.INTERMEDIATE, context.getString(R.string.intermediate)));
        presets.add(new Box86_64Preset(Box86_64Preset.PERFORMANCE, context.getString(R.string.performance)));
        for (String[] preset : customPresetsIterator(prefix, context)) presets.add(new Box86_64Preset(preset[0], preset[1]));
        return presets;
    }

    public static Box86_64Preset getPreset(String prefix, Context context, String id) {
        for (Box86_64Preset preset : getPresets(prefix, context)) if (preset.id.equals(id)) return preset;
        return null;
    }

    private static Iterable<String[]> customPresetsIterator(String prefix, Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        final String customPresetsStr = preferences.getString(prefix+"_custom_presets", "");
        final String[] customPresets = customPresetsStr.split(",");
        final int[] index = {0};
        return () -> new Iterator<String[]>() {
            @Override
            public boolean hasNext() {
                return index[0] < customPresets.length && !customPresetsStr.isEmpty();
            }

            @Override
            public String[] next() {
                return customPresets[index[0]++].split("\\|");
            }
        };
    }

    public static int getNextPresetId(Context context, String prefix) {
        int maxId = 0;
        for (String[] preset : customPresetsIterator(prefix, context)) {
            maxId = Math.max(maxId, Integer.parseInt(preset[0].replace(Box86_64Preset.CUSTOM+"-", "")));
        }
        return maxId+1;
    }

    public static void editPreset(String prefix, Context context, String id, String name, EnvVars envVars) {
        String key = prefix+"_custom_presets";
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String customPresetsStr = preferences.getString(key, "");

        if (id != null) {
            String[] customPresets = customPresetsStr.split(",");
            for (int i = 0; i < customPresets.length; i++) {
                String[] preset = customPresets[i].split("\\|");
                if (preset[0].equals(id)) {
                    customPresets[i] = id+"|"+name+"|"+envVars.toString();
                    break;
                }
            }
            customPresetsStr = String.join(",", customPresets);
        }
        else {
            String preset = Box86_64Preset.CUSTOM+"-"+getNextPresetId(context, prefix)+"|"+name+"|"+envVars.toString();
            customPresetsStr += (!customPresetsStr.isEmpty() ? "," : "")+preset;
        }
        preferences.edit().putString(key, customPresetsStr).apply();
    }

    public static void duplicatePreset(String prefix, Context context, String id) {
        ArrayList<Box86_64Preset> presets = getPresets(prefix, context);
        Box86_64Preset originPreset = null;
        for (Box86_64Preset preset : presets) {
            if (preset.id.equals(id)) {
                originPreset = preset;
                break;
            }
        }
        if (originPreset == null) return;

        String newName;
        for (int i = 1;;i++) {
            newName = originPreset.name+" ("+i+")";
            boolean found = false;
            for (Box86_64Preset preset : presets) {
                if (preset.name.equals(newName)) {
                    found = true;
                    break;
                }
            }
            if (!found) break;
        }

        editPreset(prefix, context, null, newName, getEnvVars(prefix, context, originPreset.id));
    }

    public static void removePreset(String prefix, Context context, String id) {
        String key = prefix+"_custom_presets";
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String oldCustomPresetsStr = preferences.getString(key, "");
        String newCustomPresetsStr = "";

        String[] customPresets = oldCustomPresetsStr.split(",");
        for (int i = 0; i < customPresets.length; i++) {
            String[] preset = customPresets[i].split("\\|");
            if (!preset[0].equals(id)) newCustomPresetsStr += (!newCustomPresetsStr.isEmpty() ? "," : "")+customPresets[i];
        }

        preferences.edit().putString(key, newCustomPresetsStr).apply();
    }

    public static String getExportFileName(String prefix, Context context, String id) {
        Box86_64Preset preset = getPreset(prefix, context, id);
        if (preset == null || !preset.id.startsWith(Box86_64Preset.CUSTOM))
            return prefix + "_preset.wbp";
        String safeName = preset.name.replaceAll("[^A-Za-z0-9._-]+", "_");
        return prefix + "_" + (safeName.isEmpty() ? "preset" : safeName) + ".wbp";
    }

    public static boolean exportPreset(String prefix, Context context, String id,
                                       OutputStream outputStream) {
        Box86_64Preset preset = getPreset(prefix, context, id);
        if (preset == null || !preset.id.startsWith(Box86_64Preset.CUSTOM)
                || outputStream == null) return false;
        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                outputStream, StandardCharsets.UTF_8))) {
            writer.println("Type:" + prefix.toUpperCase(Locale.ENGLISH));
            writer.println("ID:" + preset.id);
            writer.println("Name:" + preset.name);
            writer.println("EnvVars:" + getEnvVars(prefix, context, preset.id));
            return !writer.checkError();
        }
    }

    public static String importPreset(String prefix, Context context, InputStream inputStream)
            throws IOException {
        if (inputStream == null) throw new IOException("Preset file could not be opened");
        String type = null;
        String name = null;
        String envVarsString = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split(":", 2);
                if (fields.length != 2) continue;
                if (fields[0].equalsIgnoreCase("Type")) type = fields[1].trim();
                else if (fields[0].equalsIgnoreCase("Name")) name = fields[1];
                else if (fields[0].equalsIgnoreCase("EnvVars")) envVarsString = fields[1];
            }
        }
        String expectedType = prefix.toUpperCase(Locale.ENGLISH);
        // Type was not written by older WinXclipse/Ludashi .wbp exporters.
        // The BOX64_/BOX86_ key validation below is sufficient to distinguish
        // those legacy presets, while an explicit mismatching Type is rejected.
        if (type != null && !expectedType.equalsIgnoreCase(type))
            throw new IOException("Invalid " + expectedType + " preset");
        return importPreset(prefix, context, name, envVarsString);
    }

    /** Imports already-decoded preset data, used by portable community configs. */
    public static String importPreset(String prefix, Context context, String name,
                                      String envVarsString) throws IOException {
        String expectedType = prefix.toUpperCase(Locale.ENGLISH);
        String cleanName = name == null ? "" : name.replaceAll("[,|\\r\\n]+", "").trim();
        if (cleanName.isEmpty() || !isValidEnvVars(envVarsString, expectedType))
            throw new IOException("Invalid " + expectedType + " preset");
        String newId = Box86_64Preset.CUSTOM + "-" + getNextPresetId(context, prefix);
        editPreset(prefix, context, null, cleanName, new EnvVars(envVarsString));
        return newId;
    }

    private static boolean isValidEnvVars(String value, String expectedPrefix) {
        if (value == null || value.trim().isEmpty()) return false;
        for (String item : value.trim().split(" +")) {
            int separator = item.indexOf('=');
            if (separator <= 0 || separator == item.length() - 1) return false;
            String key = item.substring(0, separator);
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]*")
                    || !key.toUpperCase(Locale.ENGLISH).startsWith(expectedPrefix + "_"))
                return false;
        }
        return true;
    }

    public static void loadSpinner(String prefix, Spinner spinner, String selectedId) {
        Context context = spinner.getContext();
        ArrayList<Box86_64Preset> presets = getPresets(prefix, context);

        int selectedPosition = 0;
        for (int i = 0; i < presets.size(); i++) {
            if (presets.get(i).id.equals(selectedId)) {
                selectedPosition = i;
                break;
            }
        }

        spinner.setAdapter(new com.winlator.cmod.widget.ThemedSpinnerAdapter<>(spinner.getContext(), presets));
        spinner.setSelection(selectedPosition);
    }

    public static String getSpinnerSelectedId(Spinner spinner) {
        SpinnerAdapter adapter = spinner.getAdapter();
        int selectedPosition = spinner.getSelectedItemPosition();
        if (adapter != null && adapter.getCount() > 0 && selectedPosition >= 0) {
            return ((Box86_64Preset)adapter.getItem(selectedPosition)).id;
        }
        else return Box86_64Preset.COMPATIBILITY;
    }
}
