package com.forja.app.feature.games

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.forja.app.core.games.GameId
import com.forja.app.core.games.GameProgress
import com.forja.app.core.games.GameStore

/** Nivelurile arătate pe carduri (null = încă necitite). */
data class WaitLevels(val zid: Int?, val asalt: Int?)

/** Nivelul curent al fiecărui joc, pentru cardurile „Cât aștepți”. */
@Composable
fun rememberWaitLevels(): WaitLevels {
    val context = LocalContext.current
    val zidFlow = remember(context) { GameStore.progress(context, GameId.Zid) }
    val asaltFlow = remember(context) { GameStore.progress(context, GameId.Asalt) }
    val z by zidFlow.collectAsState(initial = null)
    val a by asaltFlow.collectAsState(initial = null)
    return WaitLevels(z?.let { cardLevel(GameId.Zid, it) }, a?.let { cardLevel(GameId.Asalt, it) })
}

private fun cardLevel(game: GameId, p: GameProgress): Int = p.current(game)
