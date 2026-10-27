package com.winlator.cmod.home

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.R
import java.util.Locale
import kotlin.math.abs

private val TileShape = RoundedCornerShape(13.dp)

@Composable
fun HomeScreen(
    games: List<HomeGame>?,
    onPlay: (HomeGame) -> Unit,
    onOpenGame: (HomeGame) -> Unit,
    onOpenClassic: (Int) -> Unit,
    onOpenStores: () -> Unit,
    onOpenControllers: () -> Unit,
) {
    val list = games.orEmpty()
    // Selection follows the shortcut, not the position: reloads (new artwork, back from a
    // game) re-sort the row and the selected game keeps its highlight.
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = list.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
    val game = list.getOrNull(selected)
    var showLibrary by remember { mutableStateOf(false) }
    var focusedOnce by remember { mutableStateOf(false) }
    val firstTileFocus = remember { FocusRequester() }
    val select: (Int) -> Unit = { index -> selectedKey = list.getOrNull(index)?.key }

    // The first load starts on the most recently played title, ready for the gamepad.
    LaunchedEffect(list.isNotEmpty()) {
        if (list.isNotEmpty() && !focusedOnce) {
            focusedOnce = true
            withFrameNanos { }
            runCatching { firstTileFocus.requestFocus() }
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Wx.Ground)
            .homeButtons(
                onDetails = { if (!showLibrary) game?.let(onOpenGame) },
                onLibrary = { showLibrary = true },
                onMenu = { onOpenClassic(R.id.main_menu_settings) },
            )
    ) {
        val compact = maxHeight < 480.dp
        HomeBackdrop(game)

        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(horizontal = 22.dp)
        ) {
            HomeTopBar(
                gameCount = list.size,
                onLibrary = { showLibrary = true },
                onStores = onOpenStores,
                onContainers = { onOpenClassic(R.id.main_menu_containers) },
                onSettings = { onOpenClassic(R.id.main_menu_settings) },
                onControllers = onOpenControllers,
                onClassic = { onOpenClassic(R.id.main_menu_shortcuts) },
            )
            when {
                games == null -> Spacer(Modifier.weight(1f))
                list.isEmpty() -> EmptyLibrary(Modifier.weight(1f)) { onOpenClassic(R.id.main_menu_containers) }
                else -> {
                    if (game != null) {
                        GameHeadline(
                            game = game,
                            compact = compact,
                            onPlay = { onPlay(game) },
                            onDetails = { onOpenGame(game) },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    RecentRow(
                        games = list,
                        selected = selected,
                        compact = compact,
                        firstTileFocus = firstTileFocus,
                        onSelect = select,
                        onPlay = onPlay,
                        onOpenLibrary = { showLibrary = true },
                    )
                }
            }
        }

        if (showLibrary) {
            LibraryOverlay(
                games = list,
                onPick = { index ->
                    select(index)
                    showLibrary = false
                },
                onClose = { showLibrary = false },
            )
        }
    }
}

/**
 * The selected game's art fills the screen: the SteamGridDB hero with a slow zoom, or the
 * cover (blurred behind, sharp on the right) until the hero arrives.
 */
@Composable
private fun HomeBackdrop(game: HomeGame?) {
    val hero = rememberFileImage(game?.heroFile, game?.heroVersion ?: 0L, 1600)
    val zoom = rememberInfiniteTransition(label = "kenBurns").animateFloat(
        initialValue = 1f,
        targetValue = 1.07f,
        animationSpec = infiniteRepeatable(tween(24_000), RepeatMode.Reverse),
        label = "zoom",
    )
    Crossfade(targetState = Pair(game?.key, hero), animationSpec = tween(450), label = "backdrop") { (_, heroImage) ->
        Box(Modifier.fillMaxSize()) {
            val cover = game?.cover
            when {
                heroImage != null -> Image(
                    bitmap = heroImage,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = zoom.value
                            scaleY = zoom.value
                        },
                )
                cover != null -> {
                    Image(
                        bitmap = cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().blur(30.dp).alpha(0.7f),
                    )
                    Image(
                        bitmap = cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxHeight()
                            .aspectRatio(2f / 3f)
                            .align(Alignment.CenterEnd)
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .drawWithContent {
                                drawContent()
                                drawRect(
                                    Brush.horizontalGradient(0f to Color.Transparent, 0.55f to Color.Black),
                                    blendMode = BlendMode.DstIn,
                                )
                            },
                    )
                }
                else -> Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF1A1440), Wx.Ground))))
            }
        }
    }
    // Readability: darken towards the text on the left and the row at the bottom.
    Box(
        Modifier.fillMaxSize().background(
            Brush.horizontalGradient(
                0f to Wx.Ground,
                0.32f to Wx.Ground.copy(alpha = 0.88f),
                0.62f to Wx.Ground.copy(alpha = 0.3f),
                1f to Color.Transparent,
            )
        )
    )
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(0.42f to Color.Transparent, 0.78f to Wx.Ground.copy(alpha = 0.88f), 1f to Wx.Ground)
        )
    )
    Box(
        Modifier
            .offset(x = (-120).dp, y = 230.dp)
            .size(width = 520.dp, height = 320.dp)
            .background(Brush.radialGradient(listOf(Wx.Violet.copy(alpha = 0.22f), Color.Transparent)))
    )
}

@Composable
private fun HomeTopBar(
    gameCount: Int,
    onLibrary: () -> Unit,
    onStores: () -> Unit,
    onContainers: () -> Unit,
    onSettings: () -> Unit,
    onControllers: () -> Unit,
    onClassic: () -> Unit,
) {
    var showMore by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.mipmap.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(36.dp),
        )
        Column(Modifier.padding(start = 8.dp)) {
            Text(stringResource(R.string.app_name), style = WxType.Heading)
            Text(gamesCount(gameCount), style = WxType.Caption.copy(fontSize = 11.sp))
        }
        Row(Modifier.padding(start = 24.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NavPill(stringResource(R.string.home_tab_games), active = true) { }
            NavPill(stringResource(R.string.home_library), active = false, onClick = onLibrary)
            NavPill(stringResource(R.string.home_stores), active = false, onClick = onStores)
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WxIconButton(WxIcons.Cube, stringResource(R.string.home_containers), onContainers)
            WxIconButton(Icons.Filled.Search, stringResource(R.string.home_search), onLibrary)
            WxIconButton(Icons.Filled.Settings, stringResource(R.string.home_settings), onSettings)
            Box {
                WxIconButton(Icons.Filled.MoreVert, stringResource(R.string.home_more), { showMore = true })
                DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_controllers)) },
                        onClick = {
                            showMore = false
                            onControllers()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_classic_view)) },
                        onClick = {
                            showMore = false
                            onClassic()
                        },
                    )
                }
            }
        }
        WxStatusCluster(Modifier.padding(start = 14.dp))
    }
}

@Composable
private fun NavPill(label: String, active: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        label,
        style = WxType.Heading.copy(fontSize = 14.sp, color = if (active || focused) Wx.Text else Wx.TextSecondary),
        modifier = Modifier
            .wxRing(focused, PillShape, width = 2.dp, glow = 12.dp)
            .clip(PillShape)
            .then(
                if (active) Modifier.background(Wx.SoftGradient).border(1.dp, Wx.TintBorder, PillShape)
                else if (focused) Modifier.background(Wx.Tint)
                else Modifier
            )
            .wxClickable(onClick) { focused = it }
            .padding(horizontal = 15.dp, vertical = 7.dp),
    )
}

/** PS5-style headline: eyebrow, logo (or title), tags and the two main actions. */
@Composable
private fun GameHeadline(game: HomeGame, compact: Boolean, onPlay: () -> Unit, onDetails: () -> Unit) {
    val logo = rememberFileImage(game.logoFile, game.logoVersion, 800)
    Column(
        Modifier.padding(start = 12.dp, top = if (compact) 4.dp else 18.dp).widthIn(max = 500.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 10.dp),
    ) {
        Text(eyebrow(game), style = WxType.Eyebrow, maxLines = 1)
        if (logo != null) {
            Image(
                bitmap = logo,
                contentDescription = game.name,
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
                modifier = Modifier.heightIn(max = if (compact) 54.dp else 80.dp).widthIn(max = 320.dp),
            )
        } else {
            Text(
                game.name,
                style = WxType.Display.copy(fontSize = if (compact) 32.sp else 40.sp, lineHeight = if (compact) 34.sp else 42.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WxChip(game.containerName)
            WxChip(game.driverLabel)
            WxChip(game.dxLabel)
            WxChip(playtimeLabel(game))
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            WxPrimaryButton(stringResource(R.string.home_play), onPlay, icon = Icons.Filled.PlayArrow, height = if (compact) 44.dp else 48.dp)
            WxTonalButton(stringResource(R.string.home_details), onDetails, icon = WxIcons.Sliders, height = if (compact) 44.dp else 48.dp)
        }
    }
}

@Composable
private fun eyebrow(game: HomeGame): String {
    if (game.playCount == 0 && game.lastPlayed == 0L) return stringResource(R.string.home_new).uppercase()
    val ago = lastPlayedLabel(game.lastPlayed) ?: return stringResource(R.string.home_continue, "").trimEnd(' ', '·').uppercase()
    return stringResource(R.string.home_continue, ago).uppercase()
}

@Composable
fun lastPlayedLabel(lastPlayed: Long): String? {
    if (lastPlayed <= 0) return null
    val now = System.currentTimeMillis()
    if (now - lastPlayed < DateUtils.MINUTE_IN_MILLIS) return stringResource(R.string.home_just_now)
    return DateUtils.getRelativeTimeSpanString(lastPlayed, now, DateUtils.MINUTE_IN_MILLIS)
        .toString().replaceFirstChar { it.lowercase(Locale.getDefault()) }
}

@Composable
fun gamesCount(count: Int): String =
    LocalContext.current.resources.getQuantityString(R.plurals.home_games, count, count)

/** "3 h 12 min", "52 min", "< 1 min". */
fun playtimeValue(game: HomeGame): String {
    val minutes = game.playtimeMs / 60_000
    return when {
        minutes >= 60 -> "${minutes / 60} h ${minutes % 60} min"
        minutes > 0 -> "$minutes min"
        else -> "< 1 min"
    }
}

@Composable
fun playtimeLabel(game: HomeGame): String {
    if (game.playCount == 0 && game.playtimeMs == 0L) return stringResource(R.string.home_never_played)
    return stringResource(R.string.home_playtime, playtimeValue(game))
}

/** Steam Deck "Recent" row with the gamepad legend on the same line. */
@Composable
private fun RecentRow(
    games: List<HomeGame>,
    selected: Int,
    compact: Boolean,
    firstTileFocus: FocusRequester,
    onSelect: (Int) -> Unit,
    onPlay: (HomeGame) -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val listState = rememberLazyListState()
    // Touch and library picks move the selection without moving focus; keep it on screen.
    LaunchedEffect(selected) {
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == selected && it.offset >= 0 }) {
            listState.animateScrollToItem(maxOf(0, selected - 1))
        }
    }
    val base = if (compact) 62.dp else 76.dp
    Column(Modifier.padding(bottom = if (compact) 8.dp else 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.home_recent), style = WxType.Heading)
            Text(
                gamesCount(games.size),
                style = WxType.Caption,
                modifier = Modifier.padding(start = 8.dp),
            )
            Spacer(Modifier.weight(1f))
            if (rememberHasGamepad()) {
                WxHints(
                    "A" to stringResource(R.string.home_hint_play),
                    "Y" to stringResource(R.string.home_hint_details),
                    "X" to stringResource(R.string.home_hint_library),
                )
            }
        }
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Bottom,
            contentPadding = PaddingValues(start = 12.dp, end = 24.dp, top = 10.dp, bottom = 4.dp),
            modifier = Modifier.fillMaxWidth().height(base * 1.5f * 1.18f + 18.dp),
        ) {
            itemsIndexed(games, key = { _, game -> game.key }) { index, game ->
                Capsule(
                    game = game,
                    isSelected = index == selected,
                    base = base,
                    modifier = if (index == 0) Modifier.focusRequester(firstTileFocus) else Modifier,
                    onFocused = { onSelect(index) },
                    // Touch: first tap selects, second tap plays. Gamepad: focus already selected it.
                    onClick = { if (index == selected) onPlay(game) else onSelect(index) },
                )
            }
            item(key = "library") { LibraryTile(base, onOpenLibrary) }
        }
    }
}

@Composable
private fun Capsule(
    game: HomeGame,
    isSelected: Boolean,
    base: Dp,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    val width by animateDpAsState(if (isSelected) base * 1.18f else base, tween(200), label = "capsuleWidth")
    Box(
        modifier
            .width(width)
            .aspectRatio(2f / 3f)
            .wxRing(isSelected, TileShape, width = 3.dp, glow = 22.dp)
            .clip(TileShape)
            .alpha(if (isSelected) 1f else 0.74f)
            .wxClickable(onClick) { if (it) onFocused() }
    ) {
        CoverArt(game, Modifier.fillMaxSize())
    }
}

@Composable
private fun LibraryTile(base: Dp, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier
            .width(base)
            .aspectRatio(2f / 3f)
            .wxRing(focused, TileShape, width = 3.dp)
            .clip(TileShape)
            .background(if (focused) Wx.TintStrong else Wx.Tint)
            .then(if (focused) Modifier else Modifier.border(1.dp, Wx.TintBorder, TileShape))
            .wxClickable(onClick) { focused = it },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(WxIcons.Grid, contentDescription = null, tint = Wx.Text, modifier = Modifier.size(24.dp))
        Text(
            stringResource(R.string.home_library),
            style = WxType.Caption.copy(fontWeight = FontWeight.SemiBold, color = Wx.Text),
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Cover art, or a generated card with the executable icon and name when there is none. */
@Composable
fun CoverArt(game: HomeGame, modifier: Modifier) {
    if (game.cover != null) {
        Image(game.cover, contentDescription = game.name, contentScale = ContentScale.Crop, modifier = modifier)
        return
    }
    // Generated covers stay inside the brand's blue-to-purple range.
    val hue = 220f + (abs(game.name.hashCode()) % 70)
    Box(
        modifier.background(Brush.verticalGradient(listOf(Color.hsv(hue, 0.55f, 0.55f), Color.hsv(hue, 0.6f, 0.16f)))),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
            if (game.icon != null) Image(game.icon, contentDescription = null, modifier = Modifier.size(34.dp))
            Text(
                game.name,
                style = WxType.Heading.copy(fontSize = 11.sp, textAlign = TextAlign.Center),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier, onOpenContainers: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.home_empty_title), style = WxType.Title.copy(fontSize = 26.sp))
        Text(
            stringResource(R.string.home_empty_body),
            style = WxType.Body.copy(color = Wx.TextSecondary, textAlign = TextAlign.Center),
            modifier = Modifier.padding(top = 8.dp, bottom = 18.dp).widthIn(max = 440.dp),
        )
        WxPrimaryButton(stringResource(R.string.home_open_containers), onOpenContainers, Modifier.focusRequester(focus), icon = WxIcons.Cube)
    }
}

/** Full library grid with search; picking a game selects it on the home screen. */
@Composable
private fun LibraryOverlay(games: List<HomeGame>, onPick: (Int) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, games) {
        games.withIndex().filter { query.isBlank() || it.value.name.contains(query.trim(), ignoreCase = true) }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Wx.Ground.copy(alpha = 0.97f))
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(horizontal = 26.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.home_library), style = WxType.Title.copy(fontSize = 25.sp, fontWeight = FontWeight.ExtraBold))
            Text(
                gamesCount(games.size),
                style = WxType.Caption,
                modifier = Modifier.padding(start = 10.dp, top = 4.dp),
            )
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
                    unfocusedContainerColor = Wx.Surface,
                    cursorColor = Wx.Lilac,
                ),
                modifier = Modifier.width(300.dp),
            )
            WxIconButton(Icons.Filled.Close, stringResource(R.string.home_hint_back), onClose, Modifier.padding(start = 10.dp))
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 18.dp, bottom = 18.dp, start = 4.dp, end = 4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(filtered, key = { it.value.key }) { entry ->
                LibraryCard(entry.value) { onPick(entry.index) }
            }
        }
    }
}

@Composable
private fun LibraryCard(game: HomeGame, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .wxRing(focused, TileShape, width = 3.dp)
                .clip(TileShape)
                .wxClickable(onClick) { focused = it }
        ) {
            CoverArt(game, Modifier.fillMaxSize())
        }
        Text(
            game.name,
            style = WxType.Caption.copy(color = if (focused) Wx.Text else Wx.TextSecondary, fontWeight = FontWeight.Medium),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * Screen-wide buttons: Y opens the selected game's page, X the library, Start the settings.
 * Consumed here because Android's fallback for Y is BACK, which would leave the app.
 */
private fun Modifier.homeButtons(onDetails: () -> Unit, onLibrary: () -> Unit, onMenu: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        val action = when (event.key) {
            Key.ButtonY -> onDetails
            Key.ButtonX -> onLibrary
            Key.ButtonStart, Key.Menu -> onMenu
            else -> return@onPreviewKeyEvent false
        }
        if (event.type == KeyEventType.KeyUp) action()
        true
    }
