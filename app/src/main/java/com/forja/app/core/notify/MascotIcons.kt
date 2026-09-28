package com.forja.app.core.notify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.forja.app.core.designsystem.components.MascotHat
import com.forja.app.core.designsystem.components.MascotState
import com.forja.app.core.designsystem.components.drawMascotStill
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Pictograma mare a notificărilor: mascota cu cască, în poza mesajului (trucul Duo — continuitatea).
 * O notificare nu poate desena Compose, deci fiecare poză se randează o dată ca PNG, în procesul aplicației, la
 * deschidere (corectura 11) — nu în lucrător. Fără PNG (încă nedeschisă după update) notificarea pleacă fără pictogramă.
 */
object MascotIcons {
    private const val VERSION = 1
    private const val PX = 192
    private val cache = ConcurrentHashMap<NudgePose, Bitmap>()

    private fun file(c: Context, pose: NudgePose) = File(c.filesDir, "notify/casca_${pose.name.lowercase()}_v$VERSION.png")

    /** Randează pozele care lipsesc (apelat din fundal, la deschiderea aplicației). */
    fun ensure(c: Context) {
        for (pose in NudgePose.entries) {
            val f = file(c, pose)
            if (f.exists() && f.length() > 0) continue
            try {
                f.parentFile?.mkdirs()
                val bmp = render(pose, PX)
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                tmp.renameTo(f)
            } catch (_: Throwable) { }
        }
    }

    /** Un cadru static al mascotei, desenat direct într-un bitmap (fără compoziție). */
    fun render(pose: NudgePose, px: Int): Bitmap {
        val image = ImageBitmap(px, px, ImageBitmapConfig.Argb8888)
        val canvas = androidx.compose.ui.graphics.Canvas(image)
        val state = MascotState.entries.firstOrNull { it.name == pose.name } ?: MascotState.Talking
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(px.toFloat(), px.toFloat())) {
            drawMascotStill(state, MascotHat.Helmet)
        }
        return image.asAndroidBitmap()
    }

    /** PNG-ul pozei, sau null dacă nu a fost încă randat. */
    fun bitmap(c: Context, pose: NudgePose): Bitmap? {
        cache[pose]?.let { return it }
        val f = file(c, pose)
        if (!f.exists()) return null
        return try { BitmapFactory.decodeFile(f.absolutePath)?.also { cache[pose] = it } } catch (_: Throwable) { null }
    }
}
