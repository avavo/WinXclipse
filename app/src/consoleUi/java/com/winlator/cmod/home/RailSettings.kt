package com.winlator.cmod.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.R

/** One row of a console settings list. */
sealed interface SettingItem {
    val title: String
    val hint: String

    class Toggle(
        override val title: String,
        override val hint: String,
        val checked: Boolean,
        val onChange: (Boolean) -> Unit,
    ) : SettingItem

    /** ‹ value ›: A or tap cycles forward, D-pad left/right steps, the chevrons step on touch. */
    class Choice(
        override val title: String,
        override val hint: String,
        val options: List<String>,
        val index: Int,
        val onChange: (Int) -> Unit,
    ) : SettingItem

    class Action(
        override val title: String,
        override val hint: String,
        val value: String = "",
        val danger: Boolean = false,
        val onClick: () -> Unit,
    ) : SettingItem
}

class SettingSection(
    val title: String,
    val blurb: String,
    val icon: ImageVector,
    val items: List<SettingItem>,
    /** Highlighted note above the rows (e.g. "Quasar profile active"). */
    val banner: String? = null,
)

private val RowShape = RoundedCornerShape(12.dp)
private val RailItemShape = RoundedCornerShape(10.dp)

/**
 * PS5/Xbox-style settings layout shared by Settings and the container editor: sections on a
 * left rail (focusing one shows it, LB/RB step through them) and rows on the right.
 */
@Composable
fun RailSettingsScreen(
    title: String,
    subtitle: String?,
    backLabel: String,
    sections: List<SettingSection>,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    BackHandler(onBack = onBack)
    var section by rememberSaveable { mutableIntStateOf(0) }
    val index = section.coerceIn(0, maxOf(0, sections.lastIndex))
    val current = sections.getOrNull(index) ?: return
    val railFocus = remember(sections.size) { List(sections.size) { FocusRequester() } }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { railFocus[index].requestFocus() }
    }

    Row(
        Modifier
            .fillMaxSize()
            .background(Wx.Ground)
            .onPreviewKeyEvent { event ->
                val step = when (event.key) {
                    Key.ButtonL1 -> -1
                    Key.ButtonR1 -> 1
                    else -> return@onPreviewKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown) {
                    section = (index + step).coerceIn(0, sections.lastIndex)
                    runCatching { railFocus[section].requestFocus() }
                }
                true
            }
    ) {
        Column(
            Modifier
                .width(236.dp)
                .fillMaxHeight()
                .background(Wx.Rail)
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Start))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                Modifier
                    .clip(PillShape)
                    .clickable(onClick = onBack)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(WxIcons.Back, contentDescription = null, tint = Wx.TextSecondary, modifier = Modifier.size(16.dp))
                Text(backLabel, style = WxType.Caption.copy(fontWeight = FontWeight.Medium), modifier = Modifier.padding(start = 4.dp))
            }
            Text(
                title,
                style = WxType.Title.copy(fontSize = 24.sp, fontWeight = FontWeight.ExtraBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp, top = 4.dp),
            )
            if (subtitle != null) {
                Text(subtitle, style = WxType.Caption, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
            }
            Spacer(Modifier.height(8.dp))
            sections.forEachIndexed { i, s ->
                RailItem(
                    icon = s.icon,
                    label = s.title,
                    selected = i == index,
                    modifier = Modifier.focusRequester(railFocus[i]),
                    onSelect = { section = i },
                )
            }
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.End))
                .padding(start = 24.dp, end = 24.dp, top = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(current.title, style = WxType.Title)
                    Text(current.blurb, style = WxType.Caption.copy(fontSize = 13.sp), maxLines = 2)
                }
                actions()
            }
            if (current.banner != null) {
                Row(
                    Modifier
                        .padding(top = 10.dp)
                        .fillMaxWidth()
                        .clip(RowShape)
                        .background(Wx.SoftGradient)
                        .border(1.dp, Wx.TintBorder, RowShape)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(WxIcons.Chip, contentDescription = null, tint = Wx.Lilac, modifier = Modifier.size(18.dp))
                    Text(current.banner, style = WxType.Caption.copy(color = Color(0xFFD6D0F2)), modifier = Modifier.padding(start = 10.dp))
                }
            }
            LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp),
            ) {
                itemsIndexed(current.items, key = { i, item -> "$index:$i:${item.title}" }) { _, item ->
                    SettingRow(item)
                }
            }
            if (rememberHasGamepad()) {
                WxHints(
                    "A" to stringResource(R.string.st_hint_change),
                    "B" to stringResource(R.string.home_hint_back),
                    "LB RB" to stringResource(R.string.st_hint_section),
                    modifier = Modifier.align(Alignment.End).padding(bottom = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun RailItem(icon: ImageVector, label: String, selected: Boolean, modifier: Modifier, onSelect: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .wxRing(focused, RailItemShape, width = 2.dp, glow = 12.dp)
            .clip(RailItemShape)
            .then(
                when {
                    selected -> Modifier.background(Wx.SoftGradient).border(1.dp, Wx.TintBorder, RailItemShape)
                    focused -> Modifier.background(Wx.Tint)
                    else -> Modifier
                }
            )
            // Focusing a section shows it, like the PS5 settings list.
            .wxClickable(onSelect) { if (it) onSelect(); focused = it }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (selected || focused) Wx.Text else Wx.TextSecondary, modifier = Modifier.size(18.dp))
        Text(
            label,
            style = WxType.Heading.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (selected || focused) Wx.Text else Wx.TextSecondary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

@Composable
private fun SettingRow(item: SettingItem) {
    var focused by remember { mutableStateOf(false) }
    val activate: () -> Unit = when (item) {
        is SettingItem.Toggle -> ({ item.onChange(!item.checked) })
        is SettingItem.Choice -> ({ if (item.options.isNotEmpty()) item.onChange((item.index + 1) % item.options.size) })
        is SettingItem.Action -> item.onClick
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .wxRing(focused, RowShape, width = 2.dp, glow = 14.dp)
            .clip(RowShape)
            .background(if (focused) Wx.SoftGradient else SolidColor(Wx.Surface))
            .then(if (focused) Modifier else Modifier.border(1.dp, Wx.TintBorder.copy(alpha = 0.18f), RowShape))
            .then(if (item is SettingItem.Choice) Modifier.choiceKeys(item) else Modifier)
            .wxClickable(activate) { focused = it }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.title, style = WxType.Label)
            if (item.hint.isNotEmpty()) Text(item.hint, style = WxType.Caption, maxLines = 2)
        }
        Spacer(Modifier.width(12.dp))
        when (item) {
            is SettingItem.Toggle -> WxSwitch(item.checked)
            is SettingItem.Choice -> ChoiceValue(item)
            is SettingItem.Action -> Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.value.isNotEmpty()) {
                    Text(
                        item.value,
                        style = WxType.Caption.copy(fontSize = 13.sp, color = if (item.danger) Wx.Danger else Wx.TextSecondary, fontWeight = FontWeight.Medium),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 220.dp),
                    )
                }
                Icon(WxIcons.ChevronRight, contentDescription = null, tint = if (item.danger) Wx.Danger else Wx.TextSecondary, modifier = Modifier.padding(start = 6.dp).size(16.dp))
            }
        }
    }
}

/** D-pad left/right steps a choice in place instead of moving focus. */
private fun Modifier.choiceKeys(item: SettingItem.Choice): Modifier = onPreviewKeyEvent { event ->
    val step = when (event.key) {
        Key.DirectionLeft -> -1
        Key.DirectionRight -> 1
        else -> return@onPreviewKeyEvent false
    }
    if (event.type == KeyEventType.KeyDown && item.options.isNotEmpty()) {
        item.onChange((item.index + step + item.options.size) % item.options.size)
    }
    true
}

@Composable
private fun ChoiceValue(item: SettingItem.Choice) {
    val size = item.options.size
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            WxIcons.ChevronLeft,
            contentDescription = null,
            tint = Wx.Lilac,
            modifier = Modifier
                .clip(CircleShape)
                .clickable { if (size > 0) item.onChange((item.index - 1 + size) % size) }
                .padding(6.dp)
                .size(14.dp),
        )
        Text(
            item.options.getOrElse(item.index) { "" },
            style = WxType.Label.copy(fontSize = 14.sp, textAlign = TextAlign.Center),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(min = 120.dp, max = 230.dp),
        )
        Icon(
            WxIcons.ChevronRight,
            contentDescription = null,
            tint = Wx.Lilac,
            modifier = Modifier
                .clip(CircleShape)
                .clickable { if (size > 0) item.onChange((item.index + 1) % size) }
                .padding(6.dp)
                .size(14.dp),
        )
    }
}

@Composable
fun WxSwitch(checked: Boolean) {
    val knob by animateDpAsState(if (checked) 21.dp else 3.dp, tween(160), label = "knob")
    Box(
        Modifier
            .size(width = 44.dp, height = 26.dp)
            .clip(PillShape)
            .background(if (checked) Wx.Gradient else SolidColor(Wx.TintBorder.copy(alpha = 0.3f)))
    ) {
        Box(
            Modifier
                .offset(x = knob, y = 3.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}
