package com.forja.app.feature.soldier

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.forja.app.ForjaApp
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*
import com.forja.app.core.soldier.Gear
import com.forja.app.core.soldier.GearItem
import com.forja.app.core.soldier.MissionStatus
import com.forja.app.core.soldier.Missions
import com.forja.app.core.soldier.Rank
import com.forja.app.core.soldier.Ranks
import com.forja.app.core.soldier.Slot
import com.forja.app.core.soldier.SoldierState
import com.forja.app.core.soldier.SoldierStore
import com.forja.app.core.util.Fmt
import kotlinx.coroutines.launch

private const val DETAILS =
    "Casca avansează în grad cu ce faci tu zilnic în FORJA. Nu bifezi nimic de mână: misiunile se citesc din jurnale — " +
        "o masă în jurnal, o tură de un kilometru, un antrenament încheiat, noaptea înregistrată, cincisprezece minute de focus, " +
        "un minut de respirație, paznicul Detox ținut, un loc nou pe hartă, un joc câștigat, o comandă „Hei FORJA”, prezența " +
        "și energia trimisă unui prieten. Fiecare misiune dă puncte o singură dată pe zi.\n\n" +
        "Punctele câștigate vreodată dau gradul (Recrut → Soldat → Fruntaș → Caporal → Sergent → … → General) și nu scad. " +
        "Soldul se cheltuie în garderobă: fiecare piesă are un grad de la care se poate purta și un preț. Fiecare grad nou vine " +
        "cu o piesă în dar, pusă direct pe Casca.\n\n" +
        "Seria: trei misiuni într-o zi fac o zi bună; șapte zile bune la rând aduc un bonus de 50 de puncte.\n\n" +
        "Ținuta se vede peste tot unde apare Casca: pe panou, în bule, în ghidaje, în notificări. Totul rămâne pe telefon."

/** „Cazarma”: mascota în uniformă, gradul și drumul spre următorul, misiunile de azi, garderoba și scara gradelor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoldierScreen(onBack: () -> Unit, onOpenRoute: (String) -> Unit) {
    val context = LocalContext.current
    val app = remember { ForjaApp.from(context) }
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    val haptics = LocalHapticFeedback.current

    val state by SoldierStore.state.collectAsState()
    var today by remember { mutableStateOf<List<MissionStatus>>(emptyList()) }

    // La intrare și la fiecare revenire: punctele pentru ce s-a făcut între timp („+N puncte” vine din schimbarea stării).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) scope.launch {
                try { today = Missions.sync(app, foreground = true).today } catch (_: Exception) { }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    SoldierPointsToast(state) { pts, names ->
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        toast.show("+$pts puncte: $names")
    }

    val rank = state.rank
    val next = Ranks.next(rank)
    val progress = Ranks.progress(state.earned)
    val day = Fmt.epochDay()
    val todayPoints = Missions.pointsOn(state, day)
    val doneToday = Missions.countMissions(state.doneOn(day))
    var slot by remember { mutableStateOf(Slot.HEAD) }
    var buying by remember { mutableStateOf<GearItem?>(null) }
    var celebrate by remember { mutableStateOf(false) }
    LaunchedEffect(state.promotionPending) { if (state.promotionPending) celebrate = true }

    Box(Modifier.fillMaxSize().topoBackground(decor = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 40.dp)
        ) {
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ModuleHeader(stamp = "CAZARMA", title = "Casca, ${rank.name}", modifier = Modifier.weight(1f), titleStyle = TitleModule.copy(fontSize = 26.sp), reveal = false)
                Spacer(Modifier.width(12.dp))
                SecondaryButton("Închide", onClick = onBack, modifier = Modifier.semantics { role = Role.Button }, padV = 8.dp)
            }

            Spacer(Modifier.height(12.dp))
            // ── Eroul: Casca în uniformă, pe un fundal cu jar; gradul, punctele și drumul spre gradul următor ──
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(272.dp)
                    .clip(CardShape)
                    .background(Surface1)
                    .border(1.dp, StrokeCard, CardShape)
            ) {
                EmberField(Modifier.fillMaxSize(), count = 26, alpha = 0.5f, speed = 0.7f)
                Mascot(
                    state = if (celebrate) MascotState.Happy else MascotState.Idle,
                    size = 200.dp,
                    modifier = Modifier.align(Alignment.Center).padding(bottom = 22.dp),
                    onTap = { toast.show(mascotLine(state, doneToday)) },
                    description = "Casca. Atinge ca să-ți spună cum stă ziua."
                )
                StampLabel(rank.name.uppercase(), modifier = Modifier.align(Alignment.TopStart).padding(14.dp), rotationDeg = -4f, appear = false)
                PointsChip(state.balance, Modifier.align(Alignment.TopEnd).padding(12.dp))
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${state.earned} PUNCTE CÂȘTIGATE", style = monoLabel(9, 0.12f).copy(color = TextSecondary), maxLines = 1)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (next != null) "ÎNCĂ ${next.minPoints - state.earned} · ${next.name.uppercase()}" else "GRADUL CEL MAI ÎNALT",
                            style = monoLabel(9, 0.12f).copy(color = Accent2), textAlign = TextAlign.End,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    ProgressBar(progress, Modifier.fillMaxWidth(), height = 5.dp)
                }
            }

            // ── Misiunile de azi ──
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Misiunile de azi")
                Spacer(Modifier.weight(1f))
                Text("+$todayPoints AZI · $doneToday DIN ${Missions.all.size}", style = monoLabel(9, 0.12f).copy(color = if (doneToday > 0) Accent2 else TextDim))
                Spacer(Modifier.width(8.dp))
                InfoDot(text = DETAILS, title = "Casca în uniformă", size = 20)
            }
            Spacer(Modifier.height(10.dp))
            val missions = if (today.isNotEmpty()) today else Missions.all.map { MissionStatus(it, it.id in state.doneOn(day)) }
            missions.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max)) {
                    row.forEachIndexed { i, m ->
                        MissionTile(m, Modifier.weight(1f).fillMaxHeight().padding(end = if (i < 2) 8.dp else 0.dp)) {
                            val r = m.mission.route
                            if (m.done) toast.show("${m.mission.title}: bifată azi, +${m.mission.points}.")
                            else if (r != null) onOpenRoute(r) else toast.show("Se bifează singură când deschizi FORJA.")
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f).padding(end = 8.dp)) }
                }
                Spacer(Modifier.height(8.dp))
            }
            if (state.streak > 0) {
                Spacer(Modifier.height(2.dp))
                val todayGood = doneToday >= Missions.GOOD_DAY
                Text(
                    "Seria: ${state.streak} ${if (state.streak == 1) "zi bună" else "zile bune"} la rând" +
                        when {
                            state.streak % 7 != 6 -> "."
                            todayGood -> " — mâine, bonusul de ${Missions.STREAK_BONUS}."
                            else -> " — azi, la a ${Missions.GOOD_DAY}-a misiune, bonusul de ${Missions.STREAK_BONUS}."
                        },
                    style = BodyTiny.copy(color = TextSecondary)
                )
            }

            // ── Garderoba ──
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Garderoba")
                Spacer(Modifier.weight(1f))
                Text("${state.owned.size} DIN ${Gear.all.size} PIESE", style = monoLabel(9, 0.12f).copy(color = TextDim))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Slot.entries.forEach { s ->
                    SlotChip(s.label, s == slot, worn = state.equipped[s] != null) { slot = s }
                    Spacer(Modifier.width(6.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            val items = Gear.inSlot(slot)
            items.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max)) {
                    row.forEachIndexed { i, item ->
                        GearTile(item, state, Modifier.weight(1f).fillMaxHeight().padding(end = if (i < 2) 8.dp else 0.dp)) {
                            val equipped = state.equipped[item.slot] == item.id
                            when {
                                equipped -> scope.launch { SoldierStore.equip(app, item.slot, null); toast.show("Jos: ${item.name}.") }
                                item.id in state.owned -> scope.launch {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    SoldierStore.equip(app, item.slot, item.id); toast.show("Pe Casca: ${item.name}.")
                                }
                                state.rank.index < item.rank -> toast.show("${item.name} se deblochează la gradul de ${Ranks.byIndex(item.rank).name}.")
                                else -> buying = item
                            }
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f).padding(end = 8.dp)) }
                }
                Spacer(Modifier.height(8.dp))
            }

            // ── Gradele ──
            Spacer(Modifier.height(16.dp))
            SectionLabel("Gradele")
            Spacer(Modifier.height(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Ranks.all.forEach { r ->
                    RankCard(r, state)
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
    }

    buying?.let { item ->
        BuySheet(item, state, onBuy = {
            scope.launch {
                val ok = SoldierStore.buy(app, item.id)
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                toast.show(if (ok) "Gata: ${item.name} e deja pe Casca." else "Nu s-a putut cumpăra.")
                buying = null
            }
        }, onClose = { buying = null })
    }
    if (celebrate) {
        PromotionSheet(state.rank, onClose = {
            celebrate = false
            scope.launch { SoldierStore.promotionSeen(app) }
        })
    }
}

/** Vorba Cascăi la atingere: după cum stă ziua. */
private fun mascotLine(s: SoldierState, done: Int): String = when {
    done == 0 -> "Prezent! Azi n-am bifat nimic încă. O masă în jurnal sau o tură și pornim."
    done < Missions.GOOD_DAY -> "Bun început: $done ${if (done == 1) "misiune" else "misiuni"}. Încă ${Missions.GOOD_DAY - done} și e o zi bună."
    done < 6 -> "Zi bună: $done misiuni. Seria merge."
    else -> "Zi de ${s.rank.name}: $done misiuni. Misiune îndeplinită."
}

@Composable
private fun PointsChip(balance: Int, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(ChipShape)
            .background(Color(0xB80D1207))
            .border(1.dp, Color(0x4D90A873), ChipShape)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics { contentDescription = "$balance puncte de cheltuit" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Star, contentDescription = null, tint = EmberHot, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text("$balance", style = BodyStrong.copy(fontSize = 15.sp, color = TextPrimary))
        Spacer(Modifier.width(4.dp))
        Text("DE CHELTUIT", style = monoLabel(8, 0.14f).copy(color = TextSecondary))
    }
}

private fun missionIcon(id: String): ImageVector = when (id) {
    "meal", "meals3" -> Icons.Outlined.Restaurant
    "move", "move5" -> Icons.Outlined.DirectionsRun
    "workout" -> Icons.Outlined.FitnessCenter
    "sleep" -> Icons.Outlined.Bedtime
    "focus" -> Icons.Outlined.SelfImprovement
    "breath" -> Icons.Outlined.Air
    "detox" -> Icons.Outlined.Shield
    "place" -> Icons.Outlined.Place
    "game" -> Icons.Outlined.SportsEsports
    "voice" -> Icons.Outlined.Mic
    "energy" -> Icons.Outlined.Bolt
    else -> Icons.Outlined.Flag
}

/** O misiune: bifa (sau cercul gol), iconița, numele scurt și punctele. Nebifată și cu drum: atingerea te duce acolo. */
@Composable
private fun MissionTile(m: MissionStatus, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val done = m.done
    Column(
        modifier
            .clip(CardShape)
            .background(if (done) Color(0x1F6F855A) else Surface1)
            .border(1.dp, if (done) Color(0x4D6F855A) else StrokeCard, CardShape)
            .semantics { role = Role.Button; contentDescription = "${m.mission.title}, ${m.mission.points} puncte, ${if (done) "bifată" else "nebifată"}" }
            .pressable(onClick, scaleDown = 0.98f)
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(22.dp).clip(CircleShape).background(if (done) Positive else Surface2).border(1.dp, if (done) Positive else StrokeCardStrong, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (done) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                else Icon(missionIcon(m.mission.id), contentDescription = null, tint = Accent2, modifier = Modifier.size(13.dp))
            }
            Spacer(Modifier.weight(1f))
            Text("+${m.mission.points}", style = monoLabel(9, 0.10f).copy(color = if (done) Accent2 else TextDim))
        }
        Spacer(Modifier.height(8.dp))
        Text(m.mission.short, style = BodyStrong.copy(fontSize = 13.sp, color = if (done) TextPrimary else TextSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SlotChip(label: String, selected: Boolean, worn: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(ChipShape)
            .background(if (selected) TabPillActive else Surface2)
            .border(1.dp, if (selected) Color(0x996F855A) else StrokeCardStrong, ChipShape)
            .semantics { role = Role.Button; contentDescription = label + if (selected) ", selectat" else "" }
            .pressable(onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (worn) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(Accent2))
            Spacer(Modifier.width(6.dp))
        }
        Text(label.uppercase(), style = monoLabel(10, 0.10f).copy(color = if (selected) Accent2 else TextSecondary))
    }
}

/** O piesă: Casca cu piesa pusă (ca să vezi cum stă), numele și starea: purtată · a ta · prețul · gradul cerut. */
@Composable
private fun GearTile(item: GearItem, state: SoldierState, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val equipped = state.equipped[item.slot] == item.id
    val owned = item.id in state.owned
    val locked = !owned && state.rank.index < item.rank
    val status = when {
        equipped -> "PE CASCA"
        owned -> "ÎN DOTARE"
        locked -> Ranks.byIndex(item.rank).name.uppercase()
        else -> "${item.cost} PUNCTE"
    }
    val statusColor = when {
        equipped -> Positive
        owned -> TextSecondary
        locked -> TextDim
        else -> EmberHot
    }
    Column(
        modifier
            .clip(CardShape)
            .background(Surface1)
            .border(1.dp, if (equipped) Color(0x996F855A) else StrokeCard, CardShape)
            .semantics { role = Role.Button; contentDescription = "${item.name}, " + if (locked) "de la gradul de ${Ranks.byIndex(item.rank).name}" else status.lowercase() }
            .pressable(onClick, scaleDown = 0.97f)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            MascotStill(outfit = state.outfit.with(item.slot, item.id), size = 74.dp, modifier = Modifier.alpha(if (locked) 0.45f else 1f))
            if (locked) Icon(Icons.Outlined.Lock, contentDescription = null, tint = TextSecondary, modifier = Modifier.align(Alignment.TopEnd).size(14.dp))
            if (equipped) Box(Modifier.align(Alignment.TopEnd).size(18.dp).clip(CircleShape).background(Positive), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(11.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(item.name, style = BodyStrong.copy(fontSize = 12.sp), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Spacer(Modifier.height(3.dp))
        Text(status, style = monoLabel(8, 0.12f).copy(color = statusColor), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun RankCard(r: Rank, state: SoldierState) {
    val current = r.index == state.rank.index
    val reached = r.index <= state.rank.index
    Column(
        Modifier
            .width(96.dp)
            .clip(CardShape)
            .background(if (current) Color(0x1F6F855A) else Surface1)
            .border(1.dp, if (current) Color(0x996F855A) else StrokeCard, CardShape)
            .alpha(if (reached) 1f else 0.6f)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        RankBadge(r.index, size = 44.dp, highlight = current)
        Spacer(Modifier.height(8.dp))
        Text(r.name, style = BodyStrong.copy(fontSize = 12.sp), maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(
            if (current) "ACUM" else "${r.minPoints} PUNCTE",
            style = monoLabel(8, 0.12f).copy(color = if (current) Accent2 else TextDim)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BuySheet(item: GearItem, state: SoldierState, onBuy: () -> Unit, onClose: () -> Unit) {
    val canPay = state.balance >= item.cost
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Surface1, shape = SheetShape) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(item.slot.label.uppercase(), style = monoLabel(9, 0.14f).copy(color = Accent2))
            Spacer(Modifier.height(4.dp))
            Text(item.name, style = TitleModule.copy(fontSize = 24.sp))
            Spacer(Modifier.height(8.dp))
            MascotStill(outfit = state.outfit.with(item.slot, item.id), size = 150.dp)
            Spacer(Modifier.height(8.dp))
            Text(
                if (canPay) "${item.cost} puncte din ${state.balance}. Rămân ${state.balance - item.cost}."
                else "Costă ${item.cost} puncte; ai ${state.balance}. Mai ai nevoie de ${item.cost - state.balance}.",
                style = Body.copy(color = TextSecondary), textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            PrimaryButton(if (canPay) "Cumpără și pune" else "Nu ajung punctele", onClick = onBuy, enabled = canPay, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Nu acum", onClick = onClose, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** „Avansat în grad”: Casca fericită pe jar, gradul nou și darul lui. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromotionSheet(rank: Rank, onClose: () -> Unit) {
    val gifts = remember(rank) { Gear.giftsOf(rank.index) }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(Unit) { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Surface1, shape = SheetShape) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                EmberField(Modifier.fillMaxSize(), count = 40, alpha = 0.8f, speed = 1.3f)
                PopIn { Mascot(state = MascotState.Happy, size = 176.dp) }
            }
            StampLabel("AVANSAT ÎN GRAD", rotationDeg = -4f, fontSize = 12)
            Spacer(Modifier.height(8.dp))
            Text(rank.name, style = TitleModule.copy(fontSize = 30.sp))
            Spacer(Modifier.height(8.dp))
            Text(
                if (gifts.isEmpty()) "Ai ajuns la gradul de ${rank.name}. Insigna nouă e pe piept."
                else "Ai ajuns la gradul de ${rank.name}. În dar: ${gifts.joinToString(" și ") { it.name.lowercase() }} — deja pe Casca.",
                style = Body.copy(color = TextSecondary), textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(18.dp))
            PrimaryButton("Prezent!", onClick = onClose, modifier = Modifier.fillMaxWidth())
        }
    }
}
