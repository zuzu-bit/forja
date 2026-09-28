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
 * Marker „bulă foto" din handoff: cerc 52dp cu inel și vârf-pin, desenat pe Canvas — fotografia prietenului
 * (users/{uid}.photoUrl) când există, altfel inițiale pe fundal închis. Pe MapLibre imaginile intră ca `Bitmap`
 * în stil (`friendBitmap`/`placeBitmap`, cache-uite de `MapIcons` per uid/stare/selectat); variantele `Drawable`
 * rămân pentru cod vechi.
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
        val d = BitmapDrawable(context.resources, friendBitmap(context, name, null, ghost, me, state, family, false))
        synchronized(cache) { cache[key] = d }
        return d
    }

    /**
     * Bitmapul avatarului (52 dp + vârf 10 dp): foto rotundă sau inițiale; inel după stare; selectat = inel amber mai gros;
     * `music` = insigna cu nota muzicală (jos-dreapta) cât prietenul ascultă ceva (nowPlaying proaspăt).
     */
    fun friendBitmap(
        context: Context,
        name: String,
        photo: Bitmap?,
        ghost: Boolean,
        me: Boolean,
        state: String,
        family: Boolean,
        selected: Boolean,
        music: Boolean = false
    ): Bitmap = drawFriend(context, name, photo, ghost, me, state, family, selected, music)

    /** Bitmapul pinului de loc (34 dp + vârf 8 dp); selectat = puțin mai mare și cu inel mai gros. */
    fun placeBitmap(context: Context, stars: Int, mine: Boolean, selected: Boolean): Bitmap =
        drawPlace(context, stars.coerceIn(0, 5), mine, selected)

    /** Conul de direcție (56 × 44 dp): vârful jos-centru, se deschide în sus; rotit de hartă după bearing. */
    fun coneBitmap(context: Context): Bitmap {
        val density = context.resources.displayMetrics.density
        val w = (56 * density).toInt()
        val h = (44 * density).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.LinearGradient(
                0f, h.toFloat(), 0f, 0f,
                Color.argb(120, 111, 133, 90), Color.argb(0, 111, 133, 90),
                android.graphics.Shader.TileMode.CLAMP
            )
        }
        val path = Path().apply {
            moveTo(w / 2f, h.toFloat())
            lineTo(0f, 0f)
            lineTo(w.toFloat(), 0f)
            close()
        }
        c.drawPath(path, paint)
        return bmp
    }

    /**
     * Pin de LOC: inel amber pentru locurile mele, albastru (SleepRem) pentru recomandările prietenilor,
     * cu numărul de stele scris mic în interior.
     */
    fun placeMarker(context: Context, stars: Int, mine: Boolean): Drawable {
        val s = stars.coerceIn(0, 5)
        val key = "p|$s|$mine"
        synchronized(cache) { cache[key]?.let { return it } }
        val d = BitmapDrawable(context.resources, drawPlace(context, s, mine, false))
        synchronized(cache) { cache[key] = d }
        return d
    }

    private fun drawFriend(
        context: Context, name: String, photo: Bitmap?, ghost: Boolean, me: Boolean, state: String, family: Boolean,
        selected: Boolean, music: Boolean = false
    ): Bitmap {
        val density = context.resources.displayMetrics.density
        val size = (52 * density).toInt()
        val tip = (10 * density).toInt()
        val bmp = Bitmap.createBitmap(size, size + tip, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f
        val ringW = if (selected) 3.5f * density else 3 * density
        val r = size / 2f - ringW

        // Transparența fantomei o dă stratul (icon-opacity 0,55) — bitmapul rămâne întreg, ca să se poată cache-ui.
        val alpha = 255

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

        // Inel: selectat amber; fantomă/somn albastru; eu olive închis; prieten olive.
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = ringW
            color = when {
                selected -> Color.parseColor("#F3B952")
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
        val inner = r - 2 * density
        c.drawCircle(cx, cy, inner, fill)

        if (photo != null) {
            // Fotografia, decupată rotund, exact în interiorul inelului.
            val save = c.save()
            val clip = Path().apply { addCircle(cx, cy, inner, Path.Direction.CW) }
            c.clipPath(clip)
            val dst = android.graphics.RectF(cx - inner, cy - inner, cx + inner, cy + inner)
            c.drawBitmap(photo, null, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            c.restoreToCount(save)
        } else {
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
        }

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

        // Insigna „♪” — ascultă muzică acum (jos-dreapta, în afara vârfului; nu atinge punctul de stare sau inima).
        if (music) drawMusicBadge(c, size - 10 * density, size - 11 * density, density)

        return bmp
    }

    /** Disc jar (#FFB35C) cu contur închis și o notă optime desenată vectorial (cap oval, tijă, steag). */
    private fun drawMusicBadge(c: Canvas, bx: Float, by: Float, density: Float) {
        val br = 7.5f * density
        c.drawCircle(bx, by, br + 1.5f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0A0A0B") })
        c.drawCircle(bx, by, br, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFB35C") })
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0A0A0B") }
        // Capul notei: oval înclinat, jos-stânga.
        val hx = bx - 1.3f * density
        val hy = by + 2.3f * density
        val save = c.save()
        c.rotate(-22f, hx, hy)
        c.drawOval(android.graphics.RectF(hx - 2.3f * density, hy - 1.65f * density, hx + 2.3f * density, hy + 1.65f * density), ink)
        c.restoreToCount(save)
        // Tija și steagul.
        val stem = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0A0A0B")
            style = Paint.Style.STROKE
            strokeWidth = 1.3f * density
            strokeCap = Paint.Cap.ROUND
        }
        val sx = hx + 2.0f * density
        val top = by - 4.4f * density
        c.drawLine(sx, hy - 0.4f * density, sx, top, stem)
        val flag = Path().apply {
            moveTo(sx, top)
            quadTo(sx + 3.4f * density, top + 1.2f * density, sx + 2.6f * density, top + 4.0f * density)
        }
        c.drawPath(flag, stem)
    }

    private fun drawPlace(context: Context, stars: Int, mine: Boolean, selected: Boolean): Bitmap {
        val density = context.resources.displayMetrics.density
        val size = ((if (selected) 38 else 34) * density).toInt()
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
            strokeWidth = (if (selected) 3.2f else 2.5f) * density
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

        return bmp
    }
}
