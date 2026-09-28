package com.forja.app.core.music

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Iconițele muzicii (viewBox 24, contur rotunjit, ca iconițele Inventarului): se colorează cu tint.
 * Folosite de ecranul Muzică (Inventar), rândul și discul din Antrenament și proba ascunsă.
 */
object MusicIcons {
    val Play = fill("M8 5v14l11-7z")
    val Pause = fill(
        "M7 4h2a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z",
        "M15 4h2a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1h-2a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z"
    )
    val Previous = fill("M19 20L9 12l10-8v16z", "M6 4h.4a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z")
    val Next = fill("M5 4l10 8-10 8V4z", "M17.6 4h.4a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1h-.4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z")

    /** Nota muzicală. */
    val Note = stroke("M9 18V5l11-2v13", "M6 21a3 3 0 1 0 0-6 3 3 0 0 0 0 6z", "M17 19a3 3 0 1 0 0-6 3 3 0 0 0 0 6z")
    /** Nota tăiată: nu a pornit / fără player. */
    val NoteOff = stroke("M9 18V9", "M9 5.5L20 3v10", "M6 21a3 3 0 1 0 0-6 3 3 0 0 0 0 6z", "M3 3l18 18")
    /** Carte deschisă: carte audio, podcast. */
    val Book = stroke(
        "M3 5.5A2.5 2.5 0 0 1 5.5 3H11v17H5.5A2.5 2.5 0 0 0 3 22.5z",
        "M21 5.5A2.5 2.5 0 0 0 18.5 3H13v17h5.5a2.5 2.5 0 0 1 2.5 2.5z",
        width = 1.8f
    )
    /** Ecran cu play: video. */
    val Video = stroke("M4 5h16a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z", "M10 9.5v5l4.5-2.5z", width = 1.8f)
    /** Inimă: Melodii apreciate. */
    val Heart = fill("M12 20.5s-7.5-4.6-9.2-9.3C1.6 7.8 3.8 4.5 7.3 4.5c2 0 3.6 1.1 4.7 2.7 1.1-1.6 2.7-2.7 4.7-2.7 3.5 0 5.7 3.3 4.5 6.7-1.7 4.7-9.2 9.3-9.2 9.3z")
    /** Săgeată în afară: deschide playerul. */
    val Open = stroke("M14 4h6v6", "M20 4l-9 9", "M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5")
    val ChevronRight = stroke("M9 6l6 6-6 6")

    /** Iconița felului unei sesiuni (rândul „Reia”). */
    fun of(kind: MediaKind): ImageVector = when (kind) {
        MediaKind.SPOKEN -> Book
        MediaKind.VIDEO -> Video
        else -> Note
    }

    private fun nodes(d: String) = PathParser().parsePathString(d).toNodes()

    private fun stroke(vararg d: String, width: Float = 2f): ImageVector = mixed(strokes = d.toList(), width = width)

    private fun fill(vararg d: String): ImageVector = mixed(fills = d.toList())

    private fun mixed(fills: List<String> = emptyList(), strokes: List<String> = emptyList(), width: Float = 2f): ImageVector {
        val b = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
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
}
