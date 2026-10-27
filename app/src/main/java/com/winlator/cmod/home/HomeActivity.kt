package com.winlator.cmod.home

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
 * Console-style home screen (Xbox / PS5 / Steam Deck inspired): a game row with the
 * selected game's art filling the background, and quick tiles into the classic UI.
 * MainActivity stays underneath and still owns first-boot installation.
 */
class HomeActivity : ComponentActivity() {
    private var games by mutableStateOf<List<HomeGame>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterImmersiveMode()
        // B / Back on the home screen behaves like a console: leave the app, keep it running.
        onBackPressedDispatcher.addCallback(this) { moveTaskToBack(true) }

        setContent {
            HomeScreen(
                games = games,
                onPlay = ::launchGame,
                onOpenClassic = ::openClassic,
                onOpenStores = {
                    Toast.makeText(this, R.string.home_stores_coming_soon, Toast.LENGTH_SHORT).show()
                },
                onOpenControllers = { ControllerAssignmentDialog.show(this) },
            )
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
     * Same artwork policy as the classic shortcut list (SteamGridDB, then the EXE icon).
     * ShortcutArtworkManager skips titles that already failed this session, so a reload
     * after one success does not retry the others.
     */
    private fun fetchMissingArtwork(list: List<HomeGame>) {
        for (game in list) {
            if (game.cover != null) continue
            val mode = ShortcutArtworkManager.getMode(this, game.shortcut)
            ShortcutArtworkManager.ensure(applicationContext, game.shortcut, false) { success ->
                Log.i(TAG, "Artwork for ${game.name} (mode $mode): ${if (success) "found" else "not found"}")
                if (success && !isFinishing) reloadGames()
            }
        }
    }

    private fun enterImmersiveMode() {
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
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
