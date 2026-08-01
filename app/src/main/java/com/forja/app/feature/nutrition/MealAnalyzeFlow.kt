package com.forja.app.feature.nutrition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.forja.app.ForjaApp
import com.forja.app.core.data.db.MealEntity
import com.forja.app.core.network.ForjaApi
import com.forja.app.core.network.GeminiFood
import com.forja.app.core.network.MealAnalysis
import com.forja.app.core.util.Fmt
import kotlinx.coroutines.flow.first
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** Rezultatul analizei unei poze cu mâncare — indiferent de sursă (cameră/galerie). */
sealed class AnalyzeOutcome {
    data class Ok(val analysis: MealAnalysis) : AnalyzeOutcome()
    data class Fail(val message: String) : AnalyzeOutcome()
}

object MealAnalyze {

    /** Server (cheia companiei) sau, ca rezervă, cheia proprie — aceeași logică peste tot. */
    suspend fun analyzeJpeg(app: ForjaApp, bytes: ByteArray): AnalyzeOutcome {
        return if (app.forjaApi.available) {
            when (val res = app.forjaApi.analyzeMeal(bytes)) {
                is ForjaApi.MealResult.Ok -> AnalyzeOutcome.Ok(res.analysis)
                is ForjaApi.MealResult.Fail -> AnalyzeOutcome.Fail(res.message)
            }
        } else {
            val key = app.prefs.geminiKey.first()
            if (key.isBlank()) return AnalyzeOutcome.Fail("Activează analiza AI din Profil.")
            when (val res = app.geminiFood.analyze(key, bytes)) {
                is GeminiFood.Result.Ok -> AnalyzeOutcome.Ok(res.analysis)
                is GeminiFood.Result.Fail -> AnalyzeOutcome.Fail(res.message)
            }
        }
    }

    /** Miniatura mesei — copiată în aplicație, ca jurnalul să aibă și poza. */
    fun savePhoto(context: Context, bytes: ByteArray): String? = try {
        val dir = File(context.filesDir, "meal_photos").apply { mkdirs() }
        val f = File(dir, "meal_${System.currentTimeMillis()}.jpg")
        f.writeBytes(bytes)
        f.absolutePath
    } catch (_: Exception) { null }

    fun downscale(bytes: ByteArray, maxSide: Int = 1600): ByteArray? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        var sample = 1
        while (opts.outWidth / sample > maxSide || opts.outHeight / sample > maxSide) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        bmp.recycle()
        out.toByteArray()
    } catch (_: Exception) { null }

    fun readUri(context: Context, uri: Uri, maxSide: Int = 1600): ByteArray? = try {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { downscale(it, maxSide) }
    } catch (_: Exception) { null }

    fun mealTypeForTime(at: Long): Int {
        val h = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).hour
        return when (h) {
            in 5..10 -> 0
            in 11..16 -> 1
            in 17..22 -> 2
            else -> 3
        }
    }

    /** Salvează masa (Room + baza companiei), cu poza atașată. */
    suspend fun saveMeal(
        app: ForjaApp,
        analysis: MealAnalysis,
        components: List<com.forja.app.core.network.FoodComponent>,
        mealType: Int,
        at: Long,
        photoPath: String?
    ): MealEntity {
        val meal = MealEntity(
            epochDay = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay(),
            mealType = mealType,
            name = analysis.fel.ifBlank { components.joinToString(" + ") { it.nume }.take(48) },
            kcal = components.sumOf { it.kcal },
            protein = components.sumOf { it.proteine },
            carbs = components.sumOf { it.carbo },
            fat = components.sumOf { it.grasimi },
            grams = components.sumOf { it.grame },
            source = "ESTIMARE AI · POZĂ",
            confidence = analysis.incredere,
            at = at,
            photoPath = photoPath
        )
        val id = app.db.mealDao().insert(meal)
        com.forja.app.core.data.CloudSync.meal(app.auth.currentUid, meal.copy(id = id))
        return meal.copy(id = id)
    }
}
