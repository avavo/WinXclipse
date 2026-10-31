package com.winlator.cmod.xenvironment.components;

import android.app.Service;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.InetAddresses;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.box86_64.Box86_64Preset;
import com.winlator.cmod.box86_64.Box86_64PresetManager;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.fexcore.FEXCorePresetManager;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.EvshimPatcher;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.GPUInformation;
import com.winlator.cmod.core.ProcessHelper;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.core.WinePrefixBootstrap;
import com.winlator.cmod.core.WinePrefixState;
import com.winlator.cmod.core.WineRuntimeLayout;
import com.winlator.cmod.core.ContentOperationRegistry;
import com.winlator.cmod.MainActivity;
import android.net.Uri;
import java.io.FileOutputStream;
import com.winlator.cmod.inputcontrols.ControllerManager;
import com.winlator.cmod.sysvshm.SysVSHMConnectionHandler;
import com.winlator.cmod.sysvshm.SysVSHMRequestHandler;
import com.winlator.cmod.sysvshm.SysVSharedMemory;
import com.winlator.cmod.xconnector.UnixSocketConfig;
import com.winlator.cmod.xconnector.XConnectorEpoll;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class BionicProgramLauncherComponent extends GuestProgramLauncherComponent {
    private String guestExecutable;
    private int pid = -1;
    private String[] bindingPaths;
    private EnvVars envVars;
    private WineInfo wineInfo;
    private String box64Preset = Box86_64Preset.COMPATIBILITY;
    private Callback<Integer> terminationCallback;
    private final Object lock = new Object();
    private static final ReentrantLock prefixBootLock = new ReentrantLock();
    private boolean wow64Mode = true;
    private final ContentsManager contentsManager;
    private final ContentProfile wineProfile;
    private Container container;
    private final Shortcut shortcut;
    private volatile boolean stopped;
    private volatile boolean startupFailed;
    private volatile boolean preparingPrefix = true;
    private Callback<String> startupFailureCallback;
    private Runnable prefixUpdatedCallback;

    public void setStartupFailureCallback(Callback<String> callback) { startupFailureCallback = callback; }
    public void setPrefixUpdatedCallback(Runnable callback) { prefixUpdatedCallback = callback; }
    public boolean hasStartupFailed() { return startupFailed; }
    public boolean isPreparingPrefix() { return preparingPrefix; }

//    private static SysVSharedMemory shmMgr;
    private static XConnectorEpoll shmServer;
    
    public void setWineInfo(WineInfo wineInfo) {
        this.wineInfo = wineInfo;
    }
    public WineInfo getWineInfo() {
        return this.wineInfo;
    }

    public Container getContainer() { return this.container; }
    public void setContainer(Container container) { this.container = container; }


    private String translatorFailure = "Required CPU translator is missing or incomplete";
    private String selectedFexBridge = "libwow64fex.dll";

    private ContentProfile selectedTranslator(ContentProfile.ContentType type, String selection, String bundledAsset)
            throws IOException {
        ContentProfile profile = contentsManager.getInstalledSelection(type, selection);
        if (profile != null) return profile;
        ContentProfile bundled = MainActivity.expectedBundledProfile(bundledAsset);
        boolean requestsBundle = bundled != null && (selection.equals(bundled.verName)
                || selection.equals(bundled.verName + "-" + bundled.verCode)
                || selection.equalsIgnoreCase(ContentsManager.getEntryName(bundled)));
        if (!requestsBundle) return null;
        Context context = environment.getContext();
        File copy = File.createTempFile("bundled-translator-", ".wcp", context.getCacheDir());
        try (ContentOperationRegistry.Token operation = ContentOperationRegistry.begin(ContentOperationRegistry.Kind.INSTALL)) {
            try (java.io.InputStream input = context.getAssets().open("contents/" + bundledAsset);
                 FileOutputStream output = new FileOutputStream(copy)) {
                if (!com.winlator.cmod.core.StreamUtils.copy(input, output)) throw new IOException("Cannot read bundled translator");
            }
            contentsManager.extraContentFile(Uri.fromFile(copy), bundled, new ContentsManager.OnInstallFinishedCallback() {
                private boolean staged = true;
                @Override public void onFailed(ContentsManager.InstallFailedReason reason, Exception error) {
                    Log.e("BionicStartup", "Translator repair failed: " + reason, error);
                }
                @Override public void onSucceed(ContentProfile content) {
                    if (staged) { staged = false; contentsManager.finishInstallContent(content, this); }
                }
            });
            contentsManager.syncContents();
            return contentsManager.getInstalledSelection(type, selection);
        } finally { copy.delete(); }
    }

    private boolean extractBox86_64Files() {
        Context context = environment.getContext();
        boolean shortcutOverride = shortcut != null && shortcut.hasExtra("box64Version");
        String selected = shortcutOverride ? shortcut.getExtra("box64Version") : container.getBox64Version();
        if (selected == null || selected.trim().isEmpty()) selected = DefaultVersion.BOX64;
        translatorFailure = "Box64 " + selected + " is missing or incomplete. Install this exact version in Downloads.";
        try {
            ContentProfile profile = selectedTranslator(ContentProfile.ContentType.CONTENT_TYPE_BOX64, selected, DefaultVersion.BOX64_ASSET);
            if (profile == null) return false;
            String appliedId = profile.verName + "-" + profile.verCode;
            String installed = PreferenceManager.getDefaultSharedPreferences(context).getString("current_box64_version", "");
            if (!appliedId.equals(installed) || !contentsManager.isAppliedContent(profile)) {
                if (!contentsManager.applyContent(profile) || !contentsManager.isAppliedContent(profile)) return false;
                PreferenceManager.getDefaultSharedPreferences(context).edit().putString("current_box64_version", appliedId).apply();
            }
            File binary = new File(environment.getImageFs().getBinDir(), "box64");
            FileUtils.chmod(binary, 0755);
            // A shortcut override applies to that launch, not to its parent container.
            container.putExtra("box64Version", appliedId);
            container.saveData();
            return binary.isFile() && binary.length() > 0;
        } catch (IOException failure) {
            Log.e("BionicStartup", translatorFailure, failure);
            return false;
        }
    }

    private boolean extractEmulatorsDlls() {
        boolean shortcutOverride = shortcut != null && shortcut.hasExtra("fexcoreVersion");
        String selected = shortcutOverride ? shortcut.getExtra("fexcoreVersion") : container.getFEXCoreVersion();
        if (selected == null || selected.trim().isEmpty()) selected = DefaultVersion.FEXCORE;
        translatorFailure = "FEXCore " + selected + " is missing or incomplete. Install this exact version in Downloads.";
        try {
            ContentProfile profile = selectedTranslator(ContentProfile.ContentType.CONTENT_TYPE_FEXCORE, selected, DefaultVersion.FEXCORE_ASSET);
            if (profile == null) return false;
            String appliedId = profile.verName + "-" + profile.verCode;
            if (!appliedId.equals(container.getExtra("fexcoreVersion")) || !contentsManager.isAppliedContent(profile)) {
                if (!contentsManager.applyContent(profile) || !contentsManager.isAppliedContent(profile)) return false;
                container.putExtra("fexcoreVersion", appliedId);
                container.saveData();
            }
            selectedFexBridge = "";
            for (ContentProfile.ContentFile file : profile.fileList) {
                if (file.target.endsWith("/libwow64fex.dll")) { selectedFexBridge = "libwow64fex.dll"; break; }
                if (file.target.endsWith("/libarm64ecfex.dll")) selectedFexBridge = "libarm64ecfex.dll";
            }
            if (selectedFexBridge.isEmpty()) return false;
            return true;
        } catch (IOException failure) {
            Log.e("BionicStartup", translatorFailure, failure);
            return false;
        }
    }

    private void populateSysWow64() {
        // A pure win32 prefix stores its 32-bit DLLs in system32 and must not
        // grow a synthetic syswow64 directory. Only 64-bit/WoW64 prefixes use it.
        if (!wineInfo.isWin64()) return;
        try {
            Context context = environment.getContext();
            File rootDir = environment.getImageFs().getRootDir();
            File sysWow64 = new File(rootDir, "/home/xuser/.wine/drive_c/windows/syswow64");
            // A 64-bit/WoW64 prefix needs its 32-bit DLL set in syswow64. The old check
            // `!isDirectory() || hasKernel` skipped population entirely when
            // the directory was missing, leaving a broken WoW64 prefix that
            // Wine then tries to repair on every launch (very slow).
            if (sysWow64.isDirectory() && new File(sysWow64, "kernel32.dll").isFile()) {
                return;
            }
            if (!sysWow64.isDirectory() && !sysWow64.mkdirs()) {
                Log.w("BionicStartup","syswow64 mkdirs failed");
                return;
            }

            File wineLibRoot = wineProfile != null && wineProfile.wineLibPath != null
                    ? ContentsManager.getSourceFile(context, wineProfile, wineProfile.wineLibPath)
                    : new File(environment.getImageFs().getWinePath(), "lib");
            File i386Dir = WineRuntimeLayout.modules(wineLibRoot, "i386-windows");
            if (!new File(i386Dir, "kernel32.dll").isFile()) return;

            File[] dlls = i386Dir.listFiles((dir, name) -> name.toLowerCase().endsWith(".dll"));
            if (dlls == null) return;
            int copied = 0;
            for (File dll : dlls) {
                if (dll.getName().equalsIgnoreCase("icu.dll")) continue;
                File target = new File(sysWow64, dll.getName());
                if (!target.isFile() && FileUtils.copy(dll, target)) copied++;
            }
            Log.i("BionicProgramLauncherComponent", "Populated syswow64 with " + copied + " i386 DLLs from " + wineLibRoot);
        } catch (Exception e) {
            Log.w("BionicProgramLauncherComponent", "syswow64 population failed", e);
        }
    }

    public BionicProgramLauncherComponent(ContentsManager contentsManager, ContentProfile wineProfile, Shortcut shortcut) {
        this.contentsManager = contentsManager;
        this.wineProfile = wineProfile;
        this.shortcut = shortcut;
    }

    @Override
    public void start() {
        if (stopped) return;
        synchronized (lock) {
            boolean translatorReady = wineInfo.usesNativeArm64()
                    ? extractEmulatorsDlls() : extractBox86_64Files();
            if (!translatorReady) {
                pid = -1;
                Log.e("BionicStartup", "Wine launch stopped: required translator is incomplete");
                failStartup(translatorFailure);
                return;
            }
            populateSysWow64();
//            checkDependencies();
            // If we end up needing to inject winebus.so into user installed contents
//            EvshimPatcher.patchWineTree(
//                    environment.getContext(),
//                    new File(environment.getImageFs().getWinePath()),
//                    wineInfo.usesNativeArm64());
        }
        // Prefix preparation can take minutes on its first boot. Keep it outside
        // the process lock so leaving the session can cancel it immediately.
        try {
            execGuestProgram();
        } catch (RuntimeException failure) {
            failStartup(failure.getMessage());
        }
    }

    @Override
    public void stop() {
        stopped = true;
        synchronized (lock) {
            if (pid != -1) {
                Process.killProcess(pid);
                pid = -1;
            }
        }
    }

    private void failStartup(String message) {
        startupFailed = true;
        Log.e("BionicStartup", "Startup stopped: " + message);
        if (!stopped && startupFailureCallback != null) startupFailureCallback.call(message);
    }

    public Callback<Integer> getTerminationCallback() {
        return terminationCallback;
    }

    public void setTerminationCallback(Callback<Integer> terminationCallback) {
        this.terminationCallback = terminationCallback;
    }

    public String getGuestExecutable() {
        return guestExecutable;
    }

    public void setGuestExecutable(String guestExecutable) {
        this.guestExecutable = guestExecutable;
    }

    public boolean isWoW64Mode() {
        return wow64Mode;
    }

    public void setWoW64Mode(boolean wow64Mode) {
        this.wow64Mode = wow64Mode;
    }

    public String[] getBindingPaths() {
        return bindingPaths;
    }

    public void setBindingPaths(String[] bindingPaths) {
        this.bindingPaths = bindingPaths;
    }

    public EnvVars getEnvVars() {
        return envVars;
    }

    public void setEnvVars(EnvVars envVars) {
        this.envVars = envVars;
    }

    public String getBox64Preset() {
        return box64Preset;
    }

    public void setBox64Preset(String box64Preset) {
        this.box64Preset = box64Preset;
    }

    private int execGuestProgram() {
        // A cancelled bootstrap may still be stopping its wineserver. Do not
        // let a quick reopen start another server for that prefix meanwhile.
        try {
            while (!stopped) {
                if (!prefixBootLock.tryLock(200, TimeUnit.MILLISECONDS)) continue;
                try {
                    return stopped ? -1 : execGuestProgramAfterPreviousBoot();
                } finally {
                    prefixBootLock.unlock();
                }
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            failStartup("Wine preparation cancelled");
        }
        return -1;
    }

    private int execGuestProgramAfterPreviousBoot() {

        // final int MAX_PLAYERS = 1; // old static method

        Context context = environment.getContext();
        File gamepadDir = new File(context.getFilesDir(), "imagefs/tmp");

        // Get the number of enabled players directly from ControllerManager.
        // Always expose P1. Touch controls can become active immediately before
        // or after Wine starts, and an initial zero here made evshim create no
        // virtual controller at all.
        final int enabledPlayerCount = Math.max(1,
                ControllerManager.getInstance().getEnabledPlayerCount());
        for (int i = 0; i < enabledPlayerCount; i++) {
            String memPath;
            if (i == 0) {
                // Player 1 uses the original, non-numbered path that is known to work.
                memPath = new File(gamepadDir, "gamepad.mem").getAbsolutePath();
            } else {
                // Players 2, 3, 4 use a 1-based index.
                memPath = new File(gamepadDir, "gamepad" + i + ".mem").getAbsolutePath();
            }

            File memFile = new File(memPath);
            memFile.getParentFile().mkdirs();
            try (RandomAccessFile raf = new RandomAccessFile(memFile, "rw")) {
                raf.setLength(64);
            } catch (IOException e) {
                Log.e("EVSHIM_HOST", "Failed to create mem file for player index "+i, e);
            }
        }


        ImageFs imageFs = environment.getImageFs();
        File rootDir = imageFs.getRootDir();

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        boolean enableBox86_64Logs = preferences.getBoolean("enable_box86_64_logs", false);
        boolean openWithAndroidBrowser = preferences.getBoolean("open_with_android_browser", false);
        boolean shareAndroidClipboard = preferences.getBoolean("share_android_clipboard", false);
        boolean enablePebLogs = preferences.getBoolean("enable_peb_logs", false);

        EnvVars envVars = new EnvVars();

        if (openWithAndroidBrowser)
            envVars.put("WINE_OPEN_WITH_ANDROID_BROWSER", "1");
        if (shareAndroidClipboard) {
            envVars.put("WINE_FROM_ANDROID_CLIPBOARD", "1");
            envVars.put("WINE_TO_ANDROID_CLIPBOARD", "1");
        }
        if (enablePebLogs) {
            envVars.put("WINE_LOG_PEB_DATA", "1");
        }

        // Use the ControllerManager's dynamic count for the environment variable
        envVars.put("EVSHIM_MAX_PLAYERS", String.valueOf(enabledPlayerCount));
        envVars.put("EVSHIM_GAMEPAD_DIR", gamepadDir.getAbsolutePath());


        envVars.put("EVSHIM_SHM_ID", "1");

        addBox64EnvVars(envVars, enableBox86_64Logs);

        String emulator = container.getEmulator();
        if (shortcut != null) {
            emulator = shortcut.getExtra("emulator", container.getEmulator());
        }

        // User-installed ARM64EC Wine/Proton packages follow the proven Mali
        // launcher path. They are always started through FEXCore; this avoids
        // interpreting a newer package with an incompatible WOWBox runtime.
        if (wineInfo.usesNativeArm64()) emulator = "fexcore";

        if (wineInfo.usesNativeArm64()) {
            if (emulator.equalsIgnoreCase("fexcore")) {
                String activeFEXCorePreset = container.getFEXCorePreset();
                if (shortcut != null) {
                    activeFEXCorePreset = shortcut.getExtra(
                            "fexcorePreset", shortcut.container.getFEXCorePreset());
                }
                envVars.putAll(FEXCorePresetManager.getEnvVars(
                        environment.getContext(), activeFEXCorePreset));

                // Proton 9.0 arm64ec resolves its FEX bridge through
                // libwow64fex.dll; requesting the legacy libarm64ecfex.dll
                // name silently kills the guest before any window appears.
                envVars.put("HODLL", selectedFexBridge);
            }
            else {
                envVars.put("HODLL", "wowbox64.dll");
            }
        }

        if (envVars.get("BOX64_MMAP32").equals("1") && !wineInfo.usesNativeArm64())
            envVars.put("WRAPPER_DISABLE_PLACED", "1");

        // Setting up essential environment variables for Wine
        envVars.put("HOME", imageFs.home_path);
        envVars.put("USER", ImageFs.USER);
        envVars.put("TMPDIR", rootDir.getPath() + "/usr/tmp");
        envVars.put("XDG_DATA_DIRS", rootDir.getPath() + "/usr/share");
        File wineLibRoot = wineProfile != null && wineProfile.wineLibPath != null
                ? ContentsManager.getSourceFile(context, wineProfile, wineProfile.wineLibPath)
                : new File(imageFs.getWinePath(), "lib");
        ArrayList<String> nativeLibraryPaths = new ArrayList<>();
        ArrayList<String> box64LibraryPaths = new ArrayList<>();
        ArrayList<String> wineDllPaths = new ArrayList<>();
        for (String architecture : new String[]{"aarch64-unix", "x86_64-unix", "i386-unix"}) {
            File moduleDir = WineRuntimeLayout.modules(wineLibRoot, architecture);
            if (moduleDir.isDirectory()) {
                wineDllPaths.add(moduleDir.getAbsolutePath());
                if (architecture.equals("aarch64-unix")) {
                    if (wineInfo.usesNativeArm64()) nativeLibraryPaths.add(moduleDir.getAbsolutePath());
                }
                else if (!wineInfo.usesNativeArm64()) {
                    box64LibraryPaths.add(moduleDir.getAbsolutePath());
                }
            }
        }
        if (wineLibRoot.isDirectory()) {
            if (wineInfo.usesNativeArm64()) nativeLibraryPaths.add(wineLibRoot.getAbsolutePath());
            else box64LibraryPaths.add(wineLibRoot.getAbsolutePath());
        }
        // LD_LIBRARY_PATH is consumed by Android's native arm64 linker. Never
        // put guest Wine modules in it: doing so can terminate the emulator before
        // Wine starts. Guest x86_64 paths belong to BOX64_LD_LIBRARY_PATH.
        nativeLibraryPaths.add(rootDir.getPath() + "/usr/lib");
        nativeLibraryPaths.add("/system/lib64");
        envVars.put("LD_LIBRARY_PATH", String.join(":", nativeLibraryPaths));
        if (!wineInfo.usesNativeArm64()) {
            File guestSystemLibs = new File(rootDir, "usr/lib/x86_64-linux-gnu");
            if (guestSystemLibs.isDirectory()) box64LibraryPaths.add(guestSystemLibs.getAbsolutePath());
            envVars.put("BOX64_LD_LIBRARY_PATH", String.join(":", box64LibraryPaths));
        }
        if (!wineDllPaths.isEmpty()) {
            envVars.put("WINEDLLPATH", String.join(":", wineDllPaths));
        }
        envVars.put("XDG_CONFIG_DIRS", rootDir.getPath() + "/usr/etc/xdg");
        envVars.put("GST_PLUGIN_PATH", rootDir.getPath() + "/usr/lib/gstreamer-1.0");
        envVars.put("FONTCONFIG_PATH", rootDir.getPath() + "/usr/etc/fonts");
        envVars.put("VK_LAYER_PATH", rootDir.getPath() + "/usr/share/vulkan/implicit_layer.d" + ":" + rootDir.getPath() + "/usr/share/vulkan/explicit_layer.d");
        envVars.put("WRAPPER_LAYER_PATH", rootDir.getPath() + "/usr/lib");
        envVars.put("WRAPPER_CACHE_PATH", rootDir.getPath() + "/usr/var/cache");
        envVars.put("WINE_NO_DUPLICATE_EXPLORER", "1");
        envVars.put("PREFIX", rootDir.getPath() + "/usr");
        envVars.put("DISPLAY", ":0");
        envVars.put("WINE_DISABLE_FULLSCREEN_HACK", "1");
        envVars.put("ENABLE_UTIL_LAYER", "1");
        envVars.put("GST_PLUGIN_FEATURE_RANK", "ximagesink:3000");
        envVars.put("ALSA_CONFIG_PATH", rootDir.getPath() + "/usr/share/alsa/alsa.conf" + ":" + rootDir.getPath() + "/usr/etc/alsa/conf.d/android_aserver.conf");
        envVars.put("ALSA_PLUGIN_DIR", rootDir.getPath() + "/usr/lib/alsa-lib");
        envVars.put("OPENSSL_CONF", rootDir.getPath() + "/usr/etc/tls/openssl.cnf");
        envVars.put("SSL_CERT_FILE", rootDir.getPath() + "/usr/etc/tls/cert.pem");
        envVars.put("SSL_CERT_DIR", rootDir.getPath() + "/usr/etc/tls/certs");
        envVars.put("WINE_X11FORCEGLX", "1");
        envVars.put("WINE_GST_NO_GL", "1");
        envVars.put("SteamGameId", "0");
        envVars.put("PROTON_AUDIO_CONVERT", "0");
        envVars.put("PROTON_VIDEO_CONVERT", "0");
        envVars.put("PROTON_DEMUX", "0");

        String winePath = wineProfile != null && wineProfile.wineBinPath != null
                ? ContentsManager.getSourceFile(context, wineProfile, wineProfile.wineBinPath).getAbsolutePath()
                : imageFs.getWinePath() + "/bin";

        Log.d("BionicProgramLauncherComponent", "WinePath is " + winePath);

        envVars.put("PATH", winePath + ":" +
                rootDir.getPath() + "/usr/bin");

 
        envVars.put("ANDROID_SYSVSHM_SERVER", rootDir.getPath() + UnixSocketConfig.SYSVSHM_SERVER_PATH);

        String primaryDNS = "8.8.4.4";
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Service.CONNECTIVITY_SERVICE);
        if (connectivityManager.getActiveNetwork() != null) {
            ArrayList<InetAddress> dnsServers = new ArrayList<>(connectivityManager.getLinkProperties(connectivityManager.getActiveNetwork()).getDnsServers());

            // Check if the dnsServers list is not empty before getting an item
            if (!dnsServers.isEmpty()) {
                primaryDNS = dnsServers.get(0).toString().substring(1);
            }
        }
        envVars.put("ANDROID_RESOLV_DNS", primaryDNS);
        envVars.put("WINE_NEW_NDIS", "1");
        
        String ld_preload = "";
        
        // Check for specific shared memory libraries
//        if ((new File(imageFs.getLibDir(), "libandroid-sysvshm.so")).exists()){
//            ld_preload = imageFs.getLibDir() + "/libandroid-sysvshm.so";
//        }

        //String nativeDir = context.getApplicationInfo().nativeLibraryDir; // e.g. /data/app/…/lib/arm64

        String sysvPath = imageFs.getLibDir() + "/libandroid-sysvshm.so";

        File fakeInputPath = new File(imageFs.getLibDir(), "libfakeinput.so");
        File packagedFakeInput = new File(context.getApplicationInfo().nativeLibraryDir,
                "libfakeinput.so");
        boolean fakeInputUsable = false;
        if (packagedFakeInput.isFile()) {
            // A truncated copy here gets LD_PRELOAD'd into wineserver and kills it
            // with SIGBUS on first stdout write. Keep verifying the size, but do
            // not rewrite an already complete library on every Wine launch.
            long packagedSize = packagedFakeInput.length();
            boolean copied = fakeInputPath.isFile() && fakeInputPath.length() == packagedSize;
            if (!copied) copied = FileUtils.copy(packagedFakeInput, fakeInputPath);
            if (!copied) {
                Log.e("FakeInput", "Failed to copy libfakeinput.so to " + fakeInputPath.getPath());
                FileUtils.delete(fakeInputPath);
            }
            else if (fakeInputPath.length() != packagedSize) {
                Log.e("FakeInput", "Truncated libfakeinput.so copy (" + fakeInputPath.length()
                        + " of " + packagedSize + " bytes); retrying once");
                FileUtils.delete(fakeInputPath);
                copied = FileUtils.copy(packagedFakeInput, fakeInputPath);
                if (!copied || fakeInputPath.length() != packagedSize) {
                    Log.e("FakeInput", "libfakeinput.so still incomplete; excluding from LD_PRELOAD");
                    FileUtils.delete(fakeInputPath);
                }
            }
            fakeInputUsable = fakeInputPath.isFile();
        }

        if (new File(sysvPath).exists()) ld_preload = sysvPath;
        if (fakeInputUsable) {
            ld_preload += (ld_preload.isEmpty() ? "" : ":") + fakeInputPath.getAbsolutePath();
        }
        else {
            Log.e("FakeInput", "Missing libfakeinput.so");
        }
        // System libraries some guest launchers/crash handlers dlopen
        // (same preloads the bionic base ships by default).
        String libjpeg = firstExistingFile("/system/lib64/libjpeg.so",
                "/system_ext/lib64/libjpeg.so");
        String libcrypto = firstExistingFile("/system/lib64/libcrypto.so",
                "/system_ext/lib64/libcrypto.so",
                new File(rootDir, "usr/lib/libcrypto.so.3").getAbsolutePath());
        for (String systemLib : new String[]{libjpeg, libcrypto}) {
            if (systemLib != null) {
                ld_preload += (ld_preload.isEmpty() ? "" : ":") + systemLib;
            }
        }
        if (!ld_preload.isEmpty()) envVars.put("LD_PRELOAD", ld_preload);
        File fakeInputDir = new File(rootDir, "dev/input");
        if (!fakeInputDir.isDirectory()) fakeInputDir.mkdirs();
        // FakeInputWriter creates a device only when it can supply events.
        // An empty placeholder is a permanently readable regular file at EOF.
        envVars.put("FAKE_EVDEV_DIR", fakeInputDir.getAbsolutePath());
        envVars.put("FAKE_EVDEV_VIBRATION", "1");
        
        // Merge any additional environment variables from external sources
        if (this.envVars != null) {
            envVars.putAll(this.envVars);
        }

        // Construct the native ARM64EC command or the Box64 x86_64 command.
        String command = "";
        String overriddenCommand = envVars.get("GUEST_PROGRAM_LAUNCHER_COMMAND");
        if (!overriddenCommand.isEmpty()) {
            String[] parts = overriddenCommand.split(";");
            for (String part : parts)
                command += part + " ";
            command = command.trim();
        }

        // Ensure the shared Box64 emulator is executable.
        if (!wineInfo.usesNativeArm64()) {
            File box64File = new File(rootDir, "/usr/bin/box64");
            if (box64File.exists()) {
                FileUtils.chmod(box64File, 0755);
            }
        }

        File prefix = new File(container.getRootDir(), ".wine");
        envVars.put("WINEPREFIX", prefix.getAbsolutePath());
        envVars.put("WINEARCH", wineInfo.isWin64() ? "win64" : "win32");
        EnvVars setupEnv = new EnvVars();
        setupEnv.putAll(envVars);
        // First boot must not wait for an online Mono/Gecko installer dialog.
        String overrides = setupEnv.get("WINEDLLOVERRIDES");
        setupEnv.put("WINEDLLOVERRIDES", (overrides.isEmpty() ? "" : overrides + ";") + "mscoree,mshtml=d");
        try {
            File bin = new File(winePath);
            File wineRoot = bin.getParentFile();
            WinePrefixBootstrap.prepare(prefix, wineInfo.isWin64(),
                    container.getWineVersion(), bin, new File(wineRoot, "share/wine/wine.inf"),
                    wineInfo.usesNativeArm64() ? null : new File(imageFs.getBinDir(), "box64"),
                    (args, timeout) -> {
                        String option = args[args.length - 1];
                        boolean cleanup = "-k".equals(option) || "-w".equals(option);
                        // Cancellation still has to stop the prefix's server if
                        // it was forked just as the user left the session.
                        return ProcessHelper.execAndWait(args, setupEnv.toStringArray(), rootDir,
                                timeout, () -> stopped && !cleanup);
                    },
                    () -> {
                        if (stopped) throw new IOException("Wine preparation cancelled");
                        if (prefixUpdatedCallback != null) prefixUpdatedCallback.run();
                        // A runtime update can materialize its own bridge files.
                        // Verify/reapply the chosen translator after Wine is off
                        // so the first game uses the selected build as well.
                        boolean ready = wineInfo.usesNativeArm64() ? extractEmulatorsDlls() : extractBox86_64Files();
                        if (!ready) throw new IOException(translatorFailure);
                        if (stopped) throw new IOException("Wine preparation cancelled");
                    });
            // The post-update installer can add environment overrides too.
            if (this.envVars != null) envVars.putAll(this.envVars);
            envVars.put("WINEPREFIX", prefix.getAbsolutePath());
            envVars.put("WINEARCH", wineInfo.isWin64() ? "win64" : "win32");
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            failStartup(failure.getMessage());
            return -1;
        }

        // Match the executable selected by bootstrap, including wine64-only WCPs.
        if (envVars.get("GUEST_PROGRAM_LAUNCHER_COMMAND").isEmpty()) {
            try {
                String wine = WinePrefixState.wineExecutable(new File(winePath)).getAbsolutePath().replace(" ", "\\ ");
                String args = guestExecutable.equals("wine") ? "" : guestExecutable.startsWith("wine ")
                        ? guestExecutable.substring(4) : " " + guestExecutable;
                command = (wineInfo.usesNativeArm64() ? "" : imageFs.getBinDir() + "/box64 ") + wine + args;
            } catch (IOException failure) {
                failStartup(failure.getMessage());
                return -1;
            }
        }
        synchronized (lock) {
            if (stopped) return -1;
            preparingPrefix = false;
            pid = ProcessHelper.exec(command, envVars.toStringArray(), rootDir, (status) -> {
                synchronized (lock) {
                    pid = -1;
                }
                if (!stopped && !environment.isWinetricksRunning() && terminationCallback != null)
                    terminationCallback.call(status);
            });
            if (pid == -1) failStartup("Could not start the selected Wine runtime");
            return pid;
        }
    }


    private void addBox64EnvVars(EnvVars envVars, boolean enableLogs) {
        envVars.put("BOX64_NOBANNER", ProcessHelper.PRINT_DEBUG && enableLogs ? "0" : "1");
        envVars.put("BOX64_DYNAREC", "1");

        if (enableLogs) {
            envVars.put("BOX64_LOG", "1");
            envVars.put("BOX64_DYNAREC_MISSING", "1");
        }

        envVars.putAll(Box86_64PresetManager.getEnvVars("box64", environment.getContext(), box64Preset));
        envVars.put("BOX64_X11GLX", "1");
    }

    public void suspendProcess() {
        synchronized (lock) {
            if (pid != -1) ProcessHelper.suspendProcess(pid);
        }
    }

    public void resumeProcess() {
        synchronized (lock) {
            if (pid != -1) ProcessHelper.resumeProcess(pid);
        }
    }

    public String execShellCommand(String command) {
        StringBuilder output = new StringBuilder();
        EnvVars envVars = new EnvVars();
        ImageFs imageFs = environment.getImageFs();

        envVars.put("PATH", imageFs.getRootDir().getPath() + "/usr/bin:/usr/local/bin:" + imageFs.getWinePath() + "/bin");

        // Execute the command and capture its output
        try {
            java.lang.Process process = Runtime.getRuntime().exec(command, envVars.toStringArray(), imageFs.getRootDir());
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));

            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            while ((line = errorReader.readLine()) != null) {
                output.append(line).append("\n");
            }

            process.waitFor();
        } catch (Exception e) {
            output.append("Error: ").append(e.getMessage());
        }

        return output.toString();
    }

    private static String firstExistingFile(String... paths) {
        for (String path : paths) {
            if (path != null && new File(path).isFile()) return path;
        }
        return null;
    }

    public void restartWineServer() {
        ProcessHelper.terminateAllWineProcesses();
        pid = execGuestProgram();
        Log.d("BionicProgramLauncherComponent", "Wine restarted successfully");

    }
}
