package com.winlator.cmod.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.R

private val CardShape = RoundedCornerShape(16.dp)

/** PS5-style game hub: hero art, logo, actions, then summary or container tabs. */
@Composable
fun GameScreen(
    game: HomeGame,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onGameSettings: () -> Unit,
    onEditContainer: () -> Unit,
    onRefreshArt: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val hero = rememberFileImage(game.heroFile, game.heroVersion, 1600)
    val logo = rememberFileImage(game.logoFile, game.logoVersion, 800)
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showMore by remember { mutableStateOf(false) }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(game.key) {
        withFrameNanos { }
        runCatching { playFocus.requestFocus() }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Wx.Ground)) {
        val compact = maxHeight < 480.dp
        val heroHeight = if (compact) 236.dp else 320.dp
        Box(Modifier.fillMaxWidth().height(heroHeight)) {
            val cover = game.cover
            when {
                hero != null -> Image(hero, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                cover != null -> Image(cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(24.dp).alpha(0.8f))
                else -> Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF1A1440), Wx.Ground))))
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0f to Wx.Ground.copy(alpha = 0.35f), 0.35f to Color.Transparent, 0.82f to Wx.Ground.copy(alpha = 0.85f), 1f to Wx.Ground)
                )
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(0f to Wx.Ground.copy(alpha = 0.85f), 0.6f to Color.Transparent)
                )
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(horizontal = 26.dp)
        ) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                WxTonalButton(stringResource(R.string.game_back_home), onBack, icon = WxIcons.Back, height = 36.dp)
                Spacer(Modifier.weight(1f))
                WxStatusCluster()
            }

            Column(
                Modifier.padding(start = 8.dp, top = if (compact) 6.dp else 30.dp).widthIn(max = 600.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "${game.containerName}  ·  ${game.wineLabel}",
                    style = WxType.Caption.copy(fontSize = 13.sp, color = Wx.Lilac, fontWeight = FontWeight.Medium),
                )
                if (logo != null) {
                    Image(
                        logo,
                        contentDescription = game.name,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart,
                        modifier = Modifier.heightIn(max = if (compact) 58.dp else 84.dp).widthIn(max = 340.dp),
                    )
                } else {
                    Text(
                        game.name,
                        style = WxType.Display.copy(fontSize = if (compact) 32.sp else 40.sp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    WxPrimaryButton(stringResource(R.string.home_play), onPlay, Modifier.focusRequester(playFocus), icon = Icons.Filled.PlayArrow, height = 44.dp)
                    WxTonalButton(stringResource(R.string.home_game_settings), onGameSettings, icon = WxIcons.Sliders, height = 44.dp)
                    Box {
                        WxIconButton(Icons.Filled.MoreVert, stringResource(R.string.home_more), { showMore = true }, size = 44.dp)
                        DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.game_refresh_art)) },
                                onClick = {
                                    showMore = false
                                    onRefreshArt()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.game_edit_container)) },
                                onClick = {
                                    showMore = false
                                    onEditContainer()
                                },
                            )
                        }
                    }
                }
            }

            Row(Modifier.padding(start = 8.dp, top = if (compact) 12.dp else 22.dp), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                GameTab(stringResource(R.string.game_tab_summary), tab == 0) { tab = 0 }
                GameTab(stringResource(R.string.game_tab_container), tab == 1) { tab = 1 }
            }

            Spacer(Modifier.height(10.dp))
            if (tab == 0) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    InfoCard(
                        stringResource(R.string.game_card_progress),
                        listOf(
                            stringResource(R.string.game_playtime) to playtimeValue(game),
                            stringResource(R.string.game_sessions) to game.playCount.toString(),
                            stringResource(R.string.game_last) to (lastPlayedLabel(game.lastPlayed) ?: stringResource(R.string.game_never)),
                        ),
                        Modifier.weight(1f),
                    )
                    InfoCard(
                        stringResource(R.string.game_card_runs),
                        listOf(
                            stringResource(R.string.game_container) to game.containerName,
                            stringResource(R.string.game_driver) to game.driverLabel,
                            stringResource(R.string.game_directx) to game.dxLabel,
                        ),
                        Modifier.weight(1f),
                    )
                    InfoCard(
                        stringResource(R.string.game_card_files),
                        listOf(
                            stringResource(R.string.game_exe) to game.exeName,
                            stringResource(R.string.game_folder) to game.windowsPath.substringBeforeLast('\\', game.windowsPath),
                            stringResource(R.string.game_resolution) to game.screenSize,
                        ),
                        Modifier.weight(1f),
                    )
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    InfoCard(
                        game.containerName,
                        listOf(
                            stringResource(R.string.game_wine) to game.wineLabel,
                            stringResource(R.string.game_emulator) to game.emulatorLabel,
                            stringResource(R.string.game_driver) to game.driverLabel,
                            stringResource(R.string.game_directx) to game.dxLabel,
                        ),
                        Modifier.weight(2f),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        WxTonalButton(stringResource(R.string.game_edit_container), onEditContainer, Modifier.fillMaxWidth(), icon = WxIcons.Cube, height = 42.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun GameTab(label: String, active: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    // Width follows the label so the gradient underline is exactly as wide as the text.
    Column(
        Modifier
            .width(IntrinsicSize.Max)
            .clip(RoundedCornerShape(8.dp))
            .then(if (focused) Modifier.background(Wx.Tint) else Modifier)
            .wxClickable(onClick) { focused = it }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(label, style = WxType.Heading.copy(color = if (active || focused) Wx.Text else Wx.TextSecondary), maxLines = 1, softWrap = false)
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(3.dp))
                .then(if (active) Modifier.background(Wx.Gradient) else Modifier)
        )
    }
}

@Composable
private fun InfoCard(title: String, rows: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(CardShape)
            .background(Wx.Surface)
            .border(1.dp, Wx.TintBorder.copy(alpha = 0.25f), CardShape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = WxType.Heading.copy(fontSize = 13.sp, color = Wx.Lilac), maxLines = 1, overflow = TextOverflow.Ellipsis)
        for ((label, value) in rows) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = WxType.Caption.copy(fontSize = 13.sp), modifier = Modifier.width(96.dp), maxLines = 1)
                Text(
                    value,
                    style = WxType.Label.copy(fontSize = 13.sp, textAlign = TextAlign.End),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
