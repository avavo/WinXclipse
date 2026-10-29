package com.winlator.cmod.home

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.contentdialog.DXVKConfigDialog
import com.winlator.cmod.contentdialog.GraphicsDriverConfigDialog
import com.winlator.cmod.contents.XclipseDriverManager
import com.winlator.cmod.core.DefaultVersion
import com.winlator.cmod.core.ShortcutArtworkManager
import java.io.File
import java.util.Locale

/** One game on the console home: a shortcut plus the stats XServerDisplayActivity records. */
class HomeGame(
    val shortcut: Shortcut,
    val name: String,
    val containerName: String,
    val cover: ImageBitmap?,
    val icon: ImageBitmap?,
    /** SteamGridDB hero/logo files; decoded lazily for the game on screen. */
    val heroFile: File?,
    val heroVersion: Long,
    val logoFile: File?,
    val logoVersion: Long,
    val lastPlayed: Long,
    val playtimeMs: Long,
    val playCount: Int,
    val driverLabel: String,
    val dxLabel: String,
    val wineLabel: String,
    val emulatorLabel: String,
    val screenSize: String,
    val exeName: String,
    val windowsPath: String,
) {
    val key: String get() = shortcut.file.path
}

object HomeGames {
    /** Same store as XServerDisplayActivity.savePlaytimeData()/incrementPlayCount(). */
    const val PLAYTIME_PREFS = "playtime_stats"

    /** Loads every shortcut of every container; most recently played first. Call off the UI thread. */
    fun load(context: Context): List<HomeGame> {
        val stats = context.getSharedPreferences(PLAYTIME_PREFS, Context.MODE_PRIVATE)
        val drivers = XclipseDriverManager(context)
        return ContainerManager(context).loadShortcutsFast().map { shortcut ->
            val container = shortcut.container
            val hero = ShortcutArtworkManager.getHeroFile(shortcut).takeIf { it.isFile }
            val logo = ShortcutArtworkManager.getLogoFile(shortcut).takeIf { it.isFile }
            val windowsPath = shortcut.path.trim().trim('"')
            HomeGame(
                shortcut = shortcut,
                name = displayName(shortcut.name),
                containerName = container.name,
                cover = shortcut.coverArt?.asImageBitmap(),
                icon = shortcut.icon?.asImageBitmap(),
                heroFile = hero,
                heroVersion = hero?.lastModified() ?: 0L,
                logoFile = logo,
                logoVersion = logo?.lastModified() ?: 0L,
                lastPlayed = stats.getLong(shortcut.name + "_last_played", 0L),
                playtimeMs = stats.getLong(shortcut.name + "_playtime", 0L),
                playCount = stats.getInt(shortcut.name + "_play_count", 0),
                driverLabel = driverLabel(drivers, shortcut.getExtra("graphicsDriverConfig", container.graphicsDriverConfig)),
                dxLabel = dxLabel(
                    shortcut.getExtra("dxwrapper", container.dxWrapper),
                    shortcut.getExtra("dxwrapperConfig", container.dxWrapperConfig),
                ),
                wineLabel = wineLabel(container.wineVersion),
                emulatorLabel = container.emulator ?: "",
                screenSize = shortcut.getExtra("screenSize", container.screenSize).replace("x", " × "),
                exeName = windowsPath.substringAfterLast('\\').substringAfterLast('/'),
                windowsPath = windowsPath,
            )
        }.sortedWith(
            compareByDescending<HomeGame> { it.lastPlayed }
                .thenBy { it.name.lowercase(Locale.ROOT) }
        )
    }

    private val LAUNCH_PREFIX = Regex("^(?:launch|play)\\s+", RegexOption.IGNORE_CASE)

    /** GOG and other installers name their links "Launch <game>"; show just the game. */
    fun displayName(shortcutName: String): String =
        shortcutName.replaceFirst(LAUNCH_PREFIX, "").ifBlank { shortcutName }

    fun driverLabel(drivers: XclipseDriverManager, graphicsDriverConfig: String?): String {
        val id = GraphicsDriverConfigDialog.getVersion(graphicsDriverConfig ?: "")
        if (id.isNullOrEmpty() || id == DefaultVersion.WRAPPER) return "Samsung"
        val name = runCatching { drivers.getDriverName(id) }.getOrNull()
        return (name?.takeIf { it.isNotBlank() } ?: id).replace('-', ' ').replace('_', ' ')
    }

    /** "dxvk" + "version=1.10.3-arm64ec-async,…" -> "DXVK 1.10.3". */
    fun dxLabel(wrapper: String?, config: String?): String {
        val kind = (wrapper ?: "").lowercase(Locale.ROOT)
        if (kind.contains("wined3d")) return "WineD3D"
        val version = DXVKConfigDialog.parseConfig(config ?: "").get("version", DefaultVersion.DXVK)
        val number = Regex("\\d+(?:\\.\\d+)+").find(version)?.value ?: version
        return "DXVK $number"
    }

    /** "proton-9.0-arm64ec" -> "Proton 9.0 arm64ec". */
    private fun wineLabel(version: String?): String {
        if (version.isNullOrBlank()) return "Wine"
        // A trailing number is the package revision ("proton-9.0-arm64ec-0"), not part of the name.
        val parts = version.split('-').filter { it.isNotBlank() }
            .let { if (it.size >= 3 && it.last().all(Char::isDigit)) it.dropLast(1) else it }
        return parts.mapIndexed { i, part ->
            if (i == 0) part.replaceFirstChar { it.titlecase(Locale.ROOT) } else part
        }.joinToString(" ")
    }
}
