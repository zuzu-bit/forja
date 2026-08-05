package com.forja.app.core.detox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forja.app.ForjaApp
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.PrimaryButton
import com.forja.app.core.designsystem.components.SecondaryButton
import kotlinx.coroutines.flow.first

/** Momentul tentației: nu pedeapsă, ci un prieten. Respiră. Amintește-ți de ce. */
class DetoxBlockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setContent { ForjaTheme { DetoxBlockScreen(onDone = { finish() }) } }
    }
}

@Composable
private fun DetoxBlockScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val app = remember { ForjaApp.from(context) }

    var letter by remember { mutableStateOf("") }
    val mascotUrl = remember { com.forja.app.core.media.Media.mediaUrl("paznic.jpg") }
    LaunchedEffect(Unit) { letter = app.prefs.detoxLetter.first() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Surface0),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(40.dp))

            val infinite = rememberInfiniteTransition(label = "breath")
            val breath by infinite.animateFloat(
                0.82f, 1.12f,
                infiniteRepeatable(tween(4600), RepeatMode.Reverse), label = "s"
            )
            Box(
                Modifier
                    .size(190.dp)
                    .scale(breath)
                    .clip(CircleShape)
                    .background(Color(0x146F855A))
                    .border(1.5.dp, Color(0x666F855A), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (mascotUrl != null) {
                    coil.compose.AsyncImage(
                        model = mascotUrl, contentDescription = "Paznicul",
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CircleShape)
                    )
                } else {
                    Text("Respiră.", style = TitleModule.copy(fontSize = 24.sp))
                }
            }
            if (mascotUrl != null) {
                Spacer(Modifier.height(14.dp))
                Text("Respiră.", style = TitleModule.copy(fontSize = 22.sp))
            }

            Spacer(Modifier.height(28.dp))
            Text(
                "Ai ales să te oprești aici.",
                style = TitleModule.copy(fontSize = 24.sp, lineHeight = 28.sp),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Nu tu ai greșit — doar un moment a bătut la ușă. Îl lași să treacă.",
                style = Body.copy(fontSize = 15.sp),
                textAlign = TextAlign.Center
            )

            if (letter.isNotBlank()) {
                Spacer(Modifier.height(20.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(CardShape)
                        .background(Surface1)
                        .border(1.dp, StrokeCard, CardShape)
                        .padding(16.dp)
                ) {
                    Text("SCRISOAREA TA, CĂTRE TINE", style = monoLabel(9, 0.14f).copy(color = TextDim))
                    Spacer(Modifier.height(8.dp))
                    Text("„$letter”", style = Body.copy(color = TextPrimary, fontSize = 15.sp, lineHeight = 21.sp))
                }
            }

            Spacer(Modifier.height(28.dp))
            PrimaryButton(
                text = "Rămân puternic",
                onClick = { onDone() },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Dacă e greu des, nu ești slab — vorbește cu cineva de încredere sau un specialist. Nu trebuie să duci asta singur.",
                style = BodyTiny.copy(color = TextDim),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            Spacer(Modifier.height(30.dp))
        }
    }
}
