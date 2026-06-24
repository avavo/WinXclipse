package com.winlator.cmod.fexcore;

import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import com.winlator.cmod.R;
import com.winlator.cmod.core.EnvVars;

import java.util.ArrayList;

public class FEXCorePresetManager {
    public static void applyPreset(String id, EnvVars envVars) {
        if (id == null || id.isEmpty()) {
            id = FEXCorePreset.INTERMEDIATE;
        }

        if (id.equals(FEXCorePreset.STABILITY)) {
            envVars.put("FEX_TSOENABLED", "1");
            envVars.put("FEX_VECTORTSOENABLED", "1");
            envVars.put("FEX_MEMCPYSETTSOENABLED", "1");
            envVars.put("FEX_HALFBARRIERTSOENABLED", "1");
            envVars.put("FEX_X87REDUCEDPRECISION", "0");
            envVars.put("FEX_MULTIBLOCK", "0");
        }
        else if (id.equals(FEXCorePreset.COMPATIBILITY)) {
            envVars.put("FEX_TSOENABLED", "1");
            envVars.put("FEX_VECTORTSOENABLED", "1");
            envVars.put("FEX_MEMCPYSETTSOENABLED", "1");
            envVars.put("FEX_HALFBARRIERTSOENABLED", "1");
            envVars.put("FEX_X87REDUCEDPRECISION", "0");
            envVars.put("FEX_MULTIBLOCK", "1");
        }
        else if (id.equals(FEXCorePreset.INTERMEDIATE)) {
            envVars.put("FEX_TSOENABLED", "1");
            envVars.put("FEX_VECTORTSOENABLED", "0");
            envVars.put("FEX_MEMCPYSETTSOENABLED", "0");
            envVars.put("FEX_HALFBARRIERTSOENABLED", "1");
            envVars.put("FEX_X87REDUCEDPRECISION", "1");
            envVars.put("FEX_MULTIBLOCK", "1");
        }
        else if (id.equals(FEXCorePreset.PERFORMANCE)) {
            envVars.put("FEX_TSOENABLED", "0");
            envVars.put("FEX_VECTORTSOENABLED", "0");
            envVars.put("FEX_MEMCPYSETTSOENABLED", "0");
            envVars.put("FEX_HALFBARRIERTSOENABLED", "0");
            envVars.put("FEX_X87REDUCEDPRECISION", "1");
            envVars.put("FEX_MULTIBLOCK", "1");
        }
        else {
            applyPreset(FEXCorePreset.INTERMEDIATE, envVars);
        }
    }

    public static ArrayList<FEXCorePreset> getPresets(Context context) {
        ArrayList<FEXCorePreset> presets = new ArrayList<>();
        presets.add(new FEXCorePreset(FEXCorePreset.STABILITY, context.getString(R.string.stability)));
        presets.add(new FEXCorePreset(FEXCorePreset.COMPATIBILITY, context.getString(R.string.compatibility)));
        presets.add(new FEXCorePreset(FEXCorePreset.INTERMEDIATE, context.getString(R.string.intermediate)));
        presets.add(new FEXCorePreset(FEXCorePreset.PERFORMANCE, context.getString(R.string.performance)));
        return presets;
    }

    public static void loadSpinner(Spinner spinner, String selectedId) {
        if (spinner == null) return;

        Context context = spinner.getContext();
        ArrayList<FEXCorePreset> presets = getPresets(context);

        if (selectedId == null || selectedId.isEmpty()) {
            selectedId = FEXCorePreset.INTERMEDIATE;
        }

        ArrayAdapter<FEXCorePreset> adapter = new ArrayAdapter<>(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                presets
        );
        spinner.setAdapter(adapter);

        int selectedIndex = 0;
        for (int i = 0; i < presets.size(); i++) {
            if (presets.get(i).id.equals(selectedId)) {
                selectedIndex = i;
                break;
            }
        }

        spinner.setSelection(selectedIndex);
    }

    public static String getSpinnerSelectedId(Spinner spinner) {
        if (spinner == null) return FEXCorePreset.INTERMEDIATE;

        Object item = spinner.getSelectedItem();
        if (item instanceof FEXCorePreset) {
            String id = ((FEXCorePreset)item).id;
            return id != null && !id.isEmpty() ? id : FEXCorePreset.INTERMEDIATE;
        }

        return FEXCorePreset.INTERMEDIATE;
    }
}
