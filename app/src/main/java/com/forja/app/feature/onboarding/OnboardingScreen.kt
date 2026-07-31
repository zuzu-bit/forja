package com.forja.app.feature.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forja.app.ForjaApp
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*
import kotlinx.coroutines.launch

private data class OnbPage(
    val kicker: String, val title: String, val sub: String,
    val cta: String, val poster: String, val video: String
)

private val pages = listOf(
    OnbPage(
        "FILMUL 1 · ZORI", "Ziua ta, filmată.",
        "Antrenament, mese, somn — ca un film despre viața ta reală.", "Continuă",
        "https://t4.ftcdn.net/jpg/04/30/39/81/500_F_430398119_8X2LMR6p3pWYrpsvH3DYgYUz32PfnxXl.jpg",
        "https://v.ftcdn.net/10/70/20/79/700_F_1070207993_vIfgD2rf5RWK9Sz68WonFo6D78QWfBWy_ST.mp4"
    ),
    OnbPage(
        "FILMUL 2 · BUCĂTĂRIE", "Mănânci real. Vezi clar.",
        "Scanezi codul sau cauți produsul — FORJA îți arată exact ce mănânci. Tu confirmi.", "Continuă",
        "https://t4.ftcdn.net/jpg/05/03/88/17/500_F_503881704_hyhi1pOJrBNqQ0dJqK1Qceno2pa8KWiJ.jpg",
        "https://v.ftcdn.net/01/94/92/63/700_F_194926378_Lo30ngI1RhPKxx8MKl8clLpbq4P304fB_ST.mp4"
    ),
    OnbPage(
        "FILMUL 3 · DIMINEAȚĂ", "Te trezești mai bine.",
        "Somnul, măsurat discret. Dimineața, un raport calm.", "Începe",
        "https://t3.ftcdn.net/jpg/04/70/98/78/500_F_470987805_jsREzUZZZNUDZ56fG4J9Cpz4UquN6zJg.jpg",
        "https://v.ftcdn.net/05/12/88/79/700_F_512887976_190EN7woFkvAws5F4qzRxGMIOuIjvyPY_ST.mp4"
    )
)

@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    val app = remember { ForjaApp.from(context) }
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(0) }

    fun finish() {
        scope.launch {
            app.prefs.setOnboardingDone()
            onFinished()
        }
    }

    Box(Modifier.fillMaxSize().background(Surface0)) {
        AnimatedContent(
            targetState = page,
            transitionSpec = { fadeIn(Springs.natural()) togetherWith fadeOut(Springs.natural()) },
            label = "onb"
        ) { i ->
            val p = pages[i]
            Box(Modifier.fillMaxSize()) {
                VideoSurface(url = p.video, posterUrl = p.poster, modifier = Modifier.fillMaxSize())
                BoxScopeBottomScrim()
                TopScrim()
            }
        }

        // „Sari"
        Text(
            "Sari",
            style = BodyStrong.copy(color = TextSecondary),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(20.dp)
                .pressable({ finish() })
        )

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 26.dp)
        ) {
            val p = pages[page]
            Text(p.kicker, style = monoLabel(10, 0.16f).copy(color = Accent2))
            Spacer(Modifier.height(10.dp))
            Text(p.title, style = TitleOnboarding)
            Spacer(Modifier.height(10.dp))
            Text(p.sub, style = Body.copy(fontSize = 15.sp, lineHeight = 20.sp))
            Spacer(Modifier.height(20.dp))

            // Dots animate — activul se alungește (snappy).
            Row(verticalAlignment = Alignment.CenterVertically) {
                repeat(3) { i ->
                    val w by animateDpAsState(if (i == page) 22.dp else 8.dp, Springs.snappy(), label = "dot$i")
                    Box(
                        Modifier
                            .padding(end = 6.dp)
                            .size(width = w, height = 8.dp)
                            .clip(CircleShape)
                            .background(if (i == page) Accent2 else Color(0x668A8F98))
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            PrimaryButton(
                text = p.cta,
                onClick = { if (page < 2) page++ else finish() },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
