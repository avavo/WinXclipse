package com.winlator.cmod.steam

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.winlator.cmod.R
import `in`.dragonbra.javasteam.enums.EOSType
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.networking.steam3.ProtocolTypes
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPlayerSteamclient.CPlayer_GetOwnedGames_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPlayerSteamclient.CPlayer_GetOwnedGames_Response
import `in`.dragonbra.javasteam.steam.handlers.steamapps.License
import `in`.dragonbra.javasteam.steam.handlers.steamapps.callback.LicenseListCallback
import `in`.dragonbra.javasteam.rpc.service.Player
import `in`.dragonbra.javasteam.steam.authentication.AuthPollResult
import `in`.dragonbra.javasteam.steam.authentication.AuthSession
import `in`.dragonbra.javasteam.steam.authentication.AuthSessionDetails
import `in`.dragonbra.javasteam.steam.authentication.AuthenticationException
import `in`.dragonbra.javasteam.steam.authentication.IAuthenticator
import `in`.dragonbra.javasteam.steam.authentication.IChallengeUrlChanged
import `in`.dragonbra.javasteam.steam.discovery.FileServerListProvider
import `in`.dragonbra.javasteam.steam.discovery.ServerQuality
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import `in`.dragonbra.javasteam.steam.handlers.steamuser.ChatMode
import `in`.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails
import `in`.dragonbra.javasteam.steam.handlers.steamuser.SteamUser
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOffCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback
import `in`.dragonbra.javasteam.steam.steamclient.AsyncJobFailedException
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration
import java.io.File
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

/**
 * A game in the user's Steam library. [installDir] is Steam's folder name for it and
 * [sizeBytes] the size of its Windows files (0 if Steam does not say); both may be missing
 * from libraries cached by older builds.
 */
data class SteamGame(
    val appId: Int,
    val name: String,
    val playtimeMinutes: Int,
    val lastPlayed: Long,
    val installDir: String? = null,
    val sizeBytes: Long = 0L,
    val depotSizes: Map<Int, Long>? = null,
    val launch: List<SteamLaunch>? = null,
    val dlcs: Map<Int, String>? = null,
    val controller: SteamControllerInfo? = null,
)

/**
 * What Steam says about a game's controller support, used to pick its first controls:
 * [support] is PICS controller_support ("full", "partial" or empty), [storeCategory] whether
 * the store lists full/partial controller support, [templateIndex] Valve's Steam Input
 * template (-1 none) and [tags] the store tag ids, most voted first.
 */
data class SteamControllerInfo(
    val support: String,
    val storeCategory: Boolean,
    val templateIndex: Int,
    val tags: List<Int>,
)

/** One entry of a game's Steam launch options (config/launch in PICS). */
data class SteamLaunch(
    val executable: String,
    val arguments: String,
    val workingDir: String,
    val type: String,
    val description: String,
    val osList: String,
    val osArch: String,
    val betaKey: String,
)

/**
 * The app's one connection to Steam: sign-in (QR or password + Steam Guard), the saved
 * session, and the owned-games list. Connection and sign-in flow adapted from GameNative's
 * SteamService (GPL-3.0, https://github.com/utkarshdalal/GameNative) on JavaSteam (MIT).
 */
object SteamSession : SteamLogin {
    private const val TAG = "SteamSession"
    private val PROTOCOLS = EnumSet.of(ProtocolTypes.WEB_SOCKET)

    private lateinit var appContext: Context
    private lateinit var store: SteamAccountStore
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val connectLock = Mutex()
    private val gson = Gson()

    private val _state = MutableStateFlow<SteamLoginState>(SteamLoginState.Idle)
    override val state: StateFlow<SteamLoginState> = _state.asStateFlow()

    private val _account = MutableStateFlow<String?>(null)
    /** Name of the saved account, or null when signed out. */
    val account: StateFlow<String?> = _account.asStateFlow()

    private val _library = MutableStateFlow<List<SteamGame>>(emptyList())
    val library: StateFlow<List<SteamGame>> = _library.asStateFlow()

    private val _libraryLoading = MutableStateFlow(false)
    val libraryLoading: StateFlow<Boolean> = _libraryLoading.asStateFlow()

    private val _libraryError = MutableStateFlow<String?>(null)
    val libraryError: StateFlow<String?> = _libraryError.asStateFlow()

    private var client: SteamClient? = null
    private var callbackLoop: Job? = null
    @Volatile private var connected = false
    @Volatile private var loggedOn = false
    @Volatile private var connectSignal: CompletableDeferred<Boolean>? = null
    @Volatile private var logonSignal: CompletableDeferred<LoggedOnCallback>? = null
    @Volatile private var codeReply: CompletableDeferred<String>? = null
    /** Licenses Steam sends after each logon; the depot downloader needs them. */
    @Volatile private var licenses = CompletableDeferred<List<License>>()
    private var loginJob: Job? = null

    private val libraryFile get() = File(appContext.filesDir, "steam/library.json")

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        store = SteamAccountStore(appContext)
        _account.value = runCatching { store.load()?.accountName }.getOrNull()
        if (_account.value != null) {
            _library.value = runCatching {
                gson.fromJson<List<SteamGame>>(libraryFile.readText(), object : TypeToken<List<SteamGame>>() {}.type)
            }.getOrNull().orEmpty()
        }
    }

    // region Sign-in

    override fun startQr() {
        restartLogin {
            val details = authDetails().apply { persistentSession = true }
            val session = client!!.authentication.beginAuthSessionViaQR(details).await()
            _state.value = SteamLoginState.Qr(session.challengeUrl)
            session.challengeUrlChanged = IChallengeUrlChanged { changed ->
                changed?.let { _state.value = SteamLoginState.Qr(it.challengeUrl) }
            }
            val result = pollUntilApproved(session)
            logOn(result.accountName, result.refreshToken)
        }
    }

    /**
     * Polls an auth session until it is approved. Unlike JavaSteam's own loop it survives
     * dropped connections, which happen when the user switches to the Steam app to approve.
     */
    private suspend fun pollUntilApproved(session: AuthSession): AuthPollResult {
        while (true) {
            delay((session.pollingInterval * 1000).toLong().coerceAtLeast(1000L))
            try {
                ensureConnected()
                session.pollAuthSessionStatus().await()?.let { return it }
            } catch (e: AsyncJobFailedException) {
                Log.i(TAG, "Poll interrupted by a disconnect, retrying")
            }
        }
    }

    override fun signInWithPassword(accountName: String, password: String) {
        restartLogin {
            _state.value = SteamLoginState.Working
            val details = authDetails().apply {
                username = accountName
                this.password = password
                persistentSession = true
                authenticator = guardBridge
            }
            val session = client!!.authentication.beginAuthSessionViaCredentials(details).await()
            val result = try {
                session.pollingWaitForResult().await()
            } catch (e: AsyncJobFailedException) {
                // Lost the connection while waiting for the app confirmation: keep polling.
                if (_state.value != SteamLoginState.AwaitingAppConfirmation) throw e
                pollUntilApproved(session)
            }
            logOn(result.accountName, result.refreshToken)
        }
    }

    override fun submitCode(code: String) {
        if (code.isBlank()) return
        _state.value = SteamLoginState.Working
        codeReply?.complete(code.trim())
    }

    override fun cancel() {
        loginJob?.cancel()
        loginJob = null
        codeReply?.cancel()
        if (!loggedOn) scope.launch { disconnect() }
        _state.value = SteamLoginState.Idle
    }

    fun signOut() {
        loginJob?.cancel()
        scope.launch {
            runCatching { client?.getHandler(SteamUser::class.java)?.logOff() }
            disconnect()
            store.clear()
            libraryFile.delete()
            _account.value = null
            _library.value = emptyList()
            _libraryError.value = null
            _state.value = SteamLoginState.Idle
        }
    }

    /** Steam Guard prompts from JavaSteam, answered from SteamLoginScreen. */
    private val guardBridge = object : IAuthenticator {
        override fun acceptDeviceConfirmation(): CompletableFuture<Boolean> {
            _state.value = SteamLoginState.AwaitingAppConfirmation
            return CompletableFuture.completedFuture(true)
        }

        override fun getDeviceCode(previousCodeWasIncorrect: Boolean): CompletableFuture<String> =
            askForCode(SteamLoginState.NeedCode(null, previousCodeWasIncorrect))

        override fun getEmailCode(email: String?, previousCodeWasIncorrect: Boolean): CompletableFuture<String> =
            askForCode(SteamLoginState.NeedCode(email ?: "", previousCodeWasIncorrect))
    }

    private fun askForCode(prompt: SteamLoginState.NeedCode): CompletableFuture<String> {
        val reply = CompletableDeferred<String>().also { codeReply = it }
        _state.value = prompt
        return CompletableFuture<String>().also { future ->
            scope.launch {
                // While the user fetched the code the connection may have dropped; JavaSteam
                // sends it right after this future completes, so reconnect first.
                runCatching { reply.await().also { ensureConnected() } }
                    .onSuccess { future.complete(it) }
                    .onFailure { future.completeExceptionally(it) }
            }
        }
    }

    private fun restartLogin(block: suspend () -> Unit) {
        loginJob?.cancel()
        codeReply?.cancel()
        _state.value = SteamLoginState.Connecting
        loginJob = scope.launch {
            try {
                ensureConnected()
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Sign-in failed", e)
                _state.value = SteamLoginState.Failed(describe(e))
            }
        }
    }

    private fun authDetails() = AuthSessionDetails().apply {
        deviceFriendlyName = deviceName()
        clientOSType = EOSType.WinUnknown
    }

    // endregion

    // region Library

    /**
     * Signs in with the saved session if needed and reloads the library: every game the
     * account has a license for (from PICS), with playtime from GetOwnedGames.
     */
    fun refreshLibrary() {
        if (!::appContext.isInitialized || _libraryLoading.value) return
        if (store.load() == null) return
        _libraryLoading.value = true
        _libraryError.value = null
        scope.launch {
            try {
                ensureLoggedOn()
                val steam = client!!
                val catalog = SteamCatalog.ownedGames(steam, awaitLicenses())
                val playtime = runCatching { playtimes(steam) }
                    .onFailure { Log.w(TAG, "Playtime unavailable", it) }
                    .getOrDefault(emptyMap())
                val games = catalog
                    .map { app ->
                        val played = playtime[app.appId]
                        SteamGame(
                            appId = app.appId,
                            name = app.name,
                            playtimeMinutes = played?.playtimeForever ?: 0,
                            lastPlayed = played?.rtimeLastPlayed?.toLong() ?: 0L,
                            installDir = app.installDir,
                            sizeBytes = app.sizeBytes,
                            depotSizes = app.depotSizes,
                            launch = app.launch,
                            dlcs = app.dlcs,
                            controller = app.controller,
                        )
                    }
                    .sortedWith(compareByDescending<SteamGame> { it.lastPlayed }.thenBy { it.name.lowercase() })
                _library.value = games
                libraryFile.parentFile?.mkdirs()
                libraryFile.writeText(gson.toJson(games))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Library refresh failed", e)
                _libraryError.value = describe(e)
            } finally {
                _libraryLoading.value = false
            }
        }
    }

    /** Playtime per app id; GetOwnedGames only lists played games, so it cannot be the library. */
    private suspend fun playtimes(steam: SteamClient): Map<Int, CPlayer_GetOwnedGames_Response.Game> {
        val steamId = steam.getHandler(SteamUser::class.java)?.steamID?.convertToUInt64()
            ?.takeIf { it != 0L } ?: store.load()?.steamId64 ?: return emptyMap()
        val request = CPlayer_GetOwnedGames_Request.newBuilder().apply {
            steamid = steamId
            includePlayedFreeGames = true
            includeFreeSub = true
        }.build()
        val player = steam.getHandler(SteamUnifiedMessages::class.java)!!.createService(Player::class.java)
        val response = player.getOwnedGames(request).await()
        if (response.result != EResult.OK) throw SteamResultException(response.result)
        return response.body.gamesList.associateBy { it.appid }
    }

    /** Connected and logged on with the saved session, for library and download work. */
    internal suspend fun ensureLoggedOn(): SteamClient {
        if (!loggedOn) {
            val saved = store.load() ?: throw IOException("Not signed in to Steam")
            ensureConnected()
            if (!loggedOn) logOn(saved.accountName, saved.refreshToken)
        }
        return client!!
    }

    internal suspend fun awaitLicenses(): List<License> = withTimeout(30_000L) { licenses.await() }

    /** The saved account (name and SteamID) for the Steam emulator's user config. */
    internal fun savedAccount(): SteamAccount? = if (::store.isInitialized) store.load() else null

    // endregion

    // region Connection

    private suspend fun ensureConnected() = connectLock.withLock {
        if (connected) return@withLock
        val steam = client ?: createClient()
        repeat(3) { attempt ->
            val signal = CompletableDeferred<Boolean>().also { connectSignal = it }
            steam.connect()
            if (withTimeoutOrNull(15_000L) { signal.await() } == true) return@withLock
            Log.w(TAG, "Connect attempt ${attempt + 1} failed")
            runCatching { steam.servers.tryMark(steam.currentEndpoint, PROTOCOLS, ServerQuality.BAD) }
            runCatching { steam.disconnect() }
            delay(1000L)
        }
        throw IOException("Can't reach Steam")
    }

    private fun createClient(): SteamClient {
        val configuration = SteamConfiguration.create {
            it.withProtocolTypes(PROTOCOLS)
            it.withCellID(store.cellId)
            it.withServerListProvider(FileServerListProvider(File(appContext.filesDir, "steam/servers.bin").apply { parentFile?.mkdirs() }))
            it.withConnectionTimeout(60_000L)
            it.withHttpClient(
                OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .pingInterval(15, TimeUnit.SECONDS)
                    .build()
            )
        }
        val steam = SteamClient(configuration)
        val callbacks = CallbackManager(steam)
        callbacks.subscribe(ConnectedCallback::class.java) {
            connected = true
            connectSignal?.complete(true)
        }
        callbacks.subscribe(DisconnectedCallback::class.java) {
            Log.i(TAG, "Disconnected (user initiated: ${it.isUserInitiated})")
            connected = false
            loggedOn = false
            connectSignal?.complete(false)
            logonSignal?.completeExceptionally(IOException("Disconnected from Steam"))
        }
        callbacks.subscribe(LoggedOnCallback::class.java) { logonSignal?.complete(it) }
        callbacks.subscribe(LicenseListCallback::class.java) {
            if (it.result == EResult.OK) licenses.complete(it.licenseList)
        }
        callbacks.subscribe(LoggedOffCallback::class.java) {
            Log.i(TAG, "Logged off: ${it.result}")
            loggedOn = false
        }
        callbackLoop = scope.launch {
            while (isActive) {
                try {
                    callbacks.runWaitCallbacks(1000L)
                } catch (e: Exception) {
                    Log.w(TAG, "Callback loop", e)
                }
            }
        }
        client = steam
        return steam
    }

    private suspend fun disconnect() = connectLock.withLock {
        callbackLoop?.cancel()
        callbackLoop = null
        runCatching { client?.disconnect() }
        client = null
        connected = false
        loggedOn = false
    }

    /** Logs on with a refresh token and saves the account on success. */
    private suspend fun logOn(accountName: String, refreshToken: String) {
        _state.value = SteamLoginState.Working
        if (licenses.isCompleted) licenses = CompletableDeferred()
        val signal = CompletableDeferred<LoggedOnCallback>().also { logonSignal = it }
        client!!.getHandler(SteamUser::class.java)!!.logOn(
            LogOnDetails(
                username = accountName,
                accessToken = refreshToken,
                shouldRememberPassword = true,
                loginID = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)?.hashCode(),
                machineName = deviceName(),
                chatMode = ChatMode.NEW_STEAM_CHAT,
            )
        )
        val result = withTimeout(30_000L) { signal.await() }
        if (result.result != EResult.OK) {
            // A rejected saved session means the token was revoked: forget it.
            if (result.result in TOKEN_REJECTED) {
                store.clear()
                _account.value = null
            }
            throw SteamResultException(result.result)
        }
        loggedOn = true
        if (result.cellID != 0) store.cellId = result.cellID
        val steamId = result.clientSteamID?.convertToUInt64() ?: 0L
        store.save(SteamAccount(accountName, refreshToken, steamId))
        _account.value = accountName
        _state.value = SteamLoginState.SignedIn(accountName)
    }

    private val TOKEN_REJECTED = setOf(EResult.InvalidPassword, EResult.AccessDenied, EResult.Expired, EResult.Revoked)

    private fun deviceName(): String {
        val name = runCatching {
            Settings.Global.getString(appContext.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()
        return "WinXclipse (" + (name ?: android.os.Build.MODEL) + ")"
    }

    // endregion

    private class SteamResultException(val result: EResult) : Exception("Steam: $result")

    private fun describe(e: Exception): String {
        val result = (e as? SteamResultException)?.result
            ?: (e as? AuthenticationException)?.result
            ?: ((e.cause ?: e) as? AuthenticationException)?.result
        return when (result) {
            EResult.InvalidPassword -> appContext.getString(R.string.steam_err_password)
            EResult.RateLimitExceeded, EResult.AccountLoginDeniedThrottle -> appContext.getString(R.string.steam_err_throttle)
            EResult.Expired, EResult.FileNotFound -> appContext.getString(R.string.steam_err_expired)
            null -> if (e is IOException || e.cause is IOException || e is AsyncJobFailedException) appContext.getString(R.string.steam_err_network)
                else appContext.getString(R.string.steam_err_generic, e.message ?: e.javaClass.simpleName)
            else -> appContext.getString(R.string.steam_err_generic, result.name)
        }
    }
}
