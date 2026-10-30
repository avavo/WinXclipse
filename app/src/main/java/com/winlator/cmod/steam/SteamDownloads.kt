package com.winlator.cmod.steam

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.winlator.cmod.R
import `in`.dragonbra.javasteam.depotdownloader.DepotDownloader
import `in`.dragonbra.javasteam.depotdownloader.IDownloadListener
import `in`.dragonbra.javasteam.depotdownloader.data.AppItem
import `in`.dragonbra.javasteam.depotdownloader.data.DownloadItem
import `in`.dragonbra.javasteam.steam.steamclient.AsyncJobFailedException
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/** Where a Steam game's download is. */
sealed interface SteamInstall {
    data object Preparing : SteamInstall

    /** [progress] is 0..1; [bytesPerSecond] is the network speed (compressed bytes). */
    data class Downloading(val progress: Float, val downloadedBytes: Long, val totalBytes: Long, val bytesPerSecond: Long) : SteamInstall

    data class Paused(val progress: Float) : SteamInstall
    data class Installed(val path: String, val sizeBytes: Long) : SteamInstall
    data class Failed(val message: String) : SteamInstall
}

/**
 * Downloads Steam games with JavaSteam's DepotDownloader (MIT) into
 * Download/WinXclipse/Steam/<installdir>, which every container sees as D:. Only the
 * Windows depots are fetched. Pausing cancels the downloader; starting again resumes,
 * reusing what is already on disk. Downloads run under [SteamDownloadService] so Android
 * keeps them going with the app in the background.
 */
object SteamDownloads {
    private const val TAG = "SteamDownloads"

    private lateinit var appContext: Context
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gson = Gson()
    private val jobs = ConcurrentHashMap<Int, Job>()

    private val _installs = MutableStateFlow<Map<Int, SteamInstall>>(emptyMap())
    val installs: StateFlow<Map<Int, SteamInstall>> = _installs.asStateFlow()

    /** Root of the Steam library on the phone; D:\WinXclipse\Steam inside default containers. */
    val libraryRoot: File
        get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "WinXclipse/Steam")

    /** Games go in steamapps/common like on Steam: some check that their folder is there. */
    private val commonDir: File
        get() = File(libraryRoot, "steamapps/common")

    private val recordFile get() = File(appContext.filesDir, "steam/installed.json")

    private data class Record(val path: String, val sizeBytes: Long, val progress: Float = 1f, val complete: Boolean = true)

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        _installs.value = loadRecords().mapValues { (_, r) ->
            if (r.complete) SteamInstall.Installed(r.path, r.sizeBytes) else SteamInstall.Paused(r.progress)
        }
    }

    fun isActive(appId: Int): Boolean = jobs[appId]?.isActive == true

    fun hasActiveDownloads(): Boolean = jobs.values.any { it.isActive }

    fun start(game: SteamGame) {
        if (isActive(game.appId)) return
        set(game.appId, SteamInstall.Preparing)
        appContext.startService(Intent(appContext, SteamDownloadService::class.java))
        jobs[game.appId] = scope.launch {
            try {
                download(game)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Download of ${game.appId} failed", e)
                set(game.appId, SteamInstall.Failed(describe(e)))
            } finally {
                jobs.remove(game.appId)
            }
        }
    }

    fun pause(appId: Int) {
        val job = jobs.remove(appId) ?: return
        job.cancel()
        val progress = (_installs.value[appId] as? SteamInstall.Downloading)?.progress ?: 0f
        set(appId, SteamInstall.Paused(progress))
        saveRecord(appId) { it?.copy(progress = progress, complete = false) }
    }

    private suspend fun download(game: SteamGame) {
        val installDir = game.installDir?.takeIf { it.isNotBlank() }
            ?: throw IOException(appContext.getString(R.string.steam_dl_no_info))
        val root = commonDir.apply { mkdirs() }
        if (!root.canWrite()) throw IOException(appContext.getString(R.string.steam_dl_no_storage))
        val target = File(root, installDir)
        val missing = game.sizeBytes - folderSize(target)
        val free = StatFs(root.path).availableBytes
        if (missing > free) {
            throw IOException(appContext.getString(R.string.steam_dl_no_space, formatBytes(missing), formatBytes(free)))
        }

        val client = SteamSession.ensureLoggedOn()
        val licenses = SteamSession.awaitLicenses()
        saveRecord(game.appId) { Record(target.path, game.sizeBytes, it?.progress ?: 0f, complete = false) }

        val done = CompletableDeferred<Unit>()
        val progress = ProgressTracker(game)
        val job = coroutineContext[Job]
        // JavaSteam keeps an 8 MB LZMA window per thread that ever decompresses a chunk, so
        // fewer parallel chunks means fewer such threads; with 8 the 256 MB heap ran out.
        val downloader = DepotDownloader(client, licenses, androidEmulation = true, maxDownloads = 4, maxDecompress = 2)
        try {
            downloader.addListener(object : IDownloadListener {
                // Late callbacks after a pause must not overwrite the Paused state.
                override fun onChunkCompleted(depotId: Int, depotPercentComplete: Float, compressedBytes: Long, uncompressedBytes: Long) {
                    if (job?.isActive != true) return
                    progress.depot(depotId, depotPercentComplete, compressedBytes)?.let { set(game.appId, it) }
                }

                override fun onDepotCompleted(depotId: Int, compressedBytes: Long, uncompressedBytes: Long) {
                    if (job?.isActive != true) return
                    progress.depot(depotId, 1f, compressedBytes, force = true)?.let { set(game.appId, it) }
                }

                override fun onDownloadCompleted(item: DownloadItem) {
                    done.complete(Unit)
                }

                override fun onDownloadFailed(item: DownloadItem, error: Throwable) {
                    done.completeExceptionally(error)
                }
            })
            downloader.add(
                AppItem(
                    appId = game.appId,
                    installToGameNameDirectory = true,
                    installDirectory = root.path,
                    os = "windows",
                    osArch = "64",
                    language = "english",
                )
            )
            downloader.finishAdding()
            done.await()
        } finally {
            downloader.close()
        }

        val size = folderSize(target)
        saveRecord(game.appId) { Record(target.path, size) }
        set(game.appId, SteamInstall.Installed(target.path, size))
    }

    /**
     * Turns per-depot callbacks into one progress value, weighting each depot by its size
     * from PICS so a small depot finishing does not jump the bar. Updates at most 4 per second.
     */
    private class ProgressTracker(private val game: SteamGame) {
        private val weights = game.depotSizes.orEmpty()
        private val percent = mutableMapOf<Int, Float>()
        private val compressed = mutableMapOf<Int, Long>()
        private var lastEmit = 0L
        private var lastBytes = 0L
        private var lastSpeedAt = System.currentTimeMillis()
        private var speed = 0L

        @Synchronized
        fun depot(depotId: Int, depotPercent: Float, compressedBytes: Long, force: Boolean = false): SteamInstall.Downloading? {
            percent[depotId] = depotPercent.coerceIn(0f, 1f)
            compressed[depotId] = compressedBytes
            val now = System.currentTimeMillis()
            if (!force && now - lastEmit < 250) return null
            lastEmit = now

            val totalNet = compressed.values.sum()
            if (now - lastSpeedAt >= 1000) {
                speed = (totalNet - lastBytes) * 1000 / (now - lastSpeedAt)
                lastBytes = totalNet
                lastSpeedAt = now
            }
            val fallback = if (weights.isEmpty()) 1L else weights.values.average().toLong().coerceAtLeast(1L)
            val ids = (weights.keys + percent.keys)
            val totalWeight = ids.sumOf { weights[it]?.takeIf { w -> w > 0 } ?: fallback }.toDouble()
            val done = ids.sumOf { (percent[it] ?: 0f).toDouble() * (weights[it]?.takeIf { w -> w > 0 } ?: fallback) }
            val fraction = if (totalWeight > 0) (done / totalWeight).toFloat() else 0f
            val total = game.sizeBytes
            return SteamInstall.Downloading(fraction, (fraction * total).toLong(), total, speed.coerceAtLeast(0))
        }
    }

    private fun set(appId: Int, install: SteamInstall) {
        _installs.update { it + (appId to install) }
    }

    private fun loadRecords(): Map<Int, Record> = runCatching {
        gson.fromJson<Map<Int, Record>>(recordFile.readText(), object : TypeToken<Map<Int, Record>>() {}.type)
    }.getOrNull().orEmpty()

    @Synchronized
    private fun saveRecord(appId: Int, change: (Record?) -> Record?) {
        val records = loadRecords().toMutableMap()
        val updated = change(records[appId])
        if (updated == null) records.remove(appId) else records[appId] = updated
        recordFile.parentFile?.mkdirs()
        recordFile.writeText(gson.toJson(records))
    }

    private fun folderSize(dir: File): Long =
        if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun describe(e: Throwable): String {
        val message = e.message
        return when {
            // Our own checks (no space, no storage access) already carry a readable message.
            e is IOException && message != null && e.cause == null -> message
            e is IOException || e is AsyncJobFailedException -> appContext.getString(R.string.steam_err_network)
            else -> appContext.getString(R.string.steam_dl_failed, message ?: e.javaClass.simpleName)
        }
    }

    fun formatBytes(bytes: Long): String {
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble().coerceAtLeast(0.0)
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return if (unit <= 1) "%.0f %s".format(value, units[unit]) else "%.1f %s".format(value, units[unit])
    }
}
