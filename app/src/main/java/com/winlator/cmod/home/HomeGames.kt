package com.winlator.cmod.home

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.container.Shortcut
import java.util.Locale

/** One game on the console home: a shortcut plus the stats XServerDisplayActivity records. */
class HomeGame(
    val shortcut: Shortcut,
    val name: String,
    val containerName: String,
    val cover: ImageBitmap?,
    val icon: ImageBitmap?,
    val lastPlayed: Long,
    val playtimeMs: Long,
    val playCount: Int,
)

object HomeGames {
    /** Same store as XServerDisplayActivity.savePlaytimeData()/incrementPlayCount(). */
    const val PLAYTIME_PREFS = "playtime_stats"

    /** Loads every shortcut of every container; most recently played first. Call off the UI thread. */
    fun load(context: Context): List<HomeGame> {
        val stats = context.getSharedPreferences(PLAYTIME_PREFS, Context.MODE_PRIVATE)
        return ContainerManager(context).loadShortcutsFast().map { shortcut ->
            HomeGame(
                shortcut = shortcut,
                name = displayName(shortcut.name),
                containerName = shortcut.container.name,
                cover = shortcut.coverArt?.asImageBitmap(),
                icon = shortcut.icon?.asImageBitmap(),
                lastPlayed = stats.getLong(shortcut.name + "_last_played", 0L),
                playtimeMs = stats.getLong(shortcut.name + "_playtime", 0L),
                playCount = stats.getInt(shortcut.name + "_play_count", 0),
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
}
