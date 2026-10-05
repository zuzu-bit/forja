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
    private const val VERSION = 2
    private const val PX = 192
    private val cache = ConcurrentHashMap<String, Bitmap>()

    /**
     * Semnătura ținutei purtate (5.0): pozele se randează pe ținută, ca notificarea să arate Casca așa cum e acum
     * îmbrăcată; la o ținută nouă, fișierele vechi se șterg la următoarea deschidere.
     */
    private fun signature(): String {
        val o = com.forja.app.core.soldier.SoldierStore.outfit.value
        val key = listOf(o.head, o.eyes, o.torso, o.belt, o.feet, o.back, o.chest, o.rank.toString()).joinToString("|")
        return key.hashCode().toUInt().toString(36)
    }
    private fun dir(c: Context) = File(c.filesDir, "notify")
    private fun file(c: Context, pose: NudgePose, sig: String = signature()) = File(dir(c), "casca_${pose.name.lowercase()}_v${VERSION}_$sig.png")

    /** Randează pozele care lipsesc pentru ținuta de acum (apelat din fundal, la deschiderea aplicației) și curăță restul. */
    fun ensure(c: Context) {
        val sig = signature()
        for (pose in NudgePose.entries) {
            val f = file(c, pose, sig)
            if (f.exists() && f.length() > 0) continue
            try {
                f.parentFile?.mkdirs()
                val bmp = render(pose, PX)
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                tmp.renameTo(f)
            } catch (_: Throwable) { }
        }
        // Pozele altor ținute (sau ale versiunilor vechi) nu mai folosesc nimănui.
        try {
            dir(c).listFiles()?.forEach { f ->
                if (f.name.startsWith("casca_") && !f.name.contains("_v${VERSION}_$sig.")) f.delete()
            }
        } catch (_: Throwable) { }
        cache.keys.removeAll { !it.endsWith(":$sig") }
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

    /** PNG-ul pozei pentru ținuta de acum, sau, până la următoarea deschidere, ultima poză randată; null dacă nu există. */
    fun bitmap(c: Context, pose: NudgePose): Bitmap? {
        val sig = signature()
        cache["${pose.name}:$sig"]?.let { return it }
        val f = file(c, pose, sig).takeIf { it.exists() }
            ?: dir(c).listFiles()?.filter { it.name.startsWith("casca_${pose.name.lowercase()}_v") }?.maxByOrNull { it.lastModified() }
            ?: return null
        return try { BitmapFactory.decodeFile(f.absolutePath)?.also { if (f.name.endsWith("_$sig.png")) cache["${pose.name}:$sig"] = it } } catch (_: Throwable) { null }
    }
}
