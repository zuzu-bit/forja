package com.forja.app.feature.focus

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forja.app.ForjaApp
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.PrimaryButton
import com.forja.app.core.designsystem.components.SecondaryButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Ecranul care apare peste aplicația blocată: un moment uman — respiră. */
class FocusBlockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = intent.getStringExtra("label") ?: "Aplicația"
        val until = intent.getStringExtra("until") ?: "18:00"
        setContent {
            ForjaTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Surface0),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 28.dp)
                    ) {
                        val infinite = rememberInfiniteTransition(label = "breath")
                        val breath by infinite.animateFloat(
                            initialValue = 0.82f, targetValue = 1.12f,
                            animationSpec = infiniteRepeatable(tween(4600), RepeatMode.Reverse),
                            label = "s"
                        )
                        Box(
                            Modifier
                                .size(180.dp)
                                .scale(breath)
                                .clip(CircleShape)
                                .background(Color(0x14FF9E2D))
                                .border(1.5.dp, Color(0x66FFB300), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Respiră.", style = TitleModule.copy(fontSize = 24.sp))
                        }
                        Spacer(Modifier.height(28.dp))
                        Text(
                            "$label e blocat până la $until.",
                            style = TitleModule.copy(fontSize = 22.sp, lineHeight = 26.sp),
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Timpul tău se întoarce la tine.",
                            style = Body.copy(fontSize = 15.sp),
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(30.dp))
                        PrimaryButton(
                            text = "Înapoi la ale mele",
                            onClick = { moveTaskToBack(true); finish() },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(10.dp))
                        SecondaryButton(
                            "Deblochează 5 min",
                            onClick = {
                                val app = ForjaApp.from(this@FocusBlockActivity)
                                CoroutineScope(Dispatchers.Default).launch {
                                    app.prefs.setFocusUnlockUntil(System.currentTimeMillis() + 5 * 60_000)
                                }
                                finish()
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}
