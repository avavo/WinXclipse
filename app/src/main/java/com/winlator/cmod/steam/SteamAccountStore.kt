package com.winlator.cmod.steam

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The signed-in Steam account: name, SteamID and the refresh token (encrypted). */
data class SteamAccount(val accountName: String, val refreshToken: String, val steamId64: Long)

/**
 * Keeps the Steam account in private preferences. The refresh token is encrypted with an
 * AES key that lives in the Android Keystore, so a copy of the preferences file is useless
 * on its own. The password is never stored.
 */
class SteamAccountStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("steam_account", Context.MODE_PRIVATE)

    fun load(): SteamAccount? {
        val name = prefs.getString(KEY_NAME, null) ?: return null
        val token = prefs.getString(KEY_TOKEN, null)?.let { runCatching { decrypt(it) }.getOrNull() } ?: return null
        return SteamAccount(name, token, prefs.getLong(KEY_STEAM_ID, 0L))
    }

    fun save(account: SteamAccount) {
        prefs.edit()
            .putString(KEY_NAME, account.accountName)
            .putString(KEY_TOKEN, encrypt(account.refreshToken))
            .putLong(KEY_STEAM_ID, account.steamId64)
            .apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_NAME).remove(KEY_TOKEN).remove(KEY_STEAM_ID).apply()
    }

    /** Steam's cell (region) id from the last logon; speeds up finding servers. */
    var cellId: Int
        get() = prefs.getInt(KEY_CELL_ID, 0)
        set(value) = prefs.edit().putInt(KEY_CELL_ID, value).apply()

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    /** Base64 of IV (12 bytes) followed by the GCM ciphertext. */
    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
        }
        return String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE))
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "winxclipse_steam"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val KEY_NAME = "account_name"
        const val KEY_TOKEN = "refresh_token"
        const val KEY_STEAM_ID = "steam_id"
        const val KEY_CELL_ID = "cell_id"
    }
}
