package com.winlator.cmod.home

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Stroke icons from the design that material-icons-core does not have. Tinted by Icon(). */
object WxIcons {
    private fun stroke(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
            .build()

    /** Container. */
    val Cube: ImageVector by lazy {
        stroke("cube") {
            moveTo(3f, 7f); lineTo(12f, 3f); lineTo(21f, 7f); lineTo(12f, 11f); close()
            moveTo(3f, 7f); lineTo(3f, 17f); lineTo(12f, 21f); lineTo(21f, 17f); lineTo(21f, 7f)
            moveTo(12f, 11f); lineTo(12f, 21f)
        }
    }

    /** Library. */
    val Grid: ImageVector by lazy {
        stroke("grid") {
            for ((x, y) in listOf(3.5f to 3.5f, 13.5f to 3.5f, 3.5f to 13.5f, 13.5f to 13.5f)) {
                moveTo(x + 1.5f, y); lineTo(x + 5.5f, y); arcToRelative(1.5f, 1.5f, 0f, false, true, 1.5f, 1.5f)
                lineTo(x + 7f, y + 5.5f); arcToRelative(1.5f, 1.5f, 0f, false, true, -1.5f, 1.5f)
                lineTo(x + 1.5f, y + 7f); arcToRelative(1.5f, 1.5f, 0f, false, true, -1.5f, -1.5f)
                lineTo(x, y + 1.5f); arcToRelative(1.5f, 1.5f, 0f, false, true, 1.5f, -1.5f); close()
            }
        }
    }

    /** Details / settings of one game. */
    val Sliders: ImageVector by lazy {
        stroke("sliders") {
            moveTo(4f, 6f); lineTo(13f, 6f); moveTo(17f, 6f); lineTo(20f, 6f)
            moveTo(4f, 12f); lineTo(7f, 12f); moveTo(11f, 12f); lineTo(20f, 12f)
            moveTo(4f, 18f); lineTo(15f, 18f); moveTo(19f, 18f); lineTo(20f, 18f)
            moveTo(17f, 6f); arcToRelative(2f, 2f, 0f, true, true, -4f, 0f); arcToRelative(2f, 2f, 0f, true, true, 4f, 0f)
            moveTo(11f, 12f); arcToRelative(2f, 2f, 0f, true, true, -4f, 0f); arcToRelative(2f, 2f, 0f, true, true, 4f, 0f)
            moveTo(19f, 18f); arcToRelative(2f, 2f, 0f, true, true, -4f, 0f); arcToRelative(2f, 2f, 0f, true, true, 4f, 0f)
        }
    }

    /** Back chevron. */
    val Back: ImageVector by lazy {
        stroke("back") { moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f) }
    }

    /** Same stroke style from an SVG path string. Arc flags must be space-separated ("0 0 1", not "01"). */
    private fun svg(name: String, d: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()

    val Home: ImageVector by lazy { svg("home", "M3 11l9-7 9 7M5 10v10h14V10") }
    val Pad: ImageVector by lazy {
        svg("pad", "M6 9h12a4 4 0 0 1 4 4v1a3 3 0 0 1 -5.2 2L15 14H9l-1.8 2A3 3 0 0 1 2 14v-1a4 4 0 0 1 4 -4zM8 11v3M6.5 12.5h3M16 12h.01M18 13.5h.01")
    }
    val Screen: ImageVector by lazy { svg("screen", "M4 5h16v11H4zM9 20h6M12 16v4") }
    val Chip: ImageVector by lazy {
        svg("chip", "M9 3v3M15 3v3M9 18v3M15 18v3M3 9h3M3 15h3M18 9h3M18 15h3M6 6h12v12H6zM10 10h4v4h-4z")
    }
    val Bug: ImageVector by lazy {
        svg("bug", "M9 9h6v8a3 3 0 0 1 -6 0zM12 4v5M5 13h4M15 13h4M6 8l3 2M18 8l-3 2M6 19l3-2M18 19l-3-2")
    }
    val Storage: ImageVector by lazy {
        svg("storage", "M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3zM4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3")
    }
    val Info: ImageVector by lazy { svg("info", "M12 21a9 9 0 1 0 0 -18a9 9 0 0 0 0 18zM12 11v5M12 8h.01") }
    val Speaker: ImageVector by lazy { svg("speaker", "M4 9h4l5-4v14l-5-4H4zM17 9a4 4 0 0 1 0 6M19.5 6.5a8 8 0 0 1 0 11") }
    val Layers3d: ImageVector by lazy { svg("layers3d", "M12 3l8 4.5v9L12 21l-8-4.5v-9zM12 12l8-4.5M12 12v9M12 12L4 7.5") }
    val Lines: ImageVector by lazy { svg("lines", "M4 6h16M4 12h10M4 18h7M17 15l3 3-3 3") }
    val ChevronLeft: ImageVector by lazy { svg("chevronLeft", "M15 5l-7 7 7 7") }
    val ChevronRight: ImageVector by lazy { svg("chevronRight", "M9 5l7 7-7 7") }
    val Download: ImageVector by lazy { svg("download", "M12 4v11M7 10l5 5 5-5M5 20h14") }
}
