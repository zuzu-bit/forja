package com.forja.app.screenshots

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import com.forja.app.core.data.db.ExerciseEntity
import com.forja.app.core.data.db.PlanEntity
import com.forja.app.core.designsystem.components.CoachMarksHost
import com.forja.app.core.designsystem.Surface1
import com.forja.app.core.designsystem.Surface2
import com.forja.app.core.designsystem.components.SectionLabel
import com.forja.app.core.designsystem.components.ToastHost
import com.forja.app.core.designsystem.components.ToastState
import com.forja.app.core.music.MusicKind
import com.forja.app.core.music.MusicQueue
import com.forja.app.feature.workout.ANTRENAMENT_STEPS
import com.forja.app.feature.workout.DiscUi
import com.forja.app.feature.workout.HubActions
import com.forja.app.feature.workout.LiveActions
import com.forja.app.feature.workout.LiveState
import com.forja.app.feature.workout.MusicDisc
import com.forja.app.feature.workout.MusicSheetActions
import com.forja.app.feature.workout.RestMusicStrip
import com.forja.app.feature.workout.WorkoutLiveContent
import com.forja.app.feature.workout.WorkoutMusicRow
import com.forja.app.feature.workout.WorkoutMusicSamples
import com.forja.app.feature.workout.WorkoutMusicSheetContent
import com.forja.app.feature.workout.WorkoutHubContent
import com.forja.app.feature.workout.WorkoutMusicState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Muzica la Antrenament (4.4): rândul „Muzică” din hub (sub citat, deasupra listei de azi), foaia Mix/Noi/Vechi/Apreciate,
 * sesiunea live cu discul pe video și banda de sub inelul pauzei — pe telefonul de referință și pe S23.
 */
abstract class WorkoutMusicShotsBase(private val suffix: String) {

    private val exercises = listOf(
        ExerciseEntity(1, "Genuflexiuni cu bara", 4, 8, "62,5", "KG", "", "", ""),
        ExerciseEntity(2, "Împins la piept", 4, 10, "40", "KG", "", "", "")
    )
    private val live = LiveState(exercises = exercises, planName = "Forță", exPos = 0, setNo = 2, startedAt = 1L, totalSetsDone = 1)
    private val rest = live.copy(resting = true, restLeft = 62)

    /** Rândul „Muzică” cum stă în hub: sub citat, deasupra lui „Azi · Forță” și a primului exercițiu. */
    @Composable
    private fun Hub(state: WorkoutMusicState, disc: DiscUi = WorkoutMusicSamples.discIdle) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            WorkoutMusicRow(state, disc, onToggle = {}, onOpenSheet = {}, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(22.dp))
            SectionLabel("Azi · Forță")
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(86.dp).clip(RoundedCornerShape(5.dp)).background(Surface1))
        }
    }

    @Composable
    private fun Sheet(state: WorkoutMusicState) {
        Column(Modifier.fillMaxWidth().padding(top = 40.dp).clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)).background(Surface1)) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 40.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Surface2))
            }
            WorkoutMusicSheetContent(state, MusicSheetActions())
        }
    }

    private val plans = listOf(
        PlanEntity(1, "Forță", "FORȚĂ · 45 MIN · SALĂ", "", 0),
        PlanEntity(2, "Cardio", "CARDIO · 30 MIN", "", 1),
        PlanEntity(3, "Mobilitate", "MOBILITATE · 20 MIN", "", 2)
    )
    private val today = exercises + listOf(
        ExerciseEntity(3, "Ramat cu gantera", 3, 10, "2×14", "KG", "", "", ""),
        ExerciseEntity(4, "Fandări", 3, 12, "corp", "CORP", "", "", "")
    )

    // ───────────── Hubul întreg (rândul „Muzică” trebuie să fie deasupra pliului, și pe S23) ─────────────
    @Test fun hubFull() = shot("workout_hub$suffix") {
        WorkoutHubContent(plans, 0, today, WorkoutMusicSamples.music, WorkoutMusicSamples.discIdle, HubActions())
    }

    /**
     * Prima vizită după 4.4.1 (cheia „antrenament_441”): ghidajul pe rând, cu rândul nou despre saltul de o clipă în
     * Spotify; fără derulare (rândul e deja în primul ecran; se derulează doar la font mărit). Hubul arată ↗ lângă listă.
     */
    @Test fun hubGuide() = shot("workout_hub_guide$suffix") {
        CoachMarksHost(steps = ANTRENAMENT_STEPS, active = true, onFinish = {}) {
            WorkoutHubContent(plans, 0, today, WorkoutMusicSamples.coldHop, WorkoutMusicSamples.discIdle, HubActions())
        }
    }

    /** Hubul derulat până jos: ultimul exercițiu și „Începe sesiunea”. */
    @Test fun hubBottom() = shot("workout_hub_bottom$suffix") {
        WorkoutHubContent(
            plans, 0, today, WorkoutMusicSamples.music, WorkoutMusicSamples.discIdle, HubActions(),
            scroll = rememberScrollState(Int.MAX_VALUE)
        )
    }

    // ───────────── Hub: rândul „Muzică” ─────────────
    @Test fun hubMix() = shot("workout_hub_music_mix$suffix", fullScreen = false) { Hub(WorkoutMusicSamples.music) }
    @Test fun hubPlaying() = shot("workout_hub_music_playing$suffix", fullScreen = false) { Hub(WorkoutMusicSamples.music, WorkoutMusicSamples.discPlaying) }
    @Test fun hubOff() = shot("workout_hub_music_off$suffix", fullScreen = false) { Hub(WorkoutMusicSamples.musicOff) }
    @Test fun hubCold() = shot("workout_hub_music_cold$suffix", fullScreen = false) { Hub(WorkoutMusicSamples.cold) }
    @Test fun hubNoAccess() = shot("workout_hub_music_noaccess$suffix", fullScreen = false) { Hub(WorkoutMusicSamples.noAccess) }

    /** 4.4.1: pornirea va sări o clipă în Spotify (29.09: tasta la YouTube) — ↗ amber de 10 dp după etichetă. */
    @Test fun hubHop() = shot("workout_hub_music_hop$suffix", fullScreen = false) { Hub(WorkoutMusicSamples.coldHop) }
    @Test fun hubMixHop() = shot("workout_hub_music_mix_hop$suffix", fullScreen = false) { Hub(WorkoutMusicSamples.musicHop) }

    // ───────────── Foaia ─────────────
    @Test fun sheetMix() = shot("workout_sheet_mix$suffix") { Sheet(WorkoutMusicSamples.music) }
    @Test fun sheetOld() = shot("workout_sheet_old$suffix") { Sheet(WorkoutMusicSamples.musicOld) }
    @Test fun sheetLiked() = shot("workout_sheet_liked$suffix") { Sheet(WorkoutMusicSamples.musicLiked) }
    @Test fun sheetCold() = shot("workout_sheet_cold$suffix") { Sheet(WorkoutMusicSamples.cold) }

    // ───────────── Sesiunea live ─────────────
    private fun liveShot(name: String, state: LiveState, disc: DiscUi) =
        shot("workout_live_$name$suffix") { WorkoutLiveContent(state, elapsedSec = 754, disc = disc, showMusic = true, actions = LiveActions()) }

    @Test fun liveSetPlaying() = liveShot("set_playing", live, WorkoutMusicSamples.discPlaying)
    @Test fun liveSetStarting() = liveShot("set_starting", live, WorkoutMusicSamples.discStarting)
    @Test fun liveSetNeedsTap() = liveShot("set_needs_tap", live, WorkoutMusicSamples.discNeedsTap)
    @Test fun liveRestPlaying() = liveShot("rest_playing", rest, WorkoutMusicSamples.discPlaying)
    @Test fun liveRestLiked() = liveShot("rest_liked", rest, WorkoutMusicSamples.discLiked)
    @Test fun liveRestNeedsTap() = liveShot("rest_needs_tap", rest, WorkoutMusicSamples.discNeedsTap)
    @Test fun liveRestFailed() = liveShot("rest_failed", rest, WorkoutMusicSamples.discFailed)

    // 4.4.1: „Nu a pornit.” + ↗ (Spotify se poate deschide) / + ▶ (nimic de deschis: încearcă din nou).
    @Test fun liveRestFailedSpotify() = liveShot("rest_failed_spotify", rest, WorkoutMusicSamples.discFailedSpotify)
    @Test fun liveRestFailedRetry() = liveShot("rest_failed_retry", rest, WorkoutMusicSamples.discFailedRetry)
    @Test fun liveSetFailedRetry() = liveShot("set_failed_retry", live, WorkoutMusicSamples.discFailedRetry)

    /** Spotify n-a primit piesa cerută: coperta fără punctul olive și rândul spus o singură dată pe antrenament. */
    @Test fun liveSetRefused() = shot("workout_live_set_refused$suffix") {
        val toast = remember { ToastState().apply { show(MusicQueue.refusedNotice(MusicKind.SPOTIFY)) } }
        Box(Modifier.fillMaxSize()) {
            WorkoutLiveContent(live, elapsedSec = 1_312, disc = WorkoutMusicSamples.discRefused, showMusic = true, actions = LiveActions())
            ToastHost(toast, Modifier.align(Alignment.BottomCenter).padding(bottom = 110.dp))
        }
    }

    // ───────────── Discul și banda, în toate fazele ─────────────
    @Test fun discs() = shot("workout_music_discs$suffix", fullScreen = false) {
        val all = listOf(
            WorkoutMusicSamples.discIdle, WorkoutMusicSamples.discStarting, WorkoutMusicSamples.discPlaying,
            WorkoutMusicSamples.discPaused, WorkoutMusicSamples.discLiked, WorkoutMusicSamples.discRefused, WorkoutMusicSamples.discNeedsTap,
            WorkoutMusicSamples.discFailed, WorkoutMusicSamples.discFailedSpotify, WorkoutMusicSamples.discFailedRetry
        )
        // 10 discuri de 48 dp nu încap pe un rând (câte 4 pe rând: 228 dp < 320): trei rânduri, fiecare fază întreagă.
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            all.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { row.forEach { MusicDisc(it, 48.dp, overVideo = true) } }
            }
            all.forEach { RestMusicStrip(it, {}, {}, {}, {}) }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE, application = Application::class)
class WorkoutMusicShots : WorkoutMusicShotsBase("")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE_S23, application = Application::class)
class WorkoutMusicShotsS23 : WorkoutMusicShotsBase("_s23")
