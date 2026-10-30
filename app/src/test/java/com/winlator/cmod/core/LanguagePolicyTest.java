package com.winlator.cmod.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LanguagePolicyTest {
    @Test public void systemPortugueseIncludesRegionalVariants() {
        assertEquals("pt", LanguagePolicy.resolve("system", "pt-BR"));
        assertEquals("pt", LanguagePolicy.resolve("system", "pt_PT"));
    }

    @Test public void systemSpanishIncludesRegionalVariants() {
        assertEquals("es", LanguagePolicy.resolve("system", "es-MX"));
        assertEquals("es", LanguagePolicy.resolve(null, "es-ES"));
    }

    @Test public void otherSystemLanguagesFallBackToEnglish() {
        assertEquals("en", LanguagePolicy.resolve("system", "zh-CN"));
        assertEquals("en", LanguagePolicy.resolve("system", "fr"));
        assertEquals("en", LanguagePolicy.resolve("system", null));
    }

    @Test public void explicitChoiceOverridesSystem() {
        assertEquals("en", LanguagePolicy.resolve("en", "pt"));
        assertEquals("pt", LanguagePolicy.resolve("pt", "es"));
        assertEquals("es", LanguagePolicy.resolve("es", "en"));
    }

    @Test public void unknownSavedValuesDoNotEnableUnsupportedLanguages() {
        assertEquals("en", LanguagePolicy.resolve("ja", "pt"));
        assertEquals(0, LanguagePolicy.indexOf("unknown"));
        assertEquals(0, LanguagePolicy.indexOf(null));
    }

    @Test public void storedValuesHaveStableIndices() {
        assertEquals(0, LanguagePolicy.indexOf("system"));
        assertEquals(1, LanguagePolicy.indexOf("en"));
        assertEquals(2, LanguagePolicy.indexOf("pt"));
        assertEquals(3, LanguagePolicy.indexOf("es"));
    }
}
