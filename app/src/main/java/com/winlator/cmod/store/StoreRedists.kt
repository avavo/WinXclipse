package com.winlator.cmod.store

import com.winlator.cmod.container.Container
import java.io.File

/**
 * The launch batch of a store game: it silently installs the redistributables the game
 * needs (Visual C++, DirectX, OpenAL…) the first time it runs in a container, then starts the
 * game in its working folder, with any environment variables it needs, and exits (so no
 * console window stays behind the game).
 *
 * Each installer leaves a marker in the container's C:\ProgramData\WinXclipse\redist, so it
 * runs once per container however the game is started (store library or home screen).
 */
object StoreRedists {
    private const val MARKERS = "C:\\ProgramData\\WinXclipse\\redist"

    /** An installer (.exe, .msi, or a .reg to import) to run once per container; [key] names its marker. */
    class Redist(val file: File, val key: String, val arguments: String)

    /** [env] holds variables the game needs; the batch sets them before starting it. */
    fun writeLaunchBatch(
        container: Container,
        batch: File,
        exe: File,
        arguments: String,
        workDir: File,
        redists: List<Redist>,
        env: Map<String, String> = emptyMap(),
    ): File {
        batch.parentFile?.mkdirs()
        batch.writeText(
            buildString {
                append("@echo off\r\n")
                if (redists.isNotEmpty()) append("if not exist \"$MARKERS\" mkdir \"$MARKERS\"\r\n")
                // goto rather than ( ) blocks: installer arguments may contain parentheses.
                redists.forEachIndexed { index, redist ->
                    val marker = "$MARKERS\\${redist.key.replace(Regex("[^A-Za-z0-9._-]"), "_")}.done"
                    val file = StoreShortcuts.windowsPath(container, redist.file)
                    val command = when {
                        redist.file.name.endsWith(".msi", true) -> "msiexec /i \"$file\""
                        redist.file.name.endsWith(".reg", true) -> "regedit /S \"$file\""
                        else -> "\"$file\""
                    }
                    append("if exist \"$marker\" goto redist$index\r\n")
                    append("start \"\" /wait $command ${redist.arguments}\r\n")
                    append("echo done>\"$marker\"\r\n")
                    append(":redist$index\r\n")
                }
                env.forEach { (name, value) -> append("set $name=$value\r\n") }
                append("start \"\" /d \"${StoreShortcuts.windowsPath(container, workDir)}\" \"${StoreShortcuts.windowsPath(container, exe)}\" $arguments\r\n")
                append("exit\r\n")
            }
        )
        return batch
    }
}
