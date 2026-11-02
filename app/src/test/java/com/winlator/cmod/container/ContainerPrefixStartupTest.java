package com.winlator.cmod.container;

import android.app.Application;
import android.content.Context;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.core.WinePrefixBootstrap;
import com.winlator.cmod.core.WinePrefixState;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class ContainerPrefixStartupTest {
    private static void write(File root, String path, String value) throws Exception {
        File target = new File(root, path);
        Files.createDirectories(target.getParentFile().toPath());
        Files.write(target.toPath(), value.getBytes(StandardCharsets.UTF_8));
    }

    private static void entry(TarArchiveOutputStream tar, String path, String value) throws Exception {
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry entry = new TarArchiveEntry(path);
        entry.setSize(data.length);
        tar.putArchiveEntry(entry);
        tar.write(data);
        tar.closeArchiveEntry();
    }

    private File downloadedRuntime(Context context, boolean completePack) throws Exception {
        return downloadedRuntime(context, completePack, "10.0-arm64ec");
    }

    private File downloadedRuntime(Context context, boolean completePack, String version) throws Exception {
        ContentProfile profile = new ContentProfile();
        profile.type = ContentProfile.ContentType.CONTENT_TYPE_PROTON;
        profile.verName = version;
        File runtime = ContentsManager.getInstallDir(context, profile);
        for (String path : new String[]{"bin/wine", "bin/wine-preloader", "bin/wineserver",
                "share/wine/wine.inf", "lib/wine/aarch64-unix/ntdll.so"}) write(runtime, path, path);
        for (String arch : new String[]{"aarch64-windows", "i386-windows"})
            for (String name : new String[]{"ntdll.dll", "kernel32.dll", "wineboot.exe"})
                write(runtime, "lib/wine/" + arch + "/" + name, arch + name);
        write(runtime, "profile.json", new JSONObject().put("type", "Proton")
                .put("versionName", profile.verName).put("versionCode", 0).put("description", "Test package")
                .put("files", new JSONArray()).put("wine", new JSONObject().put("binPath", "bin")
                        .put("libPath", "lib").put("prefixPack", "prefixPack.txz")).toString());
        // Portable archive fixture: the real APK pack has Linux c:/z: links,
        // which cannot be canonicalized on a Windows host. Its alias path is
        // additionally exercised with the actual APK template on Android.
        try (XZCompressorOutputStream xz = new XZCompressorOutputStream(
                Files.newOutputStream(new File(runtime, "prefixPack.txz").toPath()));
             TarArchiveOutputStream tar = new TarArchiveOutputStream(xz)) {
            entry(tar, ".wine/.update-timestamp", "disable\n");
            for (String name : new String[]{"system.reg", "user.reg", "userdef.reg"})
                entry(tar, ".wine/" + name, "WINE REGISTRY Version 2\n#arch=win64\n[Software\\\\Wine]\n");
            if (completePack)
                for (String[] arch : new String[][]{{"aarch64-windows", "system32"}, {"i386-windows", "syswow64"}})
                    for (String name : new String[]{"ntdll.dll", "kernel32.dll", "wineboot.exe"})
                        entry(tar, ".wine/drive_c/windows/" + arch[1] + "/" + name, arch[0] + name);
        }
        return runtime;
    }

    private Container create(Context context, ContentsManager contents) throws Exception {
        return create(context, contents, "proton-10.0-arm64ec");
    }

    private Container create(Context context, ContentsManager contents, String version) throws Exception {
        contents.syncContents();
        ContainerManager manager = new ContainerManager(context);
        Method create = ContainerManager.class.getDeclaredMethod("createContainer",
                JSONObject.class, ContentsManager.class, ConfigImportJournal.class);
        create.setAccessible(true);
        return (Container) create.invoke(manager, new JSONObject()
                .put("name", "Alias regression").put("wineVersion", version)
                .put("dxwrapper", "wined3d"), contents, null);
    }

    @Test public void createPublishReloadAndNormalizeDownloadedCatalogNameNeverRepeatWineboot() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File runtime = downloadedRuntime(context, true);
        ContentsManager contents = new ContentsManager(context);
        Container created = create(context, contents);
        assertNotNull("The downloaded prefix pack must extract and publish successfully", created);
        Container reloaded = new ContainerManager(context).getContainers().get(0);
        assertEquals(created.getRootDir(), reloaded.getRootDir());
        ContentProfile profile = WineInfo.findInstalledRuntimeProfile(contents, reloaded.getWineVersion());
        assertNotNull(profile);
        assertFalse(profile.bakedInRuntime);
        // Same normalization that XServerDisplayActivity performs before Bionic startup.
        reloaded.setWineVersion(ContentsManager.getEntryName(profile));
        assertEquals("Proton-10.0-arm64ec-0", reloaded.getWineVersion());
        assertTrue(reloaded.saveData());
        assertFalse(WinePrefixBootstrap.prepare(new File(reloaded.getRootDir(), ".wine"), true,
                reloaded.getWineVersion(), new File(runtime, "bin"), new File(runtime, "share/wine/wine.inf"),
                null, (command, timeout) -> { fail("Unexpected Wine process after catalog normalization"); return -1; },
                () -> fail("Unexpected duplicate component installation")));
    }

    @Test public void seedingMissingModulesDoesNotTurnAnIncompleteDownloadedPackIntoPrepared() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File runtime = downloadedRuntime(context, false);
        Container created = create(context, new ContentsManager(context));
        assertNotNull(created);
        File prefix = new File(created.getRootDir(), ".wine");
        assertTrue("Runtime DLLs were seeded", WinePrefixState.hasCoreFiles(prefix));
        assertFalse("Only the pack's original complete core qualifies", WinePrefixState.isPrepared(prefix,
                WinePrefixState.fingerprint(created.getWineVersion(), new File(runtime, "bin/wine"),
                        new File(runtime, "share/wine/wine.inf"))));
        List<String> phases = new ArrayList<>();
        assertTrue(WinePrefixBootstrap.prepare(prefix, true, created.getWineVersion(), new File(runtime, "bin"),
                new File(runtime, "share/wine/wine.inf"), null, (command, timeout) -> {
                    phases.add(command[command.length - 1]); return 0;
                }, () -> phases.add("components")));
        assertEquals(Arrays.asList("-u", "-k", "-w", "components"), phases);
    }

    @Test public void downloadedNestedRuntimeLayoutUsesTheSameSetupFileAsTheLauncher() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File runtime = downloadedRuntime(context, true);
        Files.createDirectory(new File(runtime, "usr").toPath());
        for (String dir : new String[]{"bin", "lib", "share"})
            Files.move(new File(runtime, dir).toPath(), new File(runtime, "usr/" + dir).toPath());
        File manifest = new File(runtime, "profile.json");
        JSONObject profile = new JSONObject(new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8));
        profile.getJSONObject("wine").put("binPath", "usr/bin").put("libPath", "usr/lib");
        Files.write(manifest.toPath(), profile.toString().getBytes(StandardCharsets.UTF_8));
        Container created = create(context, new ContentsManager(context));
        assertNotNull(created);
        assertFalse(WinePrefixBootstrap.prepare(new File(created.getRootDir(), ".wine"), true,
                "Proton-10.0-arm64ec-0", new File(runtime, "usr/bin"), new File(runtime, "usr/share/wine/wine.inf"),
                null, (command, timeout) -> { fail("Prepared nested layout repeated Wine"); return -1; },
                () -> fail("Prepared nested layout repeated components")));
    }

    @Test public void downloadedProtonWithTheBundledVersionUsesItsOwnPreparedPack() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File runtime = downloadedRuntime(context, true, "9.0-arm64ec");
        ContentsManager contents = new ContentsManager(context);
        Container created = create(context, contents, "proton-9.0-arm64ec");
        assertNotNull(created);
        assertFalse(WineInfo.findInstalledRuntimeProfile(contents, created.getWineVersion()).bakedInRuntime);
        File prefix = new File(created.getRootDir(), ".wine");
        assertFalse(WinePrefixBootstrap.prepare(prefix, true, "Proton-9.0-arm64ec-0", new File(runtime, "bin"),
                new File(runtime, "share/wine/wine.inf"), null,
                (command, timeout) -> { fail("Managed Proton 9 must use its own complete pack"); return -1; },
                () -> fail("Prepared managed Proton 9 repeated components")));
    }
}
