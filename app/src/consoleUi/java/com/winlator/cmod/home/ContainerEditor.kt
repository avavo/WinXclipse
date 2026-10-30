package com.winlator.cmod.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.box86_64.Box86_64PresetManager
import com.winlator.cmod.container.Container
import com.winlator.cmod.contentdialog.DXVKConfigDialog
import com.winlator.cmod.contents.ContentProfile
import com.winlator.cmod.contents.ContentsManager
import com.winlator.cmod.contents.CustomWrapperManager
import com.winlator.cmod.contents.XclipseDriverManager
import com.winlator.cmod.core.DefaultVersion
import com.winlator.cmod.core.EnvVars
import com.winlator.cmod.core.FileUtils
import com.winlator.cmod.core.KeyValueSet
import com.winlator.cmod.core.StringUtils
import com.winlator.cmod.core.WineRegistryEditor
import com.winlator.cmod.fexcore.FEXCorePresetManager
import com.winlator.cmod.winhandler.WinHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Wine registry values the classic editor writes to `.wine/user.reg` (not to the container JSON). */
data class WineRegistryDraft(
    val renderer: String,
    val csmt: Boolean,
    val strictShaderMath: Boolean,
    val videoMemory: String,
    val offscreen: String,
    val mouseWarp: String,
)

/**
 * Editable copy of one container. Only fields that differ from the copy taken when the editor
 * opened are written on save, so keys this editor does not show (extras owned by the runtime,
 * the classic dialogs or the in-game sidebar) are never touched.
 */
data class ContainerDraft(
    val name: String,
    val screenSize: String,
    val lcAll: String,
    val showFps: Boolean,
    val hudMode: String,
    val fullscreenStretched: Boolean,
    val desktopTheme: String,
    val graphicsDriver: String,
    val graphicsDriverConfig: String,
    val dxwrapper: String,
    val dxwrapperConfig: String,
    val audioDriver: String,
    val audioVolume: Int,
    val wincomponents: String,
    val emulator: String,
    val box64Preset: String,
    val fexcorePreset: String,
    val startupSelection: Int,
    val wow64Mode: Boolean,
    val gstreamer: Boolean,
    val inputType: Int,
    val exclusiveXInput: Boolean,
    val experimentalPerformance: Boolean,
    val envVars: String,
    val drives: String,
    val registry: WineRegistryDraft,
) {
    /** Writes the changed fields with the same setters and side effects as ContainerDetailFragment. */
    fun applyTo(container: Container, original: ContainerDraft): Boolean {
        if (this == original) return true
        if (name != original.name) container.setName(name)
        if (screenSize != original.screenSize) container.setScreenSize(screenSize)
        if (lcAll != original.lcAll) container.setLC_ALL(lcAll)
        if (showFps != original.showFps) container.setShowFPS(showFps)
        if (hudMode != original.hudMode) container.setHudMode(hudMode)
        if (fullscreenStretched != original.fullscreenStretched) container.setFullscreenStretched(fullscreenStretched)
        if (desktopTheme != original.desktopTheme) container.setDesktopTheme(desktopTheme)
        if (graphicsDriver != original.graphicsDriver) container.setGraphicsDriver(graphicsDriver)
        if (graphicsDriverConfig != original.graphicsDriverConfig) container.setGraphicsDriverConfig(graphicsDriverConfig)
        if (dxwrapper != original.dxwrapper) container.setDXWrapper(dxwrapper)
        if (dxwrapperConfig != original.dxwrapperConfig) {
            container.setDXWrapperConfig(dxwrapperConfig)
            // The classic Save mirrors the DXVK dialog's DirectDraw choice into its own field.
            container.setDDrawWrapper(DXVKConfigDialog.parseConfig(dxwrapperConfig).get("ddrawrapper").ifEmpty { "none" })
        }
        if (audioDriver != original.audioDriver) container.setAudioDriver(audioDriver)
        if (audioVolume != original.audioVolume) container.putExtra("audioVolume", if (audioVolume != 100) audioVolume.toString() else null)
        if (wincomponents != original.wincomponents) container.setWinComponents(wincomponents)
        if (emulator != original.emulator) container.setEmulator(emulator)
        if (box64Preset != original.box64Preset) container.setBox64Preset(box64Preset)
        if (fexcorePreset != original.fexcorePreset) container.setFEXCorePreset(fexcorePreset)
        if (startupSelection != original.startupSelection) container.setStartupSelection(startupSelection.toByte())
        if (wow64Mode != original.wow64Mode) container.setWoW64Mode(wow64Mode)
        if (gstreamer != original.gstreamer) container.setGstreamerWorkaround(gstreamer)
        if (inputType != original.inputType) container.setInputType(inputType)
        if (exclusiveXInput != original.exclusiveXInput) container.setExclusiveXInput(exclusiveXInput)
        if (experimentalPerformance != original.experimentalPerformance) {
            container.putExtra("experimentalPerformance", if (experimentalPerformance) "1" else null)
            // Classic rule: the tuning string only lives while the master switch is on.
            if (!experimentalPerformance) container.putExtra("xperfConfig", null)
        }
        if (envVars != original.envVars) container.setEnvVars(envVars)
        if (drives != original.drives) container.setDrives(drives)
        if (registry.renderer != original.registry.renderer) {
            container.putExtra("renderer", registry.renderer)
            // Frame generation is Vulkan-only (ContainerDetailFragment.saveFrameGenerationExtras).
            if (registry.renderer != "vulkan") container.putExtra("frameGenerationEnabled", null)
        }
        if (!container.saveData()) return false
        if (registry != original.registry) writeRegistry(container)
        return true
    }

    private fun writeRegistry(container: Container) {
        WineRegistryEditor(File(container.rootDir, ".wine/user.reg")).use { editor ->
            val d3d = "Software\\Wine\\Direct3D"
            editor.setStringValue(d3d, "renderer", registry.renderer)
            editor.setDwordValue(d3d, "csmt", if (registry.csmt) 3 else 0)
            editor.setStringValue(d3d, "OffScreenRenderingMode", registry.offscreen)
            editor.setDwordValue(d3d, "strict_shader_math", if (registry.strictShaderMath) 1 else 0)
            editor.setStringValue(d3d, "VideoMemorySize", registry.videoMemory)
            editor.setStringValue("Software\\Wine\\DirectInput", "MouseWarpOverride", registry.mouseWarp)
            editor.setStringValue(d3d, "shader_backend", "glsl")
            editor.setStringValue(d3d, "UseGLSL", "enabled")
        }
    }

    companion object {
        /** Reads the container and its registry. Call off the UI thread. */
        fun load(container: Container): ContainerDraft {
            val registry = WineRegistryEditor(File(container.rootDir, ".wine/user.reg")).use { editor ->
                val d3d = "Software\\Wine\\Direct3D"
                val renderer = container.getExtra("renderer", editor.getStringValue(d3d, "renderer", "vulkan"))
                    .let { if (it.equals("gdi", ignoreCase = true)) "vulkan" else it.lowercase(Locale.ROOT) }
                WineRegistryDraft(
                    renderer = renderer,
                    csmt = (editor.getDwordValue(d3d, "csmt", 3) ?: 3) != 0,
                    strictShaderMath = (editor.getDwordValue(d3d, "strict_shader_math", 1) ?: 1) != 0,
                    videoMemory = editor.getStringValue(d3d, "VideoMemorySize", "2048"),
                    offscreen = editor.getStringValue(d3d, "OffScreenRenderingMode", "fbo").lowercase(Locale.ROOT),
                    mouseWarp = editor.getStringValue("Software\\Wine\\DirectInput", "MouseWarpOverride", "disable").lowercase(Locale.ROOT),
                )
            }
            return ContainerDraft(
                name = container.getName(),
                screenSize = container.getScreenSize(),
                lcAll = container.getLC_ALL() ?: "",
                showFps = container.isShowFPS(),
                hudMode = container.getHudMode(),
                fullscreenStretched = container.isFullscreenStretched(),
                desktopTheme = container.getDesktopTheme(),
                graphicsDriver = container.getGraphicsDriver(),
                graphicsDriverConfig = container.getGraphicsDriverConfig() ?: Container.DEFAULT_GRAPHICSDRIVERCONFIG,
                dxwrapper = container.getDXWrapper(),
                dxwrapperConfig = container.getDXWrapperConfig() ?: "",
                audioDriver = container.getAudioDriver(),
                audioVolume = container.getExtra("audioVolume", "100").toIntOrNull()?.coerceIn(0, 100) ?: 100,
                wincomponents = container.getWinComponents(),
                emulator = (container.getEmulator() ?: Container.DEFAULT_EMULATOR).lowercase(Locale.ROOT),
                box64Preset = container.getBox64Preset(),
                fexcorePreset = container.getFEXCorePreset(),
                startupSelection = container.getStartupSelection().toInt(),
                wow64Mode = container.isWoW64Mode(),
                gstreamer = container.isGstreamerWorkaround(),
                inputType = container.getInputType(),
                exclusiveXInput = container.isExclusiveXInput(),
                experimentalPerformance = container.getExtra("experimentalPerformance") == "1",
                envVars = container.getEnvVars(),
                drives = container.getDrives(),
                registry = registry,
            )
        }
    }
}

/** graphicsDriverConfig is `k=v;k=v`; edits keep every other key and their order. */
private fun gdcGet(config: String, key: String, default: String): String =
    config.split(';').firstOrNull { it.substringBefore('=') == key }?.substringAfter('=', default) ?: default

private fun gdcSet(config: String, key: String, value: String): String {
    val parts = config.split(';').filter { it.isNotBlank() }.toMutableList()
    val index = parts.indexOfFirst { it.substringBefore('=') == key }
    if (index >= 0) parts[index] = "$key=$value" else parts.add("$key=$value")
    return parts.joinToString(";")
}

/** dxwrapperConfig is `k=v,k=v`, read with the DXVK dialog's defaults merged in, like the classic editor. */
private fun dxcGet(config: String, key: String): String = DXVKConfigDialog.parseConfig(config).get(key)

private fun dxcSet(config: String, key: String, value: String): String =
    DXVKConfigDialog.parseConfig(config).apply { put(key, value) }.toString()

/** Lists that need disk access (installed drivers, wrappers, DXVK builds). */
private class EditorCatalog(
    val driverIds: List<String>,
    val driverNames: List<String>,
    val wrapperLabels: List<String>,
    val dxvkVersions: List<String>,
)

private fun loadCatalog(context: Context, arm64ec: Boolean): EditorCatalog {
    val drivers = XclipseDriverManager(context)
    val driverIds = listOf(DefaultVersion.WRAPPER) + runCatching { drivers.enumerateInstalledDrivers() }.getOrDefault(arrayListOf())
    val driverNames = driverIds.map { id ->
        if (id == DefaultVersion.WRAPPER) context.getString(R.string.ce_driver_system)
        else runCatching { drivers.getDriverName(id) }.getOrNull()?.takeIf { it.isNotBlank() } ?: id
    }
    val wrappers = context.resources.getStringArray(R.array.graphics_driver_entries).toMutableList()
    runCatching { CustomWrapperManager(context).installedLabels }.getOrNull()?.forEach { if (it !in wrappers) wrappers.add(it) }
    // Same list as DXVKConfigDialog.loadDxvkVersionSpinner.
    val dxvk = context.resources.getStringArray(R.array.dxvk_version_entries)
        .filter { arm64ec || !it.lowercase(Locale.ENGLISH).contains("arm64ec") }.toMutableList()
    runCatching {
        val contents = ContentsManager(context)
        contents.syncContents()
        for (profile in contents.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_DXVK)) {
            val name = profile.verName
            if (!contents.isInstalledProfile(profile) || name.isNullOrEmpty()) continue
            if ((arm64ec || !name.lowercase(Locale.ENGLISH).contains("arm64ec")) && name !in dxvk) dxvk.add(name)
        }
    }
    return EditorCatalog(driverIds, driverNames, wrappers, dxvk)
}

private sealed interface EditorDialog {
    class Text(val title: String, val initial: String, val onSave: (String) -> Unit, val onRemove: (() -> Unit)? = null) : EditorDialog
    class AddVar(val onSave: (String, String) -> Unit) : EditorDialog
    class Confirm(val title: String, val message: String, val confirm: String, val onConfirm: () -> Unit) : EditorDialog
    class Drive(val letter: String, val path: String, val onChange: () -> Unit, val onRemove: () -> Unit) : EditorDialog
}

/**
 * Console container editor: the day-to-day options natively, everything rarely touched
 * (video filters, frame generation, extensions, BCn, CPU, VR, dxvk.conf, component versions)
 * one tap away in the classic editor.
 */
@Composable
fun ContainerEditorScreen(
    container: Container,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onOpenClassicEditor: () -> Unit,
    onRunDesktop: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val arm64ec = remember(container.id) { container.getWineVersion().orEmpty().contains("arm64ec", ignoreCase = true) }
    val original by produceState<ContainerDraft?>(null, container.id) {
        value = withContext(Dispatchers.IO) { ContainerDraft.load(container) }
    }
    val catalog by produceState<EditorCatalog?>(null, container.id) {
        value = withContext(Dispatchers.IO) { loadCatalog(context, arm64ec) }
    }
    var edited by remember(container.id) { mutableStateOf<ContainerDraft?>(null) }
    var dialog by remember { mutableStateOf<EditorDialog?>(null) }
    var saving by remember { mutableStateOf(false) }
    // Drive whose folder the picker is choosing (-1 = add a new drive).
    var pickingDrive by remember { mutableStateOf(-1) }

    val base = original
    val draft = edited ?: base
    val loadedCatalog = catalog
    if (base == null || draft == null || loadedCatalog == null) {
        Box(Modifier.fillMaxSize().background(Wx.Ground), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Wx.Violet)
        }
        return
    }
    val dirty = draft != base
    fun update(change: (ContainerDraft) -> ContainerDraft) {
        edited = change(edited ?: base)
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        val path = runCatching { FileUtils.getFilePathFromUri(context, uri) }.getOrNull()
        if (path.isNullOrEmpty()) {
            Toast.makeText(context, R.string.ce_drive_unavailable, Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        update { d ->
            val list = parseDrives(d.drives).toMutableList()
            if (pickingDrive in list.indices) list[pickingDrive] = list[pickingDrive].first to path
            else nextDriveLetter(list)?.let { list.add(it to path) }
            d.copy(drives = serializeDrives(list))
        }
    }

    val save: () -> Unit = {
        if (!saving) {
            saving = true
            scope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching { draft.applyTo(container, base) }.getOrDefault(false) }
                saving = false
                if (ok) {
                    Toast.makeText(context, R.string.ce_saved, Toast.LENGTH_SHORT).show()
                    onSaved()
                } else {
                    Toast.makeText(context, R.string.configuration_save_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    val back: () -> Unit = {
        if (dirty) {
            dialog = EditorDialog.Confirm(
                context.getString(R.string.ce_discard_title), "", context.getString(R.string.ce_discard), onBack,
            )
        } else {
            onBack()
        }
    }

    val sections = editorSections(
        context = context,
        draft = draft,
        catalog = loadedCatalog,
        arm64ec = arm64ec,
        update = ::update,
        showDialog = { dialog = it },
        pickDriveFolder = { index ->
            pickingDrive = index
            pickFolder.launch(null)
        },
        onOpenClassicEditor = onOpenClassicEditor,
        onDuplicate = {
            dialog = EditorDialog.Confirm(
                context.getString(R.string.ct_duplicate),
                context.getString(R.string.ct_duplicate_confirm, draft.name),
                context.getString(R.string.ct_duplicate),
                onDuplicate,
            )
        },
        onDelete = {
            dialog = EditorDialog.Confirm(
                context.getString(R.string.ct_remove),
                context.getString(R.string.ct_remove_confirm, draft.name),
                context.getString(R.string.ct_remove),
                onDelete,
            )
        },
    )

    RailSettingsScreen(
        title = draft.name,
        subtitle = HomeGamesLabels.wine(container.getWineVersion()),
        backLabel = stringResource(R.string.home_containers),
        sections = sections,
        onBack = back,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            WxTonalButton(stringResource(R.string.ct_desktop), onRunDesktop, icon = WxIcons.Screen, height = 38.dp)
            WxPrimaryButton(stringResource(R.string.st_save), save, height = 38.dp)
        }
    }

    EditorDialogHost(dialog) { dialog = null }
}

@Composable
private fun EditorDialogHost(dialog: EditorDialog?, dismiss: () -> Unit) {
    when (dialog) {
        null -> Unit
        is EditorDialog.Text -> {
            var value by remember(dialog) { mutableStateOf(dialog.initial) }
            AlertDialog(
                onDismissRequest = dismiss,
                containerColor = Wx.SurfaceHigh,
                title = { Text(dialog.title, style = WxType.Title) },
                text = { WxTextField(value) { value = it } },
                confirmButton = {
                    TextButton(onClick = {
                        dialog.onSave(value)
                        dismiss()
                    }) { Text(stringResource(R.string.st_save), color = Wx.Lilac) }
                },
                dismissButton = {
                    Row {
                        if (dialog.onRemove != null) {
                            TextButton(onClick = {
                                dialog.onRemove.invoke()
                                dismiss()
                            }) { Text(stringResource(R.string.ce_remove_item), color = Wx.Danger) }
                        }
                        TextButton(onClick = dismiss) { Text(stringResource(R.string.st_cancel), color = Wx.TextSecondary) }
                    }
                },
            )
        }
        is EditorDialog.AddVar -> {
            var name by remember(dialog) { mutableStateOf("") }
            var value by remember(dialog) { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = dismiss,
                containerColor = Wx.SurfaceHigh,
                title = { Text(stringResource(R.string.ce_add_var), style = WxType.Title) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        WxTextField(name, stringResource(R.string.ce_var_name)) { name = it }
                        WxTextField(value, stringResource(R.string.ce_var_value)) { value = it }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        dialog.onSave(name, value)
                        dismiss()
                    }) { Text(stringResource(R.string.st_save), color = Wx.Lilac) }
                },
                dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.st_cancel), color = Wx.TextSecondary) } },
            )
        }
        is EditorDialog.Confirm -> AlertDialog(
            onDismissRequest = dismiss,
            containerColor = Wx.SurfaceHigh,
            title = { Text(dialog.title, style = WxType.Title) },
            text = { if (dialog.message.isNotEmpty()) Text(dialog.message, style = WxType.Body) },
            confirmButton = {
                TextButton(onClick = {
                    dismiss()
                    dialog.onConfirm()
                }) { Text(dialog.confirm, color = Wx.Danger) }
            },
            dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.st_cancel), color = Wx.TextSecondary) } },
        )
        is EditorDialog.Drive -> AlertDialog(
            onDismissRequest = dismiss,
            containerColor = Wx.SurfaceHigh,
            title = { Text("${dialog.letter}:", style = WxType.Title) },
            text = { Text(dialog.path, style = WxType.Body) },
            confirmButton = {
                TextButton(onClick = {
                    dismiss()
                    dialog.onChange()
                }) { Text(stringResource(R.string.ce_change_folder), color = Wx.Lilac) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        dismiss()
                        dialog.onRemove()
                    }) { Text(stringResource(R.string.ce_remove_item), color = Wx.Danger) }
                    TextButton(onClick = dismiss) { Text(stringResource(R.string.st_cancel), color = Wx.TextSecondary) }
                }
            },
        )
    }
}

@Composable
private fun WxTextField(value: String, label: String? = null, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = WxType.Body,
        label = label?.let { { Text(it, style = WxType.Caption) } },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Wx.Violet,
            unfocusedBorderColor = Wx.TintBorder,
            cursorColor = Wx.Lilac,
        ),
        modifier = Modifier.padding(top = 2.dp),
    )
}

/** Label helpers shared with the containers list. */
object HomeGamesLabels {
    /** "proton-9.0-arm64ec-0" -> "Proton 9.0 arm64ec". */
    fun wine(version: String?): String {
        if (version.isNullOrBlank()) return "Wine"
        val parts = version.split('-').filter { it.isNotBlank() }
            .let { if (it.size >= 3 && it.last().all(Char::isDigit)) it.dropLast(1) else it }
        return parts.mapIndexed { i, part -> if (i == 0) part.replaceFirstChar { it.titlecase(Locale.ROOT) } else part }.joinToString(" ")
    }
}

private fun parseDrives(drives: String): List<Pair<String, String>> =
    Container.drivesIterator(drives).mapNotNull { item ->
        val letter = item[0]
        val path = item[1]
        if (letter.isNullOrEmpty()) null else letter to path
    }

private fun serializeDrives(list: List<Pair<String, String>>): String =
    list.filter { it.second.isNotBlank() }.joinToString("") { (letter, path) -> "$letter:$path" }

private fun nextDriveLetter(list: List<Pair<String, String>>): String? =
    ('D'..'Z').map { it.toString() }.firstOrNull { letter -> list.none { it.first.equals(letter, ignoreCase = true) } }

/** Toggles for the known on/off environment variables (EnvVarsView.knownEnvVars CHECKBOX types). */
private val ENV_TOGGLES = mapOf(
    "MESA_SHADER_CACHE_DISABLE" to ("false" to "true"),
    "mesa_glthread" to ("false" to "true"),
    "WINEESYNC" to ("0" to "1"),
    "WINE_LARGE_ADDRESS_AWARE" to ("0" to "1"),
    "MANGOHUD" to ("0" to "1"),
)

private val WIN_COMPONENTS = listOf("direct3d", "directsound", "directmusic", "directshow", "directplay", "xaudio", "vcrun2010", "opengl")

private fun editorSections(
    context: Context,
    draft: ContainerDraft,
    catalog: EditorCatalog,
    arm64ec: Boolean,
    update: ((ContainerDraft) -> ContainerDraft) -> Unit,
    showDialog: (EditorDialog) -> Unit,
    pickDriveFolder: (Int) -> Unit,
    onOpenClassicEditor: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
): List<SettingSection> {
    fun s(id: Int) = context.getString(id)

    /** A choice over (stored value, label) pairs; an unknown stored value is kept as its own entry. */
    fun choice(title: Int, hint: String, current: String, options: List<Pair<String, String>>, onPick: (String) -> Unit): SettingItem.Choice {
        val all = if (options.any { it.first.equals(current, ignoreCase = true) }) options else listOf(current to current) + options
        val index = all.indexOfFirst { it.first.equals(current, ignoreCase = true) }.coerceAtLeast(0)
        return SettingItem.Choice(s(title), hint, all.map { it.second }, index) { onPick(all[it].first) }
    }

    val general = SettingSection(
        s(R.string.st_sec_general), s(R.string.ce_sec_general_blurb), WxIcons.Home,
        listOf(
            SettingItem.Action(s(R.string.ce_name), s(R.string.ce_name_hint), draft.name) {
                showDialog(EditorDialog.Text(s(R.string.ce_name), draft.name, { v -> update { it.copy(name = v.trim().ifEmpty { it.name }) } }))
            },
            choice(
                R.string.ce_resolution, s(R.string.ce_resolution_hint), draft.screenSize,
                context.resources.getStringArray(R.array.screen_size_entries).drop(1).map { entry ->
                    entry.substringBefore(' ').trim() to entry.replace("x", " × ")
                },
            ) { v -> update { it.copy(screenSize = v) } },
            choice(
                R.string.ce_language, s(R.string.ce_language_hint), draft.lcAll,
                context.resources.getStringArray(R.array.some_lc_all).map { "$it.UTF-8" to it },
            ) { v -> update { it.copy(lcAll = v) } },
            SettingItem.Toggle(s(R.string.ce_show_fps), s(R.string.ce_show_fps_hint), draft.showFps) { v -> update { it.copy(showFps = v) } },
            choice(
                R.string.ce_hud, s(R.string.ce_hud_hint), draft.hudMode,
                listOf("winlator", "mangohud", "dxvk").zip(context.resources.getStringArray(R.array.hud_mode_entries).toList()),
            ) { v -> update { it.copy(hudMode = v) } },
            SettingItem.Toggle(s(R.string.ce_stretch), s(R.string.ce_stretch_hint), draft.fullscreenStretched) { v -> update { it.copy(fullscreenStretched = v) } },
        ),
    )

    val gdc = draft.graphicsDriverConfig
    val driverIndex = catalog.driverIds.indexOf(gdcGet(gdc, "version", DefaultVersion.WRAPPER))
    val quasar = driverIndex >= 0 && catalog.driverNames[driverIndex].contains("quasar", ignoreCase = true)
    val graphics = SettingSection(
        s(R.string.ce_sec_graphics), s(R.string.ce_sec_graphics_blurb), WxIcons.Screen,
        banner = if (quasar) s(R.string.ce_quasar_banner) else null,
        items = listOf(
            choice(
                R.string.ce_driver, s(R.string.ce_driver_hint), gdcGet(gdc, "version", DefaultVersion.WRAPPER),
                catalog.driverIds.zip(catalog.driverNames),
            ) { v -> update { it.copy(graphicsDriverConfig = gdcSet(it.graphicsDriverConfig, "version", v)) } },
            choice(
                R.string.ce_wrapper, s(R.string.ce_wrapper_hint), draft.graphicsDriver,
                catalog.wrapperLabels.map { StringUtils.parseIdentifier(it) to it },
            ) { v -> update { it.copy(graphicsDriver = v) } },
            choice(
                R.string.ce_vulkan_version, s(R.string.ce_vulkan_version_hint), gdcGet(gdc, "vulkanVersion", "1.3"),
                listOf("1.1", "1.2", "1.3").map { it to it },
            ) { v -> update { it.copy(graphicsDriverConfig = gdcSet(it.graphicsDriverConfig, "vulkanVersion", v)) } },
            choice(
                R.string.ce_present, s(R.string.ce_present_hint), gdcGet(gdc, "presentMode", "mailbox"),
                listOf("mailbox", "fifo", "immediate", "relaxed").map { it to it.replaceFirstChar { c -> c.titlecase(Locale.ROOT) } },
            ) { v -> update { it.copy(graphicsDriverConfig = gdcSet(it.graphicsDriverConfig, "presentMode", v)) } },
            choice(
                R.string.ce_vsync, s(R.string.ce_vsync_hint), gdcGet(gdc, "vsyncMode", "off"),
                listOf("off" to s(R.string.ce_off), "100" to "100%", "50" to "50%"),
            ) { v ->
                update {
                    // vblankOff mirrors vsyncMode, as the Video dialog writes it.
                    it.copy(graphicsDriverConfig = gdcSet(gdcSet(it.graphicsDriverConfig, "vsyncMode", v), "vblankOff", if (v == "off") "1" else "0"))
                }
            },
            choice(
                R.string.ce_max_memory, s(R.string.ce_max_memory_hint), gdcGet(gdc, "maxDeviceMemory", "0"),
                listOf("0" to s(R.string.ce_auto)) + listOf("512", "1024", "2048", "4096", "6144", "8192").map { it to "$it MB" },
            ) { v -> update { it.copy(graphicsDriverConfig = gdcSet(it.graphicsDriverConfig, "maxDeviceMemory", v)) } },
            choice(
                R.string.ce_renderer, s(R.string.ce_renderer_hint), draft.registry.renderer,
                listOf("vulkan" to "Vulkan", "gl" to "OpenGL"),
            ) { v -> update { it.copy(registry = it.registry.copy(renderer = v)) } },
        ),
    )

    val dxc = draft.dxwrapperConfig
    val isDxvk = draft.dxwrapper.startsWith("dxvk")
    val dxvkVersion = dxcGet(dxc, "version")
    val directx = SettingSection(
        s(R.string.ce_sec_directx), s(R.string.ce_sec_directx_blurb), WxIcons.Layers3d,
        buildList {
            add(
                choice(R.string.ce_dxwrapper, s(R.string.ce_dxwrapper_hint), if (isDxvk) "dxvk" else draft.dxwrapper, listOf("dxvk" to "DXVK + VKD3D", "wined3d" to "WineD3D")) { v ->
                    update { it.copy(dxwrapper = v) }
                }
            )
            if (isDxvk) {
                add(
                    choice(R.string.ce_dxvk_version, s(R.string.ce_dxvk_version_hint), dxvkVersion, catalog.dxvkVersions.map { it to it }) { v ->
                        update { it.copy(dxwrapperConfig = dxcSet(it.dxwrapperConfig, "version", v)) }
                    }
                )
                if (dxvkVersion.contains("async", ignoreCase = true)) {
                    add(SettingItem.Toggle(s(R.string.ce_async), s(R.string.ce_async_hint), dxcGet(dxc, "async") == "1") { v ->
                        update { it.copy(dxwrapperConfig = dxcSet(it.dxwrapperConfig, "async", if (v) "1" else "0")) }
                    })
                }
                add(
                    choice(
                        R.string.ce_framerate, s(R.string.ce_framerate_hint), dxcGet(dxc, "framerate").ifEmpty { "0" },
                        listOf("0" to s(R.string.ce_unlimited)) + (20..120 step 10).map { "$it" to "$it FPS" },
                    ) { v -> update { it.copy(dxwrapperConfig = dxcSet(it.dxwrapperConfig, "framerate", v)) } }
                )
                add(
                    choice(
                        R.string.ce_ddraw, s(R.string.ce_ddraw_hint), dxcGet(dxc, "ddrawrapper").ifEmpty { "none" },
                        listOf("none" to s(R.string.ce_ddraw_auto), "dd7to9" to "Dd7To9", "cnc-ddraw" to "CnC-DDraw", "wined3d" to "WineD3D"),
                    ) { v -> update { it.copy(dxwrapperConfig = dxcSet(it.dxwrapperConfig, "ddrawrapper", v)) } }
                )
                if (dxcGet(dxc, "vkd3dVersion").let { it.isNotEmpty() && it != "none" }) {
                    add(
                        choice(
                            R.string.ce_vkd3d_level, s(R.string.ce_vkd3d_level_hint), dxcGet(dxc, "vkd3dLevel").ifEmpty { "12_1" },
                            listOf("12_2", "12_1", "12_0", "11_1", "11_0").map { it to it.replace('_', '.') },
                        ) { v -> update { it.copy(dxwrapperConfig = dxcSet(it.dxwrapperConfig, "vkd3dLevel", v)) } }
                    )
                }
                val ramFix = dxcGet(dxc, "ramFix") != "0"
                add(SettingItem.Toggle(s(R.string.ce_ram_fix), s(R.string.ce_ram_fix_hint), ramFix) { v ->
                    update { it.copy(dxwrapperConfig = dxcSet(it.dxwrapperConfig, "ramFix", if (v) "1" else "0")) }
                })
                if (ramFix) {
                    add(
                        choice(
                            R.string.ce_ram_pool, s(R.string.ce_ram_pool_hint), dxcGet(dxc, "ramFixPool").ifEmpty { "0" },
                            listOf("0" to s(R.string.ce_auto)) + listOf("512", "1024", "1536", "2048", "3072", "4096").map { it to "$it MB" },
                        ) { v -> update { it.copy(dxwrapperConfig = dxcSet(it.dxwrapperConfig, "ramFixPool", v)) } }
                    )
                }
            }
        },
    )

    val audio = SettingSection(
        s(R.string.ce_sec_audio), s(R.string.ce_sec_audio_blurb), WxIcons.Speaker,
        listOf(
            choice(
                R.string.ce_audio_driver, s(R.string.ce_audio_driver_hint), draft.audioDriver,
                listOf("alsa-reflector" to "ALSA-Reflector", "alsa" to "ALSA", "pulseaudio" to "PulseAudio"),
            ) { v -> update { it.copy(audioDriver = v) } },
            choice(
                R.string.ce_volume, s(R.string.ce_volume_hint), draft.audioVolume.toString(),
                (0..100 step 10).map { "$it" to "$it%" },
            ) { v -> update { it.copy(audioVolume = v.toInt()) } },
        ),
    )

    val components = KeyValueSet(draft.wincomponents)
    val theme = draft.desktopTheme.split(',')
    val windows = SettingSection(
        s(R.string.ce_sec_windows), s(R.string.ce_sec_windows_blurb), WxIcons.Grid,
        buildList {
            for (key in WIN_COMPONENTS) {
                val label = runCatching { StringUtils.getString(context, key) }.getOrNull()?.takeIf { it.isNotBlank() } ?: key
                add(SettingItem.Toggle(label, s(R.string.ce_component_hint), components.get(key) == "1") { v ->
                    update { d -> d.copy(wincomponents = KeyValueSet(d.wincomponents).apply { put(key, if (v) "1" else "0") }.toString()) }
                })
            }
            add(
                choice(
                    R.string.ce_desktop_theme, s(R.string.ce_desktop_theme_hint), theme.firstOrNull().orEmpty(),
                    listOf("SYSTEM" to s(R.string.ce_theme_system), "LIGHT" to s(R.string.st_theme_light), "DARK" to s(R.string.st_theme_dark)),
                ) { v -> update { d -> d.copy(desktopTheme = (listOf(v) + d.desktopTheme.split(',').drop(1)).joinToString(",")) } }
            )
            add(SettingItem.Toggle(s(R.string.ce_csmt), s(R.string.ce_csmt_hint), draft.registry.csmt) { v ->
                update { it.copy(registry = it.registry.copy(csmt = v)) }
            })
            add(SettingItem.Toggle(s(R.string.ce_strict_math), s(R.string.ce_strict_math_hint), draft.registry.strictShaderMath) { v ->
                update { it.copy(registry = it.registry.copy(strictShaderMath = v)) }
            })
            add(
                choice(
                    R.string.ce_video_memory, s(R.string.ce_video_memory_hint), draft.registry.videoMemory,
                    listOf("32", "64", "128", "256", "512", "1024", "2048", "4096", "6144", "8192", "10240", "12288").map { it to "$it MB" },
                ) { v -> update { it.copy(registry = it.registry.copy(videoMemory = v)) } }
            )
            add(
                choice(R.string.ce_offscreen, s(R.string.ce_offscreen_hint), draft.registry.offscreen, listOf("fbo" to "FBO", "backbuffer" to "Backbuffer")) { v ->
                    update { it.copy(registry = it.registry.copy(offscreen = v)) }
                }
            )
            add(
                choice(
                    R.string.ce_mouse_warp, s(R.string.ce_mouse_warp_hint), draft.registry.mouseWarp,
                    listOf("disable" to s(R.string.ce_mw_disable), "enable" to s(R.string.ce_mw_enable), "force" to s(R.string.ce_mw_force)),
                ) { v -> update { it.copy(registry = it.registry.copy(mouseWarp = v)) } }
            )
        },
    )

    val box64Presets = Box86_64PresetManager.getPresets("box64", context)
    val fexPresets = FEXCorePresetManager.getPresets(context)
    val emulation = SettingSection(
        s(R.string.st_sec_emulation), s(R.string.ce_sec_emulation_blurb), WxIcons.Chip,
        buildList {
            if (arm64ec) {
                add(choice(R.string.ce_emulator, s(R.string.ce_emulator_hint), draft.emulator, listOf("fexcore" to "FEXCore", "box64" to "Box64")) { v ->
                    update { it.copy(emulator = v) }
                })
            } else {
                add(SettingItem.Action(s(R.string.ce_emulator), s(R.string.ce_emulator_fixed_hint), "Box64") { })
            }
            add(choice(R.string.st_box64_preset, s(R.string.ce_preset_hint), draft.box64Preset, box64Presets.map { it.id to it.name }) { v ->
                update { it.copy(box64Preset = v) }
            })
            if (arm64ec) {
                add(choice(R.string.st_fex_preset, s(R.string.ce_preset_hint), draft.fexcorePreset, fexPresets.map { it.id to it.name }) { v ->
                    update { it.copy(fexcorePreset = v) }
                })
            }
            add(
                SettingItem.Choice(
                    s(R.string.ce_startup), s(R.string.ce_startup_hint),
                    listOf(s(R.string.ce_startup_normal), s(R.string.ce_startup_essential), s(R.string.ce_startup_aggressive)),
                    draft.startupSelection.coerceIn(0, 2),
                ) { v -> update { it.copy(startupSelection = v) } }
            )
            add(SettingItem.Toggle(s(R.string.ce_wow64), s(R.string.ce_wow64_hint), draft.wow64Mode) { v -> update { it.copy(wow64Mode = v) } })
            add(SettingItem.Toggle(s(R.string.ce_gstreamer), s(R.string.ce_gstreamer_hint), draft.gstreamer) { v -> update { it.copy(gstreamer = v) } })
        },
    )

    val xinput = draft.inputType and WinHandler.FLAG_INPUT_TYPE_XINPUT.toInt() != 0
    val dinput = draft.inputType and WinHandler.FLAG_INPUT_TYPE_DINPUT.toInt() != 0
    fun inputType(x: Boolean, d: Boolean) =
        (if (x) WinHandler.FLAG_INPUT_TYPE_XINPUT.toInt() else 0) or (if (d) WinHandler.FLAG_INPUT_TYPE_DINPUT.toInt() else 0)
    val input = SettingSection(
        s(R.string.ce_sec_input), s(R.string.ce_sec_input_blurb), WxIcons.Pad,
        listOf(
            SettingItem.Toggle(s(R.string.ce_exclusive), s(R.string.ce_exclusive_hint), draft.exclusiveXInput) { v ->
                // Exclusive input forces XInput on and DirectInput off, as the classic checkbox does.
                update { it.copy(exclusiveXInput = v, inputType = if (v) inputType(true, false) else it.inputType) }
            },
            SettingItem.Toggle(s(R.string.ce_xinput), s(R.string.ce_xinput_hint), xinput) { v ->
                if (!draft.exclusiveXInput) update { it.copy(inputType = inputType(v, dinput)) }
            },
            SettingItem.Toggle(s(R.string.ce_dinput), s(R.string.ce_dinput_hint), dinput) { v ->
                if (!draft.exclusiveXInput) update { it.copy(inputType = inputType(xinput, v)) }
            },
        ),
    )

    val env = EnvVars(draft.envVars)
    fun withEnv(change: EnvVars.() -> Unit): (ContainerDraft) -> ContainerDraft = { d -> d.copy(envVars = EnvVars(d.envVars).apply(change).toString()) }
    val environment = SettingSection(
        s(R.string.ce_sec_env), s(R.string.ce_sec_env_blurb), WxIcons.Lines,
        buildList {
            for (name in env.toList()) {
                val value = env.get(name) ?: ""
                val toggle = ENV_TOGGLES[name]
                if (toggle != null && (value == toggle.first || value == toggle.second)) {
                    add(SettingItem.Toggle(name, "", value == toggle.second) { v -> update(withEnv { put(name, if (v) toggle.second else toggle.first) }) })
                } else {
                    add(SettingItem.Action(name, "", value) {
                        showDialog(
                            EditorDialog.Text(
                                name, value,
                                onSave = { v -> update(withEnv { put(name, v.replace(" ", "")) }) },
                                onRemove = { update(withEnv { remove(name) }) },
                            )
                        )
                    })
                }
            }
            add(SettingItem.Action(s(R.string.ce_add_var), s(R.string.ce_add_var_hint)) {
                showDialog(EditorDialog.AddVar { n, v ->
                    val name = n.trim().replace(" ", "")
                    val value = v.trim().replace(" ", "")
                    if (name.isNotEmpty() && value.isNotEmpty()) update(withEnv { put(name, value) })
                })
            })
        },
    )

    val driveList = parseDrives(draft.drives)
    val drives = SettingSection(
        s(R.string.ce_sec_drives), s(R.string.ce_sec_drives_blurb), WxIcons.Storage,
        buildList {
            driveList.forEachIndexed { index, (letter, path) ->
                add(SettingItem.Action("$letter:", path) {
                    showDialog(
                        EditorDialog.Drive(
                            letter, path,
                            onChange = { pickDriveFolder(index) },
                            onRemove = { update { d -> d.copy(drives = serializeDrives(parseDrives(d.drives).filterIndexed { i, _ -> i != index })) } },
                        )
                    )
                })
            }
            if (nextDriveLetter(driveList) != null) {
                add(SettingItem.Action(s(R.string.ce_add_drive), s(R.string.ce_add_drive_hint)) { pickDriveFolder(-1) })
            }
        },
    )

    val advanced = SettingSection(
        s(R.string.ce_sec_advanced), s(R.string.ce_sec_advanced_blurb), WxIcons.Info,
        listOf(
            SettingItem.Toggle(s(R.string.ce_xperf), s(R.string.ce_xperf_hint), draft.experimentalPerformance) { v ->
                update { it.copy(experimentalPerformance = v) }
            },
            SettingItem.Action(s(R.string.ce_more_classic), s(R.string.ce_more_classic_hint), onClick = onOpenClassicEditor),
            SettingItem.Action(s(R.string.ct_duplicate), s(R.string.ce_duplicate_hint), onClick = onDuplicate),
            SettingItem.Action(s(R.string.ct_remove), s(R.string.ce_remove_hint), s(R.string.ct_remove), danger = true, onClick = onDelete),
        ),
    )

    return listOf(general, graphics, directx, audio, windows, emulation, input, environment, drives, advanced)
}
