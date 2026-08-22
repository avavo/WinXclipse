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

public class BionicProgramLauncherComponent extends GuestProgramLauncherComponent {
    private String guestExecutable;
    private static int pid = -1;
    private String[] bindingPaths;
    private EnvVars envVars;
    private WineInfo wineInfo;
    private String box64Preset = Box86_64Preset.COMPATIBILITY;
    private Callback<Integer> terminationCallback;
    private static final Object lock = new Object();
    private boolean wow64Mode = true;
    private final ContentsManager contentsManager;
    private final ContentProfile wineProfile;
    private Container container;
    private final Shortcut shortcut;

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


    private void extractBox86_64Files() {
        ImageFs imageFs = environment.getImageFs();
        Context context = environment.getContext();
        String box64Version = container.getBox64Version();

        if (shortcut != null) {
            box64Version = shortcut.getExtra("box64Version", shortcut.container.getBox64Version());
        }

        Log.i("BionicProgramLauncherComponent", "Extracting required box64 version: " + box64Version);
        File rootDir = imageFs.getRootDir();

        // No more version check, just extract directly.
        ContentProfile profile = contentsManager.getProfileByEntryName("box64-" + box64Version);
        if (profile == null && !DefaultVersion.BOX64.equals(box64Version)) {
            Log.w("BionicProgramLauncherComponent", "Selected Box64 is no longer bundled; migrating to " + DefaultVersion.BOX64);
            box64Version = DefaultVersion.BOX64;
            profile = contentsManager.getProfileByEntryName("box64-" + box64Version);
        }
        if (profile != null) {
            contentsManager.applyContent(profile);
        } else {
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "box86_64/box64-" + box64Version + ".tzst", rootDir);
        }

        // Update the metadata so the container knows which version is installed.
        container.putExtra("box64Version", box64Version);
        container.saveData();

        // Set execute permissions.
        File box64File = new File(rootDir, "usr/bin/box64");
        if (box64File.exists()) {
            FileUtils.chmod(box64File, 0755);
        }
    }



    private void extractEmulatorsDlls() {;
        Context context = environment.getContext();
        File rootDir = environment.getImageFs().getRootDir();
        File system32dir = new File(rootDir + "/home/xuser/.wine/drive_c/windows/system32");
        boolean containerDataChanged = false;

        String fexcoreVersion = container.getFEXCoreVersion();

        if (shortcut != null) {
            fexcoreVersion = shortcut.getExtra("fexcoreVersion", shortcut.container.getFEXCoreVersion());
        }

        String fexcorePreset = container.getFEXCorePreset();
        if (shortcut != null) {
            fexcorePreset = shortcut.getExtra("fexcorePreset", shortcut.container.getFEXCorePreset());
        }
        Log.d("BionicProgramLauncherComponent", "fexcoreVersion in use: " + fexcoreVersion);
        Log.d("BionicProgramLauncherComponent", "fexcorePreset in use: " + fexcorePreset);

        File fexDll = new File(system32dir, "libwow64fex.dll");
        if (!fexcoreVersion.equals(container.getExtra("fexcoreVersion")) || !fexDll.isFile()) {
            ContentProfile profile = contentsManager.getProfile(
                    ContentProfile.ContentType.CONTENT_TYPE_FEXCORE, fexcoreVersion);
            if (profile != null)
                contentsManager.applyContent(profile);
            else
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, environment.getContext(), "fexcore/fexcore-" + fexcoreVersion + ".tzst", system32dir);
            container.putExtra("fexcoreVersion", fexcoreVersion);
            containerDataChanged = true;
        }
        if (containerDataChanged) container.saveData();
    }

    public BionicProgramLauncherComponent(ContentsManager contentsManager, ContentProfile wineProfile, Shortcut shortcut) {
        this.contentsManager = contentsManager;
        this.wineProfile = wineProfile;
        this.shortcut = shortcut;
    }

    @Override
    public void start() {
        synchronized (lock) {
            if (wineInfo.isArm64EC())
                extractEmulatorsDlls();
            else
                extractBox86_64Files();
//            checkDependencies();
            // If we end up needing to inject winebus.so into user installed contents
//            EvshimPatcher.patchWineTree(
//                    environment.getContext(),
//                    new File(environment.getImageFs().getWinePath()),
//                    wineInfo.isArm64EC());
            pid = execGuestProgram();
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            if (pid != -1) {
                Process.killProcess(pid);
                pid = -1;
            }
        }
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


        if (true) {

            envVars.put("EVSHIM_SHM_ID", 1);

        }

        addBox64EnvVars(envVars, enableBox86_64Logs);

        String emulator = container.getEmulator();
        if (shortcut != null) {
            emulator = shortcut.getExtra("emulator", container.getEmulator());
        }

        // User-installed ARM64EC Wine/Proton packages follow the proven Mali
        // launcher path. They are always started through FEXCore; this avoids
        // interpreting a newer package with an incompatible WOWBox runtime.
        if (wineInfo.isArm64EC()) emulator = "fexcore";

        if (wineInfo.isArm64EC()) {
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
                File system32Dir = new File(rootDir,
                        "/home/xuser/.wine/drive_c/windows/system32");
                envVars.put("HODLL",
                        new File(system32Dir, "libwow64fex.dll").isFile()
                                ? "libwow64fex.dll"
                                : new File(system32Dir, "libarm64ecfex.dll").isFile()
                                        ? "libarm64ecfex.dll" : "libwow64fex.dll");
            }
            else {
                envVars.put("HODLL", "wowbox64.dll");
            }
        }

        if (envVars.get("BOX64_MMAP32").equals("1") && !wineInfo.isArm64EC())
            envVars.put("WRAPPER_DISABLE_PLACED", "1");

        // Setting up essential environment variables for Wine
        envVars.put("HOME", imageFs.home_path);
        envVars.put("USER", ImageFs.USER);
        envVars.put("TMPDIR", rootDir.getPath() + "/usr/tmp");
        envVars.put("XDG_DATA_DIRS", rootDir.getPath() + "/usr/share");
        File wineLibRoot = wineProfile != null && wineProfile.wineLibPath != null
                ? ContentsManager.getSourceFile(context, wineProfile, wineProfile.wineLibPath)
                : new File(imageFs.getWinePath(), "lib");
        ArrayList<String> libraryPaths = new ArrayList<>();
        ArrayList<String> wineDllPaths = new ArrayList<>();
        for (String architecture : new String[]{"aarch64-unix", "x86_64-unix", "i386-unix"}) {
            File moduleDir = new File(wineLibRoot, "wine/" + architecture);
            if (moduleDir.isDirectory()) {
                libraryPaths.add(moduleDir.getAbsolutePath());
                wineDllPaths.add(moduleDir.getAbsolutePath());
            }
        }
        if (wineLibRoot.isDirectory()) libraryPaths.add(wineLibRoot.getAbsolutePath());
        libraryPaths.add(rootDir.getPath() + "/usr/lib");
        libraryPaths.add("/system/lib64");
        envVars.put("LD_LIBRARY_PATH", String.join(":", libraryPaths));
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
        if (packagedFakeInput.isFile()) FileUtils.copy(packagedFakeInput, fakeInputPath);

        if (new File(sysvPath).exists()) ld_preload = sysvPath;
        if (fakeInputPath.isFile()) {
            ld_preload += (ld_preload.isEmpty() ? "" : ":") + fakeInputPath.getAbsolutePath();
        }
        else {
            Log.e("FakeInput", "Missing libfakeinput.so");
        }
        if (!ld_preload.isEmpty()) envVars.put("LD_PRELOAD", ld_preload);
        File fakeInputDir = new File(rootDir, "dev/input");
        if (!fakeInputDir.isDirectory()) fakeInputDir.mkdirs();
        File event0 = new File(fakeInputDir, "event0");
        try {
            if (!event0.exists()) event0.createNewFile();
        }
        catch (IOException e) {
            Log.e("FakeInput", "Could not create initial controller device", e);
        }
        envVars.put("FAKE_EVDEV_DIR", fakeInputDir.getAbsolutePath());
        envVars.put("FAKE_EVDEV_VIBRATION", "1");
        
        // Merge any additional environment variables from external sources
        if (this.envVars != null) {
            envVars.putAll(this.envVars);
        }

        // Construct the command without Box64 to the Wine executable
        String command = "";
        String overriddenCommand = envVars.get("GUEST_PROGRAM_LAUNCHER_COMMAND");
        if (!overriddenCommand.isEmpty()) {
            String[] parts = overriddenCommand.split(";");
            for (String part : parts)
                command += part + " ";
            command = command.trim();
        }
        else {
            if (wineInfo.isArm64EC()) {
                command = winePath + "/" + guestExecutable;
            }
            else
                command = imageFs.getBinDir() + "/box64 " + guestExecutable;
        }

        // **Maybe remove this: Set execute permissions for box64 if necessary (Glibc/Proot artifact)
        File box64File = new File(rootDir, "/usr/bin/box64");
        if (box64File.exists()) {
            FileUtils.chmod(box64File, 0755);
        }

        return ProcessHelper.exec(command, envVars.toStringArray(), rootDir, (status) -> {
            synchronized (lock) {
                pid = -1;
            }
            if (!environment.isWinetricksRunning()) {
                if (terminationCallback != null)
                    terminationCallback.call(status);
            }
        });
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

    public void restartWineServer() {
        ProcessHelper.terminateAllWineProcesses();
        pid = execGuestProgram();
        Log.d("BionicProgramLauncherComponent", "Wine restarted successfully");

    }
}
