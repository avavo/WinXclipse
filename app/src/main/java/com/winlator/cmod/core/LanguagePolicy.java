package com.winlator.cmod.core;

import java.util.Locale;

/** Stable stored values, independent of the translated labels in Settings. */
public final class LanguagePolicy {
    public static final String SYSTEM = "system";
    public static final String[] OPTIONS = {SYSTEM, "en", "pt", "es"};

    private LanguagePolicy() {}

    public static String normalize(String language) {
        if (language == null) return "en";
        String tag = language.replace('_', '-').toLowerCase(Locale.ROOT);
        String primary = tag.split("-", 2)[0];
        return primary.equals("pt") || primary.equals("es") ? primary : "en";
    }

    public static String resolve(String preference, String systemLanguage) {
        return normalize(SYSTEM.equals(preference) || preference == null
                ? systemLanguage : preference);
    }

    public static int indexOf(String preference) {
        for (int i = 0; i < OPTIONS.length; i++) {
            if (OPTIONS[i].equals(preference)) return i;
        }
        return 0;
    }
}
