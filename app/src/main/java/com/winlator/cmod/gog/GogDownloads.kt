package com.winlator.cmod.gog

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.util.Log
import android.widget.Toast
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.winlator.cmod.R
import com.winlator.cmod.steam.SteamDownloadService
import com.winlator.cmod.steam.SteamDownloads
import com.winlator.cmod.steam.SteamInstall
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.InflaterInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Downloads GOG games through GOG's content system (generation 2, as GOG Galaxy and gogdl
 * do) into Download/WinXclipse/GOG/<installDirectory>, which default containers see as D:.
 * Picks the public Windows build, English or language-neutral depots, owned DLC, and 64-bit
 * depots when the game has them. Chunks are zlib-compressed and checked by MD5; files listed
 * in the depot's small-files container are cut out of it. The build's dependencies come from
 * GOG's dependency store: helpers such as DOSBox or ScummVM into the game's folder, Visual C++,
 * DirectX and other redistributables into a __redist folder all games share. Support files
 * go to __support/<product> for the game's install scripts ([GogScripts]). Pausing cancels
 * the job; starting again skips the files already complete. Adapted from GameNative
 * (GPL-3.0) and gogdl.
 */
object GogDownloads {
    private const val TAG = "GogDownloads"
    private const val WORKERS = 4
    private const val STATE_DIR = ".winxclipse_gog"

    // Stands in for a product id: the chunk comes from GOG's dependency store.
    private const val DEPENDENCIES = "dependencies"

    // 1: dependencies, support files and install scripts.
    private const val SETUP_VERSION = 1

    /** GOG dependency ids that Wine Mono covers (.NET Framework, XNA). */
    private val WINE_MONO_DEPENDENCY = Regex("dotNet\\d+C?|XNA(_40)?", RegexOption.IGNORE_CASE)

    private lateinit var appContext: Context
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gson = Gson()
    private val jobs = ConcurrentHashMap<Long, Job>()

    private val _installs = MutableStateFlow<Map<Long, SteamInstall>>(emptyMap())
    val installs: StateFlow<Map<Long, SteamInstall>> = _installs.asStateFlow()

    /** Root of the GOG library on the phone; D:\WinXclipse\GOG inside default containers. */
    val libraryRoot: File
        get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "WinXclipse/GOG")

    private val recordFile get() = File(appContext.filesDir, "gog/installed.json")

    /** A redistributable the game installs once per container; [path] is relative to [libraryRoot]. */
    data class Redist(val id: String, val path: String, val arguments: String)

    private data class Record(
        val path: String,
        val sizeBytes: Long,
        val progress: Float = 1f,
        val complete: Boolean = true,
        val dependencies: List<String>? = null,
        val redists: List<Redist>? = null,
        // What the install included; older ones get completed by [setup].
        val setupVersion: Int? = null,
    )

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        _installs.value = loadRecords().mapValues { (_, r) ->
            if (r.complete) SteamInstall.Installed(r.path, r.sizeBytes) else SteamInstall.Paused(r.progress)
        }
    }

    fun isActive(id: Long): Boolean = jobs[id]?.isActive == true

    fun hasActiveDownloads(): Boolean = jobs.values.any { it.isActive }

    fun start(game: GogGame) {
        if (isActive(game.id)) return
        set(game.id, SteamInstall.Preparing)
        appContext.startService(Intent(appContext, SteamDownloadService::class.java))
        jobs[game.id] = scope.launch {
            try {
                download(game)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Download of ${game.id} failed", e)
                set(game.id, SteamInstall.Failed(describe(e)))
            } finally {
                jobs.remove(game.id)
            }
        }
    }

    fun pause(id: Long) {
        val job = jobs.remove(id) ?: return
        job.cancel()
        val progress = (_installs.value[id] as? SteamInstall.Downloading)?.progress ?: 0f
        set(id, SteamInstall.Paused(progress))
        saveRecord(id) { it?.copy(progress = progress, complete = false) }
    }

    // region Plan

    private class Chunk(val md5: String, val compressedMd5: String, val size: Long)

    private class FileEntry(
        val path: String,
        val productId: String,
        val chunks: List<Chunk>,
        val sfcOffset: Long,
        val sfcSize: Long,
        val sfcIndex: Int,
        /** Goes to the library's shared __redist folder instead of the game's. */
        val shared: Boolean = false,
    ) {
        val size: Long get() = if (sfcIndex >= 0) sfcSize else chunks.sumOf { it.size }
    }

    /** A depot's small-files container: one blob made of chunks, files are slices of it. */
    private class SmallFiles(val productId: String, val chunks: List<Chunk>)

    private class Plan(
        val installDir: File,
        val files: List<FileEntry>,
        val directories: List<String>,
        val containers: List<SmallFiles>,
        val dependencies: List<String>,
        val redists: List<Redist>,
    ) {
        val totalBytes: Long get() = files.sumOf { it.size }
    }

    /** The manifest of the game's public generation-2 Windows build. */
    private fun buildManifest(game: GogGame, token: String): JSONObject {
        val build = GogApi.builds(token, game.id, 2).firstOrNull { it.branch == null && it.link.isNotEmpty() }
            ?: throw IOException(
                appContext.getString(
                    if (GogApi.builds(token, game.id, 1).isNotEmpty()) R.string.gog_dl_legacy else R.string.gog_dl_no_build
                )
            )
        return GogApi.manifest(build.link, token)
    }

    private suspend fun plan(game: GogGame, token: String): Plan {
        val manifest = buildManifest(game, token)
        val baseProductId = manifest.optString("baseProductId", game.id.toString())
        val installName = manifest.optString("installDirectory").ifBlank { game.title }
            .replace(Regex("[\\\\/:*?\"<>|]"), "").trim()
        val installDir = File(libraryRoot, installName)
        val depots = selectDepots(manifest.optJSONArray("depots") ?: JSONArray(), baseProductId, GogSession.ownedIds())
        if (depots.isEmpty()) throw IOException(appContext.getString(R.string.gog_dl_no_build))

        val files = linkedMapOf<String, FileEntry>()
        val directories = mutableListOf<String>()
        val containers = mutableListOf<SmallFiles>()
        for (depot in depots) {
            val productId = depot.optString("productId", baseProductId)
            val depotJson = GogApi.manifest(GogApi.depotManifestUrl(depot.optString("manifest")), token)
            val body = depotJson.optJSONObject("depot") ?: depotJson
            val sfcIndex = body.optJSONObject("smallFilesContainer")?.let { sfc ->
                containers += SmallFiles(productId, chunks(sfc.optJSONArray("chunks")))
                containers.lastIndex
            } ?: -1
            val items = body.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val path = item.optString("path").replace('\\', '/').trim('/')
                if (path.isEmpty()) continue
                when (item.optString("type")) {
                    "DepotDirectory" -> directories += path
                    "DepotFile" -> {
                        // Support files (DOSBox configurations…) are for the install scripts.
                        val support = item.optJSONArray("flags")?.toString().orEmpty().contains("\"support\"")
                        val target = if (support) "${GogScripts.SUPPORT_DIR}/$productId/$path" else path
                        val sfcRef = item.optJSONObject("sfcRef")
                        // Later depots (languages, DLC) win for the same path, like Galaxy.
                        files[target.lowercase()] = FileEntry(
                            path = target,
                            productId = productId,
                            chunks = chunks(item.optJSONArray("chunks")),
                            sfcOffset = sfcRef?.optLong("offset") ?: 0L,
                            sfcSize = sfcRef?.optLong("size") ?: 0L,
                            sfcIndex = if (sfcRef != null && sfcIndex >= 0) sfcIndex else -1,
                        )
                    }
                }
            }
        }
        val dependencyIds = strings(manifest.optJSONArray("dependencies"))
        val dependencies = dependencyPlan(dependencyIds)
        for (file in dependencies.files) {
            // The game's own files win over a bundled helper's.
            files.putIfAbsent((if (file.shared) "shared:" else "") + file.path.lowercase(), file)
        }
        return Plan(installDir, files.values.toList(), directories, containers, dependencyIds, dependencies.redists)
    }

    private class DependencyPlan(val files: List<FileEntry>, val redists: List<Redist>)

    /**
     * Files and installers of the dependencies [ids] of a build. .NET Framework and XNA are
     * left to Wine Mono: Microsoft's installers don't work under Wine, while Wine Mono (with
     * FNA for XNA) runs those games.
     */
    private fun dependencyPlan(ids: List<String>): DependencyPlan {
        val wanted = ids.filterNot { WINE_MONO_DEPENDENCY.matches(it) }
        if (wanted.isEmpty()) return DependencyPlan(emptyList(), emptyList())
        val repository = GogApi.dependencies().associateBy { it.id }
        val files = mutableListOf<FileEntry>()
        val redists = mutableListOf<Redist>()
        for (id in wanted) {
            val dependency = repository[id]
            if (dependency == null) {
                Log.w(TAG, "Unknown GOG dependency $id")
                continue
            }
            val depot = GogApi.getJson(GogApi.dependencyManifestUrl(dependency.manifest), token = null)
            val items = (depot.optJSONObject("depot") ?: depot).optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val path = item.optString("path").replace('\\', '/').trim('/')
                val chunks = chunks(item.optJSONArray("chunks"))
                if (item.optString("type") != "DepotFile" || path.isEmpty() || chunks.isEmpty()) continue
                files += FileEntry(path, DEPENDENCIES, chunks, 0L, 0L, -1, shared = !dependency.internal)
            }
            if (!dependency.internal && dependency.executable.isNotBlank()) {
                redists += Redist(id, dependency.executable.replace('\\', '/').trim('/'), dependency.arguments)
            }
        }
        return DependencyPlan(files, redists)
    }

    /** What [GogLauncher] needs from an installed game's setup. */
    class Setup(val redists: List<Redist>, val needsWineMono: Boolean)

    /**
     * An installed game's setup, completed first when an older WinXclipse installed it
     * (without dependencies, support files or install scripts) or a shared redistributable
     * was deleted: the download runs again and fetches only the missing files.
     */
    suspend fun setup(game: GogGame): Setup = withContext(Dispatchers.IO) {
        var record = loadRecords()[game.id] ?: return@withContext Setup(emptyList(), false)
        if (record.setupVersion != SETUP_VERSION || record.redists.orEmpty().any { !File(libraryRoot, it.path).isFile }) {
            Log.i(TAG, "${game.id}: completing the install (setup ${record.setupVersion})")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(appContext, R.string.gog_setup_completing, Toast.LENGTH_LONG).show()
            }
            download(game)
            record = loadRecords()[game.id] ?: return@withContext Setup(emptyList(), false)
        }
        Setup(record.redists.orEmpty(), record.dependencies.orEmpty().any { WINE_MONO_DEPENDENCY.matches(it) })
    }

    private fun strings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).map { array.getString(it) }.filter { it.isNotBlank() }

    private fun chunks(array: JSONArray?): List<Chunk> {
        if (array == null) return emptyList()
        return (0 until array.length()).map { array.getJSONObject(it) }.map {
            Chunk(it.optString("md5"), it.optString("compressedMd5"), it.optLong("size"))
        }
    }

    /**
     * Depots of the base game and owned DLC, in English or language-neutral, 64-bit when a
     * product offers both. Falls back to every depot of a product if none is English.
     */
    private fun selectDepots(all: JSONArray, baseProductId: String, owned: Set<Long>): List<JSONObject> {
        val depots = (0 until all.length()).map { all.getJSONObject(it) }.filter { depot ->
            val product = depot.optString("productId", baseProductId)
            product == baseProductId || product.toLongOrNull()?.let { it in owned } == true
        }
        return depots.groupBy { it.optString("productId", baseProductId) }.flatMap { (_, productDepots) ->
            val has64 = productDepots.any { bitness(it).contains("64") }
            val byArch = productDepots.filter { d -> bitness(d).let { it.isEmpty() || !has64 || it.contains("64") } }
            val english = byArch.filter { d ->
                val languages = d.optJSONArray("languages") ?: JSONArray()
                (0 until languages.length()).map { languages.getString(it).lowercase() }
                    .any { it == "*" || it.startsWith("en") || it == "english" }
            }
            english.ifEmpty { byArch }
        }
    }

    private fun bitness(depot: JSONObject): List<String> {
        val array = depot.optJSONArray("osBitness") ?: return emptyList()
        return (0 until array.length()).map { array.getString(it) }
    }

    // endregion

    // region Download

    private suspend fun download(game: GogGame) {
        val token = GogSession.accessToken()
        val plan = plan(game, token)
        val root = libraryRoot.apply { mkdirs() }
        if (!root.canWrite()) throw IOException(appContext.getString(R.string.steam_dl_no_storage))
        val target = plan.installDir.apply { mkdirs() }
        val stateDir = File(target, STATE_DIR).apply { mkdirs() }
        val doneFile = File(stateDir, "done.txt")
        val done = (if (doneFile.isFile) doneFile.readLines() else emptyList()).toMutableSet()
        // Files already complete on disk: a repair, or a redistributable another game fetched.
        plan.files.filter { it.path !in done }
            .filter { File(if (it.shared) root else target, it.path).let { f -> f.isFile && f.length() == it.size } }
            .forEach { done += it.path }

        val total = plan.totalBytes
        val already = plan.files.filter { it.path in done }.sumOf { it.size }
        val free = StatFs(root.path).availableBytes
        if (total - already > free) {
            throw IOException(appContext.getString(R.string.steam_dl_no_space, SteamDownloads.formatBytes(total - already), SteamDownloads.formatBytes(free)))
        }
        saveRecord(game.id) { Record(target.path, total, it?.progress ?: 0f, complete = false) }
        plan.directories.forEach { File(target, it).mkdirs() }

        val written = AtomicLong(already)
        val network = AtomicLong(0)
        val links = LinkCache()
        val doneLock = Mutex()
        coroutineScope {
            // Progress, 4 times a second.
            val ticker = launch {
                var lastNet = 0L
                var lastAt = System.currentTimeMillis()
                var speed = 0L
                while (isActive) {
                    delay(250)
                    val now = System.currentTimeMillis()
                    if (now - lastAt >= 1000) {
                        val net = network.get()
                        speed = (net - lastNet) * 1000 / (now - lastAt)
                        lastNet = net
                        lastAt = now
                    }
                    val bytes = written.get()
                    val fraction = if (total > 0) (bytes.toDouble() / total).toFloat() else 0f
                    set(game.id, SteamInstall.Downloading(fraction.coerceIn(0f, 1f), bytes, total, speed))
                }
            }

            // Small-files containers first: they are cut into many files afterwards.
            val sfcFiles = plan.files.filter { it.sfcIndex >= 0 && it.path !in done }
            for ((index, container) in plan.containers.withIndex()) {
                val members = sfcFiles.filter { it.sfcIndex == index }
                if (members.isEmpty()) continue
                val blob = File(stateDir, "sfc_$index.bin")
                writeChunks(container.chunks, container.productId, blob, links, network, written = null)
                RandomAccessFile(blob, "r").use { source ->
                    for (member in members) {
                        val out = File(target, member.path).apply { parentFile?.mkdirs() }
                        val buffer = ByteArray(member.sfcSize.toInt())
                        source.seek(member.sfcOffset)
                        source.readFully(buffer)
                        out.writeBytes(buffer)
                        written.addAndGet(member.sfcSize)
                        doneLock.withLock { markDone(doneFile, done, member.path) }
                    }
                }
                blob.delete()
            }

            // Regular files, WORKERS at a time, chunks of a file in order.
            val queue = Channel<FileEntry>(Channel.UNLIMITED)
            plan.files.filter { it.sfcIndex < 0 && it.path !in done }.forEach { queue.trySend(it) }
            queue.close()
            val workers = List(WORKERS) {
                launch {
                    for (file in queue) {
                        val out = File(if (file.shared) root else target, file.path).apply { parentFile?.mkdirs() }
                        if (file.chunks.isEmpty()) out.writeBytes(ByteArray(0))
                        else {
                            // Per game: two games may fetch the same shared redistributable at once.
                            val part = File(if (file.shared) "${out.path}.${game.id}.part" else "${out.path}.part")
                            writeChunks(file.chunks, file.productId, part, links, network, written)
                            out.delete()
                            if (!part.renameTo(out)) throw IOException("Could not write ${out.path}")
                        }
                        doneLock.withLock { markDone(doneFile, done, file.path) }
                    }
                }
            }
            workers.forEach { it.join() }
            ticker.cancel()
        }

        GogScripts.applyFiles(target)
        stateDir.deleteRecursively()
        saveRecord(game.id) {
            Record(target.path, total, dependencies = plan.dependencies, redists = plan.redists, setupVersion = SETUP_VERSION)
        }
        set(game.id, SteamInstall.Installed(target.path, total))
    }

    private fun markDone(doneFile: File, done: MutableSet<String>, path: String) {
        done += path
        doneFile.appendText(path + "\n")
    }

    /** Downloads, inflates and MD5-checks [chunks] into [out], in order. */
    private suspend fun writeChunks(
        chunks: List<Chunk>,
        productId: String,
        out: File,
        links: LinkCache,
        network: AtomicLong,
        written: AtomicLong?,
    ) {
        out.outputStream().buffered(1 shl 16).use { stream ->
            for (chunk in chunks) {
                coroutineScopeEnsureActive()
                val compressed = fetchChunk(chunk, productId, links)
                network.addAndGet(compressed.size.toLong())
                val digest = MessageDigest.getInstance("MD5")
                val counting = DigestOutputStream(stream, digest)
                InflaterInputStream(ByteArrayInputStream(compressed)).use { it.copyTo(counting, 1 shl 16) }
                counting.flush()
                val md5 = digest.digest().joinToString("") { "%02x".format(it) }
                if (chunk.md5.isNotEmpty() && !md5.equals(chunk.md5, ignoreCase = true)) {
                    throw IOException("Corrupt chunk ${chunk.compressedMd5}")
                }
                written?.addAndGet(chunk.size)
            }
        }
    }

    private suspend fun coroutineScopeEnsureActive() = kotlin.coroutines.coroutineContext.ensureActive()

    /** One chunk's compressed bytes, trying every CDN and renewing expired secure links. */
    private suspend fun fetchChunk(chunk: Chunk, productId: String, links: LinkCache): ByteArray {
        var lastError: Exception? = null
        repeat(4) { attempt ->
            val bases = links.get(productId, renew = attempt > 0)
            for (base in bases) {
                try {
                    val request = Request.Builder().url(GogApi.chunkUrl(base, chunk.compressedMd5)).build()
                    GogApi.http.newCall(request).execute().use { response ->
                        if (response.isSuccessful) return response.body.bytes()
                        lastError = GogHttpException(response.code, base)
                    }
                } catch (e: IOException) {
                    lastError = e
                }
            }
            delay(1000L * (attempt + 1))
        }
        throw lastError ?: IOException("Could not download a chunk")
    }

    /** Secure CDN links per product, renewed when they stop working. */
    private class LinkCache {
        private val lock = Mutex()
        private val links = mutableMapOf<String, List<String>>()

        suspend fun get(productId: String, renew: Boolean): List<String> = lock.withLock {
            val cached = links[productId]
            if (cached != null && !renew) return@withLock cached
            val fresh = if (productId == DEPENDENCIES) GogApi.dependencyLinks()
            else GogApi.secureLinks(GogSession.accessToken(), productId)
            links[productId] = fresh
            fresh
        }
    }

    // endregion

    private fun set(id: Long, install: SteamInstall) {
        _installs.update { it + (id to install) }
    }

    private fun loadRecords(): Map<Long, Record> = runCatching {
        gson.fromJson<Map<Long, Record>>(recordFile.readText(), object : TypeToken<Map<Long, Record>>() {}.type)
    }.getOrNull().orEmpty()

    @Synchronized
    private fun saveRecord(id: Long, change: (Record?) -> Record?) {
        val records = loadRecords().toMutableMap()
        val updated = change(records[id])
        if (updated == null) records.remove(id) else records[id] = updated
        recordFile.parentFile?.mkdirs()
        recordFile.writeText(gson.toJson(records))
    }

    private fun describe(e: Throwable): String {
        val message = e.message
        return when {
            e is IOException && e !is GogHttpException && message != null && e.cause == null -> message
            e is IOException && e !is GogHttpException -> appContext.getString(R.string.gog_err_network)
            else -> appContext.getString(R.string.steam_dl_failed, message ?: e.javaClass.simpleName)
        }
    }
}
