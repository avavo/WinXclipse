package com.winlator.cmod.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class ContentVersionTest {
    @Test public void allPublishedGraphicsPackagesMatchInspectedProfileNames() throws Exception {
        try (java.io.BufferedReader lines = new java.io.BufferedReader(new java.io.InputStreamReader(
                getClass().getResourceAsStream("/graphics-package-identities.tsv"), java.nio.charset.StandardCharsets.UTF_8))) {
            String line; int checked = 0;
            while ((line = lines.readLine()) != null) {
                if (line.startsWith("#") || line.isEmpty()) continue;
                String[] entry = line.split("\t");
                assertTrue(entry[0] + ": " + entry[2] + " != " + entry[3], ContentVersion.samePackageName(entry[1], entry[2], entry[3]));
                checked++;
            }
            assertEquals(32, checked);
        }
    }
    @Test public void graphicsPackagesMatchTheirRealEmbeddedManifests() {
        assertTrue(ContentVersion.samePackageName("DXVK", "v3.1", "dxvk-3.1"));
        assertTrue(ContentVersion.samePackageName("VKD3D", "3.1-arm64ec", "vkd3d-proton-arm64ec-3.1"));
        assertTrue(ContentVersion.samePackageName("DXVK", "v3.1.1-gplasync-arm64ec", "dxvk-gplasync-arm64ec-3.1.1-1"));
        assertTrue(ContentVersion.samePackageName("DXVK", "1.7.2-async", "dxvk-async-1.7.2"));
        assertFalse(ContentVersion.samePackageName("DXVK", "3.1-arm64ec", "dxvk-3.1"));
        assertFalse(ContentVersion.samePackageName("DXVK", "3.1-async", "dxvk-gplasync-3.1"));
        assertFalse(ContentVersion.samePackageName("VKD3D", "3.0b-71095c7b", "3.0b-12345678"));
        assertFalse(ContentVersion.samePackageName("DXVK", "3.1", "dxvk-3.1.1"));
        assertFalse(ContentVersion.samePackageName("Wine", "10-arm64ec", "10-arm64"));
    }
    @Test public void reopeningASelectorKeepsTheImportedBuildWhenNamesAreDuplicated() {
        java.util.List<String> rows = new java.util.ArrayList<>(java.util.Arrays.asList("2.6.1", "2.6.1-41", "2.6.1-42"));
        assertEquals(2, ContentVersion.retainSelection(rows, "2.6.1-42"));
        String saved = rows.get(2);
        assertEquals(2, ContentVersion.retainSelection(rows, saved));
        assertEquals(3, rows.size());
    }

    @Test public void aRemovedPackageDoesNotTurnIntoTheFirstDefaultWhenEditing() {
        java.util.List<String> rows = new java.util.ArrayList<>(java.util.Arrays.asList("2610", "2610-2246"));
        int selected = ContentVersion.retainSelection(rows, "2608-2245");
        assertEquals("2608-2245", rows.get(selected));
        assertEquals(selected, ContentVersion.retainSelection(rows, "2608-2245"));
        assertEquals(3, rows.size());
    }

    @Test public void legacyWineIdentifiersKeepTheirExactSpellingAndZeroBuildSuffix() {
        java.util.List<String> rows = new java.util.ArrayList<>(java.util.Arrays.asList("proton-9.0-arm64ec", "Proton-10.0-arm64-18"));
        assertEquals("Proton-9.0-arm64ec-0", rows.get(ContentVersion.retainSelection(rows, "Proton-9.0-arm64ec-0")));
        assertEquals("Proton-10.0-arm64-18", rows.get(ContentVersion.retainSelection(rows, "Proton-10.0-arm64-18")));
    }

    @Test public void acceptsCaseAndSeparatorAliases() {
        assertTrue(ContentVersion.matches("10.0-4-arm64ec", 0,
                "Proton_10.0-4-arm64ec.wcp", "", 0, false));
    }

    @Test public void doesNotRemoveRealNumericReleaseComponents() {
        assertFalse(ContentVersion.matches("10.0-4", 0, "10.0-5", "", 0, false));
        assertNotEquals(ContentVersion.canonical("DXVK-2.6.1"), ContentVersion.canonical("DXVK-2.6.2"));
    }

    @Test public void comparesEntryCodeOnlyAgainstTheActualManifest() {
        assertTrue(ContentVersion.matches("2610", 2246, "", "FEXCore-2610-2246", null, false));
        assertFalse(ContentVersion.matches("2610", 2246, "", "FEXCore-2610-2245", null, false));
    }

    @Test public void installedManifestCodeMustMatch() {
        assertFalse(ContentVersion.matches("10.0-arm64ec", 12, "10.0-arm64ec", "", 11, false));
    }

    @Test public void remoteAssetCodeIsRecheckedAgainstDownloadedManifest() {
        assertTrue(ContentVersion.matches("10.0-arm64ec", 1234567, "10.0-arm64ec", "", 11, true));
        assertFalse(ContentVersion.matches("10.0-arm64ec", 12, "10.0-arm64ec", "", 11, false));
    }

    @Test public void distinguishesArm64FromArm64ecAndX86() {
        assertEquals("arm64", ContentVersion.architecture("Proton-10-aarch64"));
        assertEquals("arm64", ContentVersion.architecture("Proton-10-arm64"));
        assertEquals("arm64ec", ContentVersion.architecture("Proton-10-arm64ec"));
        assertEquals("x86_64", ContentVersion.architecture("wine-9.20-x86_64"));
        assertEquals("x86", ContentVersion.architecture("wine-9.20-x86"));
        assertFalse(ContentVersion.matches("10-arm64", 0, "10-arm64ec", "", 0, true));
    }

    @Test public void missingIdentityDoesNotMatchAnArbitraryPackage() {
        assertFalse(ContentVersion.matches("2610", 2246, "", "", null, true));
        assertFalse(ContentVersion.matches("", 0, "", "", null, true));
    }

    @Test public void acceptsEquivalentAbiNamesWithoutConfusingArm64ec() {
        assertTrue(ContentVersion.matches("10.0-aarch64", 12, "10.0-arm64", "", 12, false));
        assertTrue(ContentVersion.matches("10.0-amd64", 12, "10.0-x86_64", "", 12, false));
        assertFalse(ContentVersion.matches("10.0-aarch64", 12, "10.0-arm64ec", "", 12, false));
    }

    @Test public void rawArchiveSuffixesAreNotPartOfTheVersion() {
        assertEquals(ContentVersion.canonical("Proton-10-arm64"), ContentVersion.canonical("Proton-10-arm64.txz"));
        assertEquals(ContentVersion.canonical("Proton-10-arm64"), ContentVersion.canonical("Proton-10-arm64.tar.xz"));
        assertEquals(ContentVersion.canonical("Proton-10-arm64"), ContentVersion.canonical("Proton-10-arm64.tzst"));
    }

    @Test public void matchingIdDoesNotOverrideAContradictoryVersionBuildOrAbi() {
        assertFalse(ContentVersion.matchesReference("same-id", "10-arm64ec", 12,
                "same-id", "11-arm64ec", "", 12, false));
        assertFalse(ContentVersion.matchesReference("same-id", "10-arm64ec", 12,
                "same-id", "10-arm64", "", 12, false));
        assertFalse(ContentVersion.matchesReference("same-id", "10-arm64ec", 12,
                "same-id", "10-arm64ec", "", 11, false));
    }

    @Test public void idOnlyLegacyReferencesStillWorkWithoutMatchingArbitraryPackages() {
        assertTrue(ContentVersion.matchesReference("stable", "2610", 2246, "stable", "", "", null, false));
        assertFalse(ContentVersion.matchesReference("different", "2610", 2246, "stable", "", "", null, false));
        assertFalse(ContentVersion.matchesReference("", "2610", 2246, "", "", "", null, false));
    }

    @Test public void cpuArchitectureTokensDoNotMatchBox64OrUnrelatedText() {
        assertEquals("", ContentVersion.architecture("Box64-0.4.5"));
        assertEquals("", ContentVersion.architecture("custom-arm64ecpatch-not-an-abi"));
        assertEquals("arm64ec", ContentVersion.architecture("Proton-10-ARM64_EC"));
        assertEquals(ContentVersion.canonical("10-arm64ec"), ContentVersion.canonical("10-ARM64_EC"));
    }
}
