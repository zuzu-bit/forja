package com.forja.app.feature.games

import androidx.compose.ui.graphics.Color

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

/** Nicovala: fața de oțel (lumină sus, umbră jos), gâtul și talpa. */
internal val AnvilFace = Color(0xFF9CA1A9)
internal val AnvilFaceShade = Color(0xFF6B7079)
internal val AnvilBody = Color(0xFF4A4D55)
internal val AnvilFoot = Color(0xFF3A3D44)

/** Lada din ASALT: olive, cu scânduri mai închise. */
internal val CratePlank = Color(0xFF4A5D3A)

/**
 * Voalul de sub cardurile de pauză și final, peste toată suprafața de sub antet: aproape opac, ca ce rămâne în jurul
 * cardului (tabla, coloana, zidul) să se citească drept fundal, nu drept etichete și piese tăiate de marginea cardului.
 */
internal val GameScrim = Surface0.copy(alpha = 0.9f)

/** De unde începe voalul: sub antetul de 44 dp (cu marginea de sus de 12). Antetul (pastila) rămâne viu. */
internal const val GAME_COVER_TOP_DP = 56

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
    Accent2,        // a · ladă
    Amber,          // b · sac de nisip
    TextDim,        // c · beton
    Rule,           // # · oțel
    EmberWarm,      // x · muniție
    Sage,           // w · Lat
    Sage,           // m · Schije
    Sage            // s · Calm
)

/**
 * Marginea dinăuntrul terenului ASALT (dp, pe fiecare latură): zidul și pereții scânteii stau la 4 dp de conturul
 * cardului, nu lipite de el (ca celulele ZID). Lumea jocului se scalează în interior; cardul o înconjoară.
 */
internal const val ASALT_INSET_DP = 4

/** Coloana laterală a ZID (URM., REZ., contorul, mascota). */
internal const val ZID_SIDE_DP = 64
internal const val ZID_GAP_DP = 10
internal const val ZID_PAD_MIN_DP = 120
