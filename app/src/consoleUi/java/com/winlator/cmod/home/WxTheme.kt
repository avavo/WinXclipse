package com.winlator.cmod.home

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.winlator.cmod.R

/**
 * WinXclipse console palette: night-blue ground with a violet cast, and the brand's
 * blue-to-purple gradient for anything selected, focused or primary.
 */
object Wx {
    val Ground = Color(0xFF0A0817)
    val Rail = Color(0xFF0F0C21)
    val Surface = Color(0xFF141128)
    val SurfaceHigh = Color(0xFF1C1838)
    val Text = Color(0xFFF4F2FF)
    val TextSecondary = Color(0xFFA9A3C9)
    val TextMuted = Color(0xFF9C96BD)
    val Lilac = Color(0xFFB9A8FF)
    val Ring = Color(0xFFA897FF)
    val Blue = Color(0xFF3D8BFF)
    val Violet = Color(0xFF7B5CFF)
    val Purple = Color(0xFFB44CFF)
    val Tint = Color(0x337B5CFF)
    val TintStrong = Color(0x477B5CFF)
    val TintBorder = Color(0x59A08CFF)
    val Danger = Color(0xFFFF8FA3)

    /** 135° blue → violet → purple. */
    val Gradient: Brush = Brush.linearGradient(listOf(Blue, Violet, Purple))
    val SoftGradient: Brush = Brush.linearGradient(listOf(Blue.copy(alpha = 0.3f), Purple.copy(alpha = 0.3f)))
}

/** Titles, numbers and labels. */
val Exo2 = FontFamily(
    Font(R.font.exo2_medium, FontWeight.Medium),
    Font(R.font.exo2_semibold, FontWeight.SemiBold),
    Font(R.font.exo2_bold, FontWeight.Bold),
    Font(R.font.exo2_extrabold, FontWeight.ExtraBold),
)

/** Body text: readable at small sizes. */
val Barlow = FontFamily(
    Font(R.font.barlow_regular, FontWeight.Normal),
    Font(R.font.barlow_medium, FontWeight.Medium),
    Font(R.font.barlow_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_bold, FontWeight.Bold),
)

object WxType {
    val Display = TextStyle(fontFamily = Exo2, fontWeight = FontWeight.ExtraBold, fontSize = 36.sp, lineHeight = 38.sp, color = Wx.Text)
    val Title = TextStyle(fontFamily = Exo2, fontWeight = FontWeight.Bold, fontSize = 21.sp, color = Wx.Text)
    val Heading = TextStyle(fontFamily = Exo2, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Wx.Text)
    val Eyebrow = TextStyle(fontFamily = Exo2, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.6.sp, color = Wx.Lilac)
    val Button = TextStyle(fontFamily = Exo2, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Wx.Text)
    val Body = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Normal, fontSize = 14.sp, color = Wx.Text)
    val Label = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Wx.Text)
    val Caption = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Normal, fontSize = 12.sp, color = Wx.TextSecondary)
}

@Composable
fun WxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Wx.Violet,
            secondary = Wx.Blue,
            background = Wx.Ground,
            surface = Wx.SurfaceHigh,
            onSurface = Wx.Text,
            onBackground = Wx.Text,
            surfaceContainer = Wx.SurfaceHigh,
        ),
    ) {
        CompositionLocalProvider(LocalTextStyle provides WxType.Body, content = content)
    }
}
