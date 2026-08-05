package com.forja.app.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp

// Radiusuri — rețeta „Ascuțite" din Design Studio (carduri 5, butoane 8)
object Radii {
    val card = 5.dp
    val buttonPrimary = 8.dp
    val buttonSecondary = 7.dp
    val thumb = 4.dp
    val sheet = 14.dp         // sheets păstrează o rotunjire mică sus
    val chip = 4.dp
}

val CardShape = RoundedCornerShape(Radii.card)
val ButtonShape = RoundedCornerShape(Radii.buttonPrimary)
val ButtonSmallShape = RoundedCornerShape(7.dp)
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
