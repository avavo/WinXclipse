package com.winlator.cmod.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class PackageIdentityTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void caseAndSeparatorsDoNotChangeIdentity() {
        assertTrue(PackageIdentity.sameDriver("quAsar 67", "Quasar-67.zip"));
        assertTrue(PackageIdentity.same("Wrapper-CMOD-BCN", "wrapper_cmod_bcn.tzst"));
    }

    @Test public void similarBuildsAreNotExact() {
        assertFalse(PackageIdentity.sameDriver("Quasar 67", "Quasar 68"));
        assertFalse(PackageIdentity.sameDriver("Quasar 67", "Quasar 670"));
        assertFalse(PackageIdentity.sameDriver("RADV 26.2", "RADV 26.2 Preview"));
        assertFalse(PackageIdentity.sameDriver("Xclipse 920", "Mali 920"));
        assertFalse(PackageIdentity.same("wrapper-cmod", "wrapper-cmod-bcn"));
        assertFalse(PackageIdentity.same("", ""));
    }

    @Test public void legacyAliasDoesNotDiscardVersion() {
        assertTrue(PackageIdentity.sameDriver("XclipseOld920", "Xclipse 920 Old"));
        assertFalse(PackageIdentity.sameDriver("XclipseOld920", "Xclipse 920"));
        assertFalse(PackageIdentity.sameDriver("XclipseOld920", "Xclipse 940 Old"));
    }

    @Test public void validatesCompleteDigestAndRejectsChangedPayload() throws Exception {
        File file = temporary.newFile();
        Files.write(file.toPath(), "abc".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        assertEquals(hash, PackageIdentity.sha256(file));
        assertTrue(PackageIdentity.matchesSha256(file, hash.toUpperCase()));
        assertFalse(PackageIdentity.matchesSha256(file, "abc"));
        Files.write(file.toPath(), "abd".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(PackageIdentity.matchesSha256(file, hash));
    }

    @Test public void validatesArchitectureBeforeInstallingWrapper() throws Exception {
        File file = temporary.newFile();
        byte[] elf = new byte[64];
        elf[0] = 0x7f; elf[1] = 'E'; elf[2] = 'L'; elf[3] = 'F';
        elf[4] = 2; elf[5] = 1; elf[16] = 3; elf[18] = (byte)183;
        Files.write(file.toPath(), elf);
        assertTrue(PackageIdentity.isArm64SharedLibrary(file));
        elf[18] = 62; // x86_64
        Files.write(file.toPath(), elf);
        assertFalse(PackageIdentity.isArm64SharedLibrary(file));
        Files.write(file.toPath(), new byte[5000]);
        assertFalse(PackageIdentity.isArm64SharedLibrary(file));
    }
}
