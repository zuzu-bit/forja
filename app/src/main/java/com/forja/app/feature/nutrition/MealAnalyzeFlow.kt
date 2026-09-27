package com.forja.app.feature.nutrition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.forja.app.ForjaApp
import com.forja.app.core.data.db.MealEntity
import com.forja.app.core.network.GeminiFood
import com.forja.app.core.network.MealApi
import com.forja.app.core.network.MealCheck
import com.forja.app.core.network.MealItem
import com.forja.app.core.network.MealReport
import kotlinx.coroutines.flow.first
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** Rezultatul analizei unei poze cu mâncare — indiferent de sursă (cameră/galerie). */
sealed class AnalyzeOutcome {
    data class Ok(val report: MealReport) : AnalyzeOutcome() {
        val analysis get() = report.toAnalysis()
    }
    data class Fail(val message: String) : AnalyzeOutcome()
}

/**
 * Pașii analizei, afișați sincer: fiecare trece pe „gata” doar când s-a întâmplat.
 *  0 „Trimit poza” · 1 „Modelul descompune farfuria” · 2 „Verific porțiile” (apare doar la răspuns v2).
 */
data class AnalyzeStages(
    val current: Int = 0,          // pasul activ
    val done: Int = 0,             // câți pași s-au încheiat
    val showVerify: Boolean = false, // pasul 3 există doar când serverul a răspuns cu versiunea 2
    val coherent: Boolean? = null  // rezultatul verificării de pe telefon (kcal ≈ 4P+4C+9G)
) {
    companion object {
        val STEPS = listOf("Trimit poza", "Modelul descompune farfuria", "Verific porțiile")
        val idle = AnalyzeStages()
    }
}

object MealAnalyze {
    private var mealApi: MealApi? = null
    private fun api(app: ForjaApp): MealApi = mealApi ?: MealApi(app.forjaApi).also { mealApi = it }

    /**
     * Server (cheile companiei) sau, ca rezervă, cheia Gemini proprie — aceeași logică peste tot.
     * `onStages` primește progresul real; fără server, pasul 1 și 2 se raportează la trimitere/răspuns.
     */
    suspend fun analyzeJpeg(
        app: ForjaApp,
        bytes: ByteArray,
        onStages: (AnalyzeStages) -> Unit = {}
    ): AnalyzeOutcome {
        onStages(AnalyzeStages(current = 0, done = 0))
        val outcome: AnalyzeOutcome = if (app.forjaApi.available) {
            when (val res = api(app).analyze(bytes) { stage ->
                when (stage) {
                    MealApi.Stage.UPLOADING -> onStages(AnalyzeStages(current = 0, done = 0))
                    MealApi.Stage.ANALYZING -> onStages(AnalyzeStages(current = 1, done = 1))
                    MealApi.Stage.DONE -> onStages(AnalyzeStages(current = 2, done = 2))
                }
            }) {
                is MealApi.Result.Ok -> AnalyzeOutcome.Ok(res.report)
                is MealApi.Result.Fail -> AnalyzeOutcome.Fail(res.message)
            }
        } else {
            val key = app.prefs.geminiKey.first()
            if (key.isBlank()) return AnalyzeOutcome.Fail("Activează analiza pozelor din Profil.")
            onStages(AnalyzeStages(current = 1, done = 1))
            when (val res = app.geminiFood.analyze(key, bytes)) {
                is GeminiFood.Result.Ok -> AnalyzeOutcome.Ok(MealReport.from(res.analysis, model = "gemini"))
                is GeminiFood.Result.Fail -> AnalyzeOutcome.Fail(res.message)
            }
        }
        if (outcome is AnalyzeOutcome.Ok && outcome.report.isV2) {
            // Pasul 3, real: serverul a verificat porțiile (v2), telefonul confirmă coerența kcal/macro.
            val ok = MealCheck.coherent(outcome.report.componente)
            onStages(AnalyzeStages(current = 2, done = 3, showVerify = true, coherent = ok))
        } else {
            onStages(AnalyzeStages(current = 1, done = 2, showVerify = false))
        }
        return outcome
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

    /** Salvează masa (Room + baza companiei), cu poza atașată; notează ziua în seria locală. */
    suspend fun saveMeal(
        app: ForjaApp,
        report: MealReport,
        components: List<MealItem>,
        mealType: Int,
        at: Long,
        photoPath: String?
    ): MealEntity {
        val day = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
        val meal = MealEntity(
            epochDay = day,
            mealType = mealType,
            name = report.fel.ifBlank { components.joinToString(" + ") { it.nume }.take(48) },
            kcal = components.sumOf { it.kcal },
            protein = components.sumOf { it.proteine },
            carbs = components.sumOf { it.carbo },
            fat = components.sumOf { it.grasimi },
            grams = components.sumOf { it.grame },
            source = "ESTIMARE AI · POZĂ",
            confidence = report.incredere,
            at = at,
            photoPath = photoPath
        )
        val id = app.db.mealDao().insert(meal)
        com.forja.app.core.data.CloudSync.meal(app.auth.currentUid, meal.copy(id = id))
        try { NutritionPrefs.of(app).noteMeal(day) } catch (_: Exception) { }
        return meal.copy(id = id)
    }
}
