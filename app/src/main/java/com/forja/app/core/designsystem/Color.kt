package com.forja.app.core.designsystem

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Tokens exacți din handoff — nu modifica valorile fără design.
val Surface0 = Color(0xFF0A0A0B)
val Surface1 = Color(0xFF121214)
val Surface2 = Color(0xFF1A1A1E)

val Accent = Color(0xFFFF7A00)
val Accent2 = Color(0xFFFFB300)
val OnAccent = Color(0xFF141008)

val TextPrimary = Color(0xFFF4F2EE)
val TextSecondary = Color(0xFFA7A9AE)
val TextDim = Color(0xFF7A7D83)
val TextDim2 = Color(0xFF5A5D63)

val StrokeCard = Color(0x0FFFFFFF)          // rgba(255,255,255,.06)
val StrokeCardStrong = Color(0x17FFFFFF)    // rgba(255,255,255,.09)
val StrokeOnVideo = Color(0x408A8F98)       // rgba(138,143,152,.25)
val StrokeOnVideoStrong = Color(0x4D8A8F98) // rgba(138,143,152,.3)

// Somn — paleta albastră a nopții
val SleepBg = Color(0xFF0B111C)
val SleepCard = Color(0xFF0E1622)
val SleepStroke = Color(0x337896BE)         // rgba(120,150,190,.2)
val SleepDeep = Color(0xFF3E699F)
val SleepLight = Color(0xFF31517A)
val SleepRem = Color(0xFF9DBFE8)
val SleepTextDim = Color(0xFF7E93AC)

val Positive = Color(0xFF2FBE71)            // oklch(0.72 0.17 150)
val Error = Color(0xFFFF6B57)               // oklch(0.72 0.17 25)
val LogoutText = Color(0xFFFF8A75)
val LiveDot = Color(0xFFFF4D3A)

val TabPillActive = Color(0x24FF7A00)       // rgba(255,122,0,.14)

val AccentGradient = Brush.linearGradient(listOf(Accent, Accent2))
val AccentGradientVertical = Brush.verticalGradient(listOf(Accent, Accent2))

// Fundaluri translucide peste video
val OverVideoFill = Color(0xB8101114)       // rgba(16,17,20,.72)
val UtilFill = Color(0xFF17181C)
val SwitchOff = Color(0xFF2A2A30)
