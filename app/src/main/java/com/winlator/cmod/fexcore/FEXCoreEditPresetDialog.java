package com.winlator.cmod.fexcore;

import android.content.Context;
import android.graphics.Color;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.ArrayUtils;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.StringUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Locale;

public class FEXCoreEditPresetDialog extends ContentDialog {
    private final Context context;
    private final FEXCorePreset preset;
    private final boolean readonly;
    private final boolean darkMode;
    private Runnable onConfirmCallback;
    private String savedPresetId;

    public FEXCoreEditPresetDialog(@NonNull Context context, String presetId) {
        super(context, R.layout.box86_64_edit_preset_dialog);
        this.context = context;
        this.preset = presetId != null
                ? FEXCorePresetManager.getPreset(context, presetId) : null;
        this.readonly = preset != null && !preset.isCustom();
        this.darkMode = PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("dark_mode", false);

        setTitle(R.string.fexcore_preset);
        setIcon(R.drawable.icon_env_var);

        TextView label = findViewById(R.id.TVEnvironmentVariables);
        applyFieldSetLabelStyle(label);

        final EditText nameView = findViewById(R.id.ETName);
        nameView.getLayoutParams().width = AppUtils.getPreferredDialogWidth(context);
        nameView.setEnabled(!readonly);
        nameView.setText(preset != null ? preset.name
                : context.getString(R.string.preset) + "-"
                + FEXCorePresetManager.getNextPresetId(context));
        applyEditTextStyle(nameView);
        nameView.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                findViewById(R.id.BTConfirm).setEnabled(!s.toString().trim().isEmpty());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        loadEnvVarsList();

        super.setOnConfirmCallback(() -> {
            if (readonly) {
                if (onConfirmCallback != null) onConfirmCallback.run();
                return;
            }
            String name = nameView.getText().toString().trim();
            if (name.isEmpty()) return;
            savedPresetId = FEXCorePresetManager.editPreset(context,
                    preset != null ? preset.id : null, name, getEnvVars());
            if (onConfirmCallback != null) onConfirmCallback.run();
        });
    }

    @Override
    public void setOnConfirmCallback(Runnable onConfirmCallback) {
        this.onConfirmCallback = onConfirmCallback;
    }

    public String getSavedPresetId() {
        return savedPresetId;
    }

    private EnvVars getEnvVars() {
        // Preserve imported/forward-compatible FEX variables that this version
        // of the editor does not yet render.
        EnvVars envVars = preset != null && preset.isCustom()
                ? FEXCorePresetManager.getEnvVars(context, preset.id)
                : new EnvVars();
        LinearLayout parent = findViewById(R.id.LLContent);
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            String name = ((TextView) child.findViewById(R.id.TextView)).getText().toString();
            Spinner spinner = child.findViewById(R.id.Spinner);
            ToggleButton toggle = child.findViewById(R.id.ToggleButton);
            EditText editText = child.findViewById(R.id.EditText);

            String value;
            if (toggle.getVisibility() == View.VISIBLE) {
                value = toggle.isChecked() ? "1" : "0";
            }
            else if (editText.getVisibility() == View.VISIBLE) {
                value = editText.getText().toString().trim();
                if (value.isEmpty()) value = "0";
            }
            else {
                value = spinner.getSelectedItem().toString();
            }
            envVars.put(name, value);
        }
        return envVars;
    }

    private void loadEnvVarsList() {
        try {
            LinearLayout parent = findViewById(R.id.LLContent);
            LayoutInflater inflater = LayoutInflater.from(context);
            JSONArray data = new JSONArray(FileUtils.readString(context, "fexcore_env_vars.json"));
            EnvVars envVars = preset != null
                    ? FEXCorePresetManager.getEnvVars(context, preset.id) : null;

            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.getJSONObject(i);
                String name = item.getString("name");
                View child = inflater.inflate(R.layout.box86_64_env_var_list_item, parent, false);
                ((TextView) child.findViewById(R.id.TextView)).setText(name);

                View helpButton = child.findViewById(R.id.BTHelp);
                String suffix = name.replaceFirst("^FEX_", "").toLowerCase(Locale.ENGLISH);
                String help = StringUtils.getString(context, "fexcore_env_var_help__" + suffix);
                if (help == null) helpButton.setVisibility(View.INVISIBLE);
                else {
                    String finalHelp = help;
                    helpButton.setOnClickListener(v -> AppUtils.showHelpBox(context, v, finalHelp));
                }

                Spinner spinner = child.findViewById(R.id.Spinner);
                ToggleButton toggle = child.findViewById(R.id.ToggleButton);
                EditText editText = child.findViewById(R.id.EditText);
                String value = envVars != null && envVars.has(name)
                        ? envVars.get(name) : item.getString("defaultValue");

                if (item.optBoolean("toggleSwitch", false)) {
                    toggle.setVisibility(View.VISIBLE);
                    toggle.setEnabled(!readonly);
                    toggle.setChecked("1".equals(value));
                    if (readonly) toggle.setAlpha(0.5f);
                }
                else if (item.optBoolean("editText", false)) {
                    editText.setVisibility(View.VISIBLE);
                    editText.setEnabled(!readonly);
                    editText.setText(value);
                    applyEditTextStyle(editText);
                }
                else {
                    String[] values = ArrayUtils.toStringArray(item.getJSONArray("values"));
                    if (!Arrays.asList(values).contains(value)) {
                        values = Arrays.copyOf(values, values.length + 1);
                        values[values.length - 1] = value;
                    }
                    spinner.setPopupBackgroundResource(darkMode
                            ? R.drawable.content_dialog_background_dark
                            : R.drawable.content_dialog_background);
                    spinner.setVisibility(View.VISIBLE);
                    spinner.setEnabled(!readonly);
                    spinner.setAdapter(new com.winlator.cmod.widget.ThemedSpinnerAdapter<>(spinner.getContext(), values));
                    AppUtils.setSpinnerSelectionFromValue(spinner, value);
                }
                parent.addView(child);
            }
        }
        catch (JSONException ignored) {}
    }

    private void applyFieldSetLabelStyle(TextView textView) {
        if (darkMode) {
            textView.setTextColor(Color.parseColor("#cccccc"));
            textView.setBackgroundResource(R.color.content_dialog_background_dark);
        }
        else {
            textView.setTextColor(Color.parseColor("#bdbdbd"));
            textView.setBackgroundResource(R.color.window_background_color);
        }
    }

    private void applyEditTextStyle(EditText editText) {
        editText.setTextColor(darkMode ? Color.WHITE : Color.BLACK);
        editText.setHintTextColor(Color.GRAY);
        editText.setBackgroundResource(darkMode
                ? R.drawable.edit_text_dark : R.drawable.edit_text);
    }
}
