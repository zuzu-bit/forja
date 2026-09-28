package com.forja.app.feature.inventory

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/*
 * Iconițele Inventarului, desenate din căile SVG ale prototipului (viewBox 24, contur rotunjit), ca să arate
 * exact ca acolo. Se colorează cu tint (Icon(…, tint = …)); grosimea conturului se scalează ca în SVG.
 */
internal object InvIcons {
    val Back = stroke("M15 18l-6-6 6-6")
    val Close = stroke("M6 6l12 12M18 6L6 18")
    val ChevronDown = stroke("M6 9l6 6 6-6", width = 2.4f)
    val ChevronRight = stroke("M9 6l6 6-6 6")
    val Photos = stroke(
        "M8.5 3h11a1.5 1.5 0 0 1 1.5 1.5v11a1.5 1.5 0 0 1-1.5 1.5h-11a1.5 1.5 0 0 1-1.5-1.5v-11a1.5 1.5 0 0 1 1.5-1.5z",
        "M3 7v12a2 2 0 0 0 2 2h12",
        "M10.4 8a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0-3.2 0",
        "M21 13l-4-4-6 7",
        width = 1.8f
    )
    val Folder = stroke("M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z", "M8 13h8", width = 1.8f)
    val Search = stroke("M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM21 21l-4.3-4.3")
    val Layers = stroke("M12 3l9 5-9 5-9-5 9-5zM3 13l9 5 9-5")
    val Spark = stroke("M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z")
    val Check = stroke("M5 12l4 4 10-10")
    val CheckBold = stroke("M5 12l4 4 10-10", width = 3f)
    val Play = fill("M8 5v14l11-7z")
    val Pause = fill(
        "M7 4h2a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z",
        "M15 4h2a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1h-2a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z"
    )
    val Stop = fill("M8 6h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z")
    val Skip = mixed(fills = listOf("M5 4l10 8-10 8V4z"), strokes = listOf("M19 5v14"))
    val Previous = fill("M19 20L9 12l10-8v16z", "M6 4h.4a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z")
    val Next = fill("M5 4l10 8-10 8V4z", "M17.6 4h.4a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1h-.4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z")
    val Trash = stroke("M3 6h18", "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6", "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2")
    val TrashLined = stroke(
        "M3 6h18", "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6", "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2", "M10 11v6", "M14 11v6"
    )
    val Globe = stroke(
        "M2.5 12a9.5 9.5 0 1 0 19 0a9.5 9.5 0 1 0-19 0",
        "M2.5 12h19",
        "M12 2.5a14.5 14.5 0 0 1 3.8 9.5 14.5 14.5 0 0 1-3.8 9.5 14.5 14.5 0 0 1-3.8-9.5A14.5 14.5 0 0 1 12 2.5z",
        width = 1.9f
    )
    val SelectAll = stroke("M6 3h12a3 3 0 0 1 3 3v12a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V6a3 3 0 0 1 3-3z", "M8 12l3 3 5-6")
    val Pencil = stroke("M4 20h4L19 9l-4-4L4 16v4z", "M13.5 6.5l4 4")
    val Plus = stroke("M12 5v14M5 12h14", width = 2.4f)
    val Restore = stroke("M3 12a9 9 0 1 0 3-6.7", "M3 4v5h5")
    /** Nota muzicală (două capete legate), pentru „muzica s-a oprit” pe Gata. */
    val Note = stroke("M9 18V5l12-2v13", "M3 18a3 3 0 1 0 6 0a3 3 0 1 0-6 0", "M15 16a3 3 0 1 0 6 0a3 3 0 1 0-6 0", width = 1.8f)
    val StopAtEnd = stroke("M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM9 9h6v6H9z")
    val MapPin = stroke("M12 21s-7-6.2-7-11.5A7 7 0 0 1 19 9.5C19 14.8 12 21 12 21zM12 7.2a2.3 2.3 0 1 0 0 4.6 2.3 2.3 0 0 0 0-4.6z")
    val Laptop = stroke("M5 5h14a1 1 0 0 1 1 1v10H4V6a1 1 0 0 1 1-1z", "M2 19h20")
    val Merge = stroke("M6 3v5l6 6 6-6V3", "M12 14v7")
    val Split = stroke("M12 3v7l-6 5v6", "M12 10l6 5v6")
    val Camera = stroke(
        "M4 8.5a2 2 0 0 1 2-2h2.2l1.6-2.5h4.4l1.6 2.5H18a2 2 0 0 1 2 2V18a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2z",
        "M8.6 13a3.4 3.4 0 1 0 6.8 0a3.4 3.4 0 1 0-6.8 0",
        width = 1.8f
    )
    val Image = stroke("M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z", "M4 16l5-5 4 4 3-3 4 4", "M15 8.5h.01", width = 1.8f)

    // Motivele din „De aruncat” (DeAruncat.dc.html)
    val ReasonAll = stroke("M4 5h16M4 12h16M4 19h16")
    val ReasonCopy = stroke("M9 9h11v11H9z M5 15V4h11")
    val ReasonBlur = stroke("M12 3s6 6.5 6 11a6 6 0 0 1-12 0c0-4.5 6-11 6-11z")
    val ReasonScreen = stroke("M8 3h8a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z M11 18h2")
    val ReasonTiny = stroke("M9 9h6v6H9z M4 4l3 3 M20 4l-3 3 M4 20l3-3 M20 20l-3-3")
    val ReasonAi = Spark
    val ReasonTemp = stroke("M7 3h7l4 4v14H7z", "M14 3v4h4", "M10 13h4M10 17h4")
}

private const val VIEWPORT = 24f

private fun nodes(d: String) = PathParser().parsePathString(d).toNodes()

private fun stroke(vararg d: String, width: Float = 2f): ImageVector = mixed(strokes = d.toList(), width = width)

private fun fill(vararg d: String): ImageVector = mixed(fills = d.toList())

private fun mixed(fills: List<String> = emptyList(), strokes: List<String> = emptyList(), width: Float = 2f): ImageVector {
    val b = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = VIEWPORT, viewportHeight = VIEWPORT)
    val ink = SolidColor(Color.Black)
    for (d in fills) b.addPath(pathData = nodes(d), fill = ink)
    for (d in strokes) {
        b.addPath(
            pathData = nodes(d), fill = null, stroke = ink, strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
        )
    }
    return b.build()
}
