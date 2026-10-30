package com.winlator.cmod.home

import android.content.Context
import android.graphics.BitmapFactory
import android.os.BatteryManager
import android.text.format.DateFormat
import android.util.LruCache
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date

val PillShape = RoundedCornerShape(percent = 50)

/**
 * Focus and click for every console control: tap or gamepad A activates it, focus gives a
 * light haptic tick, and pressing shrinks it slightly. No grey ripple: focus and selection
 * are drawn by the controls themselves with the brand gradient.
 */
@Composable
fun Modifier.wxClickable(onClick: () -> Unit, onFocus: (Boolean) -> Unit = {}): Modifier {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    return this
        .scale(if (pressed) 0.97f else 1f)
        .onFocusChanged { state ->
            if (state.isFocused) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onFocus(state.isFocused)
        }
        // Consume both edges so Android's fallback (A -> DPAD_CENTER) does not click twice.
        .onPreviewKeyEvent { event ->
            if (event.key != Key.ButtonA) return@onPreviewKeyEvent false
            if (event.type == KeyEventType.KeyUp) onClick()
            true
        }
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

/** Gradient ring plus violet glow, for whatever is focused or selected. */
fun Modifier.wxRing(active: Boolean, shape: Shape, width: Dp = 2.5.dp, glow: Dp = 18.dp): Modifier =
    if (!active) this
    else this
        .shadow(glow, shape, clip = false, ambientColor = Wx.Violet, spotColor = Wx.Violet)
        .border(width, Wx.Gradient, shape)

/** Primary action: blue-to-purple pill ("Jugar", "Guardar"). */
@Composable
fun WxPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    height: Dp = 46.dp,
    horizontalPadding: Dp = 26.dp,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .height(height)
            .shadow(if (focused) 24.dp else 12.dp, PillShape, clip = false, ambientColor = Wx.Violet, spotColor = Wx.Violet)
            .clip(PillShape)
            .background(Wx.Gradient)
            .then(if (focused) Modifier.border(2.5.dp, Color.White, PillShape) else Modifier)
            .wxClickable(onClick) { focused = it }
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = WxType.Button.copy(fontSize = 17.sp, color = Color.White), maxLines = 1, softWrap = false)
    }
}

/** Secondary action: violet-tinted pill. */
@Composable
fun WxTonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    height: Dp = 46.dp,
    horizontalPadding: Dp = 18.dp,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .height(height)
            .wxRing(focused, PillShape)
            .clip(PillShape)
            .background(if (focused) Wx.TintStrong else Wx.Tint)
            .then(if (focused) Modifier else Modifier.border(1.dp, Wx.TintBorder, PillShape))
            .wxClickable(onClick) { focused = it }
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Wx.Text, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = WxType.Button.copy(fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), maxLines = 1, softWrap = false)
    }
}

/** Round icon button (top bar, "more"). */
@Composable
fun WxIconButton(
    icon: Painter,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(size)
            .wxRing(focused, CircleShape, glow = 14.dp)
            .clip(CircleShape)
            .background(if (focused) Wx.TintStrong else Wx.Tint)
            .wxClickable(onClick) { focused = it },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = Wx.Text, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
fun WxIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 38.dp) =
    WxIconButton(rememberVectorPainter(icon), description, onClick, modifier, size)

/** Small violet tag: container, driver, DirectX, playtime. */
@Composable
fun WxChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = WxType.Caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = Color(0xFFE6E0FF)),
        maxLines = 1,
        modifier = modifier
            .clip(PillShape)
            .background(Wx.Tint)
            .border(1.dp, Wx.TintBorder, PillShape)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/** Steam Deck-style legend: white button glyph + upper-case action. */
@Composable
fun WxHints(vararg hints: Pair<String, String>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        for ((button, label) in hints) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .height(17.dp)
                        .then(if (button.length > 1) Modifier.clip(RoundedCornerShape(6.dp)) else Modifier.width(17.dp).clip(CircleShape))
                        .background(Wx.Text)
                        .padding(horizontal = if (button.length > 1) 5.dp else 0.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(button, style = WxType.Heading.copy(fontSize = 10.sp, color = Wx.Ground))
                }
                Text(
                    label.uppercase(),
                    style = WxType.Heading.copy(fontSize = 11.sp, letterSpacing = 0.8.sp, color = Color(0xFFE0DCF5)),
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

/** Battery and clock, refreshed every 15 s. */
@Composable
fun WxStatusCluster(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var battery by remember { mutableIntStateOf(-1) }
    var charging by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        while (true) {
            now = System.currentTimeMillis()
            battery = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            charging = manager.isCharging
            delay(15_000)
        }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (battery >= 0) {
            val fill = when {
                charging -> Color(0xFF7CE38B)
                battery <= 15 -> Color(0xFFFF6B6B)
                else -> Color(0xFFE0DCF5)
            }
            Canvas(Modifier.size(width = 24.dp, height = 12.dp)) {
                val body = Size(size.width - 3.dp.toPx(), size.height)
                drawRoundRect(Color(0xFFE0DCF5), size = body, cornerRadius = CornerRadius(2.5.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                val inset = 2.5.dp.toPx()
                drawRoundRect(
                    fill,
                    topLeft = Offset(inset, inset),
                    size = Size((body.width - 2 * inset) * battery / 100f, body.height - 2 * inset),
                    cornerRadius = CornerRadius(1.dp.toPx()),
                )
                drawRect(Color(0xFFE0DCF5), topLeft = Offset(body.width, size.height * 0.3f), size = Size(2.dp.toPx(), size.height * 0.4f))
            }
            Text("$battery%", style = WxType.Caption.copy(fontSize = 13.sp, color = Color(0xFFE0DCF5)), modifier = Modifier.padding(start = 6.dp))
        }
        Text(
            DateFormat.getTimeFormat(context).format(Date(now)),
            style = WxType.Heading.copy(fontSize = 15.sp),
            modifier = Modifier.padding(start = 14.dp),
        )
    }
}

@Composable
fun rememberHasGamepad(): Boolean = remember {
    InputDevice.getDeviceIds().any { id ->
        val sources = InputDevice.getDevice(id)?.sources ?: 0
        sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
    }
}

private val imageCache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

/**
 * Decodes an artwork file off the main thread, down-sampled to about [maxWidth] pixels,
 * and keeps it in a small shared cache. Heroes are only decoded for the game on screen.
 */
@Composable
fun rememberFileImage(file: File?, version: Long, maxWidth: Int): ImageBitmap? {
    val key = file?.let { "${it.path}@$version@$maxWidth" }
    val image by produceState(initialValue = key?.let { imageCache.get(it) }, key) {
        if (key == null || file == null) {
            value = null
            return@produceState
        }
        val cached = imageCache.get(key)
        if (cached != null) {
            value = cached
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            decodeSampled(file, maxWidth)?.also { imageCache.put(key, it) }
        }
    }
    return image
}

private fun decodeSampled(file: File, maxWidth: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0) return@runCatching null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()
