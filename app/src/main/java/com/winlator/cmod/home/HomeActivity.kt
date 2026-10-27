package com.winlator.cmod.home

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.winlator.cmod.MainActivity
import com.winlator.cmod.R
import com.winlator.cmod.XServerDisplayActivity
import com.winlator.cmod.XrActivity
import com.winlator.cmod.contentdialog.ControllerAssignmentDialog
import com.winlator.cmod.core.ShortcutArtworkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Console-style front end (Xbox / PS5 / Steam Deck inspired): home with the game row and
 * the selected game's art, and a page per game. MainActivity stays underneath and still
 * owns first-boot installation; the classic screens open from here.
 */
class HomeActivity : ComponentActivity() {
    private sealed interface Route {
        data object Home : Route
        data class Game(val key: String) : Route
        data object Settings : Route
    }

    private var games by mutableStateOf<List<HomeGame>?>(null)
    private var route by mutableStateOf<Route>(Route.Home)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterImmersiveMode()
        // B / Back on the home screen behaves like a console: leave the app, keep it running.
        onBackPressedDispatcher.addCallback(this) { moveTaskToBack(true) }

        setContent {
            WxTheme {
                AnimatedContent(
                    targetState = route,
                    transitionSpec = {
                        (fadeIn(tween(240)) + scaleIn(tween(240), initialScale = 0.98f)) togetherWith fadeOut(tween(160))
                    },
                    label = "route",
                ) { current ->
                    when (current) {
                        Route.Settings -> RailSettingsScreen(
                            title = stringResource(R.string.home_settings),
                            subtitle = null,
                            backLabel = stringResource(R.string.game_back_home),
                            sections = appSettingsSections(onOpenClassic = ::openClassic, onOpenTool = ::openTool),
                            onBack = { route = Route.Home },
                        )
                        Route.Home -> HomeScreen(
                            games = games,
                            onPlay = ::launchGame,
                            onOpenGame = { route = Route.Game(it.key) },
                            onOpenSettings = { route = Route.Settings },
                            onOpenClassic = ::openClassic,
                            onOpenStores = {
                                Toast.makeText(this@HomeActivity, R.string.home_stores_coming_soon, Toast.LENGTH_SHORT).show()
                            },
                            onOpenControllers = { ControllerAssignmentDialog.show(this@HomeActivity) },
                        )
                        is Route.Game -> {
                            val game = games?.firstOrNull { it.key == current.key }
                            if (game == null) {
                                LaunchedEffect(current) { route = Route.Home }
                            } else {
                                GameScreen(
                                    game = game,
                                    onBack = { route = Route.Home },
                                    onPlay = { launchGame(game) },
                                    onGameSettings = { openClassic(R.id.main_menu_shortcuts) },
                                    onEditContainer = { openClassic(R.id.main_menu_containers) },
                                    onRefreshArt = { refreshArtwork(game) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
        // Reload on every return so playtime and "last played" follow the session that just ended.
        reloadGames()
    }

    private fun reloadGames() {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { HomeGames.load(this@HomeActivity) }
            games = loaded
            fetchMissingArtwork(loaded)
        }
    }

    /**
     * Covers use the classic shortcut policy (SteamGridDB, then the EXE icon); the home also
     * wants the wide hero and the title logo. ShortcutArtworkManager skips titles that already
     * failed this session, so a reload after one success does not retry the others.
     */
    private fun fetchMissingArtwork(list: List<HomeGame>) {
        for (game in list) {
            if (game.cover == null) {
                val mode = ShortcutArtworkManager.getMode(this, game.shortcut)
                ShortcutArtworkManager.ensure(applicationContext, game.shortcut, false) { success ->
                    Log.i(TAG, "Cover for ${game.name} (mode $mode): ${if (success) "found" else "not found"}")
                    if (success && !isFinishing) reloadGames()
                }
            }
            if (game.heroFile == null || game.logoFile == null) {
                ShortcutArtworkManager.ensureHomeArt(applicationContext, game.shortcut) { saved ->
                    Log.i(TAG, "Hero/logo for ${game.name}: ${if (saved) "saved" else "unchanged"}")
                    if (saved && !isFinishing) reloadGames()
                }
            }
        }
    }

    private fun refreshArtwork(game: HomeGame) {
        ShortcutArtworkManager.resetHomeArt(game.shortcut)
        ShortcutArtworkManager.deleteGeneratedArtwork(game.shortcut)
        ShortcutArtworkManager.ensure(applicationContext, game.shortcut, true) { if (!isFinishing) reloadGames() }
        ShortcutArtworkManager.ensureHomeArt(applicationContext, game.shortcut) { if (!isFinishing) reloadGames() }
    }

    private fun enterImmersiveMode() {
        // Draw the backdrop behind the camera hole too (content is padded by displayCutout).
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** Same extras as ShortcutsFragment.runFromShortcut(). */
    private fun launchGame(game: HomeGame) {
        val shortcut = game.shortcut
        if (XrActivity.isEnabled(this)) {
            XrActivity.openIntent(this, shortcut.container.id, shortcut.file.path)
            return
        }
        startActivity(
            Intent(this, XServerDisplayActivity::class.java)
                .putExtra("container_id", shortcut.container.id)
                .putExtra("shortcut_path", shortcut.file.path)
                .putExtra("shortcut_name", shortcut.name)
                .putExtra("disableXinput", shortcut.getExtra("disableXinput", "0"))
        )
    }

    /** Opens a classic-only tool (saves, Box64 RC file, backend logs, about). */
    private fun openTool(tool: ClassicTool) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(EXTRA_TOOL, tool.id)
                .putExtra(EXTRA_SKIP_HOME, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }

    /** Opens a section of the classic drawer UI (R.id.main_menu_*). */
    private fun openClassic(menuItemId: Int) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra("selected_menu_item_id", menuItemId)
                .putExtra(EXTRA_SKIP_HOME, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }

    companion object {
        private const val TAG = "HomeActivity"

        /** MainActivity extra: open the classic UI without bouncing back to this screen. */
        const val EXTRA_SKIP_HOME = "skip_console_home"

        /** MainActivity extra: a ClassicTool id to open after the classic UI starts. */
        const val EXTRA_TOOL = "open_classic_tool"
        const val PREF_CONSOLE_HOME = "console_home"

        /** The console home is the default front end unless Big Picture mode or the classic UI is chosen. */
        @JvmStatic
        fun isEnabled(context: Context): Boolean {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            return prefs.getBoolean(PREF_CONSOLE_HOME, true) &&
                !prefs.getBoolean("enable_big_picture_mode", false)
        }

        @JvmStatic
        fun open(activity: Activity) {
            activity.startActivity(Intent(activity, HomeActivity::class.java))
        }
    }
}
