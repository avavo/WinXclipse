package com.winlator.cmod.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Installs inside the selected Wine only, with device checks and persistent loader recovery. */
public final class VulkanRuntimeInstaller {
    public static final class Result {
        public final int status;
        public final String output;
        public Result(int status, String output) { this.status = status; this.output = output; }
    }
    public interface Runner { Result run(String phase, String[] arguments, long timeoutMs) throws IOException, InterruptedException; }
    private static final Set<String> ATTEMPTED = ConcurrentHashMap.newKeySet();
    private static final String ROOT = "drive_c/Program Files/VulkanRT/" + VulkanRuntimePayload.VERSION + "/WinXclipse";
    private static final String WIN_ROOT = "C:\\Program Files\\VulkanRT\\" + VulkanRuntimePayload.VERSION + "\\WinXclipse";
    private static final String HASH64 = "f413d235d314b2901b20984a21b5506242203bba2d20c728f313ff1bb55bf5a4";
    private static final String HASH32 = "4a0a3cac32b5f88f7b9d46c097fd5ba60809e601cd5b8c9cca624aad4dcb9d4d";
    private VulkanRuntimeInstaller() {}

    public static boolean prepare(File prefix, File payload, boolean win64, String identity, Runner runner)
            throws IOException, InterruptedException {
        WineVulkanBackup backup = new WineVulkanBackup(prefix);
        if (backup.exists()) { stop(runner); backup.restore(); }
        boolean has32 = !win64 || new File(prefix, "drive_c/windows/syswow64/winevulkan.dll").isFile();
        String[] architectures = !win64 ? new String[]{"x86"}
                : has32 ? new String[]{"x64", "x86"} : new String[]{"x64"};
        File target = new File(prefix, ROOT), receipt = new File(target, ".verified");
        String expected = "WinXclipse VulkanRT protocol 1\n" + VulkanRuntimePayload.VERSION + "\n" + identity + "\n" + String.join(",", architectures) + "\n";
        if (receipt.isFile() && expected.equals(read(receipt)) && installedFiles(target, architectures)) return true;
        // A failed probe is retried after app restart or runtime/driver change,
        // rather than delaying every game launch in the same app process.
        String attempt = prefix.getCanonicalPath() + "\n" + expected;
        if (!ATTEMPTED.add(attempt)) return false;
        Files.createDirectories(target.toPath());
        Files.deleteIfExists(receipt.toPath());
        boolean ranWine = false;
        try {
            Map<String, String> baseline = new LinkedHashMap<>();
            for (String arch : architectures) {
                ranWine = true;
                String probe = new File(payload, arch + "/vulkaninfo.exe").getAbsolutePath();
                Result result = runner.run("baseline-" + arch, new String[]{probe, "--summary"}, 20_000);
                String signature = WineVulkanProbe.signature(result.status, result.output, false);
                if (signature.isEmpty()) throw new IOException("Wine Vulkan device check failed (" + arch + ")");
                baseline.put(arch, signature);
                String system = win64 && arch.equals("x86") ? "syswow64" : "system32";
                result = runner.run("register-" + arch,
                        new String[]{"C:\\windows\\" + system + "\\regsvr32.exe", "/s", "winevulkan.dll"}, 15_000);
                if (result.status != 0 || !new File(prefix, "drive_c/windows/" + system + "/winevulkan.json").isFile())
                    throw new IOException("Wine Vulkan ICD registration failed (" + arch + ")");
            }
            stop(runner);
            backup.create();
            try {
                Result installed = runner.run("install", new String[]{new File(payload, "installer.exe").getAbsolutePath(), "/S"}, 90_000);
                if (installed.status != 0) throw new IOException("VulkanRT installer exit " + installed.status);
                // Flush installer registry writes before collecting its real payload.
                stop(runner);
                for (String arch : architectures) {
                    String system = win64 && arch.equals("x86") ? "syswow64" : "system32";
                    File loader = new File(prefix, "drive_c/windows/" + system + "/vulkan-1.dll");
                    String hash = arch.equals("x64") ? HASH64 : HASH32;
                    if (!hash.equals(VulkanRuntimePayload.sha256(loader))) throw new IOException("VulkanRT installed loader mismatch (" + arch + ")");
                    File directory = new File(target, arch);
                    Files.createDirectories(directory.toPath());
                    Files.copy(loader.toPath(), new File(directory, "vulkan-1.dll").toPath(), StandardCopyOption.REPLACE_EXISTING);
                    Files.copy(new File(payload, arch + "/vulkaninfo.exe").toPath(), new File(directory, "vulkaninfo.exe").toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                stop(runner);
                backup.restore();
            }
            // Official runtime remains installed, with its loader usable alongside
            // the Wine bridge. Games retain their original Wine loaders (also 1.4).
            for (String arch : architectures) {
                String command = "set \"VK_DRIVER_FILES=\" && set \"VK_ICD_FILENAMES=\" && \""
                        + WIN_ROOT + "\\" + arch + "\\vulkaninfo.exe\" --summary";
                Result result = runner.run("native-" + arch, new String[]{"cmd.exe", "/d", "/s", "/c", command}, 20_000);
                String signature = WineVulkanProbe.signature(result.status, result.output, true);
                if (!baseline.get(arch).equals(signature)) throw new IOException("VulkanRT device/driver differs from the Wine bridge (" + arch + ")");
            }
            stop(runner);
            ranWine = false;
            Files.deleteIfExists(new File(target, ".last-error.txt").toPath());
            Files.write(receipt.toPath(), expected.getBytes(StandardCharsets.UTF_8));
            ATTEMPTED.remove(attempt);
            return true;
        } catch (IOException failure) {
            Files.write(new File(target, ".last-error.txt").toPath(), failure.toString().getBytes(StandardCharsets.UTF_8));
            if (backup.exists()) throw failure; // Never launch over an unrecovered loader.
            return false;
        } finally { if (ranWine) stop(runner); }
    }

    private static boolean installedFiles(File target, String[] architectures) {
        try {
            for (String arch : architectures) {
                if (!(arch.equals("x64") ? HASH64 : HASH32).equals(VulkanRuntimePayload.sha256(new File(target, arch + "/vulkan-1.dll")))) return false;
                if (!VulkanRuntimePayload.matches(new File(target, arch + "/vulkaninfo.exe"), arch + "/vulkaninfo.exe")) return false;
            }
            return true;
        } catch (IOException failure) { return false; }
    }

    private static String read(File file) throws IOException { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
    private static void stop(Runner runner) throws IOException, InterruptedException {
        int killed = runner.run("stop", new String[]{"-k"}, 15_000).status;
        int waited = runner.run("wait", new String[]{"-w"}, 15_000).status;
        if (killed != 0 && killed != 1 || waited != 0) throw new IOException("Wine server did not finish VulkanRT preparation");
    }
}
