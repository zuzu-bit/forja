package com.forja.app.core.designsystem.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forja.app.core.designsystem.*

// Modifier comun: press scale .97 cu arc snappy + haptic.
@Composable
fun Modifier.pressable(
    onClick: () -> Unit,
    scaleDown: Float = 0.97f,
    haptic: Boolean = true
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) scaleDown else 1f,
        animationSpec = Springs.snappy(), label = "press"
    )
    val haptics = LocalHapticFeedback.current
    return this
        .scale(scale)
        .clickable(interactionSource = interaction, indication = null) {
            if (haptic) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }
}

// Buton primar: gradient 92°, radius 14, text Archivo 800 16 #141008.
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    small: Boolean = false,
    enabled: Boolean = true
) {
    val shape = if (small) ButtonSmallShape else ButtonShape
    Box(
        modifier = modifier
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .clip(shape)
            .background(AccentGradient)
            .then(if (enabled) Modifier.pressable(onClick) else Modifier)
            .padding(vertical = if (small) 12.dp else 16.dp, horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .matchParentSize()
                .background(Color(0x14FFFFFF), shape)
        ) {}
        androidx.compose.material3.Text(
            text = text,
            style = if (small) ButtonTextSmall else ButtonText,
            textAlign = TextAlign.Center
        )
    }
}

// Buton secundar: fill #1A1A1E, stroke .09, text alb 700.
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textColor: Color = TextPrimary,
    padV: Dp = 14.dp
) {
    Box(
        modifier = modifier
            .clip(SecondaryShape)
            .background(Surface2)
            .border(1.dp, StrokeCardStrong, SecondaryShape)
            .pressable(onClick)
            .padding(vertical = padV, horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Text(
            text = text,
            style = Body.copy(color = textColor, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 14.sp)
        )
    }
}

// Buton peste video: blur-ish fill translucid + stroke .14, radius 13.
@Composable
fun OverVideoButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(13.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(OverVideoFill)
            .border(1.dp, Color(0x24FFFFFF), shape)
            .pressable(onClick)
            .padding(vertical = 12.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Text(text, style = BodyStrong)
    }
}

// Buton utilitar mono
@Composable
fun MonoButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = TextSecondary) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(UtilFill)
            .pressable(onClick)
            .padding(vertical = 10.dp, horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Text(text, style = monoLabel(11, 0.10f).copy(color = color))
    }
}

// Switch 44×26 din handoff: knob 20 alb, ON gradient, OFF #2A2A30, 300ms snappy.
@Composable
fun ForjaSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val knobX by animateFloatAsState(if (checked) 21f else 3f, Springs.snappy(), label = "knob")
    val haptics = LocalHapticFeedback.current
    Box(
        Modifier
            .size(width = 44.dp, height = 26.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(if (checked) Color.Transparent else SwitchOff)
            .then(if (checked) Modifier.background(AccentGradient) else Modifier)
            .clickable(remember { MutableInteractionSource() }, indication = null) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onCheckedChange(!checked)
            }
    ) {
        Box(
            Modifier
                .offset(x = knobX.dp, y = 3.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}
