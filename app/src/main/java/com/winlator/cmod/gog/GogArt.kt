package com.winlator.cmod.gog

import android.content.Context
import com.winlator.cmod.store.ArtKind
import com.winlator.cmod.store.ArtSource
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * GOG artwork from the public GamesDB (no sign-in needed), cached on disk: the Galaxy
 * vertical cover, the background as hero and the title logo.
 */
object GogArt {
    private val downloads = Semaphore(4)
    private val templateLock = Mutex()
    private const val MIN_BYTES = 2048L

    /** GamesDB template key and the "{formatter}.{ext}" suffix for each kind. */
    private fun suffix(kind: ArtKind): Pair<String, String> = when (kind) {
        ArtKind.Portrait -> "vertical_cover" to "_glx_vertical_cover.webp"
        ArtKind.Hero -> "background" to ".jpg"
        ArtKind.Header -> "horizontal_artwork" to ".jpg"
        ArtKind.Logo -> "logo" to "_glx_logo.webp"
    }

    suspend fun file(context: Context, id: Long, kind: ArtKind): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "gog-art").apply { mkdirs() }
        val file = File(dir, "${id}_${kind.name.lowercase()}")
        if (file.exists()) return@withContext file.takeIf { it.length() >= MIN_BYTES }
        val (key, ending) = suffix(kind)
        val template = templates(dir, id)[key]
        downloads.withPermit {
            if (file.exists()) return@withPermit file.takeIf { it.length() >= MIN_BYTES }
            if (template == null) {
                file.createNewFile()
                return@withPermit null
            }
            val url = template.replace("{formatter}.{ext}", ending).replace("{formatter}", "").replace("{ext}", ending.substringAfter('.'))
            runCatching {
                GogApi.http.newCall(Request.Builder().url(url).build()).execute().use { response ->
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

    /** The game's GamesDB image templates, fetched once and kept next to the images. */
    private suspend fun templates(dir: File, id: Long): Map<String, String> = templateLock.withLock {
        val cache = File(dir, "$id.json")
        if (cache.isFile) {
            runCatching {
                val json = JSONObject(cache.readText())
                return@withLock json.keys().asSequence().associateWith { json.getString(it) }
            }
        }
        val templates = GogApi.artTemplates(id)
        if (templates.isNotEmpty()) cache.writeText(JSONObject(templates).toString())
        templates
    }

    /** For the store library screen: keys are GOG product ids. */
    val source = ArtSource { context, key, kind ->
        val id = key.toLongOrNull() ?: return@ArtSource null
        file(context, id, kind)
    }
}
