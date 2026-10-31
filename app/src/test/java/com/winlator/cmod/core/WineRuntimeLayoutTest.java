package com.winlator.cmod.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class WineRuntimeLayoutTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private void elf(File bin, String name, int bits, int machine, boolean bigEndian) throws Exception {
        Files.createDirectories(bin.toPath());
        byte[] header = new byte[64];
        header[0] = 0x7f; header[1] = 'E'; header[2] = 'L'; header[3] = 'F';
        header[4] = (byte)bits; header[5] = (byte)(bigEndian ? 2 : 1);
        header[18] = (byte)(bigEndian ? machine >> 8 : machine);
        header[19] = (byte)(bigEndian ? machine : machine >> 8);
        Files.write(new File(bin, name).toPath(), header);
    }

    @Test public void arm64AndArm64ecRemainDistinctButBothUseTheNativeHost() throws Exception {
        File bin = temp.newFolder(), lib = temp.newFolder();
        elf(bin, "wine", 2, 183, false);
        assertEquals("arm64", WineRuntimeLayout.architecture("10-arm64", bin, lib));
        assertEquals("arm64ec", WineRuntimeLayout.architecture("10-arm64ec", bin, lib));
        assertTrue(WineRuntimeLayout.isNativeArm64("arm64"));
        assertTrue(WineRuntimeLayout.isNativeArm64("arm64ec"));
        WineRuntimeLayout.validateHost("arm64", bin);
        WineRuntimeLayout.validateHost("arm64ec", bin);
    }

    @Test public void noSuffixIsInferredFromTheExecutableRatherThanAssumingX64() throws Exception {
        File bin = temp.newFolder(), lib = temp.newFolder();
        elf(bin, "wine", 2, 183, false);
        assertEquals("arm64", WineRuntimeLayout.architecture("custom-10", bin, lib));
        elf(bin, "wine", 2, 62, false);
        assertEquals("x86_64", WineRuntimeLayout.architecture("custom-10", bin, lib));
        elf(bin, "wine", 1, 3, false);
        assertEquals("x86", WineRuntimeLayout.architecture("custom-10", bin, lib));
    }

    @Test public void scriptsCanDelegateToWine64OrAPreloader() throws Exception {
        File bin = temp.newFolder();
        Files.write(new File(bin, "wine").toPath(), "#!/system/bin/sh\nexec wine64".getBytes(StandardCharsets.UTF_8));
        elf(bin, "wine64", 2, 183, false);
        assertEquals("arm64", WineRuntimeLayout.hostArchitecture(bin));
    }

    @Test public void machineAndClassMustAgreeAndBigEndianHeadersAreDecoded() throws Exception {
        File bin = temp.newFolder();
        elf(bin, "wine", 2, 183, true);
        assertEquals("arm64", WineRuntimeLayout.hostArchitecture(bin));
        elf(bin, "wine", 1, 183, false);
        assertEquals("", WineRuntimeLayout.hostArchitecture(bin));
        elf(bin, "wine", 2, 3, false);
        assertEquals("", WineRuntimeLayout.hostArchitecture(bin));
    }

    @Test public void sourceDirectoriesCanIdentifyAWrapperWithNoElfHeader() throws Exception {
        File bin = temp.newFolder(), lib = temp.newFolder();
        assertTrue(new File(lib, "wine/aarch64-windows").mkdirs());
        assertEquals("arm64", WineRuntimeLayout.architecture("custom", bin, lib));
    }

    @Test public void directAndNestedModuleLayoutsAreResolvedWithoutDoubleWine() throws Exception {
        File lib = temp.newFolder();
        File nested = new File(lib, "wine/i386-windows");
        assertTrue(nested.mkdirs());
        assertEquals(nested, WineRuntimeLayout.modules(lib, "i386-windows"));
        assertEquals(nested, WineRuntimeLayout.modules(new File(lib, "wine"), "i386-windows"));
        File direct = new File(lib, "i386-windows");
        assertTrue(direct.mkdir());
        assertEquals(direct, WineRuntimeLayout.modules(lib, "i386-windows"));
    }

    @Test public void truncatedAndUnknownLaunchersDoNotBecomeAnArbitraryRuntime() throws Exception {
        File bin = temp.newFolder(), lib = temp.newFolder();
        Files.write(new File(bin, "wine").toPath(), new byte[]{0x7f, 'E', 'L', 'F'});
        assertEquals("", WineRuntimeLayout.architecture("custom", bin, lib));
        assertThrows(IOException.class, () -> WineRuntimeLayout.validateHost("", bin));
    }

    @Test public void rejectsNativeVersusX86MismatchBeforeTouchingThePrefix() throws Exception {
        File bin = temp.newFolder();
        elf(bin, "wine", 2, 183, false);
        assertThrows(IOException.class, () -> WineRuntimeLayout.validateHost("x86_64", bin));
        elf(bin, "wine", 2, 62, false);
        assertThrows(IOException.class, () -> WineRuntimeLayout.validateHost("arm64ec", bin));
        WineRuntimeLayout.validateHost("x86_64", bin);
    }

    @Test public void declaringAnAbiDoesNotLoseItWhenThePackageHasADifferentHost() throws Exception {
        File bin = temp.newFolder(), lib = temp.newFolder();
        elf(bin, "wine", 2, 62, false);
        assertEquals("arm64ec", WineRuntimeLayout.architecture("10-arm64ec", bin, lib));
        assertThrows(IOException.class, () -> WineRuntimeLayout.validateHost("arm64ec", bin));
    }
}
