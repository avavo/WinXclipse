package com.winlator.cmod.store

import android.util.Log
import java.io.File

/**
 * DOSBox as GOG and Steam ship it with their DOS games (0.74 builds on SDL 1.2), adapted to
 * run well under Wine here.
 *
 * Speed: those builds switch SDL to its DirectX driver unless SDL_VIDEODRIVER is set. Under
 * Wine that driver costs about nine wineserver round trips per event check (a message queue
 * peek plus a MsgWaitForMultipleObjects on its DirectInput events), and DOSBox checks events on
 * every 1 ms emulation tick: it then spends nearly all its time waiting for wineserver (13,000
 * requests a second measured), and cycles=max lowers the emulated CPU to a crawl. SDL's windib
 * (GDI) driver checks with a single message peek, so DOSBox runs at full speed.
 *
 * Display: the stores' configurations ask for fullscreen with overlay output, which stays
 * black under Wine with the DirectX driver. A last -conf asks for plain surface output; with
 * windib, fullscreen at the game's own resolution (Wine changes its desktop mode, and the
 * shortcut's fullscreen setting stretches it over the screen), otherwise a window, which the
 * shortcut's fullscreen setting stretches too.
 */
object DosBox {
    private const val TAG = "DosBox"

    /** Arguments and environment variables to start a DOSBox game with. */
    class Launch(val arguments: String, val env: Map<String, String>)

    /** Whether [exe] is a DOSBox build (dosbox.exe, DOSBox-X…), i.e. the game is a DOS game. */
    fun isDosBox(exe: File): Boolean = exe.name.startsWith("dosbox", true) && exe.name.endsWith(".exe", true)

    /**
     * How to start [exe] (a store's dosbox.exe) with the store's [arguments]. [conf] is where
     * the extra configuration goes, and [windowsPath] how Wine names it.
     */
    fun adapt(exe: File, arguments: String, conf: File, windowsPath: (File) -> String): Launch {
        if (!exe.name.equals("dosbox.exe", true)) return Launch(arguments, emptyMap())
        val windib = containsText(exe, "SDL_VIDEODRIVER=directx")
        val env = if (windib) mapOf("SDL_VIDEODRIVER" to "windib") else emptyMap()
        Log.i(TAG, "${exe.parentFile?.name}/${exe.name}: windib=$windib")
        // Without -conf DOSBox reads the user's own dosbox.conf, which a -conf would replace.
        if (!arguments.contains("-conf", true)) return Launch(arguments, env)
        conf.parentFile?.mkdirs()
        conf.writeText(
            if (windib) "[sdl]\r\nfullscreen=true\r\nfullresolution=original\r\noutput=surface\r\n"
            else "[sdl]\r\nfullscreen=false\r\noutput=surface\r\n"
        )
        return Launch("$arguments -conf \"${windowsPath(conf)}\"", env)
    }

    /** Whether [file] (an executable of up to 64 MB) contains [text] as plain ASCII. */
    private fun containsText(file: File, text: String): Boolean {
        if (!file.isFile || file.length() > 64L * 1024 * 1024) return false
        val needle = text.toByteArray(Charsets.US_ASCII)
        val data = runCatching { file.readBytes() }.getOrNull() ?: return false
        val first = needle[0]
        var i = 0
        while (i <= data.size - needle.size) {
            if (data[i] == first) {
                var j = 1
                while (j < needle.size && data[i + j] == needle[j]) j++
                if (j == needle.size) return true
            }
            i++
        }
        return false
    }
}
