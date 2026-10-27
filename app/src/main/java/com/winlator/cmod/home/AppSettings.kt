package com.winlator.cmod.home

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.preference.PreferenceManager
import com.winlator.cmod.BuildConfig
import com.winlator.cmod.R
import com.winlator.cmod.XrActivity
import com.winlator.cmod.box86_64.Box86_64Preset
import com.winlator.cmod.box86_64.Box86_64PresetManager
import com.winlator.cmod.contentdialog.ControllerAssignmentDialog
import com.winlator.cmod.core.LauncherIconManager
import com.winlator.cmod.core.ShortcutArtworkManager
import com.winlator.cmod.fexcore.FEXCorePresetManager
import com.winlator.cmod.inputcontrols.PreferenceKeys
import kotlin.math.roundToInt

/** Tools that only exist in the classic UI; MainActivity opens them from HomeActivity.EXTRA_TOOL. */
enum class ClassicTool(val id: String) {
    SAVES("saves"), BOX64_RC("box64rc"), BACKEND_LOGS("backend_logs"), ABOUT("about"),
    NEW_CONTAINER("new_container"),
    /** Followed by ":<container id>". */
    EDIT_CONTAINER("edit_container"),
}

/**
 * Global settings for the console UI. Same SharedPreferences keys and side effects as the
 * classic SettingsFragment, but each change is saved at once (console style) instead of
 * on a Confirm button. Editors that need the classic screens open them.
 */
@Composable
fun appSettingsSections(
    onOpenClassic: (Int) -> Unit,
    onOpenTool: (ClassicTool) -> Unit,
): List<SettingSection> {
    val context = LocalContext.current
    val activity = context as Activity
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    // Every write bumps this so the rows re-read their preference.
    var version by remember { mutableIntStateOf(0) }
    version.let { }
    var editingKey by remember { mutableStateOf(false) }

    fun bool(key: String, default: Boolean) = prefs.getBoolean(key, default)
    fun setBool(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
        version++
    }
    fun toggle(title: Int, hint: Int, key: String, default: Boolean) = SettingItem.Toggle(
        context.getString(title), context.getString(hint), bool(key, default),
    ) { setBool(key, it) }

    /** A 0..1 float preference shown as 0-100 % in steps of 5. */
    fun percent(title: Int, key: String, default: Float): SettingItem.Choice {
        val steps = (0..100 step 5).toList()
        val current = (prefs.getFloat(key, default) * 100).roundToInt()
        val index = steps.indexOfFirst { it >= current }.let { if (it < 0) steps.lastIndex else it }
        return SettingItem.Choice(context.getString(title), context.getString(R.string.st_stick_hint), steps.map { "$it%" }, index) {
            prefs.edit().putFloat(key, steps[it] / 100f).apply()
            version++
        }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            prefs.edit().putString("frontend_export_uri", uri.toString()).apply()
            version++
        }
    }

    if (editingKey) {
        ApiKeyDialog(
            initial = prefs.getString("custom_api_key", "") ?: "",
            onDismiss = { editingKey = false },
            onSave = { key ->
                prefs.edit().putString("custom_api_key", key.trim()).apply()
                editingKey = false
                version++
            },
        )
    }

    val general = SettingSection(
        stringResource(R.string.st_sec_general),
        stringResource(R.string.st_sec_general_blurb),
        WxIcons.Home,
        listOf(
            toggle(R.string.st_console_home, R.string.st_console_home_hint, HomeActivity.PREF_CONSOLE_HOME, true),
            toggle(R.string.st_big_picture, R.string.st_big_picture_hint, "enable_big_picture_mode", false),
            run {
                val follow = bool("follow_system_theme", true)
                val index = if (follow) 0 else if (bool("dark_mode", true)) 1 else 2
                SettingItem.Choice(
                    stringResource(R.string.st_classic_theme),
                    stringResource(R.string.st_classic_theme_hint),
                    listOf(stringResource(R.string.st_theme_system), stringResource(R.string.st_theme_dark), stringResource(R.string.st_theme_light)),
                    index,
                ) {
                    prefs.edit()
                        .putBoolean("follow_system_theme", it == 0)
                        .apply { if (it != 0) putBoolean("dark_mode", it == 1) }
                        .apply()
                    version++
                }
            },
            // Despite the classic label, the classic code forces portrait when this is on.
            toggle(R.string.st_classic_portrait, R.string.st_classic_portrait_hint, "lock_landscape", false),
            SettingItem.Toggle(
                stringResource(R.string.st_alt_icon),
                stringResource(R.string.st_alt_icon_hint),
                bool("use_alternative_app_icon", false),
            ) { wanted ->
                if (LauncherIconManager.setAlternativeIcon(context, wanted)) {
                    setBool("use_alternative_app_icon", wanted)
                    Toast.makeText(context, R.string.app_icon_changed, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, R.string.app_icon_change_failed, Toast.LENGTH_SHORT).show()
                }
            },
            SettingItem.Action(stringResource(R.string.st_about), stringResource(R.string.st_about_hint), BuildConfig.VERSION_NAME) {
                onOpenTool(ClassicTool.ABOUT)
            },
            SettingItem.Action(stringResource(R.string.home_classic_view), stringResource(R.string.st_classic_view_hint)) {
                onOpenClassic(R.id.main_menu_shortcuts)
            },
        ),
    )

    val library = run {
        val modes = listOf(ShortcutArtworkManager.MODE_BROWSER, ShortcutArtworkManager.MODE_EXE, ShortcutArtworkManager.MODE_CUSTOM)
        val ownKey = bool("enable_custom_api_key", false)
        val key = prefs.getString("custom_api_key", "") ?: ""
        val folder = prefs.getString("frontend_export_uri", null)
            ?.let { Uri.parse(it).lastPathSegment?.substringAfter(':') }
            ?: "Download/WinXclipse/Frontend"
        SettingSection(
            stringResource(R.string.st_sec_library),
            stringResource(R.string.st_sec_library_blurb),
            WxIcons.Grid,
            buildList {
                add(
                    SettingItem.Choice(
                        stringResource(R.string.st_art_source),
                        stringResource(R.string.st_art_source_hint),
                        listOf(stringResource(R.string.st_art_browser), stringResource(R.string.st_art_exe), stringResource(R.string.st_art_custom)),
                        modes.indexOf(prefs.getString(ShortcutArtworkManager.PREF_MODE, ShortcutArtworkManager.MODE_BROWSER)).coerceAtLeast(0),
                    ) {
                        prefs.edit().putString(ShortcutArtworkManager.PREF_MODE, modes[it]).apply()
                        version++
                    }
                )
                add(
                    SettingItem.Toggle(stringResource(R.string.st_own_key), stringResource(R.string.st_own_key_hint), ownKey) {
                        val editor = prefs.edit().putBoolean("enable_custom_api_key", it)
                        if (!it) editor.remove("custom_api_key")
                        editor.apply()
                        version++
                    }
                )
                if (ownKey) {
                    add(
                        SettingItem.Action(
                            stringResource(R.string.st_key_value),
                            stringResource(R.string.st_key_value_hint),
                            if (key.isBlank()) stringResource(R.string.st_not_set) else "••••" + key.takeLast(4),
                        ) { editingKey = true }
                    )
                }
                add(SettingItem.Action(stringResource(R.string.st_frontend_path), stringResource(R.string.st_frontend_path_hint), folder) { pickFolder.launch(null) })
            },
        )
    }

    val controls = run {
        val speeds = (10..200 step 10).toList()
        val speed = (prefs.getFloat("cursor_speed", 1f) * 100).roundToInt()
        SettingSection(
            stringResource(R.string.st_sec_controls),
            stringResource(R.string.st_sec_controls_blurb),
            WxIcons.Pad,
            listOf(
                SettingItem.Action(stringResource(R.string.home_controllers), stringResource(R.string.st_controllers_hint)) {
                    ControllerAssignmentDialog.show(activity)
                },
                SettingItem.Choice(
                    stringResource(R.string.st_cursor_speed),
                    stringResource(R.string.st_cursor_speed_hint),
                    speeds.map { "$it%" },
                    speeds.indexOfFirst { it >= speed }.let { if (it < 0) speeds.lastIndex else it },
                ) {
                    prefs.edit().putFloat("cursor_speed", speeds[it] / 100f).apply()
                    version++
                },
                toggle(R.string.st_disable_xinput, R.string.st_disable_xinput_hint, "xinput_toggle", false),
                toggle(R.string.st_touchscreen, R.string.st_touchscreen_hint, "touchscreen_toggle", false),
                percent(R.string.st_left_deadzone, PreferenceKeys.DEADZONE_LEFT, 0.1f),
                percent(R.string.st_left_sens, PreferenceKeys.SENSITIVITY_LEFT, 1f),
                toggle(R.string.st_left_square, R.string.st_stick_hint, PreferenceKeys.SQUARE_DEADZONE_LEFT, false),
                toggle(R.string.st_left_invx, R.string.st_stick_hint, PreferenceKeys.INVERT_LEFT_X, false),
                toggle(R.string.st_left_invy, R.string.st_stick_hint, PreferenceKeys.INVERT_LEFT_Y, false),
                percent(R.string.st_right_deadzone, PreferenceKeys.DEADZONE_RIGHT, 0.1f),
                percent(R.string.st_right_sens, PreferenceKeys.SENSITIVITY_RIGHT, 1f),
                toggle(R.string.st_right_invx, R.string.st_stick_hint, PreferenceKeys.INVERT_RIGHT_X, false),
                toggle(R.string.st_right_invy, R.string.st_stick_hint, PreferenceKeys.INVERT_RIGHT_Y, false),
            ),
        )
    }

    val display = SettingSection(
        stringResource(R.string.st_sec_display),
        stringResource(R.string.st_sec_display_blurb),
        WxIcons.Screen,
        buildList {
            add(toggle(R.string.st_dri3, R.string.st_dri3_hint, "use_dri3", true))
            if (XrActivity.isSupported()) add(toggle(R.string.st_xr, R.string.st_xr_hint, "use_xr", true))
            add(toggle(R.string.st_clipboard, R.string.st_clipboard_hint, "share_android_clipboard", false))
            add(toggle(R.string.st_browser, R.string.st_browser_hint, "open_with_android_browser", false))
            add(toggle(R.string.st_file_provider, R.string.st_file_provider_hint, "enable_file_provider", true))
        },
    )

    val emulation = run {
        val box64 = remember(version) { Box86_64PresetManager.getPresets("box64", context) }
        val box64Selected = prefs.getString("box64_preset", Box86_64Preset.COMPATIBILITY)
        val fex = remember(version) { FEXCorePresetManager.getPresets(context) }
        val fexSelected = remember(version) { FEXCorePresetManager.getConfiguredDefault(context) }
        SettingSection(
            stringResource(R.string.st_sec_emulation),
            stringResource(R.string.st_sec_emulation_blurb),
            WxIcons.Chip,
            listOf(
                SettingItem.Choice(
                    stringResource(R.string.st_box64_preset),
                    stringResource(R.string.st_preset_hint),
                    box64.map { it.name },
                    box64.indexOfFirst { it.id == box64Selected }.coerceAtLeast(0),
                ) {
                    prefs.edit().putString("box64_preset", box64[it].id).apply()
                    version++
                },
                SettingItem.Choice(
                    stringResource(R.string.st_fex_preset),
                    stringResource(R.string.st_preset_hint),
                    fex.map { it.name },
                    fex.indexOfFirst { it.id == fexSelected }.coerceAtLeast(0),
                ) {
                    prefs.edit().putString("fexcore_preset", fex[it].id).apply()
                    version++
                },
                SettingItem.Action(stringResource(R.string.st_edit_presets), stringResource(R.string.st_edit_presets_hint)) {
                    onOpenClassic(R.id.main_menu_settings)
                },
            ),
        )
    }

    val debug = run {
        // The classic list editor offers all 521 Wine channels; these are the useful sets.
        val presets = mutableListOf("warn,err,fixme", "err", "seh,loaddll,err", "seh,loaddll,warn,err,fixme")
        val currentChannels = prefs.getString("wine_debug_channels", "warn,err,fixme") ?: "warn,err,fixme"
        if (currentChannels !in presets) presets.add(0, currentChannels)
        SettingSection(
            stringResource(R.string.st_sec_debug),
            stringResource(R.string.st_sec_debug_blurb),
            WxIcons.Bug,
            listOf(
                toggle(R.string.st_wine_log, R.string.st_wine_log_hint, "enable_wine_debug", false),
                SettingItem.Choice(
                    stringResource(R.string.st_wine_channels),
                    stringResource(R.string.st_wine_channels_hint),
                    presets.map { it.replace(",", ", ") },
                    presets.indexOf(currentChannels),
                ) {
                    prefs.edit().putString("wine_debug_channels", presets[it]).apply()
                    version++
                },
                toggle(R.string.st_box64_logs, R.string.st_box64_logs_hint, "enable_box86_64_logs", false),
                toggle(R.string.st_peb_logs, R.string.st_peb_logs_hint, "enable_peb_logs", false),
                toggle(R.string.st_lifecycle_logs, R.string.st_lifecycle_logs_hint, "enable_wine_lifecycle_logs", false),
                toggle(R.string.st_backend_sidebar, R.string.st_backend_sidebar_hint, "show_backend_logs_sidebar", false),
                SettingItem.Action(stringResource(R.string.st_backend_logs), stringResource(R.string.st_backend_logs_hint)) {
                    onOpenTool(ClassicTool.BACKEND_LOGS)
                },
            ),
        )
    }

    val storage = SettingSection(
        stringResource(R.string.st_sec_storage),
        stringResource(R.string.st_sec_storage_blurb),
        WxIcons.Storage,
        listOf(
            SettingItem.Action(stringResource(R.string.st_saves), stringResource(R.string.st_saves_hint)) { onOpenTool(ClassicTool.SAVES) },
            SettingItem.Action(stringResource(R.string.st_box64rc), stringResource(R.string.st_box64rc_hint)) { onOpenTool(ClassicTool.BOX64_RC) },
            SettingItem.Action(stringResource(R.string.st_soundfonts), stringResource(R.string.st_soundfonts_hint)) { onOpenClassic(R.id.main_menu_settings) },
            SettingItem.Action(stringResource(R.string.st_backup), stringResource(R.string.st_backup_hint)) { onOpenClassic(R.id.main_menu_settings) },
        ),
    )

    return listOf(general, library, controls, display, emulation, debug, storage)
}

@Composable
private fun ApiKeyDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Wx.SurfaceHigh,
        title = { Text(stringResource(R.string.st_key_value), style = WxType.Title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = WxType.Body,
                placeholder = { Text(stringResource(R.string.st_key_value_hint), style = WxType.Caption) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Wx.Violet,
                    unfocusedBorderColor = Wx.TintBorder,
                    cursorColor = Wx.Lilac,
                ),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text(stringResource(R.string.st_save), color = Wx.Lilac) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.st_cancel), color = Wx.TextSecondary) } },
    )
}
