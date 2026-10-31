package com.winlator.cmod.store

import androidx.compose.runtime.staticCompositionLocalOf

/** Compose belongs to the unfinished interface reserved for 1.0.0. */
val LocalArtSource = staticCompositionLocalOf<ArtSource> { ArtSource { _, _, _ -> null } }
