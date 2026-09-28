package com.forja.app.feature.inventory

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.forja.app.core.designsystem.Accent
import com.forja.app.core.designsystem.Accent2
import com.forja.app.core.designsystem.Body
import com.forja.app.core.designsystem.TextPrimary
import com.forja.app.core.designsystem.TextSecondary
import com.forja.app.core.designsystem.TitleModule
import com.forja.app.core.designsystem.heroNumeral
import com.forja.app.core.designsystem.monoLabel
import java.util.Calendar

/*
 * Inventarul 4.3 — valorile din prototip (proto43/…dc.html; px = dp) care nu există în Color.kt.
 * Numite o singură dată aici; restul culorilor vin din designsystem (Surface0/1/2, Accent, Accent2, TextDim…).
 */

internal val Amber = Color(0xFFF3B952)            // #F3B952 — progres, jar, pas activ
internal val AmberPale = Color(0xFFFFD27A)        // #FFD27A — scântei
internal val MediaBg = Color(0xFF16181C)          // #16181C — fundalul zonelor media din carduri
internal val Raised = Color(0xFF17181C)           // #17181C — butoane-iconiță
internal val Stack1 = Color(0xFF1B1C20)           // #1B1C20 — foaia din spate a teancului
internal val Stack2 = Color(0xFF23252B)           // #23252B — foaia din mijloc / segment activ
internal val Track = Color(0xFF2A2B30)            // #2A2B30 — segmente viitoare, comutator oprit
internal val Rule = Color(0xFF3A3D44)             // #3A3D44 — contur stins, sol, puncte
internal val TrackBar = Color(0xFF1F2024)         // #1F2024 — bara piesei
internal val Paper = Color(0xFFE9E4DA)            // foaia unui document
internal val PaperInk = Color(0xFF9A958C)         // rândurile de pe foaie
internal val PaperFold = Color(0xFFC9C3B8)        // colțul îndoit
internal val DocBlue = Color(0xFF7FA3C7)          // insigna DOCX
internal val MoodBlue = Color(0xFF7FA3C7)         // Obosit
internal val MoodViolet = Color(0xFF9D8FC9)       // Neliniștit
internal val MoodRed = Color(0xFFFF6B57)          // Nervos
internal val MusicGlow = Color(0xFF1A1712)        // centrul fundalului radial la Muzică

internal val W06 = Color(0x0FFFFFFF)
internal val W08 = Color(0x14FFFFFF)
internal val W09 = Color(0x17FFFFFF)
internal val W10 = Color(0x1AFFFFFF)
internal val W12 = Color(0x1FFFFFFF)
internal val W14 = Color(0x24FFFFFF)
internal val W16 = Color(0x29FFFFFF)

/** Gradientul butonului principal (90°, #4A5D3A → #6F855A). */
internal val CtaBrush = Brush.horizontalGradient(listOf(Accent, Accent2))

internal val R4 = RoundedCornerShape(4.dp)
internal val R5 = RoundedCornerShape(5.dp)
internal val R6 = RoundedCornerShape(6.dp)
internal val R8 = RoundedCornerShape(8.dp)
internal val SheetTop = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)

// ───────────────────────────── Tipografia din prototip ─────────────────────────────
// Barlow Condensed 700/800 → fișierul 700 (FontWeight.ExtraBold în Type.kt, ca ButtonText); 600 → SemiBold.

/** Barlow Condensed (titluri, etichete mari). */
internal fun cond(size: Int, line: Int = size + 2, color: Color = TextPrimary, tracking: Float = 0f, semi: Boolean = false): TextStyle =
    TitleModule.copy(
        fontSize = size.sp, lineHeight = line.sp, color = color, letterSpacing = tracking.em,
        fontWeight = if (semi) FontWeight.SemiBold else FontWeight.ExtraBold
    )

/** Cifre-erou (tabulare). */
internal fun hero(size: Int, line: Int = size, color: Color = TextPrimary): TextStyle =
    heroNumeral(size).copy(lineHeight = line.sp, color = color)

/** JetBrains Mono, etichete. */
internal fun mono(size: Int, tracking: Float = 0f, color: Color = TextSecondary, bold: Boolean = false): TextStyle =
    monoLabel(size, tracking).copy(color = color, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium)

/** Hanken Grotesk, corp. */
internal fun body(size: Int, color: Color = TextSecondary, weight: FontWeight = FontWeight.SemiBold, line: Int = size + 5): TextStyle =
    Body.copy(fontSize = size.sp, lineHeight = line.sp, color = color, fontWeight = weight)

// ───────────────────────────── Formate ─────────────────────────────

private const val NBSP = ' '

/** „3 214” (spațiu neîntrerupt între mii). */
internal fun fmtCount(n: Int): String {
    val s = n.coerceAtLeast(0).toString()
    val sb = StringBuilder()
    s.forEachIndexed { i, c ->
        if (i > 0 && (s.length - i) % 3 == 0) sb.append(NBSP)
        sb.append(c)
    }
    return sb.toString()
}

/** „12,4 GB”, „380 MB”, „940 KB”. */
internal fun fmtSize(bytes: Long): String {
    val b = bytes.coerceAtLeast(0L).toDouble()
    val gb = b / (1024.0 * 1024 * 1024)
    val mb = b / (1024.0 * 1024)
    return when {
        gb >= 100 -> "${gb.toLong()}${NBSP}GB"
        gb >= 1 -> "${oneDecimal(gb)}${NBSP}GB"
        mb >= 1 -> "${mb.toLong().coerceAtLeast(1)}${NBSP}MB"
        else -> "${(b / 1024).toLong().coerceAtLeast(if (bytes > 0) 1 else 0)}${NBSP}KB"
    }
}

private fun oneDecimal(v: Double): String {
    val tenths = Math.round(v * 10)
    return "${tenths / 10},${tenths % 10}"
}

/** „~ 25 MIN” — estimarea sinceră, rotunjită în sus; null sub o secundă. */
internal fun fmtMinutes(sec: Int?): String? {
    if (sec == null || sec <= 0) return null
    val min = ((sec + 59) / 60).coerceAtLeast(1)
    return "~$NBSP$min${NBSP}MIN"
}

/** „34 %”. */
internal fun fmtPercent(p: Int): String = "${p.coerceIn(0, 100)}$NBSP%"

/** „1:12” — minute:secunde. */
internal fun fmtClock(ms: Long): String {
    val s = (ms.coerceAtLeast(0L) / 1000).toInt()
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

private val MONTHS = arrayOf("ian", "feb", "mar", "apr", "mai", "iun", "iul", "aug", "sep", "oct", "nov", "dec")

/** „aug 2023” din momentul dat (fus orar local). */
internal fun fmtMonth(ms: Long): String {
    if (ms <= 0) return ""
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    return "${MONTHS[c.get(Calendar.MONTH)]} ${c.get(Calendar.YEAR)}"
}

/** „14 aug 2023”. */
internal fun fmtDay(ms: Long): String {
    if (ms <= 0) return ""
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    return "${c.get(Calendar.DAY_OF_MONTH)} ${MONTHS[c.get(Calendar.MONTH)]} ${c.get(Calendar.YEAR)}"
}

/** Cheia lunii (an*12+lună), pentru antetele de lună. */
internal fun monthKey(ms: Long): Int {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    return c.get(Calendar.YEAR) * 12 + c.get(Calendar.MONTH)
}

/** Procentul întreg dintr-un progres (0..100). */
internal fun percentOf(done: Int, total: Int): Int =
    if (total <= 0) 0 else ((done.toLong() * 100) / total).toInt().coerceIn(0, 100)

