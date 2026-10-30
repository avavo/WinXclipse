package com.winlator.cmod.store

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import java.io.File

/** Kinds of store artwork the library and home screen use. */
enum class ArtKind { Portrait, Hero, Header, Logo }

/** A game as the store library screen shows it, whatever store it comes from. */
data class LibraryEntry(
    val key: String,
    val name: String,
    val sizeBytes: Long = 0L,
    /** Null when the store does not report playtime. */
    val playtimeMinutes: Int? = null,
    val lastPlayed: Long = 0L,
)

/** Where a store's artwork comes from: a cached file, or null when the store has none. */
fun interface ArtSource {
    suspend fun file(context: Context, key: String, kind: ArtKind): File?
}

/** The art source of the library being shown. */
val LocalArtSource = staticCompositionLocalOf<ArtSource> { ArtSource { _, _, _ -> null } }
