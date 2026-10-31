package com.winlator.cmod.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class CommunityEndpointTest {
    private static final String ID = "b62a8ca0-4e72-4e49-9056-8dfe9b6357fa";
    @Test public void revisionAndOriginAreBoundToTheExactFile() {
        String url = CommunityEndpoint.file(ID, 7, false);
        assertTrue(CommunityEndpoint.trustedFile(url, ID, 7, false));
        assertFalse(CommunityEndpoint.trustedFile(url, ID, 8, false));
        assertFalse(CommunityEndpoint.trustedFile(url, ID, 7, true));
        assertFalse(CommunityEndpoint.trustedFile(url.replace("https:", "http:"), ID, 7, false));
        assertFalse(CommunityEndpoint.trustedFile(url.replace(".chatgpt.site", ".chatgpt.site.attacker.example"), ID, 7, false));
    }
    @Test public void pathsAndInvalidRevisionsCannotBecomeDownloadTargets() {
        assertFalse(CommunityEndpoint.trustedFile("https://example.com", "../admin", 1, false));
        assertFalse(CommunityEndpoint.trustedFile("", ID, 0, false));
        assertFalse(CommunityEndpoint.trustedFile("", null, 1, false));
    }
    @Test public void officialSourceCannotCarryCredentialsOrRedirectParameters() {
        String url = CommunityEndpoint.LEGACY + "Game-S24FE.zip";
        assertTrue(CommunityEndpoint.legacySource(url));
        assertFalse(CommunityEndpoint.legacySource(url + "?target=https://evil.example"));
        assertFalse(CommunityEndpoint.legacySource(url + "#fragment"));
        assertFalse(CommunityEndpoint.legacySource(url.replace("github.com/", "github.com.evil.example/")));
        assertFalse(CommunityEndpoint.legacySource(CommunityEndpoint.LEGACY));
    }
    @Test public void archivesRequireAFullDigestAndBoundedNonemptyZip() {
        String hash = "a".repeat(64);
        assertTrue(CommunityEndpoint.validArchive(hash, 22));
        assertTrue(CommunityEndpoint.validArchive(hash, CommunityEndpoint.MAX_ZIP));
        assertFalse(CommunityEndpoint.validArchive(hash, CommunityEndpoint.MAX_ZIP + 1));
        assertFalse(CommunityEndpoint.validArchive(hash, 0));
        assertFalse(CommunityEndpoint.validArchive("", 500));
        assertFalse(CommunityEndpoint.validArchive("g".repeat(64), 500));
    }
}
