package com.winlator.cmod.gog

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * GOG's web APIs as GOG Galaxy uses them: OAuth sign-in, the owned-games list, GamesDB
 * artwork and the content system (builds, manifests, secure CDN links). Endpoints and the
 * Galaxy client id are the public ones also used by Heroic/gogdl and GameNative (GPL-3.0).
 */
internal object GogApi {
    const val CLIENT_ID = "46899977096215655"
    private const val CLIENT_SECRET = "9d85c43b1482497dbbce61f6e4aa173a433796eeae2ca8c5f6129f2dc4de46d9"
    const val REDIRECT_URI = "https://embed.gog.com/on_login_success?origin=client"
    private const val CONTENT_SYSTEM = "https://content-system.gog.com"
    private const val CDN = "https://cdn.gog.com"

    val loginUrl: String
        get() = "https://auth.gog.com/auth?client_id=$CLIENT_ID" +
            "&redirect_uri=${URLEncoder.encode(REDIRECT_URI, "UTF-8")}&response_type=code&layout=galaxy"

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // region Sign-in

    data class Tokens(val accessToken: String, val refreshToken: String, val userId: String, val expiresAt: Long)

    fun exchangeCode(code: String): Tokens = token(
        "grant_type" to "authorization_code", "code" to code, "redirect_uri" to REDIRECT_URI,
    )

    fun refresh(refreshToken: String): Tokens = token("grant_type" to "refresh_token", "refresh_token" to refreshToken)

    private fun token(vararg params: Pair<String, String>): Tokens {
        val url = "https://auth.gog.com/token".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", CLIENT_ID)
            .addQueryParameter("client_secret", CLIENT_SECRET)
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
        val json = getJson(url.toString(), token = null)
        val access = json.optString("access_token")
        if (access.isEmpty()) throw IOException(json.optString("error_description", "GOG sign-in failed"))
        return Tokens(
            accessToken = access,
            refreshToken = json.optString("refresh_token"),
            userId = json.optString("user_id"),
            expiresAt = System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000,
        )
    }

    fun userName(token: String): String? =
        runCatching { getJson("https://embed.gog.com/userData.json", token).optString("username").ifEmpty { null } }.getOrNull()

    // endregion

    // region Library

    data class Product(val id: Long, val title: String)

    fun ownedIds(token: String): Set<Long> {
        val owned = getJson("https://embed.gog.com/user/data/games", token).optJSONArray("owned") ?: JSONArray()
        return (0 until owned.length()).map { owned.getLong(it) }.toSet()
    }

    /** Owned Windows games (hidden ones too), from the account's product pages. */
    fun ownedGames(token: String): List<Product> {
        val games = linkedMapOf<Long, Product>()
        for (hidden in listOf(0, 1)) {
            var page = 1
            do {
                val json = getJson("https://embed.gog.com/account/getFilteredProducts?mediaType=1&hiddenFlag=$hidden&page=$page", token)
                val products = json.optJSONArray("products") ?: JSONArray()
                for (i in 0 until products.length()) {
                    val p = products.getJSONObject(i)
                    val windows = p.optJSONObject("worksOn")?.optBoolean("Windows", true) ?: true
                    if (!windows || p.optBoolean("isGame", true).not()) continue
                    val id = p.optLong("id")
                    if (id > 0) games[id] = Product(id, p.optString("title").trim().ifEmpty { "GOG $id" })
                }
                val totalPages = json.optInt("totalPages", 1)
                page++
            } while (page <= totalPages)
        }
        return games.values.toList()
    }

    /** Store details used to pick controls: GOG feature ids and genre tag slugs (main ones first). */
    data class Details(val features: List<String>, val tags: List<String>)

    fun details(id: Long): Details? = runCatching {
        val embedded = getJson("https://api.gog.com/v2/games/$id?locale=en-US", token = null).optJSONObject("_embedded")
            ?: return@runCatching null
        val features = embedded.optJSONArray("features") ?: JSONArray()
        val tags = embedded.optJSONArray("tags") ?: JSONArray()
        Details(
            features = (0 until features.length()).map { features.getJSONObject(it).optString("id") },
            tags = (0 until tags.length()).map { tags.getJSONObject(it) }
                .sortedBy { it.optInt("level", 9) }
                .map { it.optString("slug") },
        )
    }.getOrNull()

    /** GamesDB image templates ("…{formatter}.{ext}"), by kind: vertical_cover, background, logo. */
    fun artTemplates(id: Long): Map<String, String> = runCatching {
        val game = getJson("https://gamesdb.gog.com/platforms/gog/external_releases/$id", token = null).optJSONObject("game")
            ?: return@runCatching emptyMap()
        listOf("vertical_cover", "background", "logo", "horizontal_artwork")
            .mapNotNull { kind -> game.optJSONObject(kind)?.optString("url_format")?.takeIf { it.isNotEmpty() }?.let { kind to it } }
            .toMap()
    }.getOrDefault(emptyMap())

    // endregion

    // region Content system

    data class Build(val productId: String, val generation: Int, val branch: String?, val link: String)

    fun builds(token: String, id: Long, generation: Int): List<Build> {
        val items = getJson("$CONTENT_SYSTEM/products/$id/os/windows/builds?generation=$generation", token)
            .optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).map { items.getJSONObject(it) }.map {
            Build(
                productId = it.optString("product_id"),
                generation = it.optInt("generation", generation),
                branch = it.optString("branch").takeIf { b -> b.isNotEmpty() && b != "null" },
                link = it.optString("link"),
            )
        }
    }

    /** A build or depot manifest (zlib or gzip compressed JSON). */
    fun manifest(url: String, token: String): JSONObject = getJson(url, token)

    fun depotManifestUrl(hash: String) = "$CDN/content-system/v2/meta/${galaxyPath(hash)}"

    /** CDN base URLs for a product's chunks, valid for a while (re-request on HTTP 403). */
    fun secureLinks(token: String, productId: String): List<String> =
        links(getJson("$CONTENT_SYSTEM/products/$productId/secure_link?_version=2&generation=2&path=/", token))

    /** CDN base URLs for dependency chunks (public: no account needed). */
    fun dependencyLinks(): List<String> =
        links(getJson("$CONTENT_SYSTEM/open_link?generation=2&_version=2&path=/dependencies/store/", token = null))

    private fun links(json: JSONObject): List<String> {
        val urls = json.optJSONArray("urls") ?: JSONArray()
        return (0 until urls.length()).mapNotNull { i ->
            val entry = urls.optJSONObject(i) ?: return@mapNotNull null
            var url = entry.optString("url_format")
            val params = entry.optJSONObject("parameters")
            params?.keys()?.forEach { key -> url = url.replace("{$key}", params.get(key).toString()) }
            url.replace("\\/", "/").takeIf { it.isNotEmpty() }
        }
    }

    /**
     * An entry of GOG's dependency repository. [internal] ones (DOSBox, ScummVM…) are part of
     * the game and go into its folder; the others are redistributables (Visual C++, DirectX…)
     * shared by every game under __redist and installed with [executable] and [arguments].
     */
    data class Dependency(
        val id: String,
        val internal: Boolean,
        val manifest: String,
        val executable: String,
        val arguments: String,
    )

    fun dependencies(): List<Dependency> {
        val repository = getJson("$CONTENT_SYSTEM/dependencies/repository?generation=2", token = null)
        val depots = getJson(repository.getString("repository_manifest"), token = null).optJSONArray("depots") ?: JSONArray()
        return (0 until depots.length()).map { depots.getJSONObject(it) }.map {
            val executable = it.optJSONObject("executable")
            Dependency(
                id = it.optString("dependencyId"),
                internal = it.optBoolean("internal"),
                manifest = it.optString("manifest"),
                executable = executable?.optString("path").orEmpty(),
                arguments = executable?.optString("arguments").orEmpty(),
            )
        }.filter { it.id.isNotEmpty() && it.manifest.isNotEmpty() }
    }

    fun dependencyManifestUrl(hash: String) = "$CDN/content-system/v2/dependencies/meta/${galaxyPath(hash)}"

    /** Chunk URL: the hash's "aa/bb/aabb…" path inserted before the secure link's query. */
    fun chunkUrl(base: String, compressedMd5: String): String {
        val query = base.indexOf('?')
        val path = if (query >= 0) base.substring(0, query) else base
        val suffix = if (query >= 0) base.substring(query) else ""
        return path.trimEnd('/') + "/" + galaxyPath(compressedMd5) + suffix
    }

    private fun galaxyPath(hash: String) =
        if (hash.length < 4) hash else "${hash.substring(0, 2)}/${hash.substring(2, 4)}/$hash"

    // endregion

    /** GET → JSON, transparently inflating zlib/gzip bodies (GOG manifests are compressed). */
    fun getJson(url: String, token: String?): JSONObject {
        val request = Request.Builder().url(url).apply { if (token != null) header("Authorization", "Bearer $token") }.build()
        http.newCall(request).execute().use { response ->
            val body = response.body.bytes()
            if (!response.isSuccessful) throw GogHttpException(response.code, url)
            return JSONObject(inflateIfNeeded(body))
        }
    }

    private fun inflateIfNeeded(data: ByteArray): String {
        val gzip = data.size >= 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()
        val zlib = data.size >= 2 && data[0] == 0x78.toByte()
        return when {
            gzip -> GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }.toString(Charsets.UTF_8)
            zlib -> InflaterInputStream(ByteArrayInputStream(data)).use { it.readBytes() }.toString(Charsets.UTF_8)
            else -> data.toString(Charsets.UTF_8)
        }
    }
}

internal class GogHttpException(val code: Int, url: String) : IOException("GOG HTTP $code: ${url.substringBefore('?')}")
