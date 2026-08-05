package com.forja.app.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.forja.app.core.designsystem.Accent2
import com.forja.app.core.designsystem.Surface0

/**
 * Fundalul „Topografic" din Design Studio: linii de nivel + inele concentrice
 * și, opțional, decor discret (brazi, munți, frunză) pe margini — desenat local,
 * fără imagini, în accentul Camuflaj la opacitate mică.
 */
fun Modifier.topoBackground(decor: Boolean = true, intensity: Float = 1f): Modifier =
    this.background(Surface0).drawBehind {
        val line = Accent2.copy(alpha = 0.07f * intensity)
        val deco = Accent2.copy(alpha = 0.16f * intensity)
        val w = size.width
        val h = size.height
        val sw = 1.4f * density

        // Linii de nivel — curbe orizontale ondulate
        var y = h * 0.06f
        var i = 0
        while (y < h) {
            val amp = h * (0.014f + 0.008f * ((i % 3)))
            val p = Path()
            p.moveTo(-w * 0.05f, y)
            p.cubicTo(w * 0.20f, y - amp, w * 0.34f, y + amp, w * 0.52f, y)
            p.cubicTo(w * 0.70f, y - amp, w * 0.84f, y + amp, w * 1.05f, y)
            drawPath(p, line, style = Stroke(width = sw))
            y += h * 0.085f
            i++
        }

        // Inele concentrice (curbe de nivel închise) — sus-dreapta și mijloc-dreapta
        rings(Offset(w * 0.86f, h * 0.10f), w * 0.11f, line, sw)
        rings(Offset(w * 0.90f, h * 0.55f), w * 0.09f, line, sw)

        if (decor) {
            // Brazi — stânga-sus
            pine(Offset(w * 0.10f, h * 0.055f), w * 0.055f, deco)
            pine(Offset(w * 0.17f, h * 0.115f), w * 0.042f, deco)
            // Munți — dreapta-sus și stânga-jos
            mountain(Offset(w * 0.72f, h * 0.135f), w * 0.10f, deco, sw)
            mountain(Offset(w * 0.16f, h * 0.90f), w * 0.09f, deco, sw)
            // Frunză — dreapta-jos
            leaf(Offset(w * 0.82f, h * 0.80f), w * 0.055f, deco, sw)
        }
    }

private fun DrawScope.rings(c: Offset, r: Float, color: androidx.compose.ui.graphics.Color, sw: Float) {
    drawCircle(color, r, c, style = Stroke(sw))
    drawCircle(color, r * 0.62f, c, style = Stroke(sw))
    drawCircle(color, r * 0.28f, c, style = Stroke(sw))
}

/** Brad stilizat: două triunghiuri suprapuse + trunchi. */
private fun DrawScope.pine(base: Offset, s: Float, color: androidx.compose.ui.graphics.Color) {
    val p = Path()
    // triunghiul de sus
    p.moveTo(base.x, base.y)
    p.lineTo(base.x - s * 0.55f, base.y + s * 0.75f)
    p.lineTo(base.x + s * 0.55f, base.y + s * 0.75f)
    p.close()
    // triunghiul de jos, mai lat
    p.moveTo(base.x, base.y + s * 0.35f)
    p.lineTo(base.x - s * 0.75f, base.y + s * 1.25f)
    p.lineTo(base.x + s * 0.75f, base.y + s * 1.25f)
    p.close()
    drawPath(p, color)
    // trunchi
    drawRect(
        color,
        topLeft = Offset(base.x - s * 0.09f, base.y + s * 1.25f),
        size = androidx.compose.ui.geometry.Size(s * 0.18f, s * 0.28f)
    )
}

/** Munte în contur: două vârfuri. */
private fun DrawScope.mountain(base: Offset, s: Float, color: androidx.compose.ui.graphics.Color, sw: Float) {
    val p = Path()
    p.moveTo(base.x - s, base.y + s * 0.62f)
    p.lineTo(base.x - s * 0.28f, base.y - s * 0.30f)
    p.lineTo(base.x + s * 0.10f, base.y + s * 0.18f)
    p.lineTo(base.x + s * 0.42f, base.y - s * 0.10f)
    p.lineTo(base.x + s, base.y + s * 0.62f)
    drawPath(p, color, style = Stroke(width = sw * 1.6f))
}

/** Frunză plină cu nervură. */
private fun DrawScope.leaf(c: Offset, s: Float, color: androidx.compose.ui.graphics.Color, sw: Float) {
    val p = Path()
    p.moveTo(c.x, c.y - s)
    p.cubicTo(c.x + s * 1.1f, c.y - s * 0.55f, c.x + s * 0.7f, c.y + s * 0.8f, c.x, c.y + s)
    p.cubicTo(c.x - s * 0.7f, c.y + s * 0.8f, c.x - s * 1.1f, c.y - s * 0.55f, c.x, c.y - s)
    p.close()
    drawPath(p, color)
    drawLine(
        Surface0.copy(alpha = 0.55f),
        Offset(c.x, c.y - s * 0.8f), Offset(c.x, c.y + s * 0.85f),
        strokeWidth = sw
    )
}
