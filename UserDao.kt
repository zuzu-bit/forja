package com.forja.app.core.designsystem

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// ── Paleta FORJA ─ negru industrial + metal incandescent ──────────────────────

// Fundaluri
val Coal = Color(0xFF0B0B0D)          // fundalul aplicației
val Surface1 = Color(0xFF131316)      // suprafețe (câmpuri de text, bare)
val Surface2 = Color(0xFF1A1A1F)      // carduri
val BorderSubtle = Color(0xFF26262C)  // contururi discrete

// Accente (focul forjei)
val Ember = Color(0xFFFF7A1A)         // portocaliu incandescent — accent principal
val Molten = Color(0xFFFFB324)        // auriu topit — accent secundar
val OnEmber = Color(0xFF160B02)       // text pe accente

// Text
val TextPrimary = Color(0xFFF4F2EE)
val TextSecondary = Color(0xFFA3A3AB)

// Stări
val Danger = Color(0xFFFF5C5C)
val Success = Color(0xFF4CC38A)

/** Gradientul semnătură FORJA, folosit pe butoane și elemente cheie. */
val EmberBrush = Brush.linearGradient(listOf(Ember, Molten))
