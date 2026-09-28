package com.forja.app.feature.workout

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forja.app.core.designsystem.Accent
import com.forja.app.core.designsystem.Accent2
import com.forja.app.core.designsystem.BodyStrong
import com.forja.app.core.designsystem.EmberWarm
import com.forja.app.core.designsystem.LocalReducedMotion
import com.forja.app.core.designsystem.OnAccent
import com.forja.app.core.designsystem.OverVideoFill
import com.forja.app.core.designsystem.SheetShape
import com.forja.app.core.designsystem.StrokeCardStrong
import com.forja.app.core.designsystem.Surface0
import com.forja.app.core.designsystem.Surface1
import com.forja.app.core.designsystem.Surface2
import com.forja.app.core.designsystem.TextDim
import com.forja.app.core.designsystem.TextDim2
import com.forja.app.core.designsystem.TextPrimary
import com.forja.app.core.designsystem.TextSecondary
import com.forja.app.core.designsystem.TitleModule
import com.forja.app.core.designsystem.components.ForjaCard
import com.forja.app.core.designsystem.components.ForjaSwitch
import com.forja.app.core.designsystem.components.InfoDot
import com.forja.app.core.designsystem.components.PrimaryButton
import com.forja.app.core.designsystem.components.SectionLabel
import com.forja.app.core.designsystem.components.coachTarget
import com.forja.app.core.designsystem.components.pressable
import com.forja.app.core.designsystem.monoLabel
import com.forja.app.core.music.Badge
import com.forja.app.core.music.FPlaylist
import com.forja.app.core.music.Mix
import com.forja.app.core.music.MusicIcons
import com.forja.app.core.music.PlayItem
import com.forja.app.core.music.StartState
import com.forja.app.core.music.Tier
import com.forja.app.core.music.TierCounts
import com.forja.app.core.music.Track
import com.forja.app.core.music.MusicKind

/*
 * Muzica la Antrenament (workout-music.md §1.3): rândul „Muzică” din hub (comutator + eticheta listei), foaia
 * Mix/Noi/Vechi/Apreciate, discul de pe video (cât faci seria) și banda de sub inelul pauzei. Fără propoziții pe ecran:
 * explicația stă la „i” și în ghidajul primei vizite.
 */

private val Amber = Color(0xFFF3B952)
private val DiscBg = Color(0xFF16181C)

// ───────────────────────────── Stările ─────────────────────────────

/** Starea muzicii la Antrenament (hub + foaie), fără Android. */
data class WorkoutMusicState(
    val on: Boolean = false,
    val access: Boolean = false,
    val mix: Mix = Mix.MIX,
    val counts: TierCounts = TierCounts(),
    /** Lista pentru fiecare alegere (pentru sesiunea planului de azi). */
    val lists: Map<Mix, FPlaylist> = emptyMap(),
    val stopAtEnd: Boolean = true,
    /** Numele playerului (pentru „Melodii apreciate · Spotify”). */
    val player: String? = null
) {
    val list: FPlaylist? get() = lists[mix]

    /** Alegerea chiar folosită: fără acces sau fără destule piese, Melodii apreciate. */
    val effective: Mix get() = when {
        !access -> Mix.LIKED
        mix == Mix.LIKED -> Mix.LIKED
        list?.liked != false -> Mix.LIKED
        else -> mix
    }

    /** Eticheta mono de sub „Muzică” (≤ 3 cuvinte). */
    val label: String get() = when {
        !on -> "OPRITĂ"
        effective == Mix.LIKED -> "APRECIATE"
        effective == Mix.NEW -> "NOI · ${list?.items?.size ?: 0}"
        effective == Mix.OLD -> "VECHI · ${list?.items?.size ?: 0}"
        else -> "MIX · ${list?.items?.size ?: 0} PIESE"
    }

    /** O alegere se poate face doar când are piese (Apreciate: mereu). */
    fun enabled(m: Mix): Boolean = when (m) {
        Mix.LIKED -> true
        else -> access && lists[m]?.liked == false
    }

    /** Numărul de sub alegere în foaie. */
    fun count(m: Mix): String? = when (m) {
        Mix.LIKED -> null
        else -> lists[m]?.items?.size?.takeIf { it > 0 && access }?.toString()
    }
}

/** Faza discului (din starea motorului + sesiunea care cântă). */
enum class DiscPhase { IDLE, STARTING, PLAYING, PAUSED, NEEDS_TAP, FAILED }

/** Ce arată discul / banda: coperta, progresul piesei, semnul (listă FORJA / Apreciate). */
data class DiscUi(
    val phase: DiscPhase,
    val art: ImageBitmap? = null,
    val progress: Float = 0f,
    val badge: Badge = Badge.NONE,
    val title: String? = null,
    val artist: String? = null,
    /** „Deschide Spotify” / „Deschide playerul”, pentru NEEDS_TAP și FAILED. */
    val action: String? = null
)

/** Faza discului: starea motorului (doar pornirile Antrenamentului) + piesa care cântă. */
fun discUi(state: StartState, fromWorkout: Boolean, track: Track?, art: ImageBitmap?, positionMs: Long, queueActive: Boolean): DiscUi {
    val s = if (fromWorkout) state else StartState.Idle
    val progress = if (track != null && track.durationMs > 0) (positionMs.toFloat() / track.durationMs).coerceIn(0f, 1f) else 0f
    val badge = when {
        track == null -> Badge.NONE
        queueActive -> Badge.FORJA
        s is StartState.Playing -> s.badge.takeIf { it != Badge.FORJA } ?: Badge.NONE
        else -> Badge.NONE
    }
    return when {
        s is StartState.Starting -> DiscUi(DiscPhase.STARTING, art, progress, badge, track?.title, track?.artist)
        track != null && track.playing -> DiscUi(DiscPhase.PLAYING, art, progress, badge, track.title, track.artist)
        s is StartState.NeedsTap -> DiscUi(
            DiscPhase.NEEDS_TAP, action = if (s.step.pkg == MusicKind.SPOTIFY) "Deschide Spotify" else "Deschide playerul"
        )
        s is StartState.Failed -> DiscUi(DiscPhase.FAILED, action = "Deschide playerul")
        track != null -> DiscUi(DiscPhase.PAUSED, art, progress, badge, track.title, track.artist)
        else -> DiscUi(DiscPhase.IDLE)
    }
}

// ───────────────────────────── Hub: rândul „Muzică” ─────────────────────────────

/**
 * `[ disc ]  Muzică   MIX · 12 PIESE ›   (comutator)` — atingerea pe rând comută, eticheta deschide foaia.
 * Deasupra lui „Începe sesiunea” (singura acțiune principală).
 */
@Composable
fun WorkoutMusicRow(
    state: WorkoutMusicState,
    disc: DiscUi,
    onToggle: (Boolean) -> Unit,
    onOpenSheet: () -> Unit,
    modifier: Modifier = Modifier
) {
    ForjaCard(modifier.coachTarget("antrenament.muzica"), padding = 0.dp) {
        Row(
            Modifier
                .pressable({ onToggle(!state.on) }, scaleDown = 0.99f)
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .semantics(mergeDescendants = true) {
                    role = Role.Switch
                    contentDescription = "Muzică"
                    stateDescription = if (state.on) "pornită, ${state.label.lowercase()}" else "oprită"
                }
                .padding(start = 12.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MusicDisc(disc, size = 40.dp, dim = !state.on)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Muzică", style = BodyStrong.copy(fontSize = 15.sp))
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .pressable(onOpenSheet, scaleDown = 0.97f)
                        .semantics(mergeDescendants = true) { role = Role.Button; contentDescription = "Alege muzica: ${state.label.lowercase()}" }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(state.label, style = monoLabel(9, 0.12f).copy(color = if (state.on) Accent2 else TextDim), maxLines = 1)
                    Spacer(Modifier.width(4.dp))
                    Icon(MusicIcons.ChevronRight, null, tint = if (state.on) Accent2 else TextDim, modifier = Modifier.size(11.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            ForjaSwitch(checked = state.on, onCheckedChange = onToggle)
        }
    }
}

// ───────────────────────────── Foaia Mix / Noi / Vechi / Apreciate ─────────────────────────────

data class MusicSheetActions(
    val onMix: (Mix) -> Unit = {},
    val onStopAtEnd: (Boolean) -> Unit = {},
    val onDone: () -> Unit = {}
)

private const val SHEET_INFO =
    "Mix: ce asculți acum, plus ce iubeai acum o lună. Noi: preferatele din ultimele zile. Vechi: piese ascultate des, " +
        "uitate de o lună. Apreciate: Melodii apreciate din Spotify.\n\nLista se face pe telefon, din ce ai ascultat cât " +
        "FORJA a avut acces la muzică. Muzica pornită de FORJA se oprește la final; a ta, niciodată."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutMusicSheet(state: WorkoutMusicState, actions: MusicSheetActions, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Surface1,
        shape = SheetShape
    ) {
        WorkoutMusicSheetContent(state, actions, Modifier.navigationBarsPadding())
    }
}

/** Conținutul foii (separat, ca să se poată fotografia fără ModalBottomSheet). */
@Composable
fun WorkoutMusicSheetContent(state: WorkoutMusicState, actions: MusicSheetActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionLabel("Muzica sesiunii", color = Accent2)
                Spacer(Modifier.height(4.dp))
                Text("Ce cântă la sală.", style = TitleModule.copy(fontSize = 22.sp, lineHeight = 25.sp))
            }
            InfoDot(SHEET_INFO, title = "Muzica sesiunii")
        }
        Spacer(Modifier.height(16.dp))
        MixSegments(state, actions.onMix)
        Spacer(Modifier.height(16.dp))
        Preview(state)
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(5.dp))
                .background(Surface2)
                .border(1.dp, StrokeCardStrong, RoundedCornerShape(5.dp))
                .pressable({ actions.onStopAtEnd(!state.stopAtEnd) }, scaleDown = 0.99f)
                .semantics(mergeDescendants = true) {
                    role = Role.Switch
                    contentDescription = "Oprește la final"
                    stateDescription = if (state.stopAtEnd) "pornit" else "oprit"
                }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Oprește la final", style = BodyStrong.copy(fontSize = 14.sp), modifier = Modifier.weight(1f))
            ForjaSwitch(checked = state.stopAtEnd, onCheckedChange = actions.onStopAtEnd)
        }
        Spacer(Modifier.height(18.dp))
        PrimaryButton("Gata", onClick = actions.onDone, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun MixSegments(state: WorkoutMusicState, onMix: (Mix) -> Unit) {
    val shape = RoundedCornerShape(7.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Surface0)
            .border(1.dp, StrokeCardStrong, shape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        for (m in Mix.entries) {
            val selected = state.effective == m && state.on || !state.on && state.mix == m
            val enabled = state.enabled(m)
            val label = when (m) {
                Mix.MIX -> "Mix"
                Mix.NEW -> "Noi"
                Mix.OLD -> "Vechi"
                Mix.LIKED -> "Apreciate"
            }
            Column(
                Modifier
                    .weight(if (m == Mix.LIKED) 1.35f else 1f)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (selected) Accent.copy(alpha = 0.45f) else Color.Transparent)
                    .then(if (selected) Modifier.border(1.dp, Accent2, RoundedCornerShape(5.dp)) else Modifier)
                    .then(if (enabled) Modifier.pressable({ onMix(m) }) else Modifier)
                    .semantics(mergeDescendants = true) {
                        role = Role.Tab
                        contentDescription = label
                        stateDescription = when {
                            !enabled -> "fără piese încă"
                            selected -> "ales"
                            else -> "neales"
                        }
                    }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val a = if (enabled) 1f else 0.38f
                Text(label, style = BodyStrong.copy(fontSize = 13.sp, color = (if (selected) OnAccent else TextSecondary).copy(alpha = a)), maxLines = 1)
                Spacer(Modifier.height(3.dp))
                if (m == Mix.LIKED) {
                    Icon(MusicIcons.Heart, null, tint = (if (selected) OnAccent else EmberWarm).copy(alpha = 0.9f * a), modifier = Modifier.size(10.dp))
                } else {
                    Text(state.count(m) ?: "—", style = monoLabel(9, 0.08f).copy(color = (if (selected) OnAccent else TextDim).copy(alpha = a)))
                }
            }
        }
    }
}

@Composable
private fun Preview(state: WorkoutMusicState) {
    val list = state.list
    when {
        state.effective == Mix.LIKED -> LikedRow(state.player, cold = state.mix != Mix.LIKED && state.access)
        list != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            list.items.take(5).forEachIndexed { i, item -> TrackRow(i + 1, item) }
            val more = list.items.size - 5
            if (more > 0) Text("+ $more", style = monoLabel(9, 0.12f).copy(color = TextDim), modifier = Modifier.padding(start = 46.dp))
        }
    }
}

@Composable
private fun TrackRow(n: Int, item: PlayItem) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(4.dp)).background(DiscBg).border(1.dp, StrokeCardStrong, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("$n", style = monoLabel(11, 0f).copy(color = if (n == 1) Amber else TextSecondary))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = BodyStrong.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.artist.uppercase(), style = monoLabel(8, 0.10f).copy(color = TextDim), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            when (item.tier) {
                Tier.NEW -> "NOUĂ"
                Tier.OLD -> "VECHE"
                Tier.STEADY -> "DES"
            },
            style = monoLabel(8, 0.12f).copy(color = if (item.tier == Tier.OLD) Amber.copy(alpha = 0.8f) else TextDim2)
        )
    }
}

/** Melodii apreciate (Spotify): fără listă FORJA — la rece (puține ascultări) sau aleasă. */
@Composable
private fun LikedRow(player: String?, cold: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(4.dp)).background(EmberWarm.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(MusicIcons.Heart, null, tint = EmberWarm, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Melodii apreciate", style = BodyStrong.copy(fontSize = 14.sp))
                Text((player ?: "Playerul tău").uppercase(), style = monoLabel(8, 0.10f).copy(color = TextDim))
            }
        }
        if (cold) Text("Ascultă câteva zile. Lista ta se face singură.", style = BodyStrong.copy(fontSize = 12.sp, color = TextDim))
    }
}

// ───────────────────────────── Discul ─────────────────────────────

/**
 * Discul muzicii: coperta (se rotește încet cât cântă; fix sub mișcare redusă), inel subțire = progresul piesei,
 * punct olive = lista FORJA, inimă = Melodii apreciate. Pornește: bare + arc care se rotește. Atinge ca să pornești:
 * play cu margine amber care pulsează. Nu a pornit: nota tăiată.
 */
@Composable
fun MusicDisc(ui: DiscUi, size: Dp, modifier: Modifier = Modifier, dim: Boolean = false, overVideo: Boolean = false) {
    val reduced = LocalReducedMotion.current
    val inf = if (!reduced) rememberInfiniteTransition(label = "disc") else null
    val spin = if (ui.phase == DiscPhase.PLAYING && ui.art != null) inf?.animateFloat(0f, 360f, infiniteRepeatable(tween(14_000, easing = LinearEasing)), label = "discSpin") else null
    val arc = if (ui.phase == DiscPhase.STARTING) inf?.animateFloat(0f, 360f, infiniteRepeatable(tween(1_200, easing = LinearEasing)), label = "discArc") else null
    val pulse = if (ui.phase == DiscPhase.NEEDS_TAP) inf?.animateFloat(0.45f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "discPulse") else null
    val bars = if (ui.phase == DiscPhase.STARTING || ui.phase == DiscPhase.PLAYING && ui.art == null) {
        inf?.animateFloat(0f, 1f, infiniteRepeatable(tween(800, easing = LinearEasing)), label = "discBars")
    } else null

    Box(modifier.size(size).graphicsLayer { alpha = if (dim) 0.55f else 1f }, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(if (overVideo) OverVideoFill else DiscBg)
                .border(1.dp, if (overVideo) Color(0x33FFFFFF) else StrokeCardStrong, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            val art = ui.art
            when {
                art != null && (ui.phase == DiscPhase.PLAYING || ui.phase == DiscPhase.PAUSED || ui.phase == DiscPhase.STARTING) -> Image(
                    art, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().padding(size * 0.08f).clip(CircleShape).graphicsLayer { rotationZ = spin?.value ?: 0f }
                )
                ui.phase == DiscPhase.STARTING || ui.phase == DiscPhase.PLAYING -> Bars(bars?.value, Modifier.size(size * 0.46f))
                ui.phase == DiscPhase.FAILED -> Icon(MusicIcons.NoteOff, null, tint = TextDim, modifier = Modifier.size(size * 0.42f))
                ui.phase == DiscPhase.PAUSED -> Icon(MusicIcons.Note, null, tint = TextSecondary, modifier = Modifier.size(size * 0.42f))
                else -> Icon(MusicIcons.Play, null, tint = if (ui.phase == DiscPhase.NEEDS_TAP) Amber else TextPrimary, modifier = Modifier.size(size * 0.42f))
            }
            if (ui.phase == DiscPhase.PAUSED && art != null) {
                Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) {
                    Icon(MusicIcons.Pause, null, tint = TextPrimary, modifier = Modifier.size(size * 0.34f))
                }
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val sw = if (size >= 44.dp) 2.5.dp.toPx() else 2.dp.toPx()
            val tl = Offset(sw / 2, sw / 2)
            val sz = Size(this.size.width - sw, this.size.height - sw)
            when (ui.phase) {
                DiscPhase.PLAYING, DiscPhase.PAUSED -> if (ui.progress > 0f) {
                    drawArc(Amber.copy(alpha = if (ui.phase == DiscPhase.PAUSED) 0.5f else 0.95f), -90f, 360f * ui.progress, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
                }
                DiscPhase.STARTING -> drawArc(Accent2, (arc?.value ?: 300f) - 90f, 70f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
                DiscPhase.NEEDS_TAP -> drawCircle(Amber.copy(alpha = pulse?.value ?: 0.9f), (this.size.minDimension - sw) / 2f, style = Stroke(sw))
                else -> Unit
            }
        }
        // Semnul listei: punct olive (lista FORJA) sau inimă (Apreciate), jos-dreapta.
        if (ui.badge != Badge.NONE && (ui.phase == DiscPhase.PLAYING || ui.phase == DiscPhase.PAUSED)) {
            val b = (size * 0.3f).coerceAtLeast(12.dp)
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(b)
                    .clip(CircleShape)
                    .background(Surface0)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(if (ui.badge == Badge.FORJA) Accent2 else EmberWarm.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                if (ui.badge == Badge.LIKED) Icon(MusicIcons.Heart, null, tint = EmberWarm, modifier = Modifier.size(b * 0.55f))
            }
        }
    }
}

/** Trei bare de egalizator (amber / jar / olive); fixe sub mișcare redusă. */
@Composable
private fun Bars(phase: Float?, modifier: Modifier) {
    val colors = remember { listOf(Amber, EmberWarm, Accent2) }
    val rest = remember { floatArrayOf(0.55f, 0.9f, 0.4f) }
    Canvas(modifier) {
        val bw = size.width / 5f
        for (i in 0 until 3) {
            val lv = phase?.let { p ->
                val x = ((p - i * 0.18f) % 1f + 1f) % 1f
                0.3f + 0.7f * (if (x < 0.5f) x * 2f else (1f - x) * 2f)
            } ?: rest[i]
            val h = size.height * lv
            drawRoundRect(colors[i], topLeft = Offset(i * bw * 2f, size.height - h), size = Size(bw, h), cornerRadius = CornerRadius(bw / 2f))
        }
    }
}

/**
 * Discul de pe video, colțul dreapta-jos (cât faci seria). Atingere = play/pauză (sau pașii „atinge ca să pornești” /
 * „deschide playerul”); apăsare lungă = playerul; glisare spre stânga = melodia următoare.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun VideoMusicDisc(ui: DiscUi, onTap: () -> Unit, onLongPress: () -> Unit, onNext: () -> Unit, modifier: Modifier = Modifier) {
    val swipe = with(LocalDensity.current) { 36.dp.toPx() }
    val description = when (ui.phase) {
        DiscPhase.PLAYING -> "Muzică: cântă" + (ui.title?.let { ", $it" } ?: "") + (ui.artist?.takeIf { it.isNotBlank() }?.let { ", $it" } ?: "")
        DiscPhase.PAUSED -> "Muzică: pe pauză"
        DiscPhase.STARTING -> "Muzică: pornește"
        DiscPhase.NEEDS_TAP -> "Atinge ca să pornești muzica"
        DiscPhase.FAILED -> "Muzica nu a pornit. Atinge ca să deschizi playerul"
        DiscPhase.IDLE -> "Pornește muzica"
    }
    Box(
        modifier
            .size(56.dp)
            .clip(CircleShape)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
                onLongClick = onLongPress
            )
            .pointerInput(Unit) {
                var dx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dx = 0f },
                    onDragEnd = { if (dx < -swipe) onNext() },
                    onHorizontalDrag = { _, d -> dx += d }
                )
            }
            .semantics {
                role = Role.Button
                contentDescription = description
                customActions = listOf(
                    CustomAccessibilityAction("Melodia următoare") { onNext(); true },
                    CustomAccessibilityAction("Deschide playerul") { onLongPress(); true }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        MusicDisc(ui, 48.dp, overVideo = true)
    }
}

/**
 * Banda de sub inelul pauzei (mâinile sunt libere): coperta 40 dp, un rând „Titlu · Artist”, ⏮ ⏯ ⏭ de 36 dp.
 * Cât pornește: „Pornește…”; la o atingere de pornit: „Deschide Spotify”; eșec: „Nu a pornit.” + deschide playerul.
 */
@Composable
fun RestMusicStrip(
    ui: DiscUi,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(5.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(shape)
            .background(Surface1)
            .border(1.dp, StrokeCardStrong, shape)
            .padding(start = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MusicDisc(ui, 40.dp)
        Spacer(Modifier.width(10.dp))
        val line = when (ui.phase) {
            DiscPhase.PLAYING, DiscPhase.PAUSED -> listOfNotNull(ui.title, ui.artist?.takeIf { it.isNotBlank() }).joinToString(" · ")
            DiscPhase.STARTING -> "Pornește…"
            DiscPhase.NEEDS_TAP -> ui.action ?: "Deschide playerul"
            DiscPhase.FAILED -> "Nu a pornit."
            DiscPhase.IDLE -> "Muzica ta"
        }
        Text(
            line,
            style = BodyStrong.copy(fontSize = 14.sp, color = if (ui.phase == DiscPhase.NEEDS_TAP) Amber else TextPrimary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        when (ui.phase) {
            DiscPhase.NEEDS_TAP, DiscPhase.FAILED -> StripButton(MusicIcons.Open, ui.action ?: "Deschide playerul", onOpen)
            DiscPhase.STARTING -> Unit
            else -> {
                StripButton(MusicIcons.Previous, "Piesa anterioară", onPrevious)
                StripButton(if (ui.phase == DiscPhase.PLAYING) MusicIcons.Pause else MusicIcons.Play, if (ui.phase == DiscPhase.PLAYING) "Pauză" else "Pornește", onToggle)
                StripButton(MusicIcons.Next, "Piesa următoare", onNext)
            }
        }
    }
}

@Composable
private fun StripButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).pressable(onClick).semantics { role = Role.Button; contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = TextPrimary, modifier = Modifier.size(20.dp))
    }
}

// ───────────────────────────── Mostre (capturi) ─────────────────────────────

/** Date false pentru capturile rândului, foii, discului și benzii. */
object WorkoutMusicSamples {
    private fun item(n: Int, title: String, artist: String, tier: Tier) =
        PlayItem("k$n", title, artist, MusicKind.SPOTIFY, "spotify:track:$n", null, 200, tier)

    private val items = listOf(
        item(1, "Marș de dimineață", "Fanfara FORJA", Tier.NEW),
        item(2, "Pas de defilare", "Garda de Onoare", Tier.NEW),
        item(3, "Nopți albe", "Vama", Tier.NEW),
        item(4, "Zori de zi", "Direcția 5", Tier.OLD),
        item(5, "Fier și foc", "Forja Band", Tier.NEW),
        item(6, "Linia întâi", "Celula 3", Tier.NEW),
        item(7, "Tăcerea dinainte", "Stejar", Tier.OLD),
        item(8, "A doua serie", "Fanfara FORJA", Tier.STEADY),
        item(9, "Drum de munte", "Carpați", Tier.NEW),
        item(10, "Ultima tură", "Garda de Onoare", Tier.OLD),
        item(11, "Respiră", "Vama", Tier.STEADY),
        item(12, "Acasă", "Direcția 5", Tier.NEW)
    )
    private val counts = TierCounts(new = 14, old = 6, steady = 5)
    private fun list(mix: Mix, xs: List<PlayItem>) = FPlaylist(mix, xs, emptyList(), MusicKind.SPOTIFY, counts)

    val music = WorkoutMusicState(
        on = true, access = true, mix = Mix.MIX, counts = counts,
        lists = mapOf(
            Mix.MIX to list(Mix.MIX, items),
            Mix.NEW to list(Mix.NEW, items.filter { it.tier == Tier.NEW } + items.filter { it.tier == Tier.STEADY }),
            Mix.OLD to list(Mix.OLD, items.filter { it.tier == Tier.OLD } + items.filter { it.tier == Tier.STEADY }),
            Mix.LIKED to list(Mix.LIKED, emptyList())
        ),
        player = "Spotify"
    )
    val musicOff = music.copy(on = false)
    val musicOld = music.copy(mix = Mix.OLD)
    val musicLiked = music.copy(mix = Mix.LIKED)
    /** Puține ascultări încă: Mix/Noi/Vechi fără piese, pornește Melodii apreciate. */
    val cold = WorkoutMusicState(
        on = true, access = true, mix = Mix.MIX, counts = TierCounts(2, 0, 1),
        lists = Mix.entries.associateWith { FPlaylist(it, emptyList(), emptyList(), null, TierCounts(2, 0, 1)) }, player = "Spotify"
    )
    /** Fără acces la muzică: doar Apreciate. */
    val noAccess = WorkoutMusicState(on = true, access = false, player = null)

    val discIdle = DiscUi(DiscPhase.IDLE)
    val discStarting = DiscUi(DiscPhase.STARTING)
    val discPlaying = DiscUi(DiscPhase.PLAYING, progress = 0.36f, badge = Badge.FORJA, title = "Marș de dimineață", artist = "Fanfara FORJA")
    val discPaused = discPlaying.copy(phase = DiscPhase.PAUSED)
    val discLiked = discPlaying.copy(badge = Badge.LIKED, title = "Nopți albe", artist = "Vama")
    val discNeedsTap = DiscUi(DiscPhase.NEEDS_TAP, action = "Deschide Spotify")
    val discFailed = DiscUi(DiscPhase.FAILED, action = "Deschide playerul")
}
