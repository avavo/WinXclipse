package com.winlator.cmod.core;

import android.app.LocaleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.preference.PreferenceManager;

import java.util.Locale;

public final class AppLanguage {
    public static final String PREFERENCE = "app_language";
    private static final String LAST_APPLIED = "app_language_last_applied";

    private AppLanguage() {}

    /** Called before Activity creation, including launches directly from a game shortcut. */
    public static Context attach(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String choice = preferences.getString(PREFERENCE, LanguagePolicy.SYSTEM);
        LocaleList system = Resources.getSystem().getConfiguration().getLocales();
        LocaleManager manager = Build.VERSION.SDK_INT >= 33
                ? context.getSystemService(LocaleManager.class) : null;
        if (manager != null) {
            system = manager.getSystemLocales();
            String observed = manager.getApplicationLocales().toLanguageTags();
            String last = preferences.getString(LAST_APPLIED, "");
            // Honor a language changed outside the app in Android's per-app settings.
            if (!observed.equals(last)) {
                choice = observed.isEmpty() ? LanguagePolicy.SYSTEM : LanguagePolicy.normalize(observed);
                preferences.edit().putString(PREFERENCE, choice).apply();
            }
        }
        String language = LanguagePolicy.resolve(choice,
                system.isEmpty() ? "en" : system.get(0).getLanguage());
        apply(context, language);
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.setLocales(LocaleList.forLanguageTags(language));
        configuration.setLayoutDirection(Locale.forLanguageTag(language));
        return context.createConfigurationContext(configuration);
    }

    /** Preference is committed with the other Settings fields before applying/recreating. */
    public static void applyPreference(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        LocaleManager manager = Build.VERSION.SDK_INT >= 33
                ? context.getSystemService(LocaleManager.class) : null;
        LocaleList system = manager != null ? manager.getSystemLocales()
                : Resources.getSystem().getConfiguration().getLocales();
        apply(context, LanguagePolicy.resolve(preferences.getString(PREFERENCE, LanguagePolicy.SYSTEM),
                system.isEmpty() ? "en" : system.get(0).getLanguage()));
    }

    private static void apply(Context context, String language) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(LAST_APPLIED, language).apply();
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleManager manager = context.getSystemService(LocaleManager.class);
            LocaleList locales = LocaleList.forLanguageTags(language);
            if (manager != null && !manager.getApplicationLocales().equals(locales)) {
                manager.setApplicationLocales(locales);
            }
        } else {
            LocaleListCompat locales = LocaleListCompat.forLanguageTags(language);
            if (!AppCompatDelegate.getApplicationLocales().equals(locales)) {
                AppCompatDelegate.setApplicationLocales(locales);
            }
        }
    }
}
