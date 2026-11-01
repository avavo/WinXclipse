package com.winlator.cmod.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Prepares a verified side-by-side runtime without replacing Wine's live loaders. */
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
        File unavailable = new File(target, ".baseline-unavailable");
        String expected = "WinXclipse VulkanRT protocol 2\n" + VulkanRuntimePayload.VERSION + "\n" + identity + "\n" + String.join(",", architectures) + "\n";
        if (receipt.isFile() && expected.equals(read(receipt)) && installedFiles(target, architectures)) return true;
        // Cache only an explicit unsupported Wine bridge, never a timeout or
        // incomplete probe. Keep the original bridge and diagnostic; do not mark
        // VulkanRT installed. A config/runtime change or six hours retries it.
        long age = System.currentTimeMillis() - unavailable.lastModified();
        if (unavailable.isFile() && age >= 0 && age < 6L * 60 * 60 * 1000
                && expected.equals(read(unavailable))) return false;
        // Other failures are retried after restart or runtime/driver change.
        String attempt = prefix.getCanonicalPath() + "\n" + expected;
        if (!ATTEMPTED.add(attempt)) return false;
        Files.createDirectories(target.toPath());
        Files.deleteIfExists(receipt.toPath());
        boolean ranWine = false, unsupportedBridge = false;
        try {
            Map<String, String> baseline = new LinkedHashMap<>();
            for (String arch : architectures) {
                ranWine = true;
                String probe = new File(payload, arch + "/vulkaninfo.exe").getAbsolutePath();
                Result result = runner.run("baseline-" + arch, new String[]{probe, "--summary"}, 20_000);
                String signature = WineVulkanProbe.signature(result.status, result.output, false);
                if (signature.isEmpty()) {
                    unsupportedBridge = result.status != 0 && result.output != null
                            && result.output.contains("Failed to load Wine graphics driver supporting Vulkan")
                            && result.output.contains("ERROR_INITIALIZATION_FAILED");
                    throw new IOException("Wine Vulkan device check failed (" + arch + ")");
                }
                baseline.put(arch, signature);
                String system = win64 && arch.equals("x86") ? "syswow64" : "system32";
                result = runner.run("register-" + arch,
                        new String[]{"C:\\windows\\" + system + "\\regsvr32.exe", "/s", "winevulkan.dll"}, 15_000);
                if (result.status != 0 || !new File(prefix, "drive_c/windows/" + system + "/winevulkan.json").isFile())
                    throw new IOException("Wine Vulkan ICD registration failed (" + arch + ")");
            }
            // The package already contains the exact official DLLs. Running NSIS
            // only to collect those files started another translated process and
            // required two server restarts and loader backup/restore. Publish the
            // same verified files beside vulkaninfo instead, leaving system32 and
            // syswow64 untouched. Only legacy interrupted installs need recovery.
            for (String arch : architectures) {
                File directory = new File(target, arch);
                Files.createDirectories(directory.toPath());
                for (String name : new String[]{"vulkan-1.dll", "vulkaninfo.exe"}) {
                    Path temporary = Files.createTempFile(directory.toPath(), ".vulkanrt-", ".tmp");
                    try {
                        Files.copy(new File(payload, arch + "/" + name).toPath(), temporary, StandardCopyOption.REPLACE_EXISTING);
                        if (!VulkanRuntimePayload.matches(temporary.toFile(), arch + "/" + name))
                            throw new IOException("VulkanRT payload mismatch (" + arch + "/" + name + ")");
                        Files.move(temporary, new File(directory, name).toPath(), StandardCopyOption.REPLACE_EXISTING);
                    } finally { Files.deleteIfExists(temporary); }
                }
            }
            // Separate Wine processes resolve their local DLL and overrides without
            // restarting the shared server. Verify both architectures before the
            // one final stop/flush and success receipt.
            for (String arch : architectures) {
                ranWine = true;
                String command = "set \"VK_DRIVER_FILES=\" && set \"VK_ICD_FILENAMES=\" && \""
                        + WIN_ROOT + "\\" + arch + "\\vulkaninfo.exe\" --summary";
                Result result = runner.run("native-" + arch, new String[]{"cmd.exe", "/d", "/s", "/c", command}, 20_000);
                String signature = WineVulkanProbe.signature(result.status, result.output, true);
                if (!baseline.get(arch).equals(signature)) throw new IOException("VulkanRT device/driver differs from the Wine bridge (" + arch + ")");
            }
            stop(runner);
            ranWine = false;
            Files.deleteIfExists(new File(target, ".last-error.txt").toPath());
            Files.deleteIfExists(unavailable.toPath());
            Files.write(receipt.toPath(), expected.getBytes(StandardCharsets.UTF_8));
            ATTEMPTED.remove(attempt);
            return true;
        } catch (IOException failure) {
            Files.write(new File(target, ".last-error.txt").toPath(), failure.toString().getBytes(StandardCharsets.UTF_8));
            if (backup.exists()) throw failure; // Never launch over an unrecovered loader.
            return false;
        } finally {
            if (ranWine) stop(runner);
            // Persist only after the server stopped and original loaders are safe.
            if (unsupportedBridge && !backup.exists()) {
                Files.write(unavailable.toPath(), expected.getBytes(StandardCharsets.UTF_8));
                ATTEMPTED.remove(attempt);
            }
        }
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
