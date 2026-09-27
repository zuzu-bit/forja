package com.forja.app.core.designsystem.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forja.app.core.designsystem.Accent2
import com.forja.app.core.designsystem.AccentGradient
import com.forja.app.core.designsystem.BodyStrong
import com.forja.app.core.designsystem.BodyTiny
import com.forja.app.core.designsystem.EmberHot
import com.forja.app.core.designsystem.EmberWarm
import com.forja.app.core.designsystem.Error
import com.forja.app.core.designsystem.LocalReducedMotion
import com.forja.app.core.designsystem.Positive
import com.forja.app.core.designsystem.SwitchOff
import com.forja.app.core.designsystem.TextDim
import com.forja.app.core.designsystem.TextPrimary
import com.forja.app.core.designsystem.TextSecondary
import com.forja.app.core.designsystem.heroNumeral
import com.forja.app.core.designsystem.monoLabel
import kotlin.math.roundToInt

/*
 * Inelele și barele „rației” — kcal, macro-uri, scor.
 * Toate se umplu 0 → valoare în ~900 ms; sub LocalReducedMotion sar direct la starea finală.
 */

// Culorile macro-urilor (aceleași peste tot în modul): proteine verde, carbo amber, grăsimi ember, fibre olive stins.
val MacroProteinColor = Positive
val MacroCarbColor = EmberHot
val MacroFatColor = EmberWarm
val MacroFibreColor = Accent2

private const val FILL_MS = 900

@Composable
private fun fillSpec(): AnimationSpec<Float> =
    if (LocalReducedMotion.current) snap() else tween(FILL_MS, easing = FastOutSlowInEasing)

/** Progresul „pornit de la zero” la prima compunere; sub mișcare redusă e direct valoarea. */
@Composable
private fun animatedFill(target: Float, key: Any?): Float {
    val reduced = LocalReducedMotion.current
    var started by remember(key) { mutableStateOf(reduced) }
    LaunchedEffect(key) { started = true }
    val v by animateFloatAsState(if (started) target.coerceIn(0f, 1f) else 0f, fillSpec(), label = "fill")
    return if (reduced) target.coerceIn(0f, 1f) else v
}

/**
 * Inel mare de kcal (sau orice raport): arcul se umple din vârf; peste 100 % trece pe roșu stins.
 * `progress` e raportul față de obiectiv; conținutul din centru îl dai tu (numeral, etichetă).
 */
@Composable
fun MacroRing(
    progress: Float,
    modifier: Modifier = Modifier,
    ringSize: Dp = 132.dp,
    strokeWidth: Dp = 11.dp,
    track: Color = Color(0x1AFFFFFF),
    brush: Brush = AccentGradient,
    overColor: Color = Error,
    key: Any? = null,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val p = animatedFill(progress.coerceAtLeast(0f).coerceAtMost(1f), key)
    val over = progress > 1f
    Box(modifier.size(ringSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = strokeWidth.toPx()
            val arcSize = Size(size.width - sw, size.height - sw)
            val topLeft = Offset(sw / 2, sw / 2)
            drawArc(track, -90f, 360f, false, topLeft = topLeft, size = arcSize, style = Stroke(sw, cap = StrokeCap.Round))
            if (p > 0f) {
                if (over) {
                    drawArc(overColor.copy(alpha = 0.9f), -90f, 360f * p, false, topLeft = topLeft, size = arcSize, style = Stroke(sw, cap = StrokeCap.Round))
                } else {
                    drawArc(brush, -90f, 360f * p, false, topLeft = topLeft, size = arcSize, style = Stroke(sw, cap = StrokeCap.Round))
                }
            }
        }
        content()
    }
}

/** Inel kcal gata compus: numeral mare, „din N”, culoare de depășire. */
@Composable
fun KcalRing(
    kcal: Int,
    target: Int,
    modifier: Modifier = Modifier,
    ringSize: Dp = 132.dp,
    key: Any? = null
) {
    val t = target.coerceAtLeast(1)
    MacroRing(progress = kcal.toFloat() / t, modifier = modifier, ringSize = ringSize, key = key) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CountUpNumeral(target = kcal.toFloat(), size = 30, decimals = 0)
            Text("din $t kcal", style = BodyTiny.copy(color = TextSecondary))
        }
    }
}

/**
 * Bară orizontală de macro: etichetă + grame la stânga, valoarea față de reper la dreapta.
 * `target` = null → bara se umple față de `max` (folosit la componente), fără text de reper.
 */
@Composable
fun MacroBar(
    label: String,
    grams: Int,
    color: Color,
    modifier: Modifier = Modifier,
    target: Int? = null,
    max: Int? = null,
    height: Dp = 7.dp,
    key: Any? = null
) {
    val denom = (target ?: max ?: grams).coerceAtLeast(1)
    val ratio = grams.toFloat() / denom
    val p = animatedFill(ratio, key)
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(label, style = monoLabel(9, 0.14f).copy(color = color))
                Spacer(Modifier.size(6.dp))
                Text("$grams g", style = BodyStrong.copy(fontSize = 13.sp))
            }
            if (target != null) {
                Text(
                    if (grams > target) "peste reper cu ${grams - target} g" else "reper $target g",
                    style = BodyTiny.copy(color = if (grams > target) Error else TextDim)
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(CircleShape)
                .background(SwitchOff)
        ) {
            if (p > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(p)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(if (ratio > 1f) Error.copy(alpha = 0.85f) else color)
                )
            }
        }
    }
}

/**
 * „Scor de rație” 1–10: cadran de 270°, acul e arcul umplut; culoarea urmează scorul
 * (sub 4 roșu stins, 4–6 amber, peste 6 verde). Numeralul la mijloc, „/10” dedesubt.
 */
@Composable
fun ScoreDial(
    score: Int,
    modifier: Modifier = Modifier,
    size: Dp = 84.dp,
    strokeWidth: Dp = 8.dp,
    key: Any? = null
) {
    val s = score.coerceIn(1, 10)
    val ratio = s / 10f
    val p = animatedFill(ratio, key)
    val color = when {
        s < 4 -> Error
        s < 7 -> EmberHot
        else -> Positive
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = strokeWidth.toPx()
            val arcSize = Size(this.size.width - sw, this.size.height - sw)
            val topLeft = Offset(sw / 2, sw / 2)
            // Cadranul: de la 135° (stânga-jos) prin vârf până la 45° (dreapta-jos).
            drawArc(Color(0x1AFFFFFF), 135f, 270f, false, topLeft = topLeft, size = arcSize, style = Stroke(sw, cap = StrokeCap.Round))
            if (p > 0f) {
                drawArc(color, 135f, 270f * p, false, topLeft = topLeft, size = arcSize, style = Stroke(sw, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val shown = if (LocalReducedMotion.current) s else (p * 10f).roundToInt().coerceIn(0, s)
            Text("$shown", style = heroNumeral(26).copy(color = TextPrimary))
            Text("/10", style = monoLabel(8, 0.12f).copy(color = TextDim))
        }
    }
}
