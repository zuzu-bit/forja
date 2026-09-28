package com.forja.app.feature.games

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.forja.app.feature.inventory.InvIconButton
import com.forja.app.feature.inventory.InvIcons
import com.forja.app.feature.inventory.InvProgressPill
import com.forja.app.feature.inventory.PillState

/*
 * Antetul jocurilor (fără stare, îl randează și capturile): pastila inventarului sau „Închide”, mijlocul, acțiunile.
 */

/**
 * Antetul jocului (44 dp): pastila inventarului sau „Închide” în stânga, ceva la mijloc (viețile), acțiunile în dreapta.
 * Fără pastilă (rulare eșuată sau anulată): „Închide”, iar jocul merge mai departe.
 */
@Composable
internal fun GameHeader(
    pill: PillState?,
    onPill: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    center: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {}
) {
    Box(modifier.fillMaxWidth().height(44.dp)) {
        Box(Modifier.align(Alignment.CenterStart)) {
            if (pill != null) InvProgressPill(pill, onPill) else InvIconButton(InvIcons.Close, "Închide", onClose, iconSize = 18.dp)
        }
        Box(Modifier.align(Alignment.Center)) { center() }
        Row(
            Modifier.align(Alignment.CenterEnd),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) { trailing() }
    }
}

/** Butonul de pauză din antet. */
@Composable
internal fun PauseButton(onPause: () -> Unit, modifier: Modifier = Modifier) {
    InvIconButton(InvIcons.Pause, "Pauză", onPause, modifier = modifier, iconSize = 18.dp)
}
