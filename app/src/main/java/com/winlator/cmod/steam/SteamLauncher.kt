package com.winlator.cmod.steam

import android.content.Context
import android.util.Log
import com.winlator.cmod.R
import com.winlator.cmod.container.Container
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.store.DosBox
import com.winlator.cmod.store.StoreRedists
import com.winlator.cmod.store.StoreShortcuts
import com.winlator.cmod.store.WineMono
import com.winlator.cmod.core.ExeIconExtractor
import com.winlator.cmod.core.ShortcutArtworkManager
import com.winlator.cmod.core.StringUtils
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns an installed Steam game into a container shortcut that runs it, for any game:
 *
 *  - the executable, arguments and working folder come from the game's Steam launch
 *    options (Windows ones, 64-bit preferred), with a scan of the folder as a fallback;
 *  - games protected by SteamStub DRM (a `.bind` PE section) start through gbe_fork's
 *    ColdClientLoader, which provides an emulated steamclient and patches the stub in memory;
 *  - other games that ship `steam_api(64).dll` get gbe_fork's emulator in its place (the
 *    original is kept as `.orig`), with a `steam_settings` folder for the account, app id
 *    and owned DLC;
 *  - games without Steam integration start as they are.
 *
 * gbe_fork (LGPL-3.0) ships in assets/gbe. The Steam library must be visible to Wine:
 * when no container drive covers it, a drive letter is added for it.
 */
object SteamLauncher {
    private const val TAG = "SteamLauncher"
    private const val EMU_DIR = "_winxclipse_steam"
    private const val ASSETS = "gbe"

    /** Result of [prepare]: the shortcut to launch and, when one was added, the new drive. */
    data class Prepared(val shortcut: Shortcut, val addedDrive: String?)

    suspend fun prepare(context: Context, game: SteamGame, installDir: File, container: Container): Prepared =
        withContext(Dispatchers.IO) {
            val target = pickTarget(context, game, installDir)
            val addedDrive = StoreShortcuts.ensureDrive(container, installDir, SteamDownloads.libraryRoot)
            val exe = target.exe
            val pe = PeInfo.read(exe)
            val account = SteamSession.savedAccount()
            val settings = EmuSettings(game.appId, account?.accountName ?: "Player", account?.steamId64 ?: 0L, game.dlcs.orEmpty())

            // What actually starts: the game, or the ColdClientLoader that starts it.
            val run: Target
            if (pe?.hasSteamStub == true) {
                Log.i(TAG, "${game.appId}: SteamStub DRM, using ColdClientLoader (${if (pe.is64Bit) 64 else 32}-bit)")
                restoreSteamApi(installDir)
                val loader = installColdClient(context, container, installDir, target, pe.is64Bit, settings)
                run = Target(loader, "", null)
            } else {
                val replaced = replaceSteamApi(context, installDir, settings)
                Log.i(TAG, "${game.appId}: replaced $replaced steam_api dll(s), launching ${exe.name}")
                if (replaced > 0) File(exe.parentFile, "steam_appid.txt").writeText(game.appId.toString())
                run = target
            }

            if (WineMono.isDotNet(exe) || shipsDotNet(installDir)) WineMono.ensure(context, container)
            // DOS re-releases that run Steam's DOSBox, adapted to run well under Wine here.
            val dosBox = DosBox.adapt(run.exe, run.arguments, File(installDir, "$EMU_DIR/dosbox.conf")) {
                StoreShortcuts.windowsPath(container, it)
            }

            // A batch file is needed for Steam's first-run redistributables (each runs once per
            // container), for environment variables, or when the game wants a working folder
            // other than its executable's (shortcuts start there).
            val redists = redists(installDir)
            val needsWorkDir = run.workDir != null && run.workDir != run.exe.parentFile
            val launchFile: File
            val execArgs: String
            if (redists.isNotEmpty() || dosBox.env.isNotEmpty() || needsWorkDir) {
                launchFile = StoreRedists.writeLaunchBatch(
                    container, File(installDir, "$EMU_DIR/launch.bat"),
                    run.exe, dosBox.arguments, run.workDir ?: run.exe.parentFile!!, redists, dosBox.env,
                )
                execArgs = ""
            } else {
                launchFile = run.exe
                execArgs = dosBox.arguments
            }

            val shortcut = StoreShortcuts.writeShortcut(
                context, container, "steam", game.appId.toString(), game.name, launchFile, execArgs, exe,
            ) { SteamControls.choose(game.controller, exe) }
            StoreShortcuts.copyArtwork(
                shortcut,
                portrait = SteamArt.file(context, game.appId, SteamArt.Kind.Portrait),
                hero = SteamArt.file(context, game.appId, SteamArt.Kind.Hero),
                logo = SteamArt.file(context, game.appId, SteamArt.Kind.Logo),
                exe = exe,
            )
            Prepared(shortcut, addedDrive)
        }

    // region Which executable

    private class Target(val exe: File, val arguments: String, val workDir: File?)

    private fun pickTarget(context: Context, game: SteamGame, installDir: File): Target {
        val windows = game.launch.orEmpty().filter { option ->
            option.executable.isNotBlank() && option.betaKey.isBlank() &&
                (option.osList.isBlank() || option.osList.split(",").any { it.trim().equals("windows", true) }) &&
                option.osArch.let { it.isBlank() || it == "64" || it == "32" }
        }
        val ranked = windows.sortedWith(
            compareBy<SteamLaunch>({ typeRank(it.type) }, { if (it.osArch == "64") 0 else if (it.osArch.isBlank()) 1 else 2 })
        )
        for (option in ranked) {
            val exe = resolve(installDir, option.executable) ?: continue
            if (!exe.isFile || !isLaunchable(exe.name)) continue
            val workDir = option.workingDir.takeIf { it.isNotBlank() }?.let { resolve(installDir, it) }?.takeIf { it.isDirectory }
            return Target(exe, option.arguments.trim(), workDir)
        }
        val fallback = scanForExe(installDir)
            ?: throw IOException(context.getString(R.string.steam_play_no_exe))
        Log.i(TAG, "${game.appId}: no usable launch option, using ${fallback.path}")
        return Target(fallback, "", null)
    }

    /** Default launch first, then alternatives ("option"), then servers, editors, VR. */
    private fun typeRank(type: String): Int = when (type.lowercase()) {
        "", "default", "none" -> 0
        "option", "option1", "option2", "option3" -> 1
        else -> 2
    }

    private fun isLaunchable(name: String): Boolean =
        name.endsWith(".exe", true) || name.endsWith(".bat", true) || name.endsWith(".cmd", true)

    /** Resolves a Steam relative path ("bin\\Game.exe") on the phone, ignoring letter case. */
    private fun resolve(base: File, relative: String): File? {
        var current = base
        for (part in relative.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }) {
            if (part == "..") {
                current = current.parentFile ?: return null
                continue
            }
            val exact = File(current, part)
            current = if (exact.exists()) exact
            else current.listFiles()?.firstOrNull { it.name.equals(part, ignoreCase = true) } ?: return null
        }
        return current
    }

    private val NOT_THE_GAME = Regex(
        "(unins|setup|install|redist|vcredist|vc_redist|dxsetup|dotnet|crash|report|helper|cef|easyanticheat|battleye|uninstall|updater)",
        RegexOption.IGNORE_CASE,
    )

    /** Largest executable in the top two levels that does not look like a tool or installer. */
    private fun scanForExe(installDir: File): File? =
        installDir.walkTopDown().maxDepth(2)
            .filter { it.isFile && it.name.endsWith(".exe", true) && !NOT_THE_GAME.containsMatchIn(it.name) }
            .filterNot { it.path.contains(EMU_DIR) || it.path.contains("_CommonRedist", true) }
            .maxByOrNull { it.length() }

    // endregion

    // region Steam emulation (gbe_fork)

    private class EmuSettings(val appId: Int, val accountName: String, val steamId64: Long, val dlcs: Map<Int, String>)

    private fun gbeVersion(context: Context): String =
        context.assets.open("$ASSETS/VERSION.txt").bufferedReader().use { it.readLine().orEmpty() }

    /**
     * Replaces every steam_api(64).dll with gbe_fork's; returns how many the game has.
     * A `<dll>.winxclipse` marker records version and size of the emulator we wrote, so a
     * game update that brings back Valve's dll is noticed and becomes the new `.orig`.
     */
    private fun replaceSteamApi(context: Context, installDir: File, settings: EmuSettings): Int {
        val version = gbeVersion(context)
        val dlls = steamApiDlls(installDir)
        for (dll in dlls) {
            val asset = if (dll.name.equals("steam_api64.dll", true)) "steam_api64.dll" else "steam_api.dll"
            val original = File(dll.path + ".orig")
            val marker = File(dll.path + ".winxclipse")
            val settingsDir = File(dll.parentFile, "steam_settings")
            val ours = marker.isFile && marker.readText() == "$version:${dll.length()}"
            if (!ours) {
                val isValveDll = !marker.isFile || marker.readText().substringAfter(':') != dll.length().toString()
                if (isValveDll && (!original.exists() || marker.isFile)) {
                    File(settingsDir, "steam_interfaces.txt").delete()
                    writeInterfaces(dll, settingsDir)
                    dll.copyTo(original, overwrite = true)
                }
                copyAsset(context, asset, dll)
                marker.writeText("$version:${dll.length()}")
            }
            writeSteamSettings(settingsDir, settings)
        }
        return dlls.size
    }

    /** Puts the game's own steam_api dlls back (for ColdClientLoader, which needs them). */
    private fun restoreSteamApi(installDir: File) {
        installDir.walkTopDown().maxDepth(6)
            .filter { it.isFile && it.name.startsWith("steam_api", true) && it.name.endsWith(".dll.orig", true) }
            .toList()
            .forEach { original ->
                val dll = File(original.path.removeSuffix(".orig"))
                dll.delete()
                original.renameTo(dll)
                File(dll.path + ".winxclipse").delete()
            }
    }

    private fun steamApiDlls(installDir: File): List<File> =
        installDir.walkTopDown().maxDepth(6)
            .filter { it.isFile && (it.name.equals("steam_api.dll", true) || it.name.equals("steam_api64.dll", true)) }
            .filterNot { it.path.contains(EMU_DIR) }
            .toList()

    private fun installColdClient(
        context: Context,
        container: Container,
        installDir: File,
        target: Target,
        is64Bit: Boolean,
        settings: EmuSettings,
    ): File {
        val dir = File(installDir, EMU_DIR).apply { mkdirs() }
        val loader = if (is64Bit) "steamclient_loader_x64.exe" else "steamclient_loader_x86.exe"
        val extra = if (is64Bit) "steamclient_extra_x64.dll" else "steamclient_extra_x86.dll"
        val marker = File(dir, ".gbe_version")
        val version = gbeVersion(context) + ":" + if (is64Bit) "x64" else "x86"
        if (!marker.isFile || marker.readText() != version || !File(dir, loader).isFile) {
            for (name in listOf(loader, "steamclient.dll", "steamclient64.dll", "GameOverlayRenderer.dll", "GameOverlayRenderer64.dll")) {
                copyAsset(context, name, File(dir, name))
            }
            File(dir, "extra_dlls").deleteRecursively()
            copyAsset(context, extra, File(dir, "extra_dlls/$extra"))
            marker.writeText(version)
        }
        writeSteamSettings(File(dir, "steam_settings"), settings)
        val exe = StoreShortcuts.windowsPath(container, target.exe)
        val runDir = target.workDir?.let { StoreShortcuts.windowsPath(container, it) }.orEmpty()
        File(dir, "ColdClientLoader.ini").writeText(
            """
            |[SteamClient]
            |Exe=$exe
            |ExeRunDir=$runDir
            |ExeCommandLine=${target.arguments}
            |AppId=${settings.appId}
            |SteamClientDll=steamclient.dll
            |SteamClient64Dll=steamclient64.dll
            |
            |[Injection]
            |ForceInjectSteamClient=0
            |ForceInjectGameOverlayRenderer=0
            |DllsToInjectFolder=extra_dlls
            |IgnoreInjectionError=1
            |IgnoreLoaderArchDifference=1
            |
            |[Persistence]
            |Mode=0
            |
            |[Debug]
            |ResumeByDebugger=0
            |""".trimMargin().replace("\n", "\r\n")
        )
        return File(dir, loader)
    }

    private fun writeSteamSettings(dir: File, settings: EmuSettings) {
        dir.mkdirs()
        File(dir, "steam_appid.txt").writeText(settings.appId.toString())
        File(dir, "configs.user.ini").writeText(
            buildString {
                appendLine("[user::general]")
                appendLine("account_name=${settings.accountName}")
                if (settings.steamId64 != 0L) appendLine("account_steamid=${settings.steamId64}")
                // The depots were downloaded in English, so the game is told English too.
                appendLine("language=english")
            }
        )
        File(dir, "configs.main.ini").writeText("[main::connectivity]\ndisable_lan_only=1\n")
        File(dir, "configs.app.ini").writeText(
            buildString {
                appendLine("[app::dlcs]")
                appendLine("unlock_all=0")
                settings.dlcs.toSortedMap().forEach { (id, name) -> appendLine("$id=${name.ifBlank { "DLC $id" }}") }
            }
        )
    }

    /**
     * Lists the Steam interface versions the game's original steam_api was built with
     * (strings like SteamUser021), so the emulator answers with matching ones.
     */
    private fun writeInterfaces(originalDll: File, settingsDir: File) {
        val out = File(settingsDir, "steam_interfaces.txt")
        if (out.exists()) return
        val pattern = Regex("^Steam[A-Za-z]+[0-9]{3}$")
        val found = sortedSetOf<String>()
        val text = StringBuilder()
        originalDll.inputStream().buffered().use { input ->
            while (true) {
                val b = input.read()
                if (b in 0x20..0x7E) {
                    text.append(b.toChar())
                    continue
                }
                if (text.length >= 10 && pattern.matches(text)) found += text.toString()
                text.setLength(0)
                if (b == -1) break
            }
        }
        if (found.isEmpty()) return
        settingsDir.mkdirs()
        out.writeText(found.joinToString("\n", postfix = "\n"))
    }

    private fun copyAsset(context: Context, name: String, dest: File) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".tmp")
        context.assets.open("$ASSETS/$name").use { input -> tmp.outputStream().use { input.copyTo(it) } }
        dest.delete()
        if (!tmp.renameTo(dest)) throw IOException("Could not write $dest")
    }

    // endregion

    // region Container side

    private fun commonRedist(installDir: File): File? =
        installDir.listFiles()?.firstOrNull { it.isDirectory && it.name.equals("_CommonRedist", true) }

    /**
     * Visual C++ runtimes the game ships in _CommonRedist (what Steam installs on first
     * launch). .NET and XNA come from Wine Mono instead; Wine covers DirectX itself.
     */
    private fun redists(installDir: File): List<StoreRedists.Redist> {
        val vcDir = commonRedist(installDir)?.listFiles()?.firstOrNull { it.isDirectory && it.name.equals("vcredist", true) }
            ?: return emptyList()
        return vcDir.walkTopDown().maxDepth(3)
            .filter { it.isFile && it.name.endsWith(".exe", true) && it.name.startsWith("vc", true) }
            .map { file ->
                val year = file.parentFile?.name.orEmpty()
                val arguments = when {
                    file.name.startsWith("vc_redist", true) -> "/install /quiet /norestart"
                    year.startsWith("2005") || year.startsWith("2008") -> "/q"
                    else -> "/q /norestart"
                }
                StoreRedists.Redist(file, "steam-$year-${file.name.lowercase()}-${file.length()}", arguments)
            }
            .sortedBy { it.key }
            .toList()
    }

    /** True when the game ships .NET Framework or XNA installers, as .NET games do. */
    private fun shipsDotNet(installDir: File): Boolean =
        commonRedist(installDir)?.listFiles()?.any {
            it.isDirectory && (it.name.startsWith("dotnet", true) || it.name.startsWith("xna", true))
        } == true

    // endregion

    /** The bits of a Windows executable's PE header that decide how to launch it. */
    private class PeInfo(val is64Bit: Boolean, val hasSteamStub: Boolean) {
        companion object {
            fun read(file: File): PeInfo? = runCatching {
                if (!file.name.endsWith(".exe", true)) return null
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(0x3C)
                    val peOffset = Integer.reverseBytes(raf.readInt()).toLong()
                    raf.seek(peOffset)
                    if (Integer.reverseBytes(raf.readInt()) != 0x00004550) return null // "PE\0\0"
                    val machine = java.lang.Short.reverseBytes(raf.readShort()).toInt() and 0xFFFF
                    val sections = java.lang.Short.reverseBytes(raf.readShort()).toInt() and 0xFFFF
                    raf.skipBytes(12)
                    val optionalSize = java.lang.Short.reverseBytes(raf.readShort()).toInt() and 0xFFFF
                    raf.skipBytes(2)
                    raf.seek(raf.filePointer + optionalSize)
                    var stub = false
                    repeat(sections.coerceAtMost(96)) {
                        val name = ByteArray(8).also { raf.readFully(it) }
                        if (String(name, Charsets.US_ASCII).trimEnd('\u0000') == ".bind") stub = true
                        raf.skipBytes(32)
                    }
                    PeInfo(is64Bit = machine == 0x8664 || machine == 0xAA64, hasSteamStub = stub)
                }
            }.getOrNull()
        }
    }
}
