package com.winlator.cmod.steam

import kotlinx.coroutines.flow.StateFlow

/** Where a Steam sign-in is, as shown by SteamLoginScreen. */
sealed interface SteamLoginState {
    data object Idle : SteamLoginState
    data object Connecting : SteamLoginState

    /** QR sign-in: [challengeUrl] is encoded in the QR code and changes every few seconds. */
    data class Qr(val challengeUrl: String) : SteamLoginState

    /** Password accepted; the user must approve the sign-in in the Steam mobile app. */
    data object AwaitingAppConfirmation : SteamLoginState

    /**
     * Steam Guard code needed: from the mobile authenticator, or e-mailed to [email].
     * [wrongCode] is set when Steam rejected the previous one.
     */
    data class NeedCode(val email: String?, val wrongCode: Boolean = false) : SteamLoginState

    data object Working : SteamLoginState
    data class SignedIn(val accountName: String) : SteamLoginState
    data class Failed(val message: String) : SteamLoginState
}

/**
 * Steam sign-in as the UI sees it. The user types credentials on the phone; only the
 * refresh token Steam returns is stored (never the password).
 */
interface SteamLogin {
    val state: StateFlow<SteamLoginState>

    /** Starts (or restarts) QR sign-in; the state cycles through [SteamLoginState.Qr]. */
    fun startQr()

    fun signInWithPassword(accountName: String, password: String)

    /** Answers [SteamLoginState.NeedCode]. */
    fun submitCode(code: String)

    fun cancel()
}
