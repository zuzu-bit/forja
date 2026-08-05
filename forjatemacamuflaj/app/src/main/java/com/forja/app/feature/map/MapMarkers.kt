package com.forja.app.feature.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/**
 * Marker „bulă foto" din handoff: cerc 44dp cu inel amber și vârf-pin,
 * desenat pe Canvas (inițiale pe fundal închis — onest, fără poze false).
 */
object MapMarkers {

    fun friendMarker(
        context: Context,
        name: String,
        ghost: Boolean = false,
        me: Boolean = false,
        state: String = "idle"
    ): Drawable {
        val density = context.resources.displayMetrics.density
        val size = (52 * density).toInt()
        val tip = (10 * density).toInt()
        val bmp = Bitmap.createBitmap(size, size + tip, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f
        val r = size / 2f - 3 * density

        val alpha = if (ghost) 90 else 255

        // Vârf-pin
        val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (me) Color.parseColor("#4A5D3A") else Color.parseColor("#6F855A")
            this.alpha = alpha
        }
        val path = Path().apply {
            moveTo(cx - 6 * density, size - 4 * density)
            lineTo(cx + 6 * density, size - 4 * density)
            lineTo(cx, size + tip - 2 * density)
            close()
        }
        c.drawPath(path, tipPaint)

        // Inel amber
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3 * density
            color = when {
                ghost -> Color.parseColor("#9DBFE8")
                state == "sleep" -> Color.parseColor("#9DBFE8")
                me -> Color.parseColor("#4A5D3A")
                else -> Color.parseColor("#6F855A")
            }
            this.alpha = alpha
        }
        c.drawCircle(cx, cy, r, ring)

        // Interior
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A1A1E")
            this.alpha = alpha
        }
        c.drawCircle(cx, cy, r - 2 * density, fill)

        // Inițiale
        val initials = name.trim().split(Regex("\\s+")).take(2)
            .mapNotNull { it.firstOrNull()?.uppercase() }.joinToString("")
            .ifEmpty { "?" }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F4F2EE")
            this.alpha = alpha
            textSize = 15 * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val ty = cy - (text.descent() + text.ascent()) / 2
        c.drawText(initials, cx, ty, text)

        // Punct de stare (verde = în mișcare, albastru = doarme)
        if (!ghost && state in setOf("run", "walk", "ride", "sleep")) {
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (state == "sleep") Color.parseColor("#9DBFE8") else Color.parseColor("#2FBE71")
            }
            c.drawCircle(size - 8 * density, 8 * density, 5 * density, dot)
            val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2 * density
                color = Color.parseColor("#0A0A0B")
            }
            c.drawCircle(size - 8 * density, 8 * density, 5 * density, dotRing)
        }

        return BitmapDrawable(context.resources, bmp)
    }
}
