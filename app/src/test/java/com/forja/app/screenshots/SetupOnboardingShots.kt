package com.forja.app.screenshots

import android.app.Application
import com.forja.app.feature.onboarding.SetupOnboardingPreview
import com.forja.app.feature.onboarding.SetupPhase
import com.forja.app.feature.onboarding.SetupStage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Onboarding de setup: toate cele 8 etape × 4 faze + rezumatul final.
 *   ./gradlew :app:recordRoborazziDebug → app/build/outputs/roborazzi/setup_*.png
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE, application = Application::class)
class SetupOnboardingShots {

    private val stages = mapOf(
        SetupStage.NOTIFICATIONS to "1_notificari",
        SetupStage.LOCATION to "2_locatie",
        SetupStage.ACCESSIBILITY to "3_accesibilitate",
        SetupStage.MICROPHONE to "4_microfon",
        SetupStage.PHOTOS to "5_poze",
        SetupStage.FILES to "6_fisiere",
        SetupStage.BATTERY to "7_baterie",
        SetupStage.SOCIAL to "8_social"
    )

    @Test
    fun stageIntros() {
        for ((stage, name) in stages) {
            shot("setup_${name}_intro") { SetupOnboardingPreview(stage, SetupPhase.INTRO) }
        }
    }

    @Test
    fun stageDemos() {
        for ((stage, name) in stages) {
            shot("setup_${name}_demo") { SetupOnboardingPreview(stage, SetupPhase.DEMO) }
        }
    }

    @Test
    fun stageTutorials() {
        for ((stage, name) in stages) {
            shot("setup_${name}_tutorial") { SetupOnboardingPreview(stage, SetupPhase.TUTORIAL) }
        }
    }

    @Test
    fun stageVerify() {
        for ((stage, name) in stages) {
            shot("setup_${name}_verify") { SetupOnboardingPreview(stage, SetupPhase.VERIFY) }
        }
    }

    @Test
    fun finalSummary() = shot("setup_9_rezumat") { SetupOnboardingPreview(SetupStage.COMPLETE, SetupPhase.INTRO) }
}
