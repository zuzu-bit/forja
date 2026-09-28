package com.forja.app.screenshots

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.forja.app.core.data.db.MealEntity
import com.forja.app.feature.nutrition.BodyProfile
import com.forja.app.feature.nutrition.MascotVoice
import com.forja.app.feature.nutrition.Targets
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * Rația: cardul zilei (gol / cu profil și mese) și rezultatul chestionarului (IMC + rația), la ambele profiluri:
 * telefonul de referință (PHONE) și S23-ul Lanei (PHONE_S23, 360 × 696 dp — cardul are cu 33 dp mai puțin pe lățime,
 * iar la rezultat bara cu butonul lasă mai puțin loc derulării).
 * Ambele sunt `private` în fișierele lor — le apelăm prin [PrivateComposable], cu date false, fără ForjaApp.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE, application = Application::class)
class NutritionShots {

    private val profile = BodyProfile(
        sex = 1, age = 32, heightCm = 180, weightKg = 78f, activity = 2, goal = 0, diet = 0, done = true
    )

    private val meals = listOf(
        meal(type = 0, name = "Omletă cu legume", kcal = 420, p = 28, c = 12, f = 29),
        meal(type = 1, name = "Piept de pui cu orez", kcal = 650, p = 48, c = 70, f = 14),
        meal(type = 3, name = "Iaurt grecesc cu nuci", kcal = 280, p = 18, c = 14, f = 16),
    )

    @Test fun dayCardEmpty() = shotEmpty("nutrition_daycard_empty")
    @Test fun dayCardWithProfile() = shotProfile("nutrition_daycard_profile")
    @Test fun onboardingResult() = shotResult("nutrition_onboarding_result")

    // ───────────────────────────── PHONE_S23 (Galaxy S23 al Lanei) ─────────────────────────────

    @Config(qualifiers = PHONE_S23)
    @Test fun dayCardEmptyS23() = shotEmpty("nutrition_daycard_empty_s23")
    @Config(qualifiers = PHONE_S23)
    @Test fun dayCardWithProfileS23() = shotProfile("nutrition_daycard_profile_s23")
    @Config(qualifiers = PHONE_S23)
    @Test fun onboardingResultS23() = shotResult("nutrition_onboarding_result_s23")

    /** Cel mai lung rând de sub inel pe 360 dp: „peste cu … kcal” + cipul + o serie de două cifre. */
    @Config(qualifiers = PHONE_S23)
    @Test fun dayCardOverS23() {
        val card = dayCard()
        val targets = requireNotNull(Targets.of(profile))
        val over = meals + meal(type = 2, name = "Paste carbonara", kcal = 1250, p = 42, c = 120, f = 58)
        shot("nutrition_daycard_over_s23", fullScreen = false) {
            Box(Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) {
                card.Render(over, over.sumOf { it.kcal }, targets.kcal, targets, profile, 12, MascotVoice.Sergent)
            }
        }
    }

    private fun shotEmpty(name: String) {
        val card = dayCard()
        shot(name, fullScreen = false) {
            Box(Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) {
                card.Render(emptyList<MealEntity>(), 0, 2000, null, null, 0, MascotVoice.Camarad)
            }
        }
    }

    private fun shotProfile(name: String) {
        val card = dayCard()
        val targets = requireNotNull(Targets.of(profile))
        shot(name, fullScreen = false) {
            Box(Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) {
                card.Render(meals, meals.sumOf { it.kcal }, targets.kcal, targets, profile, 4, MascotVoice.Sergent)
            }
        }
    }

    private fun shotResult(name: String) {
        val result = PrivateComposable.find(
            "com.forja.app.feature.nutrition.NutritionOnboardingKt", "ResultStep",
            BodyProfile::class.java, Float::class.javaPrimitiveType!!
        )
        assumeTrue("ResultStep(profile, bottomInset, …) și-a schimbat semnătura — actualizează testul", result != null)
        shot(name) {
            // bottomInset e Dp (clasă-valoare) → float în semnătura JVM; onBack / onNext se completează singure.
            result!!.Render(profile, 0f)
        }
    }

    private fun dayCard(): PrivateComposable {
        val int = Int::class.javaPrimitiveType!!
        // Doar datele; callback-urile (onTarget, onProfile, onVoice pe ramura 4.3…) și modifier-ul se completează singure.
        val card = PrivateComposable.find(
            "com.forja.app.feature.nutrition.NutritionScreenKt", "DayCard",
            List::class.java, int, int, Targets::class.java, BodyProfile::class.java, int, MascotVoice::class.java
        )
        assumeTrue("DayCard(meals, kcal, target, targets, profile, streak, voice, …) și-a schimbat semnătura — actualizează testul", card != null)
        return card!!
    }

    private fun meal(type: Int, name: String, kcal: Int, p: Int, c: Int, f: Int) = MealEntity(
        epochDay = LocalDate.now().toEpochDay(),
        mealType = type,
        name = name,
        kcal = kcal,
        protein = p,
        carbs = c,
        fat = f,
        grams = 350,
        source = "MANUAL",
        confidence = "—",
        at = System.currentTimeMillis()
    )
}
