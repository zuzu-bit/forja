package com.forja.app.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp

// Radiusuri din handoff
object Radii {
    val card = 13.dp          // carduri 12–14
    val buttonPrimary = 14.dp
    val buttonSecondary = 11.dp
    val thumb = 10.dp         // thumbs 9–10
    val sheet = 20.dp         // sheets 20 sus
    val chip = 7.dp           // chips 6–8
}

val CardShape = RoundedCornerShape(Radii.card)
val ButtonShape = RoundedCornerShape(Radii.buttonPrimary)
val ButtonSmallShape = RoundedCornerShape(12.dp)
val SecondaryShape = RoundedCornerShape(Radii.buttonSecondary)
val ThumbShape = RoundedCornerShape(Radii.thumb)
val SheetShape = RoundedCornerShape(topStart = Radii.sheet, topEnd = Radii.sheet)
val ChipShape = RoundedCornerShape(Radii.chip)

private val ForjaColorScheme: ColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    secondary = Accent2,
    background = Surface0,
    onBackground = TextPrimary,
    surface = Surface1,
    onSurface = TextPrimary,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextSecondary,
    error = Error,
    outline = StrokeCardStrong
)

@Composable
fun ForjaTheme(content: @Composable () -> Unit) {
    val reduced = rememberSystemReducedMotion()
    CompositionLocalProvider(LocalReducedMotion provides reduced) {
        MaterialTheme(
            colorScheme = ForjaColorScheme,
            content = content
        )
    }
}
