package com.winlator.cmod.gog

import android.content.Context
import android.util.Log
import com.winlator.cmod.R
import com.winlator.cmod.container.Container
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.steam.SteamControls
import com.winlator.cmod.store.ArtKind
import com.winlator.cmod.store.DosBox
import com.winlator.cmod.store.StoreRedists
import com.winlator.cmod.store.StoreShortcuts
import com.winlator.cmod.store.WineMono
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Turns an installed GOG game into a container shortcut. GOG games carry their launch
 * options in goggame-<id>.info ("playTasks"): the primary file task gives executable,
 * arguments and working folder. GOG builds are DRM-free; besides, a game may need its
 * build's redistributables and its install scripts' registry values (both applied once per
 * container from the launch batch), and Wine Mono (.NET and XNA games).
 */
object GogLauncher {
    private const val TAG = "GogLauncher"

    data class Prepared(val shortcut: Shortcut, val addedDrive: String?)

    private class Task(val exe: File, val arguments: String, val workDir: File?, val env: Map<String, String> = emptyMap())

    suspend fun prepare(context: Context, game: GogGame, installDir: File, container: Container): Prepared =
        withContext(Dispatchers.IO) {
            // First, so that what the game runs (DOSBox, say) is in place.
            val setup = try {
                GogDownloads.setup(game)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Offline with a game an older WinXclipse installed: try anyway.
                Log.w(TAG, "${game.id}: setup unavailable", e)
                GogDownloads.Setup(emptyList(), needsWineMono = false)
            }
            val found = primaryTask(game, installDir) ?: scanForExe(installDir)?.let { Task(it, "", null) }
                ?: throw IOException(context.getString(R.string.steam_play_no_exe))
            val addedDrive = StoreShortcuts.ensureDrive(container, installDir, GogDownloads.libraryRoot)
            val task = forDosBox(found, installDir, container)
            Log.i(TAG, "${game.id}: launching ${task.exe.name}")

            if (setup.needsWineMono || WineMono.isDotNet(task.exe)) WineMono.ensure(context, container)
            val steps = setup.redists
                .map { StoreRedists.Redist(File(GogDownloads.libraryRoot, it.path), "gog-${it.id}", it.arguments) }
                .filter { it.file.isFile } +
                listOfNotNull(GogScripts.registryStep(installDir, container, File(installDir, "_winxclipse/registry.reg")))

            // A batch file is needed for the redistributables and registry values (each runs
            // once per container), for environment variables, or when the game wants a working
            // folder other than its exe's.
            val launchFile: File
            val execArgs: String
            if (steps.isNotEmpty() || task.env.isNotEmpty() || (task.workDir != null && task.workDir != task.exe.parentFile)) {
                launchFile = StoreRedists.writeLaunchBatch(
                    container, File(installDir, "_winxclipse/launch.bat"),
                    task.exe, task.arguments, task.workDir ?: task.exe.parentFile!!, steps, task.env,
                )
                execArgs = ""
            } else {
                launchFile = task.exe
                execArgs = task.arguments
            }

            val shortcut = StoreShortcuts.writeShortcut(
                context, container, "gog", game.id.toString(), game.title, launchFile, execArgs, task.exe,
            ) { chooseControls(game, task.exe) }
            StoreShortcuts.copyArtwork(
                shortcut,
                portrait = GogArt.file(context, game.id, ArtKind.Portrait),
                hero = GogArt.file(context, game.id, ArtKind.Hero),
                logo = GogArt.file(context, game.id, ArtKind.Logo),
                exe = task.exe,
            )
            Prepared(shortcut, addedDrive)
        }

    /** The primary task of goggame-<id>.info (any goggame-*.info as a fallback). */
    private fun primaryTask(game: GogGame, installDir: File): Task? {
        val infos = installDir.walkTopDown().maxDepth(2)
            .filter { it.isFile && it.name.startsWith("goggame-", true) && it.name.endsWith(".info", true) }
            .sortedBy { if (it.name.equals("goggame-${game.id}.info", true)) 0 else 1 }
            .toList()
        for (info in infos) {
            val tasks = runCatching { JSONObject(info.readText()).optJSONArray("playTasks") }.getOrNull() ?: continue
            val all = (0 until tasks.length()).map { tasks.getJSONObject(it) }
                .filter { it.optString("type", "FileTask") == "FileTask" && it.optString("path").isNotBlank() }
            val primary = all.firstOrNull { it.optBoolean("isPrimary") } ?: all.firstOrNull() ?: continue
            val base = info.parentFile ?: installDir
            val exe = resolve(base, primary.optString("path")) ?: continue
            val workDir = primary.optString("workingDir").takeIf { it.isNotBlank() }?.let { resolve(base, it) }?.takeIf { it.isDirectory }
            return Task(exe, primary.optString("arguments").trim(), workDir)
        }
        return null
    }

    /** Resolves a GOG relative path ("bin\\Game.exe") ignoring letter case. */
    private fun resolve(base: File, relative: String): File? {
        var current = base
        for (part in relative.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }) {
            val exact = File(current, part)
            current = if (exact.exists()) exact else current.listFiles()?.firstOrNull { it.name.equals(part, true) } ?: return null
        }
        return current
    }

    private val NOT_THE_GAME = Regex("(unins|setup|install|redist|dxsetup|vcredist|crash|report|helper)", RegexOption.IGNORE_CASE)

    private fun scanForExe(installDir: File): File? =
        installDir.walkTopDown().maxDepth(2)
            .filter { it.isFile && it.name.endsWith(".exe", true) && !NOT_THE_GAME.containsMatchIn(it.name) }
            .filterNot { f -> listOf("__redist", "_winxclipse", GogScripts.SUPPORT_DIR).any { f.path.contains(it, true) } }
            .maxByOrNull { it.length() }

    /** GOG's DOSBox games, adapted to run well under Wine here ([DosBox]). */
    private fun forDosBox(task: Task, installDir: File, container: Container): Task {
        val launch = DosBox.adapt(task.exe, task.arguments, File(installDir, "_winxclipse/dosbox.conf")) {
            StoreShortcuts.windowsPath(container, it)
        }
        return Task(task.exe, launch.arguments, task.workDir, launch.env)
    }

    /** GOG genre slugs of mouse-driven and of movement-and-aim games. */
    private val MOUSE_TAGS = setOf(
        "point-and-click", "visual-novel", "strategy", "rts", "turn-based", "turn-based-strategy",
        "puzzle", "card-game", "hidden-object", "text-adventure", "interactive-fiction", "tactical",
    )
    private val ACTION_TAGS = setOf(
        "shooter", "fpp", "tpp", "racing", "platformer", "fighting", "action", "hack-and-slash", "soulslike",
    )

    private fun chooseControls(game: GogGame, exe: File): SteamControls.Mode {
        val details = GogApi.details(game.id)
        val tags = details?.tags.orEmpty()
        return SteamControls.decide(
            padSupport = details?.features?.contains("controller_support") == true,
            wasd = false,
            mouseRank = tags.indexOfFirst { it in MOUSE_TAGS },
            actionRank = tags.indexOfFirst { it in ACTION_TAGS },
            gameExe = exe,
        )
    }
}
