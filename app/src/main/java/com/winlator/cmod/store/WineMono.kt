package com.winlator.cmod.store

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.winlator.cmod.R
import com.winlator.cmod.container.Container
import com.winlator.cmod.contents.ContentsManager
import com.winlator.cmod.core.TarCompressorUtils
import com.winlator.cmod.core.WineInfo
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Wine Mono, Wine's own .NET Framework, which .NET and XNA games (common among indies)
 * need to start. The bundled Proton ships without it, so the first time such a game is
 * played, the exact version that Wine asks for is downloaded from the Wine Mono project and
 * unpacked into Wine's share/wine/mono, where every container using that Wine finds it.
 */
object WineMono {
    private const val TAG = "WineMono"

    // Wine names the Wine Mono it wants in appwiz.cpl ("wine-mono-9.3.1-x86.msi").
    private val VERSION = Regex("wine-mono-(\\d+\\.\\d+\\.\\d+)-x86\\.msi")

    private val lock = Mutex()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** True when [exe] is a .NET assembly (its PE header has a CLR runtime directory). */
    fun isDotNet(exe: File): Boolean = runCatching {
        if (!exe.name.endsWith(".exe", true)) return false
        RandomAccessFile(exe, "r").use { raf ->
            raf.seek(0x3C)
            val pe = Integer.reverseBytes(raf.readInt()).toLong()
            raf.seek(pe)
            if (Integer.reverseBytes(raf.readInt()) != 0x00004550) return false // "PE\0\0"
            val optional = pe + 24
            raf.seek(optional)
            val magic = java.lang.Short.reverseBytes(raf.readShort()).toInt() and 0xFFFF
            val directories = optional + if (magic == 0x20B) 112 else 96
            raf.seek(directories - 4)
            if (Integer.reverseBytes(raf.readInt()) <= 14) return false
            raf.seek(directories + 14 * 8)
            Integer.reverseBytes(raf.readInt()) != 0 && Integer.reverseBytes(raf.readInt()) != 0
        }
    }.getOrDefault(false)

    /**
     * Makes sure the Wine of [container] has Wine Mono. Returns false when it can't (no
     * network, unknown Wine): the game then starts anyway and fails as it would have.
     */
    suspend fun ensure(context: Context, container: Container): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                if (File(container.rootDir, ".wine/drive_c/windows/mono/mono-2.0").isDirectory) return@withLock true
                val wineDir = wineDir(context, container) ?: throw IOException("Wine of container ${container.id} not found")
                val version = expectedVersion(wineDir) ?: throw IOException("No Wine Mono version in ${wineDir.name}")
                val monoDir = File(wineDir, "share/wine/mono")
                val target = File(monoDir, "wine-mono-$version")
                if (File(target, "bin/libmono-2.0-x86_64.dll").isFile) return@withLock true

                toast(context, context.getString(R.string.wine_mono_downloading, version))
                Log.i(TAG, "Installing Wine Mono $version into ${wineDir.path}")
                val archive = File(context.cacheDir, "wine-mono-$version-x86.tar.xz")
                download(version, archive)
                val staging = File(monoDir, ".staging").apply { deleteRecursively(); mkdirs() }
                try {
                    if (!TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, archive, staging)) {
                        throw IOException("Could not unpack Wine Mono $version")
                    }
                    val unpacked = File(staging, "wine-mono-$version")
                    if (!File(unpacked, "bin/libmono-2.0-x86_64.dll").isFile) throw IOException("Unexpected Wine Mono archive")
                    target.deleteRecursively()
                    if (!unpacked.renameTo(target)) throw IOException("Could not move Wine Mono into place")
                } finally {
                    staging.deleteRecursively()
                    archive.delete()
                }
                Log.i(TAG, "Wine Mono $version installed")
                true
            } catch (e: Exception) {
                Log.w(TAG, "Wine Mono not available", e)
                toast(context, context.getString(R.string.wine_mono_failed, e.message ?: e.javaClass.simpleName))
                false
            }
        }
    }

    private fun wineDir(context: Context, container: Container): File? {
        val contents = ContentsManager(context).apply { syncContents() }
        val info = WineInfo.fromIdentifier(context, contents, container.wineVersion)
        return info.path?.takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isDirectory }
    }

    private fun expectedVersion(wineDir: File): String? =
        wineDir.walkTopDown().maxDepth(4)
            .filter { it.isFile && it.name.equals("appwiz.cpl", true) }
            .firstNotNullOfOrNull { VERSION.find(it.readBytes().toString(Charsets.ISO_8859_1))?.groupValues?.get(1) }

    /** The release from the Wine Mono project on GitHub, or WineHQ's mirror of it. */
    private fun download(version: String, out: File) {
        val urls = listOf(
            "https://github.com/madewokherd/wine-mono/releases/download/wine-mono-$version/wine-mono-$version-x86.tar.xz",
            "https://dl.winehq.org/wine/wine-mono/$version/wine-mono-$version-x86.tar.xz",
        )
        var lastError: IOException? = null
        for (url in urls) {
            try {
                http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}: $url")
                    val part = File(out.path + ".part")
                    part.outputStream().use { response.body.byteStream().copyTo(it, 1 shl 16) }
                    if (!part.renameTo(out)) throw IOException("Could not write ${out.path}")
                }
                return
            } catch (e: IOException) {
                Log.w(TAG, "Download from $url failed", e)
                lastError = e
            }
        }
        throw lastError ?: IOException("Could not download Wine Mono $version")
    }

    private fun toast(context: Context, text: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show() }
    }
}
