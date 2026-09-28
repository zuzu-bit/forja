package com.forja.app.feature.focus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.PrimaryButton
import com.forja.app.core.designsystem.components.StampLabel
import com.forja.app.core.focus.ReturnToForja

/** Ecranul care apare peste aplicația consemnată: post de pază, nu exercițiu de respirație. Singura ieșire duce la copac. */
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
                        StampLabel("POST DE PAZĂ")
                        Spacer(Modifier.height(22.dp))
                        val reduced = LocalReducedMotion.current
                        val sway = if (reduced) 0f else {
                            val infinite = rememberInfiniteTransition(label = "wind")
                            val v by infinite.animateFloat(
                                initialValue = -1f, targetValue = 1f,
                                animationSpec = infiniteRepeatable(tween(3600), RepeatMode.Reverse),
                                label = "sway"
                            )
                            v
                        }
                        GuardTree(sway)
                        Spacer(Modifier.height(26.dp))
                        Text(
                            "$label e blocat până la $until.",
                            style = TitleModule.copy(fontSize = 22.sp, lineHeight = 26.sp),
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Copacul tău crește cât stai la post.",
                            style = Body.copy(fontSize = 15.sp),
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(30.dp))
                        // O singură cale, cea aleasă de tine: înapoi în FORJA, la copacul care crește.
                        PrimaryButton(
                            text = "La copac",
                            onClick = { ReturnToForja.go(this@FocusBlockActivity) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

/** Copacul de la post: trunchi, trei coroane care se leagănă ușor în vânt, umbră la sol. */
@Composable
private fun GuardTree(sway: Float) {
    Canvas(Modifier.size(150.dp)) {
        val w = size.width
        val h = size.height
        drawOval(Color(0x33000000), topLeft = Offset(w * 0.22f, h * 0.87f), size = Size(w * 0.56f, h * 0.08f))
        drawRect(Color(0xFF5B4632), topLeft = Offset(w * 0.46f, h * 0.62f), size = Size(w * 0.08f, h * 0.28f))
        for (i in 0..2) {
            val top = h * (0.08f + i * 0.17f)
            val half = w * (0.17f + i * 0.09f)
            val base = top + h * 0.27f
            val dx = sway * (3 - i) * 2.2f
            val p = Path().apply {
                moveTo(w / 2f + dx, top)
                lineTo(w / 2f - half, base)
                lineTo(w / 2f + half, base)
                close()
            }
            drawPath(p, if (i % 2 == 0) Color(0xFF4A5D3A) else Color(0xFF6F855A))
        }
    }
}
