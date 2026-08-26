package com.winlator.cmod.contentdialog;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;
import com.winlator.cmod.XServerDisplayActivity;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.KeyValueSet;
import com.winlator.cmod.renderer.GLRenderer;
import com.winlator.cmod.renderer.effects.ColorEffect;
import com.winlator.cmod.renderer.effects.CRTEffect;
import com.winlator.cmod.renderer.effects.FXAAEffect;
import com.winlator.cmod.renderer.effects.NTSCCombinedEffect;
import com.winlator.cmod.renderer.effects.ToonEffect;
import com.winlator.cmod.widget.SeekBar;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

public class ScreenEffectDialog extends ContentDialog {

    private final XServerDisplayActivity activity;
    private final SharedPreferences preferences;
    private final Spinner sProfile;
    private final SeekBar sbBrightness;
    private final SeekBar sbContrast;
    private final SeekBar sbGamma;

    private static final String TAG = "ScreenEffectDialog";


    public ScreenEffectDialog(XServerDisplayActivity activity) {
        super(activity, R.layout.screen_effect_dialog);
        this.activity = activity;

        preferences = PreferenceManager.getDefaultSharedPreferences(activity);

        boolean isDarkMode = AppUtils.isDarkMode(activity);

        TextView lblColorAdjustment = findViewById(R.id.LBLColorAdjustment);
        applyFieldSetLabelStyle(lblColorAdjustment, isDarkMode);

        sProfile = findViewById(R.id.SProfile);
        sbBrightness = findViewById(R.id.SBBrightness);
        sbContrast = findViewById(R.id.SBContrast);
        sbGamma = findViewById(R.id.SBGamma);

        GLRenderer renderer = activity.getXServerView().getRenderer();
        if (renderer == null) {
            Log.e(TAG, "Renderer is null in ScreenEffectDialog initialization!");
            return;
        }

        ColorEffect colorEffect = (ColorEffect) renderer.getEffectComposer().getEffect(ColorEffect.class);

        Log.d(TAG, "ScreenEffectDialog (Color Custom) initialized");

        if (colorEffect != null) {
            Log.d(TAG, "ColorEffect found");
            sbBrightness.setValue(colorEffect.getBrightness() * 100);
            sbContrast.setValue(colorEffect.getContrast() * 100);
            sbGamma.setValue(colorEffect.getGamma());
        } else {
            Log.d(TAG, "ColorEffect not found, resetting settings");
            resetSettings();
        }

        loadProfileSpinner(sProfile, activity.getScreenEffectProfile());

        sProfile.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position > 0) {
                    loadProfile(sProfile.getSelectedItem().toString());
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        Button resetButton = findViewById(R.id.BTReset);
        resetButton.setVisibility(View.VISIBLE);
        resetButton.setOnClickListener(v -> resetSettings());

        findViewById(R.id.BTConfirm).setOnClickListener(v -> {
            Log.d(TAG, "BTConfirm clicked. Preparing to save profile and apply effects.");
            saveProfile(sProfile);
            Log.d(TAG, "Profile saved.");
            Log.d(TAG, "Calling applyEffects() directly.");
            applyEffects(colorEffect, renderer);
            Log.d(TAG, "Effects applied. Dismissing dialog.");
            dismiss();
            Log.d(TAG, "Dialog dismissed.");
        });

        findViewById(R.id.BTAddProfile).setOnClickListener(v -> promptAddProfile());
        findViewById(R.id.BTRemoveProfile).setOnClickListener(v -> promptDeleteProfile());

        setOnConfirmCallback(() -> {
            Log.d(TAG, "OnConfirm callback triggered. Applying effects.");
            applyEffects(colorEffect, renderer);
            Log.d(TAG, "Effects applied from callback.");
            dismiss();
            Log.d(TAG, "Dialog dismissed after callback.");
        });

    }

    private static void applyFieldSetLabelStyle(TextView textView, boolean isDarkMode) {
//        Context context = textView.getContext();

        if (isDarkMode) {
            // Apply dark mode-specific attributes
            textView.setTextColor(Color.parseColor("#cccccc")); // Set text color to #cccccc
            textView.setBackgroundResource(R.color.window_background_color_dark); // Set dark background color
        } else {
            // Apply light mode-specific attributes (original FieldSetLabel)
            textView.setTextColor(Color.parseColor("#bdbdbd")); // Set text color to #bdbdbd
            textView.setBackgroundResource(R.color.window_background_color); // Set light background color
        }
    }

    private void promptAddProfile() {
        ContentDialog.prompt(activity, R.string.do_you_want_to_add_a_new_profile, null, name -> addProfile(name, sProfile));
    }

    private void promptDeleteProfile() {
        if (sProfile.getSelectedItemPosition() > 0) {
            String selectedProfile = sProfile.getSelectedItem().toString();
            ContentDialog.confirm(activity, R.string.do_you_want_to_remove_this_profile, () -> removeProfile(selectedProfile, sProfile));
        } else {
            AppUtils.showToast(activity, R.string.no_profile_selected);
        }
    }

    private void addProfile(String newName, Spinner sProfile) {
        Set<String> profiles = new LinkedHashSet<>(preferences.getStringSet("screen_effect_profiles", new LinkedHashSet<>()));
        for (String profile : profiles) {
            String[] parts = profile.split(":");
            if (parts[0].equals(newName)) {
                return;
            }
        }
        profiles.add(newName + ":");
        preferences.edit().putStringSet("screen_effect_profiles", profiles).apply();
        loadProfileSpinner(sProfile, newName);
    }

    private void loadProfileSpinner(Spinner sProfile, String selectedName) {
        Set<String> profiles = new LinkedHashSet<>(preferences.getStringSet("screen_effect_profiles", new LinkedHashSet<>()));
        ArrayList<String> items = new ArrayList<>();
        items.add("-- " + activity.getString(R.string.default_profile) + " --");
        int selectedPosition = 0, position = 1;
        for (String profile : profiles) {
            String[] parts = profile.split(":");
            items.add(parts[0]);
            if (parts[0].equals(selectedName)) {
                selectedPosition = position;
            }
            position++;
        }
        sProfile.setAdapter(new com.winlator.cmod.widget.ThemedSpinnerAdapter<>(sProfile.getContext(), items));
        sProfile.setSelection(selectedPosition);
    }

    private void loadProfile(String name) {
        Set<String> profiles = new LinkedHashSet<>(preferences.getStringSet("screen_effect_profiles", new LinkedHashSet<>()));
        for (String profile : profiles) {
            String[] parts = profile.split(":");
            if (parts[0].equals(name) && parts.length > 1 && !parts[1].isEmpty()) {
                KeyValueSet settings = new KeyValueSet(parts[1]);
                sbBrightness.setValue(settings.getFloat("brightness", 0));
                sbContrast.setValue(settings.getFloat("contrast", 1.0f));
                sbGamma.setValue(settings.getFloat("gamma", 1.0f));
                return;
            }
        }
    }

    private void removeProfile(String targetName, Spinner sProfile) {
        Set<String> profiles = new LinkedHashSet<>(preferences.getStringSet("screen_effect_profiles", new LinkedHashSet<>()));
        profiles.removeIf(profile -> profile.split(":")[0].equals(targetName));
        preferences.edit().putStringSet("screen_effect_profiles", profiles).apply();
        loadProfileSpinner(sProfile, null);
        resetSettings();
    }

    private void resetSettings() {
        sbBrightness.setValue(0);
        sbContrast.setValue(0);
        sbGamma.setValue(1.0f);
    }

    private void saveProfile(Spinner sProfile) {
        if (sProfile.getSelectedItemPosition() > 0) {
            String selectedProfile = sProfile.getSelectedItem().toString();
            Set<String> oldProfiles = new LinkedHashSet<>(preferences.getStringSet("screen_effect_profiles", new LinkedHashSet<>()));
            Set<String> newProfiles = new LinkedHashSet<>();
            KeyValueSet settings = new KeyValueSet();
            settings.put("brightness", sbBrightness.getValue());
            settings.put("contrast", sbContrast.getValue());
            settings.put("gamma", sbGamma.getValue());

            for (String profile : oldProfiles) {
                String[] parts = profile.split(":");
                if (parts[0].equals(selectedProfile)) {
                    newProfiles.add(selectedProfile + ":" + settings.toString());
                } else {
                    newProfiles.add(profile);
                }
            }
            preferences.edit().putStringSet("screen_effect_profiles", newProfiles).apply();
            activity.setScreenEffectProfile(selectedProfile);
        }
    }

    public void applyEffects(ColorEffect colorEffect, GLRenderer renderer) {
        Log.d(TAG, "applyEffects() called - Color Custom only");

        float brightness = sbBrightness.getValue();
        float contrast = sbContrast.getValue();
        float gamma = sbGamma.getValue();

        Log.d(TAG, "Settings - Brightness: " + brightness + ", Contrast: " + contrast + ", Gamma: " + gamma);

        // Check ColorEffect state
        if (colorEffect == null) {
            Log.d(TAG, "ColorEffect is null, creating new instance.");
            colorEffect = new ColorEffect();
        }

        // Check if renderer and effect composer are non-null
        if (renderer == null) {
            Log.e(TAG, "Renderer is null!");
            return;
        }

        if (renderer.getEffectComposer() == null) {
            Log.e(TAG, "EffectComposer is null!");
            return;
        }

        // Apply or remove ColorEffect (Color Custom only - FXAA/CRT/Toon/NTSC now in sidebar Display)
        if (brightness == 0 && contrast == 0 && gamma == 1.0f) {
            Log.d(TAG, "No adjustments are applied. Removing ColorEffect if it exists.");
            renderer.getEffectComposer().removeEffect(colorEffect);
        } else {
            Log.d(TAG, "Applying ColorEffect adjustments.");
            colorEffect.setBrightness(brightness / 100f);
            colorEffect.setContrast(contrast / 100f);
            colorEffect.setGamma(gamma);
            renderer.getEffectComposer().addEffect(colorEffect);
            Log.d(TAG, "ColorEffect added/updated.");
        }

        saveProfile(sProfile);
        Log.d(TAG, "Profile saved after applying effects.");
    }

    public void setOnConfirmCallback(Runnable confirmCallback) {
        Log.d(TAG, "Setting OnConfirm callback.");
        this.onConfirmCallback = confirmCallback;
    }


}
