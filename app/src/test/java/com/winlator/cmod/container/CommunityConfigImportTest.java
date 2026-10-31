package com.winlator.cmod.container;

import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.ContentVersion;
import com.winlator.cmod.core.DefaultVersion;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class CommunityConfigImportTest {
    private static JSONObject manifest() throws Exception {
        return new JSONObject().put("container", new JSONObject()
                .put("id", 99).put("name", "My game").put("wineVersion", "proton-9.0-arm64ec")
                .put("graphicsDriver", "wrapper-default")
                .put("dxwrapperConfig", "version=" + DefaultVersion.DXVK + ",vkd3dVersion=none,ramFix=1,framerate=0")
                .put("graphicsDriverConfig", "version=System;vulkanVersion=1.4;blacklistedExtensions=badExt"));
    }

    private static ContentProfile profile(ContentProfile.ContentType type, String name, int code) {
        ContentProfile p = new ContentProfile(); p.type = type; p.verName = name; p.verCode = code; return p;
    }

    private static JSONObject ref(String role, ContentProfile p) throws Exception {
        return new JSONObject().put("role", role).put("type", p.type.toString())
                .put("versionName", p.verName).put("versionCode", p.verCode)
                .put("contentId", p.getContentId()).put("entryName", ContentsManager.getEntryName(p));
    }

    private static CommunityConfigManager.ContentResolution resolution(String role, ContentProfile p) throws Exception {
        return new CommunityConfigManager.ContentResolution(ref(role, p), p, null, -1);
    }

    @Test public void exactBuildsAndAbiSurviveApplicationAndJsonPersistence() throws Exception {
        JSONObject source = manifest();
        ContentProfile wine = profile(ContentProfile.ContentType.CONTENT_TYPE_PROTON, "10.0-arm64", 18);
        ContentProfile box = profile(ContentProfile.ContentType.CONTENT_TYPE_BOX64, "0.4.5", 7);
        ContentProfile fex = profile(ContentProfile.ContentType.CONTENT_TYPE_FEXCORE, "2610", 2246);
        ContentProfile dxvk = profile(ContentProfile.ContentType.CONTENT_TYPE_DXVK, "2.6.1", 42);
        ContentProfile vkd3d = profile(ContentProfile.ContentType.CONTENT_TYPE_VKD3D, "2.14", 33);
        List<ContentProfile> chosen = Arrays.asList(wine, box, fex, dxvk, vkd3d);
        List<CommunityConfigManager.ContentResolution> resolutions = Arrays.asList(
                resolution("wineRuntime", wine), resolution("box64", box), resolution("fexcore", fex),
                resolution("dxvk", dxvk), resolution("vkd3d", vkd3d));
        JSONObject imported = new JSONObject(CommunityConfigManager.applyResolution(null, source, resolutions, chosen).toString());
        assertEquals("Proton-10.0-arm64-18", imported.getString("wineVersion"));
        assertEquals("0.4.5-7", imported.getString("box64Version"));
        assertEquals("2610-2246", imported.getString("fexcoreVersion"));
        assertEquals("2.6.1-42", CommunityConfigManager.importSelection(imported, "dxvk"));
        assertEquals("2.14-33", CommunityConfigManager.importSelection(imported, "vkd3d"));
        assertTrue(imported.getString("dxwrapperConfig").contains("ramFix=1"));
        assertFalse(imported.has("id"));
        assertEquals("proton-9.0-arm64ec", source.getJSONObject("container").getString("wineVersion"));
        List<CommunityConfigManager.DependencyReview> review = CommunityConfigManager.reviewDependencies(source, resolutions, chosen);
        assertEquals(imported.getString("wineVersion"), review.get(0).selected);
        assertEquals("2.6.1-42", review.get(3).selected);
        assertTrue(review.stream().noneMatch(row -> row.substituted || row.missing));
    }

    @Test public void explicitFallbacksAppearInTheFinalReviewAndPreserveUnrelatedOptions() throws Exception {
        JSONObject source = manifest();
        source.getJSONObject("container").put("graphicsDriver", "wrapper-custom")
                .put("graphicsDriverConfig", "version=quAsar 67;vulkanVersion=1.4;blacklistedExtensions=badExt");
        ContentProfile missing = profile(ContentProfile.ContentType.CONTENT_TYPE_DXVK, "2.7", 4);
        List<CommunityConfigManager.ContentResolution> resolved = Arrays.asList(
                new CommunityConfigManager.ContentResolution(ref("dxvk", missing), null, null, -1),
                new CommunityConfigManager.ContentResolution(new JSONObject().put("role", "graphicsDriver").put("entryName", "quAsar 67"), null, null, -1),
                new CommunityConfigManager.ContentResolution(new JSONObject().put("role", "wrapper").put("entryName", "wrapper-custom"), null, null, -1));
        List<ContentProfile> chosen = Arrays.asList(null, null, null);
        List<CommunityConfigManager.DependencyReview> before = CommunityConfigManager.reviewDependencies(source, resolved, null);
        assertEquals(3, before.stream().filter(row -> row.missing).count());
        List<CommunityConfigManager.DependencyReview> after = CommunityConfigManager.reviewDependencies(source, resolved, chosen);
        assertEquals(3, after.stream().filter(row -> row.substituted).count());
        assertFalse(after.get(3).missing);
        assertEquals(DefaultVersion.DXVK, after.get(3).selected);
        assertEquals("System", after.get(5).selected);
        JSONObject imported = CommunityConfigManager.applyResolution(null, source, resolved, chosen);
        assertEquals("wrapper-default", imported.getString("graphicsDriver"));
        assertEquals("version=System;vulkanVersion=1.4;blacklistedExtensions=badExt", imported.getString("graphicsDriverConfig"));
    }

    @Test public void sameNameWithPinnedBuildIsNeverAssumedToBeTheBundledPackage() throws Exception {
        JSONObject reference = new JSONObject().put("role", "dxvk").put("entryName", DefaultVersion.DXVK);
        assertTrue(CommunityConfigManager.isBundledReference(null, reference));
        reference.put("versionCode", 81);
        assertFalse(CommunityConfigManager.isBundledReference(null, reference));
        reference.remove("versionCode"); reference.put("contentId", "custom-build");
        assertFalse(CommunityConfigManager.isBundledReference(null, reference));
        reference.put("contentId", JSONObject.NULL).put("versionCode", JSONObject.NULL);
        assertTrue(CommunityConfigManager.isBundledReference(null, reference));
        JSONObject idOnly = new JSONObject().put("role", "dxvk").put("contentId", "stable-pinned-id");
        assertFalse(CommunityConfigManager.isDisabledReference(idOnly));
        assertFalse(CommunityConfigManager.isBundledReference(null, idOnly));
        assertTrue(CommunityConfigManager.isDisabledReference(new JSONObject().put("entryName", "none")));
    }

    @Test public void legacySelectionsWithoutRefsAreResolvedIncludingTheirBuildSuffix() throws Exception {
        JSONObject source = manifest();
        source.getJSONObject("container").put("wineVersion", "Proton-10.0-arm64-18")
                .put("fexcoreVersion", "2610-2246")
                .put("dxwrapperConfig", "version=2.6.1-42,vkd3dVersion=none,ramFix=1");
        JSONArray refs = CommunityConfigManager.importReferences(null, source);
        assertEquals(3, refs.length());
        assertEquals("Proton", refs.getJSONObject(0).getString("type"));
        JSONObject dxvk = refs.getJSONObject(2);
        assertEquals("dxvk", dxvk.getString("role"));
        assertEquals("", dxvk.getString("versionName"));
        assertTrue(ContentVersion.matches("2.6.1", 42, dxvk.getString("versionName"), dxvk.getString("entryName"), null, false));
        assertFalse(ContentVersion.matches("2.6.1", 43, "", dxvk.getString("entryName"), null, false));
        assertFalse(source.has("contents"));
        source.put("contents", new JSONArray().put(new JSONObject().put("role", "wineRuntime")
                .put("type", "").put("entryName", "Wine-10.0-arm64-18")));
        JSONArray recovered = CommunityConfigManager.importReferences(null, source);
        assertEquals("Wine", recovered.getJSONObject(0).getString("type"));
        assertEquals("", source.getJSONArray("contents").getJSONObject(0).getString("type"));
    }

    @Test public void duplicateAndUnknownRolesStopBeforeAnyImportSideEffects() throws Exception {
        JSONObject source = manifest();
        JSONObject wine = new JSONObject().put("role", "wineRuntime").put("entryName", "10-arm64");
        source.put("contents", new JSONArray().put(wine).put(new JSONObject(wine.toString())));
        assertThrows(JSONException.class, () -> CommunityConfigManager.importReferences(null, source));
        source.put("contents", new JSONArray().put(new JSONObject().put("role", "unknown-future-role")));
        assertThrows(JSONException.class, () -> CommunityConfigManager.importReferences(null, source));
        source.put("contents", new JSONArray().put(new JSONObject().put("role", "dxvk").put("type", "Box64").put("entryName", DefaultVersion.DXVK)));
        assertThrows(JSONException.class, () -> CommunityConfigManager.importReferences(null, source));
    }

    @Test public void explicitBuiltinAndDisabledRefsOverrideConflictingContainerFields() throws Exception {
        JSONObject data = manifest().getJSONObject("container");
        data.put("dxwrapperConfig", "version=2.7-99,vkd3dVersion=2.14-33,ramFix=1");
        JSONArray refs = new JSONArray()
                .put(new JSONObject().put("role", "dxvk").put("entryName", "").put("versionName", DefaultVersion.DXVK))
                .put(new JSONObject().put("role", "vkd3d").put("entryName", "none"));
        CommunityConfigManager.applyBundledImportSelections(null, data, refs);
        assertEquals(DefaultVersion.DXVK, CommunityConfigManager.importSelection(data, "dxvk"));
        assertEquals("none", CommunityConfigManager.importSelection(data, "vkd3d"));
        assertTrue(data.getString("dxwrapperConfig").contains("ramFix=1"));
    }

    @Test public void incompleteChoiceListsAndWrongContentTypesCannotCreateAContainer() throws Exception {
        JSONObject source = manifest();
        ContentProfile dxvk = profile(ContentProfile.ContentType.CONTENT_TYPE_DXVK, "2.6.1", 42);
        List<CommunityConfigManager.ContentResolution> resolved = Collections.singletonList(resolution("dxvk", dxvk));
        assertThrows(JSONException.class, () -> CommunityConfigManager.previewResolution(source, resolved, new ArrayList<>()));
        ContentProfile box = profile(ContentProfile.ContentType.CONTENT_TYPE_BOX64, "0.4.5", 7);
        assertThrows(JSONException.class, () -> CommunityConfigManager.previewResolution(source, resolved, Collections.singletonList(box)));
        JSONObject contradictory = ref("dxvk", box);
        List<CommunityConfigManager.ContentResolution> bad = Collections.singletonList(
                new CommunityConfigManager.ContentResolution(contradictory, box, null, -1));
        assertThrows(JSONException.class, () -> CommunityConfigManager.previewResolution(source, bad, Collections.singletonList(box)));
        ContentProfile proton = profile(ContentProfile.ContentType.CONTENT_TYPE_PROTON, "10-arm64", 7);
        ContentProfile wine = profile(ContentProfile.ContentType.CONTENT_TYPE_WINE, "10-arm64", 7);
        assertThrows(JSONException.class, () -> CommunityConfigManager.previewResolution(source,
                Collections.singletonList(resolution("wineRuntime", proton)), Collections.singletonList(wine)));
    }

    @Test public void freshImportDropsPrefixReceiptsButKeepsHudAndUserSettings() throws Exception {
        JSONObject source = manifest();
        source.getJSONObject("container").put("extraData", new JSONObject()
                .put("dxwrapperIntegrityVersion", "1").put("ddrawIntegrityVersion", "1")
                .put("bcnLayerVersion", "3").put("wincomponents", "already-applied")
                .put("hudMode", "mangohud1").put("sharpnessLevel", "0.8"));
        JSONObject applied = CommunityConfigManager.applyResolution(null, source, Collections.emptyList(), Collections.emptyList());
        JSONObject saved = new JSONObject(applied.toString()).getJSONObject("extraData");
        assertFalse(saved.has("dxwrapperIntegrityVersion"));
        assertFalse(saved.has("ddrawIntegrityVersion"));
        assertFalse(saved.has("bcnLayerVersion"));
        assertFalse(saved.has("wincomponents"));
        assertEquals("mangohud1", saved.getString("hudMode"));
        assertEquals("0.8", saved.getString("sharpnessLevel"));
        assertTrue(source.getJSONObject("container").getJSONObject("extraData").has("wincomponents"));
    }
}
