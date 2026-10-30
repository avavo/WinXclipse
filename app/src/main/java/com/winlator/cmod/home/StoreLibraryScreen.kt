package com.winlator.cmod.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.R
import com.winlator.cmod.steam.SteamDownloads
import com.winlator.cmod.store.ArtKind
import com.winlator.cmod.store.ArtSource
import com.winlator.cmod.store.LibraryEntry
import com.winlator.cmod.store.LocalArtSource
import androidx.compose.runtime.CompositionLocalProvider
import com.winlator.cmod.steam.SteamInstall
import java.io.File
import kotlin.math.abs

private val SteamTileShape = RoundedCornerShape(13.dp)

/**
 * The games in a signed-in store account: grid on the right, the focused game's hero art
 * behind everything and its details on the left, like the home screen.
 */
@Composable
fun StoreLibraryScreen(
    title: String,
    locationHint: String,
    emptyText: String,
    art: ArtSource,
    account: String?,
    games: List<LibraryEntry>,
    installs: Map<String, SteamInstall>,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onInstall: (LibraryEntry) -> Unit,
    onPause: (LibraryEntry) -> Unit,
    onPlay: (LibraryEntry, SteamInstall.Installed) -> Unit,
    containerNames: Map<String, String>,
    onChooseContainer: (LibraryEntry, SteamInstall.Installed) -> Unit,
    picker: SteamContainerPicker?,
    onPickContainer: (Int) -> Unit,
    onDismissPicker: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var query by rememberSaveable { mutableStateOf("") }
    var focusedId by rememberSaveable { mutableStateOf<String?>(null) }
    val filtered = remember(query, games) {
        games.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
    }
    val focused = games.firstOrNull { it.key == focusedId } ?: filtered.firstOrNull()

    CompositionLocalProvider(LocalArtSource provides art) {
    Box(Modifier.fillMaxSize().background(Wx.Ground)) {
        SteamBackdrop(focused)
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(horizontal = 26.dp, vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WxIconButton(WxIcons.Back, stringResource(R.string.game_back_home), onBack)
                Column(Modifier.padding(start = 14.dp)) {
                    Text(
                        title,
                        style = WxType.Title.copy(fontSize = 24.sp, fontWeight = FontWeight.ExtraBold),
                    )
                    Text(
                        listOfNotNull(account, if (games.isEmpty()) null else gamesCount(games.size)).joinToString("  ·  "),
                        style = WxType.Caption,
                    )
                }
                Spacer(Modifier.weight(1f))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.home_search), style = WxType.Body.copy(color = Wx.TextMuted)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Wx.Lilac) },
                    textStyle = WxType.Body,
                    shape = PillShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Wx.Violet,
                        unfocusedBorderColor = Wx.TintBorder,
                        focusedContainerColor = Wx.Tint,
                        unfocusedContainerColor = Wx.Surface.copy(alpha = 0.8f),
                        cursorColor = Wx.Lilac,
                    ),
                    modifier = Modifier.width(260.dp),
                )
                if (loading) {
                    Box(Modifier.padding(start = 10.dp).size(38.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Wx.Lilac, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    }
                } else {
                    WxIconButton(Icons.Filled.Refresh, stringResource(R.string.steam_lib_refresh), onRefresh, Modifier.padding(start = 10.dp))
                }
            }
            Row(Modifier.fillMaxSize().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                Column(
                    Modifier.width(280.dp).fillMaxHeight().padding(bottom = 8.dp),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    if (focused != null) {
                        SteamHeadline(
                            game = focused,
                            locationHint = locationHint,
                            install = installs[focused.key],
                            containerName = containerNames[focused.key],
                            onInstall = { onInstall(focused) },
                            onPause = { onPause(focused) },
                            onPlay = { onPlay(focused, it) },
                            onChooseContainer = { onChooseContainer(focused, it) },
                        )
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when {
                        games.isEmpty() -> LibraryMessage(
                            text = error ?: if (loading) stringResource(R.string.steam_lib_loading) else emptyText,
                            isError = error != null,
                            loading = loading && error == null,
                            onRetry = onRefresh,
                        )
                        else -> LazyVerticalGrid(
                            columns = GridCells.Adaptive(92.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(top = 6.dp, bottom = 18.dp, start = 4.dp, end = 4.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(filtered, key = { it.key }) { game ->
                                SteamCard(game, installs[game.key], selected = game.key == focused?.key) { focusedId = game.key }
                            }
                        }
                    }
                }
            }
        }
        if (picker != null) ContainerPicker(picker, onPickContainer, onDismissPicker)
    }
    }
}

/** Container chooser shown before a store game's first launch, or when changing it. */
class SteamContainerPicker(val gameName: String, val containers: List<Pair<Int, String>>)

@Composable
private fun ContainerPicker(picker: SteamContainerPicker, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Wx.Ground.copy(alpha = 0.85f))
            .wxClickable(onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Wx.Surface)
                .border(1.dp, Wx.TintBorder, RoundedCornerShape(20.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.steam_play_pick_container, picker.gameName),
                style = WxType.Heading.copy(fontSize = 17.sp),
            )
            Column(
                Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                picker.containers.forEachIndexed { index, (id, name) ->
                    WxTonalButton(
                        name,
                        { onPick(id) },
                        Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(first) else Modifier),
                        height = 44.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun SteamHeadline(
    game: LibraryEntry,
    locationHint: String,
    install: SteamInstall?,
    containerName: String?,
    onInstall: () -> Unit,
    onPause: () -> Unit,
    onPlay: (SteamInstall.Installed) -> Unit,
    onChooseContainer: (SteamInstall.Installed) -> Unit,
) {
    val logo = rememberStoreArt(game.key, ArtKind.Logo, 640)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Crossfade(targetState = game.key to logo, animationSpec = tween(250), label = "logo") { (_, image) ->
            if (image != null) {
                Image(
                    image,
                    contentDescription = game.name,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.BottomStart,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 110.dp),
                )
            } else {
                Text(
                    game.name,
                    style = WxType.Display.copy(fontSize = 28.sp, lineHeight = 31.sp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        game.playtimeMinutes?.let { minutes ->
            Text(
                if (minutes > 0) stringResource(R.string.steam_lib_playtime, minutesLabel(minutes))
                else stringResource(R.string.steam_lib_never_played),
                style = WxType.Label.copy(color = Wx.TextSecondary),
            )
        }
        lastPlayedLabel(game.lastPlayed * 1000L)?.let {
            Text(stringResource(R.string.home_last_played, it), style = WxType.Caption)
        }
        Spacer(Modifier.height(4.dp))
        when (install) {
            SteamInstall.Preparing -> {
                DownloadBar(null)
                Text(stringResource(R.string.steam_dl_preparing), style = WxType.Caption)
                WxTonalButton(stringResource(R.string.steam_dl_pause), onPause, height = 40.dp)
            }
            is SteamInstall.Downloading -> {
                DownloadBar(install.progress)
                Text(
                    stringResource(R.string.steam_dl_percent, (install.progress * 100).toInt()) + "  ·  " +
                        stringResource(
                            R.string.steam_dl_progress,
                            SteamDownloads.formatBytes(install.downloadedBytes),
                            SteamDownloads.formatBytes(install.totalBytes),
                            SteamDownloads.formatBytes(install.bytesPerSecond),
                        ),
                    style = WxType.Caption.copy(color = Wx.Text),
                )
                WxTonalButton(stringResource(R.string.steam_dl_pause), onPause, height = 40.dp)
            }
            is SteamInstall.Paused -> {
                DownloadBar(install.progress)
                Text(stringResource(R.string.steam_dl_paused, (install.progress * 100).toInt()), style = WxType.Caption)
                WxPrimaryButton(stringResource(R.string.steam_dl_resume), onInstall, icon = WxIcons.Download, height = 44.dp)
            }
            is SteamInstall.Installed -> {
                WxChip(stringResource(R.string.steam_dl_installed, SteamDownloads.formatBytes(install.sizeBytes)))
                WxPrimaryButton(
                    stringResource(R.string.steam_dl_play),
                    { onPlay(install) },
                    icon = Icons.Filled.PlayArrow,
                    height = 44.dp,
                )
                // Own line: next to Play it does not fit the 280 dp column.
                if (containerName != null) {
                    WxTonalButton(
                        stringResource(R.string.steam_play_container, containerName),
                        { onChooseContainer(install) },
                        height = 36.dp,
                        horizontalPadding = 14.dp,
                    )
                }
            }
            is SteamInstall.Failed, null -> {
                if (install is SteamInstall.Failed) {
                    Text(install.message, style = WxType.Caption.copy(color = Wx.Danger), maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                WxPrimaryButton(
                    if (game.sizeBytes > 0) stringResource(R.string.steam_lib_install_size, SteamDownloads.formatBytes(game.sizeBytes))
                    else stringResource(R.string.steam_lib_install),
                    onInstall,
                    icon = WxIcons.Download,
                    height = 44.dp,
                )
                Text(locationHint, style = WxType.Caption.copy(fontSize = 11.sp))
            }
        }
    }
}

/** Blue-to-purple download bar; [progress] null shows an indeterminate bar. */
@Composable
private fun DownloadBar(progress: Float?, modifier: Modifier = Modifier.fillMaxWidth()) {
    val colors = Modifier.height(6.dp).clip(PillShape)
    if (progress == null) {
        LinearProgressIndicator(modifier = modifier.then(colors), color = Wx.Violet, trackColor = Wx.Surface)
        return
    }
    Box(modifier.then(colors).background(Wx.Surface)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(Wx.Gradient))
    }
}

@Composable
private fun SteamCard(game: LibraryEntry, install: SteamInstall?, selected: Boolean, onSelect: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .wxRing(focused || selected, SteamTileShape, width = 3.dp)
                .clip(SteamTileShape)
                .wxClickable(onSelect) {
                    focused = it
                    if (it) onSelect()
                }
        ) {
            SteamCover(game, Modifier.fillMaxSize())
            val progress = when (install) {
                is SteamInstall.Downloading -> install.progress
                is SteamInstall.Paused -> install.progress
                SteamInstall.Preparing -> 0f
                else -> null
            }
            if (progress != null) {
                DownloadBar(progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(6.dp))
            }
            if (install is SteamInstall.Installed) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .clip(PillShape)
                        .background(Wx.Gradient),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
        }
        Text(
            game.name,
            style = WxType.Caption.copy(color = if (focused || selected) Wx.Text else Wx.TextSecondary, fontWeight = FontWeight.Medium),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Portrait capsule; older games without one get their store header, then a generated card. */
@Composable
private fun SteamCover(game: LibraryEntry, modifier: Modifier) {
    val (portraitFile, checked) = rememberStoreArtFile(game.key, ArtKind.Portrait)
    val portrait = rememberFileImage(portraitFile, 0L, 300)
    val header = if (checked && portraitFile == null) rememberStoreArt(game.key, ArtKind.Header, 460) else null
    val image = portrait ?: header
    if (image != null) {
        Image(image, contentDescription = game.name, contentScale = ContentScale.Crop, modifier = modifier)
        return
    }
    val hue = 220f + (abs(game.name.hashCode()) % 70)
    Box(
        modifier.background(Brush.verticalGradient(listOf(Color.hsv(hue, 0.55f, 0.55f), Color.hsv(hue, 0.6f, 0.16f)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            game.name,
            style = WxType.Heading.copy(fontSize = 11.sp, textAlign = TextAlign.Center),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(8.dp),
        )
    }
}

@Composable
private fun SteamBackdrop(game: LibraryEntry?) {
    val hero = game?.let { rememberStoreArt(it.key, ArtKind.Hero, 1600) }
    Crossfade(targetState = game?.key to hero, animationSpec = tween(450), label = "steamBackdrop") { (_, image) ->
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF1A1440), Wx.Ground))))
        }
    }
    Box(
        Modifier.fillMaxSize().background(
            Brush.horizontalGradient(
                0f to Wx.Ground.copy(alpha = 0.92f),
                0.4f to Wx.Ground.copy(alpha = 0.78f),
                1f to Wx.Ground.copy(alpha = 0.55f),
            )
        )
    )
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Wx.Ground)))
}

@Composable
private fun LibraryMessage(text: String, isError: Boolean, loading: Boolean, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (loading) CircularProgressIndicator(color = Wx.Violet)
        Text(
            text,
            style = WxType.Body.copy(color = if (isError) Wx.Danger else Wx.TextSecondary, textAlign = TextAlign.Center),
        )
        if (isError) WxTonalButton(stringResource(R.string.steam_try_again), onRetry, height = 40.dp)
    }
}

/** "3 h 12 min", "52 min". */
private fun minutesLabel(minutes: Int): String =
    if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"

/** The cached artwork file (null if Steam has none) and whether the lookup has finished. */
@Composable
private fun rememberStoreArtFile(key: String, kind: ArtKind): Pair<File?, Boolean> {
    val context = LocalContext.current
    val art = LocalArtSource.current
    val result by produceState<Pair<File?, Boolean>>(null to false, key, kind, art) {
        value = art.file(context, key, kind) to true
    }
    return result
}

/** Store artwork for [key], downloaded once and decoded off the main thread. */
@Composable
fun rememberStoreArt(key: String, kind: ArtKind, maxWidth: Int): ImageBitmap? =
    rememberFileImage(rememberStoreArtFile(key, kind).first, 0L, maxWidth)
