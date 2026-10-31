package com.winlator.cmod.steam

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Library artwork from Steam's public CDN, cached on disk (cache dir, so Android may trim it). */
object SteamArt {
    enum class Kind(val remoteName: String) {
        Portrait("library_600x900.jpg"),
        Hero("library_hero.jpg"),
        Header("header.jpg"),
        Logo("logo.png"),
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val downloads = Semaphore(4)

    fun url(appId: Int, kind: Kind) = "https://cdn.cloudflare.steamstatic.com/steam/apps/$appId/${kind.remoteName}"

    /**
     * The cached file, downloading it first if needed; null when Steam has no such image
     * for the game (remembered with an empty file so it is not asked for again).
     */
    suspend fun file(context: Context, appId: Int, kind: Kind): File? = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "steam-art/${appId}_${kind.name.lowercase()}")
        if (file.exists()) return@withContext file.takeIf { it.length() >= MIN_BYTES }
        downloads.withPermit {
            if (file.exists()) return@withPermit file.takeIf { it.length() >= MIN_BYTES }
            runCatching {
                file.parentFile?.mkdirs()
                http.newCall(Request.Builder().url(url(appId, kind)).build()).execute().use { response ->
                    val tmp = File(file.path + ".part")
                    when {
                        response.isSuccessful -> {
                            tmp.outputStream().use { out -> response.body.byteStream().copyTo(out) }
                            tmp.renameTo(file)
                        }
                        response.code == 404 -> file.createNewFile()
                        else -> Unit
                    }
                }
                file.takeIf { it.exists() && it.length() >= MIN_BYTES }
            }.getOrNull()
        }
    }

    /**
     * Smaller files are placeholders: for newer games the legacy logo.png path serves a
     * blank 640x360 image of a few hundred bytes.
     */
    private const val MIN_BYTES = 2048L

    /** For the store library screen: keys are app ids. */
    val source = com.winlator.cmod.store.ArtSource { context, key, kind ->
        val appId = key.toIntOrNull() ?: return@ArtSource null
        file(context, appId, Kind.valueOf(kind.name))
    }
}
