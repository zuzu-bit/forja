package com.forja.app.feature.soldier

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.forja.app.ForjaApp
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*
import com.forja.app.core.soldier.Missions
import com.forja.app.core.soldier.Ranks
import com.forja.app.core.soldier.SoldierStore
import com.forja.app.core.util.Fmt
import kotlinx.coroutines.launch

/**
 * Cardul Cascăi de pe panoul „Azi”: mascota în uniformă, gradul, drumul spre gradul următor și misiunile de azi —
 * dintr-o privire, fără text obositor. Atingerea deschide Cazarma. Punctele se sincronizează la fiecare revenire.
 */
@Composable
fun SoldierCard(modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val context = LocalContext.current
    val app = remember { ForjaApp.from(context) }
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    val state by SoldierStore.state.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) scope.launch {
                try {
                    val r = Missions.sync(app, foreground = true)
                    if (r.newPoints > 0) toast.show("Casca: +${r.newPoints} puncte (${r.newlyDone.joinToString(", ") { it.short.lowercase() }}).")
                } catch (_: Exception) { }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val rank = state.rank
    val next = Ranks.next(rank)
    val progress = Ranks.progress(state.earned)
    val day = Fmt.epochDay()
    val done = Missions.countMissions(state.doneOn(day))
    val points = Missions.pointsOn(state, day)

    Row(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Surface1)
            .border(1.dp, if (state.promotionPending) Color(0x996F855A) else StrokeCard, CardShape)
            .semantics {
                role = Role.Button
                contentDescription = "Casca, ${rank.name}. $done misiuni bifate azi, $points puncte. Deschide Cazarma."
            }
            .pressable(onOpen, scaleDown = 0.985f)
            .padding(start = 8.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Mascot(size = 96.dp, state = if (state.promotionPending) MascotState.Happy else MascotState.Idle)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StampLabel(rank.name.uppercase(), rotationDeg = -3f, fontSize = 10, appear = false)
                Spacer(Modifier.weight(1f))
                if (state.promotionPending) {
                    Text("GRAD NOU", style = monoLabel(8, 0.14f).copy(color = EmberHot))
                } else {
                    Icon(Icons.Filled.Star, contentDescription = null, tint = EmberHot, modifier = Modifier.size(11.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("${state.balance}", style = monoLabel(9, 0.10f).copy(color = TextSecondary))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (next != null) "${next.minPoints - state.earned} puncte până la ${next.name}" else "Gradul cel mai înalt",
                style = BodySmall.copy(color = TextSecondary)
            )
            Spacer(Modifier.height(6.dp))
            ProgressBar(progress, Modifier.fillMaxWidth(), height = 4.dp)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Misiunile: un punct pe misiune — bifate olive, restul stinse.
                val total = Missions.all.size
                repeat(total) { i ->
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (i < done) Accent2 else SwitchOff)
                    )
                    if (i < total - 1) Spacer(Modifier.width(3.dp))
                }
                Spacer(Modifier.weight(1f))
                Text(if (points > 0) "+$points AZI" else "MISIUNILE DE AZI", style = monoLabel(8, 0.12f).copy(color = if (points > 0) Accent2 else TextDim))
            }
        }
    }
}
