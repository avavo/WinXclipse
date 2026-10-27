package com.winlator.cmod.home

import android.content.Context
import android.os.BatteryManager
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.InputDevice
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
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
import kotlinx.coroutines.delay
import java.util.Date
import kotlin.math.abs

private val Accent = Color(0xFF4FC3F7)
private val Scrim = Color(0xFF05070C)
private val TileShape = RoundedCornerShape(10.dp)
private val PanelShape = RoundedCornerShape(12.dp)

@Composable
fun HomeScreen(
    games: List<HomeGame>?,
    onPlay: (HomeGame) -> Unit,
    onOpenClassic: (Int) -> Unit,
    onOpenStores: () -> Unit,
    onOpenControllers: () -> Unit,
) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Scrim, surface = Color(0xFF151A22))) {
        val list = games.orEmpty()
        // Selection follows the shortcut, not the position: reloads (new artwork, back from a
        // game) re-sort the row and the selected game keeps its highlight.
        var selectedKey by remember { mutableStateOf<String?>(null) }
        val selected = list.indexOfFirst { it.shortcut.file.path == selectedKey }.coerceAtLeast(0)
        var showLibrary by remember { mutableStateOf(false) }
        var showOptions by remember { mutableStateOf(false) }
        var focusedOnce by remember { mutableStateOf(false) }
        val firstTileFocus = remember { FocusRequester() }
        val game = list.getOrNull(selected)
        val select: (Int) -> Unit = { index -> selectedKey = list.getOrNull(index)?.shortcut?.file?.path }

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
                .background(Scrim)
                .homeButtons(
                    onOptions = { if (!showLibrary && game != null) showOptions = true },
                    onLibrary = { showLibrary = true },
                )
        ) {
            val compact = maxHeight < 480.dp
            val tileWidth = if (compact) 74.dp else 104.dp

            HeroBackground(game)

            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.displayCutout)
                    .padding(horizontal = 24.dp, vertical = if (compact) 10.dp else 18.dp)
            ) {
                TopBar(
                    onSearch = { showLibrary = true },
                    onSettings = { onOpenClassic(R.id.main_menu_settings) },
                )
                when {
                    games == null -> Spacer(Modifier.weight(1f))
                    list.isEmpty() -> EmptyLibrary(
                        modifier = Modifier.weight(1f),
                        onOpenContainers = { onOpenClassic(R.id.main_menu_containers) },
                    )
                    else -> {
                        GameRow(
                            games = list,
                            selected = selected,
                            tileWidth = tileWidth,
                            firstTileFocus = firstTileFocus,
                            onSelect = select,
                            onPlay = onPlay,
                            onOpenLibrary = { showLibrary = true },
                        )
                        if (game != null) {
                            GameInfo(
                                game = game,
                                compact = compact,
                                showOptions = showOptions,
                                onShowOptions = { showOptions = it },
                                onPlay = { onPlay(game) },
                                onGameSettings = { onOpenClassic(R.id.main_menu_shortcuts) },
                                onOpenLibrary = { showLibrary = true },
                            )
                        }
                        Spacer(Modifier.weight(1f))
                    }
                }
                QuickTiles(
                    compact = compact,
                    onLibrary = { showLibrary = true },
                    onStores = onOpenStores,
                    onControllers = onOpenControllers,
                    onOpenClassic = onOpenClassic,
                )
                if (rememberHasGamepad()) HintBar()
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
}

/** Selected game's art: blurred across the screen, sharp key art on the right (PS5 style). */
@Composable
private fun HeroBackground(game: HomeGame?) {
    Crossfade(targetState = game, animationSpec = tween(450), label = "hero") { current ->
        Box(Modifier.fillMaxSize()) {
            val cover = current?.cover
            val art = cover ?: current?.icon
            if (art != null) {
                Image(
                    bitmap = art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().blur(28.dp).graphicsLayer { alpha = 0.7f },
                )
                if (cover != null) {
                    // Sharp key art on the right, faded out (not darkened) towards the left so it
                    // melts into the blurred copy behind it without a visible edge.
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
            } else {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF1B2735), Scrim))))
            }
        }
    }
    Box(
        Modifier.fillMaxSize().background(
            Brush.horizontalGradient(0f to Scrim.copy(alpha = 0.9f), 0.55f to Scrim.copy(alpha = 0.5f), 1f to Color.Transparent)
        )
    )
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Scrim.copy(alpha = 0.9f))))
}

@Composable
private fun TopBar(onSearch: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var battery by remember { mutableIntStateOf(-1) }
    var charging by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        while (true) {
            now = System.currentTimeMillis()
            battery = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            charging = batteryManager.isCharging
            delay(15_000)
        }
    }

    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.mipmap.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(34.dp),
        )
        Text(
            text = stringResource(R.string.app_name),
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 10.dp),
        )
        Spacer(Modifier.weight(1f))
        TopBarIcon(Icons.Filled.Search, stringResource(R.string.home_search), onSearch)
        TopBarIcon(Icons.Filled.Settings, stringResource(R.string.home_settings), onSettings)
        if (battery >= 0) BatteryIndicator(battery, charging, Modifier.padding(start = 14.dp))
        Text(
            text = DateFormat.getTimeFormat(context).format(Date(now)),
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 14.dp),
        )
    }
}

@Composable
private fun TopBarIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (focused) Color.White.copy(alpha = 0.22f) else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .gamepadActions(onAccept = onClick)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun BatteryIndicator(level: Int, charging: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val fill = if (charging) Color(0xFF7CE38B) else if (level <= 15) Color(0xFFFF6B6B) else Color.White
        Canvas(Modifier.size(width = 24.dp, height = 12.dp)) {
            val body = Size(size.width - 3.dp.toPx(), size.height)
            drawRoundRect(Color.White, size = body, cornerRadius = CornerRadius(2.dp.toPx()), style = Stroke(1.5.dp.toPx()))
            val inset = 2.5.dp.toPx()
            drawRoundRect(
                fill,
                topLeft = Offset(inset, inset),
                size = Size((body.width - 2 * inset) * level / 100f, body.height - 2 * inset),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
            drawRect(Color.White, topLeft = Offset(body.width, size.height * 0.3f), size = Size(2.dp.toPx(), size.height * 0.4f))
        }
        Text("$level%", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
    }
}

/** Steam Deck "Recent games" row: most recently played first, the selected capsule enlarged. */
@Composable
private fun GameRow(
    games: List<HomeGame>,
    selected: Int,
    tileWidth: Dp,
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
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(start = 6.dp, end = 24.dp, top = 14.dp, bottom = 14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        itemsIndexed(games, key = { _, game -> game.shortcut.file.path }) { index, game ->
            GameTile(
                game = game,
                isSelected = index == selected,
                width = tileWidth,
                modifier = if (index == 0) Modifier.focusRequester(firstTileFocus) else Modifier,
                onFocused = { onSelect(index) },
                // Touch: first tap selects, second tap plays. Gamepad: focus already selected it.
                onClick = { if (index == selected) onPlay(game) else onSelect(index) },
            )
        }
        item(key = "library") {
            ShortcutTile(
                icon = rememberVectorPainter(Icons.AutoMirrored.Filled.List),
                label = stringResource(R.string.home_see_all),
                width = tileWidth,
                onClick = onOpenLibrary,
            )
        }
    }
}

@Composable
private fun GameTile(
    game: HomeGame,
    isSelected: Boolean,
    width: Dp,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    val scale by animateFloatAsState(if (isSelected) 1.14f else 1f, tween(180), label = "tileScale")
    Box(
        modifier
            .width(width)
            .aspectRatio(2f / 3f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(
                if (isSelected) Modifier.shadow(18.dp, TileShape, ambientColor = Accent, spotColor = Accent)
                else Modifier
            )
            .clip(TileShape)
            .border(if (isSelected) 2.5.dp else 0.dp, if (isSelected) Color.White else Color.Transparent, TileShape)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .gamepadActions(onAccept = onClick)
            .clickable(onClick = onClick)
    ) {
        CoverArt(game, Modifier.fillMaxSize())
    }
}

/** Cover art, or a generated card with the executable icon and name when there is none. */
@Composable
private fun CoverArt(game: HomeGame, modifier: Modifier) {
    if (game.cover != null) {
        Image(game.cover, contentDescription = game.name, contentScale = ContentScale.Crop, modifier = modifier)
        return
    }
    val hue = (abs(game.name.hashCode()) % 360).toFloat()
    Box(
        modifier.background(Brush.verticalGradient(listOf(Color.hsv(hue, 0.45f, 0.45f), Color.hsv(hue, 0.6f, 0.18f)))),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
            if (game.icon != null) {
                Image(game.icon, contentDescription = null, modifier = Modifier.size(36.dp))
            }
            Text(
                text = game.name,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun ShortcutTile(icon: Painter, label: String, width: Dp, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier
            .width(width)
            .aspectRatio(2f / 3f)
            .clip(TileShape)
            .background(Color.White.copy(alpha = if (focused) 0.22f else 0.1f))
            .border(if (focused) 2.5.dp else 0.dp, if (focused) Color.White else Color.Transparent, TileShape)
            .onFocusChanged { focused = it.isFocused }
            .gamepadActions(onAccept = onClick)
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
        Text(label, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

/** PS5-style title block: name, stats, a white "Play" pill and the "..." options menu. */
@Composable
private fun GameInfo(
    game: HomeGame,
    compact: Boolean,
    showOptions: Boolean,
    onShowOptions: (Boolean) -> Unit,
    onPlay: () -> Unit,
    onGameSettings: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    Column(Modifier.padding(start = 6.dp)) {
        Text(
            text = game.name,
            color = Color.White,
            fontSize = if (compact) 24.sp else 32.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = gameStats(game),
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(Modifier.padding(top = if (compact) 8.dp else 14.dp), verticalAlignment = Alignment.CenterVertically) {
            PlayButton(onPlay)
            Box(Modifier.padding(start = 12.dp)) {
                RoundIconButton(Icons.Filled.MoreVert, stringResource(R.string.home_options)) { onShowOptions(true) }
                DropdownMenu(expanded = showOptions, onDismissRequest = { onShowOptions(false) }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_play)) },
                        leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                        onClick = {
                            onShowOptions(false)
                            onPlay()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_game_settings)) },
                        leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        onClick = {
                            onShowOptions(false)
                            onGameSettings()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_library)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                        onClick = {
                            onShowOptions(false)
                            onOpenLibrary()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun gameStats(game: HomeGame): String {
    val parts = mutableListOf(game.containerName)
    if (game.playCount == 0 && game.playtimeMs == 0L) {
        parts += stringResource(R.string.home_never_played)
    } else {
        val minutes = game.playtimeMs / 60_000
        val playtime = when {
            minutes >= 60 -> "${minutes / 60} h ${minutes % 60} min"
            minutes > 0 -> "$minutes min"
            else -> "< 1 min"
        }
        parts += stringResource(R.string.home_playtime, playtime)
        if (game.lastPlayed > 0) {
            val now = System.currentTimeMillis()
            val ago = if (now - game.lastPlayed < DateUtils.MINUTE_IN_MILLIS) {
                stringResource(R.string.home_just_now)
            } else {
                // Mid-sentence: "Last played 2 hours ago", "Última vez hace 2 horas".
                DateUtils.getRelativeTimeSpanString(game.lastPlayed, now, DateUtils.MINUTE_IN_MILLIS)
                    .toString().replaceFirstChar { it.lowercase() }
            }
            parts += stringResource(R.string.home_last_played, ago)
        }
    }
    return parts.joinToString("  ·  ")
}

@Composable
private fun PlayButton(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.88f))
            .border(if (focused) 3.dp else 0.dp, if (focused) Accent else Color.Transparent, RoundedCornerShape(20.dp))
            .onFocusChanged { focused = it.isFocused }
            .gamepadActions(onAccept = onClick)
            .clickable(onClick = onClick)
            .padding(horizontal = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.Black, modifier = Modifier.size(22.dp))
        Text(
            stringResource(R.string.home_play),
            color = Color.Black,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun RoundIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (focused) 0.35f else 0.14f))
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color.White.copy(alpha = 0.4f), CircleShape)
            .onFocusChanged { focused = it.isFocused }
            .gamepadActions(onAccept = onClick)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

/** Xbox-style quick tiles into stores and the classic WinXclipse screens. */
@Composable
private fun QuickTiles(
    compact: Boolean,
    onLibrary: () -> Unit,
    onStores: () -> Unit,
    onControllers: () -> Unit,
    onOpenClassic: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).height(if (compact) 50.dp else 64.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        QuickTile(rememberVectorPainter(Icons.AutoMirrored.Filled.List), stringResource(R.string.home_library), onLibrary)
        QuickTile(rememberVectorPainter(Icons.Filled.ShoppingCart), stringResource(R.string.home_stores), onStores)
        QuickTile(painterResource(R.drawable.icon_container), stringResource(R.string.home_containers)) {
            onOpenClassic(R.id.main_menu_containers)
        }
        QuickTile(painterResource(R.drawable.icon_gamepad), stringResource(R.string.home_controllers), onControllers)
        QuickTile(rememberVectorPainter(Icons.Filled.Settings), stringResource(R.string.home_settings)) {
            onOpenClassic(R.id.main_menu_settings)
        }
        QuickTile(rememberVectorPainter(Icons.Filled.Menu), stringResource(R.string.home_classic_view)) {
            onOpenClassic(R.id.main_menu_shortcuts)
        }
    }
}

@Composable
private fun RowScope.QuickTile(icon: Painter, label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(PanelShape)
            .background(Color.White.copy(alpha = if (focused) 0.24f else 0.09f))
            .border(if (focused) 2.dp else 0.dp, if (focused) Accent else Color.Transparent, PanelShape)
            .onFocusChanged { focused = it.isFocused }
            .gamepadActions(onAccept = onClick)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        Text(
            label,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/** Steam Deck-style button legend, only when a gamepad is connected. */
@Composable
private fun HintBar() {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Hint("A", stringResource(R.string.home_hint_select))
        Hint("B", stringResource(R.string.home_hint_back))
        Hint("Y", stringResource(R.string.home_hint_options))
    }
}

@Composable
private fun Hint(button: String, label: String) {
    Row(Modifier.padding(start = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
            Text(button, color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Text(label.uppercase(), color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier, onOpenContainers: () -> Unit) {
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.home_empty_title), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            stringResource(R.string.home_empty_body),
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp).width(420.dp),
        )
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { focus.requestFocus() }
        }
        Box(Modifier.focusRequester(focus)) {
            PlayButtonLike(stringResource(R.string.home_open_containers), onOpenContainers)
        }
    }
}

@Composable
private fun PlayButtonLike(label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = if (focused) 1f else 0.88f))
            .border(if (focused) 3.dp else 0.dp, if (focused) Accent else Color.Transparent, RoundedCornerShape(20.dp))
            .onFocusChanged { focused = it.isFocused }
            .gamepadActions(onAccept = onClick)
            .clickable(onClick = onClick)
            .padding(horizontal = 26.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
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
            .background(Scrim.copy(alpha = 0.96f))
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(horizontal = 24.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.home_library), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.home_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier.width(300.dp),
            )
            IconButton(onClick = onClose, modifier = Modifier.padding(start = 8.dp)) {
                Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White)
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 14.dp, bottom = 14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(filtered, key = { it.value.shortcut.file.path }) { entry ->
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
                .clip(TileShape)
                .border(if (focused) 2.5.dp else 0.dp, if (focused) Color.White else Color.Transparent, TileShape)
                .onFocusChanged { focused = it.isFocused }
                .gamepadActions(onAccept = onClick)
                .clickable(onClick = onClick)
        ) {
            CoverArt(game, Modifier.fillMaxSize())
        }
        Text(
            game.name,
            color = Color.White,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * A on a focused element accepts it. Both the down and up events are consumed so
 * Android's fallback key (A -> DPAD_CENTER) does not fire a second click.
 */
private fun Modifier.gamepadActions(onAccept: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        if (event.key != Key.ButtonA) return@onPreviewKeyEvent false
        if (event.type == KeyEventType.KeyUp) onAccept()
        true
    }

/**
 * Screen-wide buttons: Y/Start/Menu opens the selected game's options, X the library.
 * Consumed here because Android's fallback for Y is BACK, which would leave the app.
 */
private fun Modifier.homeButtons(onOptions: () -> Unit, onLibrary: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        val action = when (event.key) {
            Key.ButtonY, Key.ButtonStart, Key.Menu -> onOptions
            Key.ButtonX -> onLibrary
            else -> return@onPreviewKeyEvent false
        }
        if (event.type == KeyEventType.KeyUp) action()
        true
    }

@Composable
private fun rememberHasGamepad(): Boolean = remember {
    InputDevice.getDeviceIds().any { id ->
        val sources = InputDevice.getDevice(id)?.sources ?: 0
        sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
    }
}
