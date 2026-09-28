package com.forja.app.feature.inventory

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.forja.app.core.designsystem.Accent2
import com.forja.app.core.designsystem.LocalReducedMotion
import com.forja.app.core.designsystem.Springs
import com.forja.app.core.designsystem.Surface1
import com.forja.app.core.designsystem.components.Mascot
import com.forja.app.core.designsystem.components.MascotState
import com.forja.app.core.designsystem.components.pressable
import com.forja.app.core.inventory.InvProgress
import com.forja.app.core.inventory.InvStage
import com.forja.app.core.inventory.Inventory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Cereri de navigare spre o pagină a Inventarului (pastila globală, modurile de așteptare, notificarea):
 * ecranul de pe ruta CLEANUP o citește la intrare sau, dacă e deja deschis, o primește pe loc.
 */
object InventoryLinks {
    private val _open = MutableStateFlow<InvPage?>(null)
    val open: StateFlow<InvPage?> = _open.asStateFlow()

    fun request(page: InvPage) { _open.value = page }
    fun peek(): InvPage? = _open.value
    fun consume(): InvPage? = _open.value.also { _open.value = null }

    private val _seenReady = MutableStateFlow<String?>(null)
    /** Rularea ale cărei dosare au fost deja văzute (S4): pastila olive „Gata” nu mai stă pe ecran. */
    val seenReady: StateFlow<String?> = _seenReady.asStateFlow()
    fun markReadySeen(runId: String?) { if (runId != null) _seenReady.value = runId }
}

/** Starea pastilei din progresul motorului; null = nimic de arătat. */
fun pillStateOf(p: InvProgress?): PillState? = when (p?.stage) {
    InvStage.Scanning, InvStage.Grouping, InvStage.Naming, InvStage.Applying -> PillState(percentOf(p.done, p.total), ready = false)
    InvStage.Ready -> PillState(100, ready = true)
    else -> null
}

/**
 * Pastila de progres GLOBALĂ (MainActivity): sus, centrată, cât timp rularea e activă, pe ecranele din afara
 * Inventarului (acolo progresul e deja pe ecran) și a modurilor de așteptare (acolo pastila e în antet).
 * Când rularea ajunge „gata”: pastila devine olive cu bifă, vibrație scurtă și un toast cu mascota fericită.
 * `visibleOnRoute` = ruta curentă permite pastila; `toastOnRoute` = ruta curentă permite toastul de final.
 */
@Composable
fun InventoryPillHost(
    visibleOnRoute: Boolean,
    toastOnRoute: Boolean,
    onOpen: (InvPage) -> Unit,
    modifier: Modifier = Modifier
) {
    val progress by Inventory.progress.collectAsState()
    val state = pillStateOf(progress)
    val reduced = LocalReducedMotion.current
    val view = LocalView.current

    // „Gata” văzut pentru această rulare (atingere sau dosarele deschise): pastila olive nu rămâne pe ecran la nesfârșit.
    val seenReady by InventoryLinks.seenReady.collectAsState()
    var toast by remember { mutableStateOf(false) }
    var lastStage by remember { mutableStateOf<InvStage?>(null) }
    val runId = progress?.runId
    LaunchedEffect(progress?.stage, runId) {
        val now = progress?.stage
        val before = lastStage
        lastStage = now
        if (now == InvStage.Ready && before != null && before in RUNNING_STAGES) {
            // Vibrația scurtă a UI-ului; sunetul și pauza muzicii le face motorul (Music.onInventoryDone).
            view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
            toast = true
        }
    }
    LaunchedEffect(toast) {
        if (toast) { delay(4_500); toast = false }
    }

    val show = visibleOnRoute && state != null && !(state.ready && seenReady == runId)
    Column(
        modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedVisibility(
            visible = show,
            enter = if (reduced) fadeIn(snap()) else slideInVertically(Springs.natural()) { -it * 2 } + fadeIn(tween(160)),
            exit = if (reduced) fadeOut(snap()) else slideOutVertically(tween(220)) { -it * 2 } + fadeOut(tween(160))
        ) {
            val s = state ?: PillState(0, false)
            InvProgressPill(
                s,
                onClick = {
                    if (s.ready) {
                        InventoryLinks.markReadySeen(runId)
                        toast = false
                        onOpen(InvPage.Folders)
                    } else {
                        onOpen(if (progress?.stage == InvStage.Applying) InvPage.Apply else InvPage.Run)
                    }
                },
                elevated = true
            )
        }
        AnimatedVisibility(
            visible = toast && toastOnRoute,
            enter = if (reduced) fadeIn(snap()) else slideInVertically(Springs.natural()) { -it } + fadeIn(tween(180)),
            exit = if (reduced) fadeOut(snap()) else slideOutVertically(tween(220)) { -it } + fadeOut(tween(160))
        ) {
            ReadyToast(onOpen = {
                toast = false
                InventoryLinks.markReadySeen(runId)
                onOpen(InvPage.Folders)
            })
        }
    }
}

/** Toastul de final: mascota fericită + „Dosarele sunt gata” + „Vezi”. */
@Composable
internal fun ReadyToast(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier
            .pressable(onOpen)
            .padding(top = 10.dp, start = 20.dp, end = 20.dp)
            .widthIn(max = 360.dp)
            .shadow(18.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(Surface1)
            .border(1.dp, W12, shape)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "Dosarele sunt gata. Vezi."
                liveRegion = LiveRegionMode.Polite
            }
            .padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Mascot(state = MascotState.Happy, size = 44.dp)
        Spacer(Modifier.width(8.dp))
        Text("Dosarele sunt gata", style = cond(18))
        Spacer(Modifier.width(14.dp))
        Box(contentAlignment = Alignment.Center) {
            Text("Vezi", style = cond(18, color = Accent2))
        }
    }
}
