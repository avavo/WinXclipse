package com.winlator.cmod.gog

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.winlator.cmod.R
import com.winlator.cmod.store.SecureStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A game in the user's GOG library. */
data class GogGame(val id: Long, val title: String)

/**
 * The GOG account: sign-in with the code from GOG's own login page (shown in a WebView,
 * so WinXclipse never sees the password), the refresh token (encrypted), an access token
 * renewed when it expires, and the owned-games list.
 */
object GogSession {
    private const val TAG = "GogSession"

    private lateinit var appContext: Context
    private lateinit var store: SecureStore
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val tokenLock = Mutex()
    private val gson = Gson()

    private val _account = MutableStateFlow<String?>(null)
    /** Name of the signed-in account, or null when signed out. */
    val account: StateFlow<String?> = _account.asStateFlow()

    private val _library = MutableStateFlow<List<GogGame>>(emptyList())
    val library: StateFlow<List<GogGame>> = _library.asStateFlow()

    private val _libraryLoading = MutableStateFlow(false)
    val libraryLoading: StateFlow<Boolean> = _libraryLoading.asStateFlow()

    private val _libraryError = MutableStateFlow<String?>(null)
    val libraryError: StateFlow<String?> = _libraryError.asStateFlow()

    @Volatile private var accessToken: String? = null
    @Volatile private var accessExpiresAt = 0L
    @Volatile private var ownedIds: Set<Long> = emptySet()

    private val libraryFile get() = File(appContext.filesDir, "gog/library.json")

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        store = SecureStore(appContext, "gog_account", "winxclipse_gog")
        if (store.get(KEY_REFRESH) != null) {
            _account.value = store.get(KEY_NAME) ?: "GOG"
            _library.value = runCatching {
                gson.fromJson<List<GogGame>>(libraryFile.readText(), object : TypeToken<List<GogGame>>() {}.type)
            }.getOrNull().orEmpty()
        }
    }

    /** Finishes sign-in with the code GOG's login page redirected to. */
    suspend fun signIn(code: String): String {
        val (tokens, name) = withContext(Dispatchers.IO) {
            val tokens = GogApi.exchangeCode(code)
            tokens to (GogApi.userName(tokens.accessToken) ?: "GOG")
        }
        tokenLock.withLock {
            accessToken = tokens.accessToken
            accessExpiresAt = tokens.expiresAt
            store.put(mapOf(KEY_REFRESH to tokens.refreshToken, KEY_NAME to name, KEY_USER to tokens.userId))
        }
        _account.value = name
        return name
    }

    fun signOut() {
        scope.launch {
            tokenLock.withLock {
                accessToken = null
                accessExpiresAt = 0
                store.clear()
            }
            libraryFile.delete()
            ownedIds = emptySet()
            _account.value = null
            _library.value = emptyList()
            _libraryError.value = null
        }
    }

    /** A valid access token, refreshed with the stored refresh token when needed. */
    internal suspend fun accessToken(): String = tokenLock.withLock {
        val current = accessToken
        if (current != null && System.currentTimeMillis() < accessExpiresAt - 60_000) return@withLock current
        val refresh = store.get(KEY_REFRESH) ?: throw IOException(appContext.getString(R.string.gog_not_signed_in))
        val tokens = try {
            GogApi.refresh(refresh)
        } catch (e: GogHttpException) {
            // Refresh token revoked or expired: sign out so the user can sign in again.
            if (e.code == 400 || e.code == 401) {
                store.clear()
                _account.value = null
            }
            throw e
        }
        accessToken = tokens.accessToken
        accessExpiresAt = tokens.expiresAt
        if (tokens.refreshToken.isNotEmpty()) store.put(mapOf(KEY_REFRESH to tokens.refreshToken))
        tokens.accessToken
    }

    /** Ids of every owned product, DLC included (for picking a game's DLC depots). */
    internal suspend fun ownedIds(): Set<Long> {
        if (ownedIds.isEmpty()) ownedIds = GogApi.ownedIds(accessToken())
        return ownedIds
    }

    fun refreshLibrary() {
        if (!::appContext.isInitialized || _libraryLoading.value || _account.value == null) return
        _libraryLoading.value = true
        _libraryError.value = null
        scope.launch {
            try {
                val token = accessToken()
                ownedIds = GogApi.ownedIds(token)
                val games = GogApi.ownedGames(token)
                    .map { GogGame(it.id, it.title) }
                    .sortedBy { it.title.lowercase() }
                Log.i(TAG, "Library: ${ownedIds.size} owned products, ${games.size} Windows games")
                _library.value = games
                libraryFile.parentFile?.mkdirs()
                libraryFile.writeText(gson.toJson(games))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Library refresh failed", e)
                _libraryError.value = if (e is IOException && e !is GogHttpException) appContext.getString(R.string.gog_err_network)
                else appContext.getString(R.string.gog_err_generic, e.message ?: e.javaClass.simpleName)
            } finally {
                _libraryLoading.value = false
            }
        }
    }

    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_NAME = "account_name"
    private const val KEY_USER = "user_id"
}
