package com.forja.app.feature.nutrition

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*
import com.forja.app.core.media.Media

private const val CHEF_KEY = "mascot_chef.jpg"
private const val GUIDE_KEY = "guide.jpg"

/**
 * Mascota modulului: bucătarul (`mascot_chef.jpg`), cu ghidul (`guide.jpg`) ca rezervă REALĂ.
 * `Media.mediaUrl` întoarce un URL pentru orice cheie (null doar fără server), deci rezerva se decide după
 * manifestul serverului (`/media/_list`, încărcat la pornire): până e generat fișierul bucătarului, ghidul.
 * Manifest gol (încă neîncărcat sau server mut) → încercăm bucătarul, iar la eroare [ChefMascot] trece pe ghid.
 */
@Composable
internal fun rememberMascotUrl(): String? {
    val manifest by Media.manifest.collectAsState()
    return remember(manifest) {
        if (manifest.isEmpty() || CHEF_KEY in manifest) Media.mediaUrl(CHEF_KEY) else Media.mediaUrl(GUIDE_KEY)
    }
}

/**
 * Mascota într-un cerc olive, cu „dans” subtil (ca în cardul de motivație din panou).
 * Sub mișcare redusă stă pe loc. Fără server media → doar ștampila „RAȚIE”.
 */
@Composable
fun ChefMascot(
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    dance: Boolean = true,
    url: String? = rememberMascotUrl()
) {
    // Rezerva la încărcare: 404 pe bucătar → ghidul; dacă pică și ghidul, rămâne ștampila.
    var shown by remember(url) { mutableStateOf(url) }
    val reduced = LocalReducedMotion.current
    val animate = dance && !reduced
    var rot = 0f
    var sc = 1f
    if (animate) {
        val infinite = rememberInfiniteTransition(label = "chef")
        val r by infinite.animateFloat(-4f, 4f, infiniteRepeatable(tween(1500), RepeatMode.Reverse), label = "rot")
        val s by infinite.animateFloat(0.97f, 1.04f, infiniteRepeatable(tween(1150), RepeatMode.Reverse), label = "sc")
        rot = r; sc = s
    }
    Box(
        modifier
            .size(size)
            .graphicsLayer { rotationZ = rot; scaleX = sc; scaleY = sc }
            .clip(CircleShape)
            .background(Color(0x1F6F855A))
            .border(1.dp, Color(0x4D6F855A), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (shown != null) {
            AsyncImage(
                model = shown, contentDescription = "Bucătarul",
                contentScale = ContentScale.Crop,
                onError = {
                    val guide = Media.mediaUrl(GUIDE_KEY)
                    shown = if (shown != guide) guide else null
                },
                modifier = Modifier.fillMaxSize().clip(CircleShape)
            )
        } else {
            Text("RAȚIE", style = monoLabel(8, 0.14f).copy(color = Accent2))
        }
    }
}

/**
 * Ecranul „Analiză…”: mascota + trei pași afișați sincer. Un pas e „gata” doar când s-a întâmplat;
 * al treilea apare doar când răspunsul are versiunea 2 (serverul a verificat porțiile).
 */
@Composable
fun AnalyzeStagePanel(stages: AnalyzeStages, modifier: Modifier = Modifier) {
    val visibleSteps = if (stages.showVerify) AnalyzeStages.STEPS else AnalyzeStages.STEPS.take(2)
    ForjaCard(modifier, fill = Surface1.copy(alpha = 0.96f), padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulseGlow(color = Accent2, radius = 40.dp, minAlpha = 0.12f, maxAlpha = 0.3f) {
                ChefMascot(size = 60.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Analiză", style = TitleModule.copy(fontSize = 20.sp, lineHeight = 23.sp))
                Spacer(Modifier.height(2.dp))
                Text("Estimare cu model. Tu confirmi porția.", style = BodySmall.copy(color = TextSecondary))
            }
        }
        Spacer(Modifier.height(16.dp))
        visibleSteps.forEachIndexed { i, label ->
            val done = i < stages.done
            val active = !done && i == stages.current
            Row(
                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StepDot(done = done, active = active)
                Spacer(Modifier.width(10.dp))
                Text(
                    label,
                    style = BodyStrong.copy(
                        fontSize = 14.sp,
                        color = when {
                            done -> TextPrimary
                            active -> Accent2
                            else -> TextDim
                        }
                    )
                )
                Spacer(Modifier.weight(1f))
                Text(
                    when {
                        done -> "gata"
                        active -> "acum"
                        else -> "urmează"
                    },
                    style = monoLabel(8, 0.12f).copy(color = if (done) Positive else TextDim)
                )
            }
        }
        if (stages.showVerify && stages.coherent == false) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Caloriile nu se potrivesc cu macro-urile. Verifică gramajele înainte de salvare.",
                style = BodyTiny.copy(color = EmberHot)
            )
        }
    }
}

@Composable
private fun StepDot(done: Boolean, active: Boolean) {
    val reduced = LocalReducedMotion.current
    var alpha = 1f
    if (active && !reduced) {
        val infinite = rememberInfiniteTransition(label = "step")
        val a by infinite.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
        alpha = a
    }
    Box(
        Modifier
            .size(12.dp)
            .graphicsLayer { this.alpha = alpha }
            .clip(CircleShape)
            .background(
                when {
                    done -> Positive
                    active -> Accent2
                    else -> SwitchOff
                }
            )
    )
}
