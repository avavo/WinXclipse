package com.winlator.cmod.store

import android.content.Context
import android.util.Log
import com.winlator.cmod.container.Container
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.core.ExeIconExtractor
import com.winlator.cmod.core.ShortcutArtworkManager
import com.winlator.cmod.core.StringUtils
import com.winlator.cmod.steam.SteamControls
import java.io.File
import java.io.IOException

/**
 * The container side of launching a store game (Steam, GOG…): a drive that shows the
 * store's library to Wine, a home-screen shortcut with the store's artwork, fullscreen and
 * first controls, keeping whatever the user changed in the game's settings since.
 */
object StoreShortcuts {
    private const val TAG = "StoreShortcuts"

    // Bumped when the first-controls choice changes (2: DOS games get the DOS keyboard).
    private const val CONTROLS_VERSION = "2"

    /** Adds a drive for [libraryRoot] when none of the container's drives covers [dir]. */
    fun ensureDrive(container: Container, dir: File, libraryRoot: File): String? {
        if (driveFor(container, dir) != null) return null
        val used = container.drivesIterator().map { it[0].uppercase() }.toSet() + setOf("A", "B", "C", "Z")
        val letter = (listOf("S", "G") + ('D'..'Y').map { it.toString() }).first { it !in used }
        val root = libraryRoot.path
        container.drives = container.drives + "$letter:$root"
        container.saveData()
        Log.i(TAG, "Added drive $letter: -> $root to container ${container.id}")
        return "$letter:"
    }

    /** The container drive (letter, path) whose folder contains [file]. */
    private fun driveFor(container: Container, file: File): Pair<String, String>? =
        container.drivesIterator()
            .map { it[0].uppercase() to it[1].trimEnd('/') }
            .filter { (letter, path) -> letter.isNotEmpty() && path.isNotEmpty() && (file.path == path || file.path.startsWith("$path/")) }
            .maxByOrNull { it.second.length }

    fun windowsPath(container: Container, file: File): String {
        val (letter, path) = driveFor(container, file) ?: throw IOException("No drive for ${file.path}")
        return "$letter:" + file.path.removePrefix(path).replace('/', '\\').ifEmpty { "\\" }
    }

    /** Wine's view of the folder ("…/.wine/dosdevices/d:/…"), as other shortcuts store it. */
    private fun unixWorkDir(context: Context, container: Container, dir: File): String {
        val (letter, path) = driveFor(container, dir) ?: return dir.path
        return "${wineHome(context, container)}/.wine/dosdevices/${letter.lowercase()}:" + dir.path.removePrefix(path)
    }

    private fun wineHome(context: Context, container: Container): String {
        val imageFs = File(context.filesDir, "imagefs").absolutePath
        val root = container.rootDir.absolutePath
        return if (root.startsWith(imageFs)) root.removePrefix(imageFs) else "/home/xuser"
    }

    /**
     * Writes (or updates) the game's shortcut in [container].
     *
     * [store] prefixes the shortcut's bookkeeping extras ("steam", "gog"), [id] is stored as
     * `<store>Id`. [gameExe] names the game's window class (Wine uses the executable name),
     * so the renderer can stretch a windowed game ("forceFullscreen"). [controls] picks the
     * first on-screen controls; it runs once per shortcut.
     */
    fun writeShortcut(
        context: Context,
        container: Container,
        store: String,
        id: String,
        name: String,
        launchFile: File,
        execArgs: String,
        gameExe: File,
        controls: () -> SteamControls.Mode,
    ): Shortcut {
        val fileName = name.replace(Regex("[\\\\/:*?\"<>|]"), "").trim().ifEmpty { "$store $id" }
        val desktopDir = container.desktopDir.apply { mkdirs() }
        val file = File(desktopDir, "$fileName.desktop")

        // Keep what the user set in the game's settings (controls profile, fullscreen, etc.):
        // defaults only once, the store's arguments only while the user has not edited them.
        val extras = readExtras(file)
        if (extras["${store}Defaults"] != "1") {
            extras.putIfAbsent("forceFullscreen", "1")
            extras[ShortcutArtworkManager.EXTRA_MODE] = ShortcutArtworkManager.MODE_CUSTOM
            extras["${store}Defaults"] = "1"
        }
        // First controls once; picked again when a newer WinXclipse picks better (new modes), as
        // long as the user kept the profile picked last time.
        val firstControls = extras["${store}ControlsDefaults"] != "1"
        val previous = SteamControls.Mode.entries.firstOrNull { it.name.lowercase() == extras["${store}Controls"] }
        val outdated = !firstControls && extras["${store}ControlsVersion"] != CONTROLS_VERSION && previous != null &&
            extras["controlsProfile"] == SteamControls.profileOf(previous)?.toString()
        if (firstControls || outdated) {
            val mode = controls()
            Log.i(TAG, "$store $id: first controls $mode")
            if (outdated && mode != previous) {
                extras.remove("controlsProfile")
                if (previous == SteamControls.Mode.MOUSE_TOUCH && extras["simTouchScreen"] == "1") extras.remove("simTouchScreen")
            }
            if (!extras.containsKey("controlsProfile")) {
                SteamControls.profileOf(mode)?.let { extras["controlsProfile"] = it.toString() }
                if (mode == SteamControls.Mode.MOUSE_TOUCH) extras.putIfAbsent("simTouchScreen", "1")
            }
            extras["${store}Controls"] = mode.name.lowercase()
            extras["${store}ControlsDefaults"] = "1"
        }
        extras["${store}ControlsVersion"] = CONTROLS_VERSION
        if (extras["execArgs"].orEmpty() == extras["${store}ExecArgs"].orEmpty()) {
            if (execArgs.isEmpty()) extras.remove("execArgs") else extras["execArgs"] = execArgs
        }
        extras["${store}ExecArgs"] = execArgs
        extras["${store}AppId"] = id

        val content = buildString {
            append("[Desktop Entry]\n")
            append("Name=$fileName\n")
            append("Exec=env WINEPREFIX=\"${wineHome(context, container)}/.wine\" wine ${StringUtils.escapeFileDOSPath(windowsPath(container, launchFile))}\n")
            append("Type=Application\n")
            append("Path=${unixWorkDir(context, container, launchFile.parentFile!!)}\n")
            append("Icon=$fileName\n")
            append("StartupWMClass=${gameExe.name}\n")
            append("container_id=${container.id}\n")
            append("\n[Extra Data]\n")
            extras.forEach { (key, value) -> append("$key=$value\n") }
        }
        if (!Shortcut.writeDesktopFileWithBackup(file, content)) throw IOException("Could not write $file")
        return Shortcut(container, file)
    }

    /** The [Extra Data] section of an existing shortcut, in order. */
    private fun readExtras(file: File): LinkedHashMap<String, String> {
        val extras = LinkedHashMap<String, String>()
        if (!file.isFile) return extras
        var inExtras = false
        for (line in file.readLines()) {
            val text = line.trim()
            if (text.startsWith("[")) {
                inExtras = text == "[Extra Data]"
                continue
            }
            val eq = text.indexOf('=')
            if (inExtras && eq > 0) extras[text.substring(0, eq)] = text.substring(eq + 1)
        }
        return extras
    }

    /** The store's cover, hero and logo for the home screen, and the exe icon for menus. */
    fun copyArtwork(shortcut: Shortcut, portrait: File?, hero: File?, logo: File?, exe: File) {
        val cover = shortcut.generatedCoverArtFile
        portrait?.let {
            cover.parentFile?.mkdirs()
            it.copyTo(cover, overwrite = true)
        }
        hero?.copyTo(ShortcutArtworkManager.getHeroFile(shortcut), overwrite = true)
        logo?.copyTo(ShortcutArtworkManager.getLogoFile(shortcut), overwrite = true)
        val icon = File(shortcut.container.getIconsDir(64).apply { mkdirs() }, "${shortcut.name}.png")
        if (!icon.isFile && exe.name.endsWith(".exe", true)) ExeIconExtractor.extractAsync(exe, icon, false, null)
        shortcut.reloadCoverArt()
    }
}
