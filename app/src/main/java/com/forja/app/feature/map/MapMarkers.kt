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
 * Iconițele sunt cache-uite per (nume, stare, eu, fantomă, familie) — nu re-rasterizăm la fiecare recompoziție.
 */
object MapMarkers {

    private val cache = HashMap<String, Drawable>()

    /** Golește cache-ul (ex. la schimbarea densității) — rar necesar. */
    fun clearCache() = synchronized(cache) { cache.clear() }

    fun friendMarker(
        context: Context,
        name: String,
        ghost: Boolean = false,
        me: Boolean = false,
        state: String = "idle",
        family: Boolean = false
    ): Drawable {
        val key = "f|$name|$state|$me|$ghost|$family"
        synchronized(cache) { cache[key]?.let { return it } }
        val d = drawFriend(context, name, ghost, me, state, family)
        synchronized(cache) { cache[key] = d }
        return d
    }

    /**
     * Pin de LOC: inel amber pentru locurile mele, albastru (SleepRem) pentru recomandările prietenilor,
     * cu numărul de stele scris mic în interior.
     */
    fun placeMarker(context: Context, stars: Int, mine: Boolean): Drawable {
        val s = stars.coerceIn(0, 5)
        val key = "p|$s|$mine"
        synchronized(cache) { cache[key]?.let { return it } }
        val d = drawPlace(context, s, mine)
        synchronized(cache) { cache[key] = d }
        return d
    }

    private fun drawFriend(
        context: Context, name: String, ghost: Boolean, me: Boolean, state: String, family: Boolean
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

        // Inel
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

        // Insigna „♥” — familie: te vede și în fantomă (și tu pe el).
        if (family) {
            val br = 7 * density
            val bx = 9 * density
            val by = 9 * density
            val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0A0A0B") }
            c.drawCircle(bx, by, br + 1.5f * density, badge)
            val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#9DBFE8") }
            c.drawCircle(bx, by, br, badgeFill)
            val heart = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#0A0A0B")
                textSize = 9 * density
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val hy = by - (heart.descent() + heart.ascent()) / 2
            c.drawText("♥", bx, hy, heart)
        }

        return BitmapDrawable(context.resources, bmp)
    }

    private fun drawPlace(context: Context, stars: Int, mine: Boolean): Drawable {
        val density = context.resources.displayMetrics.density
        val size = (34 * density).toInt()
        val tip = (8 * density).toInt()
        val bmp = Bitmap.createBitmap(size, size + tip, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f
        val r = size / 2f - 2.5f * density
        val ringColor = if (mine) Color.parseColor("#F3B952") else Color.parseColor("#9DBFE8")

        val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ringColor }
        val path = Path().apply {
            moveTo(cx - 5 * density, size - 4 * density)
            lineTo(cx + 5 * density, size - 4 * density)
            lineTo(cx, size + tip - 1.5f * density)
            close()
        }
        c.drawPath(path, tipPaint)

        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * density
            color = ringColor
        }
        c.drawCircle(cx, cy, r, ring)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1A1A1E") }
        c.drawCircle(cx, cy, r - 1.5f * density, fill)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ringColor
            textSize = (if (stars > 0) 11 else 13) * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val label = if (stars > 0) "★$stars" else "●"
        val ty = cy - (text.descent() + text.ascent()) / 2
        c.drawText(label, cx, ty, text)

        return BitmapDrawable(context.resources, bmp)
    }
}
