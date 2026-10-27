package com.winlator.cmod.home

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.R
import com.winlator.cmod.container.Container
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.contents.XclipseDriverManager

/** One container card: the Container plus the labels the classic list never showed. */
class ContainerCard(
    val container: Container,
    val wine: String,
    val driver: String,
    val dx: String,
    val emulator: String,
    val games: Int,
)

/** Loads every container with its labels and shortcut count. Call off the UI thread. */
fun loadContainerCards(context: Context, manager: ContainerManager): List<ContainerCard> {
    val drivers = XclipseDriverManager(context)
    return manager.containers.map { container ->
        ContainerCard(
            container = container,
            wine = HomeGamesLabels.wine(container.wineVersion),
            driver = HomeGames.driverLabel(drivers, container.graphicsDriverConfig),
            dx = HomeGames.dxLabel(container.dxWrapper, container.dxWrapperConfig),
            emulator = container.emulator?.let { if (it.equals("box64", true)) "Box64" else "FEXCore" } ?: "FEXCore",
            games = runCatching { manager.loadShortcutsForContainer(container).size }.getOrDefault(0),
        )
    }
}

private val CardShape = RoundedCornerShape(16.dp)
private val Tints = listOf(
    Brush.linearGradient(listOf(Wx.Blue, Color(0xFF5E6CFF))),
    Brush.linearGradient(listOf(Color(0xFF6A5CFF), Color(0xFF8E5CFF))),
    Brush.linearGradient(listOf(Color(0xFF9B5CFF), Color(0xFFC84CF0))),
)

@Composable
fun ContainersScreen(
    cards: List<ContainerCard>?,
    busy: Boolean,
    onBack: () -> Unit,
    onNew: () -> Unit,
    onEdit: (Container) -> Unit,
    onRunDesktop: (Container) -> Unit,
    onDuplicate: (Container) -> Unit,
    onDelete: (Container) -> Unit,
    onClassicEditor: (Container) -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var selectedId by rememberSaveable { mutableStateOf<Int?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val list = cards.orEmpty()
    val selected = list.firstOrNull { it.container.id == selectedId } ?: list.firstOrNull()
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(list.isNotEmpty()) {
        if (list.isNotEmpty()) {
            withFrameNanos { }
            runCatching { firstFocus.requestFocus() }
        }
    }

    Box(Modifier.fillMaxSize().background(Wx.Ground)) {
        Box(
            Modifier
                .padding(start = 520.dp)
                .size(560.dp, 380.dp)
                .background(Brush.radialGradient(listOf(Wx.Violet.copy(alpha = 0.18f), Color.Transparent)))
        )
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(horizontal = 26.dp, vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WxIconButton(WxIcons.Back, stringResource(R.string.game_back_home), onBack)
                Column(Modifier.padding(start = 14.dp)) {
                    Text(stringResource(R.string.home_containers), style = WxType.Title.copy(fontSize = 25.sp, fontWeight = FontWeight.ExtraBold))
                    Text(
                        context.resources.getQuantityString(R.plurals.ct_containers, list.size, list.size),
                        style = WxType.Caption,
                    )
                }
                Spacer(Modifier.weight(1f))
                WxPrimaryButton(stringResource(R.string.ct_new), onNew, icon = Icons.Filled.Add, height = 42.dp)
            }

            when {
                cards == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Wx.Violet)
                }
                list.isEmpty() -> Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.ct_empty_title), style = WxType.Title.copy(fontSize = 24.sp))
                    Text(stringResource(R.string.ct_empty_body), style = WxType.Body.copy(color = Wx.TextSecondary), modifier = Modifier.padding(top = 8.dp))
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(list, key = { it.container.id }) { card ->
                        val index = list.indexOf(card)
                        ContainerCardView(
                            card = card,
                            tint = Tints[index % Tints.size],
                            isSelected = card === selected,
                            modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                            onSelect = { selectedId = card.container.id },
                            onEdit = { onEdit(card.container) },
                            onRunDesktop = { onRunDesktop(card.container) },
                            onDuplicate = {
                                confirm = context.getString(R.string.ct_duplicate_confirm, card.container.name) to { onDuplicate(card.container) }
                            },
                            onDelete = {
                                confirm = context.getString(R.string.ct_remove_confirm, card.container.name) to { onDelete(card.container) }
                            },
                            onClassicEditor = { onClassicEditor(card.container) },
                        )
                    }
                }
            }
        }

        if (busy) {
            Box(Modifier.fillMaxSize().background(Wx.Ground.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Wx.Violet)
                    Text(stringResource(R.string.ct_working), style = WxType.Label, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    }

    confirm?.let { (message, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = Wx.SurfaceHigh,
            text = { Text(message, style = WxType.Body) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    action()
                }) { Text(stringResource(android.R.string.ok), color = Wx.Lilac) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.st_cancel), color = Wx.TextSecondary) }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContainerCardView(
    card: ContainerCard,
    tint: Brush,
    isSelected: Boolean,
    modifier: Modifier,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onRunDesktop: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onClassicEditor: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    val active = isSelected || focused
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 150.dp)
            .wxRing(focused, CardShape, width = 2.dp)
            .clip(CardShape)
            .background(if (active) Wx.SoftGradient else Brush.linearGradient(listOf(Wx.Surface, Wx.Surface)))
            .then(if (focused) Modifier else Modifier.border(1.dp, if (isSelected) Wx.TintBorder else Wx.TintBorder.copy(alpha = 0.2f), CardShape))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .wxClickable(onClick = { if (isSelected) onEdit() else onSelect() }) { focused = it; if (it) onSelect() },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(tint), contentAlignment = Alignment.Center) {
                    Icon(WxIcons.Cube, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.padding(start = 10.dp)) {
                    Text(card.container.name, style = WxType.Heading.copy(fontSize = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(card.wine, style = WxType.Caption, maxLines = 1)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                WxChip(card.driver)
                WxChip(card.dx)
            }
            Text(
                LocalContext.current.resources.getQuantityString(R.plurals.home_games, card.games, card.games) + "  ·  " + card.emulator,
                style = WxType.Caption,
            )
        }
        if (isSelected) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                WxPrimaryButton(stringResource(R.string.ct_edit), onEdit, Modifier.weight(1f), height = 34.dp, horizontalPadding = 8.dp)
                WxTonalButton(stringResource(R.string.ct_desktop), onRunDesktop, Modifier.weight(1f), height = 34.dp, horizontalPadding = 8.dp)
                Box {
                    WxIconButton(Icons.Filled.MoreVert, stringResource(R.string.home_more), { showMore = true }, size = 34.dp)
                    DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.ct_duplicate)) }, onClick = { showMore = false; onDuplicate() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.ct_classic_edit)) }, onClick = { showMore = false; onClassicEditor() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.ct_remove), color = Wx.Danger) }, onClick = { showMore = false; onDelete() })
                    }
                }
            }
        }
    }
}
