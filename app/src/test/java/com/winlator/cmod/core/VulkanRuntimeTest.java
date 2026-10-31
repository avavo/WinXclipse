package com.winlator.cmod.core;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

public class VulkanRuntimeTest {
    @ClassRule public static TemporaryFolder common = new TemporaryFolder();
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static File payload, archive;

    @BeforeClass public static void prepareOriginalPayload() throws Exception {
        archive = new File("src/main/assets/" + VulkanRuntimePayload.ASSET);
        if (!archive.isFile()) archive = new File("app/src/main/assets/" + VulkanRuntimePayload.ASSET);
        assertTrue("Original VulkanRT payload must be in the clone", archive.isFile());
        payload = VulkanRuntimePayload.prepare(common.newFolder(), () -> new FileInputStream(archive));
    }

    private static void write(File file, String value) throws IOException {
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
    }
    private static String read(File file) throws IOException { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
    private static String probe(boolean nativeLoader) {
        return "trace:loaddll:build_module Loaded L\"C:\\windows\\system32\\vulkan-1.dll\" at 0x100: "
                + (nativeLoader ? "native" : "builtin") + "\n"
                + "trace:loaddll:build_module Loaded L\"C:\\windows\\system32\\winevulkan.dll\" at 0x200: builtin\n"
                + "GPU0:\n deviceName = Xclipse 940\n vendorID = 0x144d\n deviceID = 0x940\n"
                + " driverVersion = 26.3.0\n driverName = Quasar 67\n";
    }

    @Test public void packageIsVerifiedAndReadyPayloadDoesNotReextract() throws Exception {
        assertTrue(VulkanRuntimePayload.isReady(payload));
        assertTrue(VulkanRuntimePayload.matches(new File(payload, "installer.exe"), "installer.exe"));
        assertEquals(payload, VulkanRuntimePayload.prepare(payload.getParentFile(), () -> { throw new AssertionError("Reextracted"); }));
    }

    @Test public void missingOrChangedExecutableIsRepairedFromOriginalPackage() throws Exception {
        File parent = temp.newFolder();
        File installed = VulkanRuntimePayload.prepare(parent, () -> new FileInputStream(archive));
        write(new File(installed, "x86/vulkaninfo.exe"), "truncated");
        assertFalse(VulkanRuntimePayload.isReady(installed));
        VulkanRuntimePayload.prepare(parent, () -> new FileInputStream(archive));
        assertTrue(VulkanRuntimePayload.isReady(installed));
    }

    @Test public void rejectsZipTraversalAndPreservesPreviousInstallation() throws Exception {
        File parent = temp.newFolder(), existing = new File(parent, "vulkanrt-" + VulkanRuntimePayload.VERSION);
        write(new File(existing, "keep.txt"), "previous data");
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(data)) {
            zip.putNextEntry(new ZipEntry("../escaped.exe")); zip.write(1); zip.closeEntry();
        }
        assertThrows(IOException.class, () -> VulkanRuntimePayload.prepare(parent, () -> new ByteArrayInputStream(data.toByteArray())));
        assertEquals("previous data", read(new File(existing, "keep.txt")));
        assertFalse(new File(parent, "escaped.exe").exists());
    }

    @Test public void emptyArchiveDoesNotPublishReadyPayload() throws Exception {
        File parent = temp.newFolder();
        assertThrows(IOException.class, () -> VulkanRuntimePayload.prepare(parent, () -> new ByteArrayInputStream(new byte[0])));
        assertFalse(new File(parent, "vulkanrt-" + VulkanRuntimePayload.VERSION).exists());
    }

    @Test public void requiresDeviceAndActualNativeOrBuiltinLoaderEvidence() {
        assertFalse(WineVulkanProbe.signature(0, probe(false), false).isEmpty());
        assertEquals("", WineVulkanProbe.signature(0, probe(false), true));
        assertEquals("", WineVulkanProbe.signature(1, probe(true), true));
        assertEquals("", WineVulkanProbe.signature(0, "deviceName = Xclipse 940", true));
        assertEquals("", WineVulkanProbe.signature(0, probe(true).replace("deviceName", "unrelated"), true));
        assertEquals("", WineVulkanProbe.signature(0, probe(true) + "deviceType = PHYSICAL_DEVICE_TYPE_CPU", true));
    }

    @Test public void distinguishesFallbackDriverOnTheSameGpu() {
        assertNotEquals(WineVulkanProbe.signature(0, probe(true), true),
                WineVulkanProbe.signature(0, probe(true).replace("Quasar 67", "stock"), true));
    }

    private class Fixture {
        final File prefix = temp.newFolder();
        final File original64 = new File(prefix, "drive_c/windows/system32/vulkan-1.dll");
        final File original32 = new File(prefix, "drive_c/windows/syswow64/vulkan-1.dll");
        final File target = new File(prefix, "drive_c/Program Files/VulkanRT/" + VulkanRuntimePayload.VERSION + "/WinXclipse");
        final List<String> phases = new ArrayList<>();
        String fail = "", differentDriver = "";
        boolean timeout, stuck, corruptInstall, win64 = true;
        Fixture() throws Exception {
            write(original64, "original Wine64"); write(original32, "original Wine32");
            write(new File(prefix, "drive_c/windows/system32/winevulkan.dll"), "bridge64");
            write(new File(prefix, "drive_c/windows/syswow64/winevulkan.dll"), "bridge32");
        }
        VulkanRuntimeInstaller.Result run(String phase, String[] args, long timeoutMs) throws IOException {
            phases.add(phase);
            if (phase.equals("wait") && stuck) return new VulkanRuntimeInstaller.Result(1, "");
            if (phase.equals("install")) {
                assertEquals("/S", args[1]);
                assertEquals(new File(payload, "installer.exe").getAbsolutePath(), args[0]);
                Files.copy(new File(payload, (win64 ? "x64" : "x86") + "/vulkan-1.dll").toPath(), original64.toPath(), StandardCopyOption.REPLACE_EXISTING);
                Files.copy(new File(payload, "x86/vulkan-1.dll").toPath(), original32.toPath(), StandardCopyOption.REPLACE_EXISTING);
                if (corruptInstall) write(original64, "truncated Windows loader");
                if (timeout) throw new IOException("Installer timed out");
            }
            if (phase.startsWith("register-")) {
                String system = win64 && phase.endsWith("x86") ? "syswow64" : "system32";
                write(new File(prefix, "drive_c/windows/" + system + "/winevulkan.json"), "ICD");
            }
            if (phase.equals(fail)) return new VulkanRuntimeInstaller.Result(1, "failed");
            if (phase.startsWith("native-")) {
                assertEquals("original Wine64", read(original64));
                assertEquals("original Wine32", read(original32));
            }
            String output = probe(phase.startsWith("native-"));
            if (phase.equals(differentDriver)) output = output.replace("Quasar 67", "stock");
            return new VulkanRuntimeInstaller.Result(0, output);
        }
        boolean prepare(String identity) throws Exception { return VulkanRuntimeInstaller.prepare(prefix, payload, win64, identity, this::run); }
        void originalLoaders() throws Exception {
            assertEquals("original Wine64", read(original64));
            assertEquals("original Wine32", read(original32));
        }
    }

    @Test public void installsAfterBaselineThenRestoresWineLoadersBeforeNativeDeviceChecks() throws Exception {
        Fixture f = new Fixture();
        assertTrue(f.prepare("runtime10-driver67"));
        f.originalLoaders();
        assertTrue(new File(f.target, ".verified").isFile());
        assertTrue(f.phases.indexOf("baseline-x86") < f.phases.indexOf("install"));
        assertTrue(f.phases.indexOf("install") < f.phases.indexOf("native-x64"));
        f.phases.clear();
        assertTrue(f.prepare("runtime10-driver67"));
        assertTrue("No reinstall at every launch", f.phases.isEmpty());
    }

    @Test public void failedBaselineNeverRunsInstallerOrMarksReady() throws Exception {
        Fixture f = new Fixture(); f.fail = "baseline-x86";
        assertFalse(f.prepare("unsupported"));
        assertFalse(f.phases.contains("install"));
        assertFalse(new File(f.target, ".verified").exists());
        f.originalLoaders();
        f.phases.clear(); assertFalse(f.prepare("unsupported")); assertTrue(f.phases.isEmpty());
    }

    @Test public void win32UsesItsSystem32AndDoesNotDemandAnX64Device() throws Exception {
        Fixture f = new Fixture(); f.win64 = false;
        assertTrue(f.prepare("win32"));
        assertTrue(f.phases.contains("native-x86"));
        assertFalse(f.phases.contains("native-x64"));
        f.originalLoaders();
    }

    @Test public void a64BitOnlyWineDoesNotInventMissing32BitSupport() throws Exception {
        Fixture f = new Fixture(); Files.delete(new File(f.prefix, "drive_c/windows/syswow64/winevulkan.dll").toPath());
        assertTrue(f.prepare("win64-only"));
        assertTrue(f.phases.contains("native-x64"));
        assertFalse(f.phases.contains("native-x86"));
    }

    @Test public void installerTimeoutRestoresBothLoadersAndLeavesNoSuccessReceipt() throws Exception {
        Fixture f = new Fixture(); f.timeout = true;
        assertFalse(f.prepare("timeout"));
        f.originalLoaders();
        assertFalse(new File(f.target, ".verified").exists());
        assertFalse(new WineVulkanBackup(f.prefix).exists());
    }

    @Test public void zeroInstallerExitWithDamagedPayloadDoesNotCountAsInstalled() throws Exception {
        Fixture f = new Fixture(); f.corruptInstall = true;
        assertFalse(f.prepare("zero exit but broken")); f.originalLoaders();
        assertFalse(new File(f.target, ".verified").exists());
    }

    @Test public void anUnstoppableWineServerBlocksTheRealGameInsteadOfEditingLiveLoaders() throws Exception {
        Fixture f = new Fixture(); f.stuck = true;
        assertThrows(IOException.class, () -> f.prepare("stuck server"));
        assertFalse(f.phases.contains("install")); f.originalLoaders();
    }

    @Test public void nativeProbeMustMatchOriginalDriverAndCreateDeviceForBothArchitectures() throws Exception {
        Fixture f = new Fixture(); f.differentDriver = "native-x86";
        assertFalse(f.prepare("different"));
        f.originalLoaders();
        assertFalse(new File(f.target, ".verified").exists());
    }

    @Test public void changedRuntimeOrDamagedProbeTriggersNewVerification() throws Exception {
        Fixture f = new Fixture(); assertTrue(f.prepare("first"));
        f.phases.clear(); assertTrue(f.prepare("second")); assertTrue(f.phases.contains("install"));
        write(new File(f.target, "x64/vulkaninfo.exe"), "corrupt");
        f.phases.clear(); assertTrue(f.prepare("second")); assertTrue(f.phases.contains("install"));
    }

    @Test public void interruptedInstallationIsRecoveredBeforeAnyNewProbe() throws Exception {
        Fixture f = new Fixture(); WineVulkanBackup backup = new WineVulkanBackup(f.prefix); backup.create();
        write(f.original64, "partial installer output");
        f.fail = "baseline-x64";
        assertFalse(f.prepare("recover"));
        assertEquals(Arrays.asList("stop", "wait"), f.phases.subList(0, 2));
        f.originalLoaders();
    }

    @Test public void corruptedRecoveryDataStopsInsteadOfDiscardingOriginals() throws Exception {
        Fixture f = new Fixture(); WineVulkanBackup backup = new WineVulkanBackup(f.prefix); backup.create();
        write(new File(f.prefix, ".winxclipse-vulkanrt-recovery/loader0"), "damaged");
        assertThrows(IOException.class, () -> f.prepare("bad recovery"));
        assertTrue(backup.exists()); f.originalLoaders();
    }

    @Test public void recoveryRemovesOnlyLoadersThatDidNotPreviouslyExist() throws Exception {
        Fixture f = new Fixture(); Files.delete(f.original32.toPath());
        WineVulkanBackup backup = new WineVulkanBackup(f.prefix); backup.create();
        write(f.original32, "added by installer"); backup.restore();
        assertFalse(f.original32.exists()); assertEquals("original Wine64", read(f.original64));
    }

    @Test public void detachesSharedRuntimeHardlinksBeforeTheInstallerCanOverwriteThem() throws Exception {
        Fixture f = new Fixture(); File shared = temp.newFile("shared-runtime.dll"); write(shared, "shared Wine loader");
        Files.delete(f.original64.toPath()); Files.createLink(f.original64.toPath(), shared.toPath());
        WineVulkanBackup backup = new WineVulkanBackup(f.prefix); backup.create();
        assertFalse(Files.isSameFile(f.original64.toPath(), shared.toPath()));
        write(f.original64, "installer overwrote its private loader");
        assertEquals("shared Wine loader", read(shared));
        backup.restore(); assertEquals("shared Wine loader", read(f.original64));
    }
}
