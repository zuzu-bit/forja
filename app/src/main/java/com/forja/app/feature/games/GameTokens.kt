package com.forja.app.feature.games

import androidx.compose.ui.graphics.Color
import com.forja.app.core.designsystem.Accent
import com.forja.app.core.designsystem.Accent2
import com.forja.app.core.designsystem.EmberWarm
import com.forja.app.core.designsystem.Surface0
import com.forja.app.core.designsystem.TextDim
import com.forja.app.feature.inventory.Amber
import com.forja.app.feature.inventory.PaperFold
import com.forja.app.feature.inventory.Rule

/*
 * Paleta jocurilor: culorile „materialelor” FORJA (olive, jar, nisip, oțel), nu șapte nuanțe aprinse.
 * Numite o singură dată aici; restul vine din designsystem și din tokenii Inventarului.
 */

/** Salvia de pe FRONT (DESIGN §11.2) — piesa S. */
internal val Sage = Color(0xFF8FA876)

/** Teracota — piesa Z. */
internal val Terracotta = Color(0xFFB5654A)

/** Albastrul de oțel (fostul MoodBlue) — piesa J. */
internal val SteelBlue = Color(0xFF7FA3C7)

/** Niturile molozului și ale oțelului. */
internal val Rivet = Color(0xFF5A5D63)

/** Nicovala: corpul și fața. */
internal val AnvilBody = Color(0xFF2A2B30)
internal val AnvilFace = Color(0xFF5A5D63)

/** Lada din ASALT: olive, cu scânduri mai închise. */
internal val CratePlank = Color(0xFF3B4A2F)

/** Voalul de sub cardurile de pauză și final. */
internal val GameScrim = Surface0.copy(alpha = 0.72f)

/** Culoarea fiecărei piese ZID, indexată pe cod (0 gol, 1–7 I O T S Z J L, 8 moloz). */
internal val ZidKindColors: Array<Color> = arrayOf(
    Color.Transparent,
    Accent2,        // I
    Amber,          // O
    EmberWarm,      // T
    Sage,           // S
    Terracotta,     // Z
    SteelBlue,      // J
    PaperFold,      // L
    Rule            // moloz
)

/** Culorile cărămizilor ASALT, indexate pe fel (AsaltEngine.CRATE…SLOW). */
internal val AsaltBrickColors: Array<Color> = arrayOf(
    Color.Transparent,
    Accent,         // a · ladă
    Amber,          // b · sac de nisip
    TextDim,        // c · beton
    Rule,           // # · oțel
    EmberWarm,      // x · muniție
    Accent2,        // w · Lat
    Accent2,        // m · Schije
    Accent2         // s · Calm
)

/** Coloana laterală a ZID (URM., REZ., contorul, mascota). */
internal const val ZID_SIDE_DP = 64
internal const val ZID_GAP_DP = 10
internal const val ZID_PAD_MIN_DP = 120
