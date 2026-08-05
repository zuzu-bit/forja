@file:OptIn(ExperimentalTextApi::class)

package com.forja.app.core.designsystem

import androidx.compose.ui.text.ExperimentalTextApi

import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.forja.app.R

// Display: Barlow Condensed — rețeta „Condensat" din Design Studio
// (500/600/700 statice, latin + latin-ext, deci cu diacritice românești)
val BarlowCondensed = FontFamily(
    Font(R.font.barlowc_500, weight = FontWeight.Medium),
    Font(R.font.barlowc_600, weight = FontWeight.SemiBold),
    Font(R.font.barlowc_600, weight = FontWeight.Bold),
    Font(R.font.barlowc_700, weight = FontWeight.ExtraBold),
    Font(R.font.barlowc_700, weight = FontWeight.Black)
)

// Archivo rămâne disponibil (variabil, axa wdth) pentru accente rare
val ArchivoExpanded = FontFamily(
    Font(
        R.font.archivo_var, weight = FontWeight.ExtraBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(800), FontVariation.width(118f))
    ),
    Font(
        R.font.archivo_var, weight = FontWeight.Black,
        variationSettings = FontVariation.Settings(FontVariation.weight(900), FontVariation.width(122f))
    ),
    Font(
        R.font.archivo_var, weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700), FontVariation.width(114f))
    )
)

// Body: Hanken Grotesk (variabil, 400–800)
val Hanken = FontFamily(
    Font(R.font.hanken_var, weight = FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.hanken_var, weight = FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.hanken_var, weight = FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.hanken_var, weight = FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.hanken_var, weight = FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(800)))
)

// Utilitar: JetBrains Mono
val Mono = FontFamily(
    Font(R.font.jbmono_var, weight = FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.jbmono_var, weight = FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.jbmono_var, weight = FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700)))
)

private val Flat = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)
private val NoPad = PlatformTextStyle(includeFontPadding = false)

// Titluri module 26–28
val TitleModule = TextStyle(
    fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold,
    fontSize = 28.sp, lineHeight = 31.sp, color = TextPrimary,
    letterSpacing = 0.01.em,
    platformStyle = NoPad, lineHeightStyle = Flat
)

// Onboarding 40
val TitleOnboarding = TitleModule.copy(fontSize = 40.sp, lineHeight = 43.sp, fontWeight = FontWeight.Black)

// Splash 56
val TitleSplash = TitleModule.copy(fontSize = 56.sp, lineHeight = 58.sp, fontWeight = FontWeight.Black)

// Numerale-erou (tabular) — condensate, à la cronometru militar
fun heroNumeral(size: Int) = TextStyle(
    fontFamily = BarlowCondensed, fontWeight = FontWeight.ExtraBold,
    fontSize = size.sp, lineHeight = (size + 2).sp, color = TextPrimary,
    fontFeatureSettings = "tnum",
    platformStyle = NoPad, lineHeightStyle = Flat
)

val Body = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 19.sp, color = TextSecondary, platformStyle = NoPad)
val BodyStrong = Body.copy(fontWeight = FontWeight.Bold, color = TextPrimary)
val BodySmall = Body.copy(fontSize = 12.sp, lineHeight = 16.sp)
val BodyTiny = Body.copy(fontSize = 11.sp, lineHeight = 14.sp)

// Etichete mono UPPERCASE, letter-spacing .08–.18em
fun monoLabel(size: Int = 10, tracking: Float = 0.14f) = TextStyle(
    fontFamily = Mono, fontWeight = FontWeight.Medium,
    fontSize = size.sp, letterSpacing = tracking.em, color = TextDim,
    platformStyle = NoPad
)

// Text butoane primare: Barlow Condensed 700, ușor mai mare (condensat)
val ButtonText = TextStyle(
    fontFamily = BarlowCondensed, fontWeight = FontWeight.ExtraBold,
    fontSize = 17.sp, letterSpacing = 0.02.em, color = OnAccent, platformStyle = NoPad
)
val ButtonTextSmall = ButtonText.copy(fontSize = 15.sp)
