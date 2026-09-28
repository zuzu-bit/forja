package com.forja.app.core.designsystem.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/*
 * STUB TEMPORAR (pachetul D, FORJA 4.3) — doar semnăturile EXACTE din DESIGN-4.3 §8, ca Echiparea să compileze.
 * Cadrul adevărat (suprapunere întunecată cu decupaj, MascotSays, „Înainte” / „Am înțeles”, DataStore „forja_tutorial”)
 * vine din pachetul E și ÎNLOCUIEȘTE acest fișier la integrare. Aici: nicio suprapunere, doar conținutul.
 */

/** Un pas de ghidaj: ținta (cheia dată cu [coachTarget]), textul (≤ 90 de caractere) și fața mascotei. */
data class CoachStep(val target: String, val text: String, val mascot: MascotState = MascotState.Talking)

/** STUB: randează doar conținutul; ghidajul adevărat (o singură dată pe ecran) vine din pachetul E. */
@Composable
fun CoachMarks(screen: String, steps: List<CoachStep>, content: @Composable () -> Unit) {
    content()
}

/** STUB: marchează ținta unui pas de ghidaj; aici nu schimbă nimic. */
fun Modifier.coachTarget(key: String): Modifier = this

/** STUB: starea ghidajului per ecran; aici totul e „văzut”, deci nimic nu se arată. */
object Tutorial {
    suspend fun reset(context: Context) {}
    fun seen(context: Context, screen: String): Flow<Boolean> = flowOf(true)
    suspend fun markSeen(context: Context, screen: String) {}
}
