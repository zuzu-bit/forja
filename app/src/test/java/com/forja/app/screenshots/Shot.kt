package com.forja.app.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.forja.app.core.designsystem.ForjaTheme
import com.forja.app.core.designsystem.LocalReducedMotion
import com.forja.app.core.designsystem.Surface0
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.roborazziSystemPropertyOutputDirectory

/*
 * Capturi de ecran pe JVM (Roborazzi + Robolectric, fără emulator) — DESIGN-4.3 §6.
 *
 *   ./gradlew :app:recordRoborazziDebug      → app/build/outputs/roborazzi/<nume>.png
 *   GitHub → Actions → „UI shots” (ui-shots.yml) → release „ui-shots-latest”, asset ui-shots.zip
 *
 * Fiecare clasă de test poartă:
 *   @RunWith(RobolectricTestRunner::class)
 *   @GraphicsMode(GraphicsMode.Mode.NATIVE)
 *   @Config(sdk = [35], qualifiers = PHONE, application = Application::class)
 * `application = Application::class` ocolește ForjaApp.onCreate (Firebase, MapLibre, Room) — ecranele capturate
 * primesc date false, nu singleton-uri. Rulat fără „record” (ex. `./gradlew test`), shot() nu face nimic.
 *
 * Ecran nou (pachetele F/G): o metodă @Test în clasa potrivită (sau o clasă nouă cu adnotările de mai sus) cu
 *   shot("inventory_s4_rezultate") { InventoryResults(stare falsă) }
 * Atenție: Robolectric rulează cadrele cât timp cineva cere cadre noi. O animație infinită care NU ascultă de
 * LocalReducedMotion (rememberInfiniteTransition, bucle cu withFrameNanos) blochează captura — ține-le sub `if (!reduced)`,
 * ca în Mascot.kt și Effects.kt. `delay(…)` nu blochează, dar nici nu „trece”: stările de după o întârziere nu apar.
 */

/** Telefonul de referință: 393 × 851 dp (Pixel 5 / 7a), xxhdpi → PNG de 1179 × 2553 px. */
const val PHONE = "w393dp-h851dp-xxhdpi"

/**
 * Galaxy S23 al Lanei: 360 × 780 dp (densitate 3,0), minus bara de stare (~36 dp) și bara cu 3 butoane a One UI (48 dp).
 * Robolectric raportează inseturi zero, deci înălțimea utilă se dă direct: ecranele care nu încap aici se văd tăiate/suprapuse.
 */
const val PHONE_S23 = "w360dp-h696dp-xxhdpi"

/**
 * Randează `content` în tema FORJA și salvează `<nume>.png`.
 * Mișcarea redusă e pornită: animațiile sar la starea finală, iar buclele infinite (respirația mascotei, scântei)
 * nu pornesc — cadrul e determinist și Robolectric nu rămâne blocat în cadre fără sfârșit.
 * `fullScreen = false` → PNG-ul are exact mărimea conținutului (carduri, componente).
 */
@OptIn(ExperimentalRoborazziApi::class)
fun shot(name: String, fullScreen: Boolean = true, content: @Composable () -> Unit) {
    captureRoboImage(filePath = "${roborazziSystemPropertyOutputDirectory()}/$name.png") {
        ForjaTheme {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                Box(if (fullScreen) Modifier.fillMaxSize().background(Surface0) else Modifier.background(Surface0)) {
                    content()
                }
            }
        }
    }
}
