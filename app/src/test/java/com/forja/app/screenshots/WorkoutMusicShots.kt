package com.forja.app.screenshots

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import com.forja.app.core.data.db.ExerciseEntity
import com.forja.app.core.data.db.PlanEntity
import com.forja.app.core.designsystem.components.MascotState
import com.forja.app.core.designsystem.components.CoachMarksHost
import com.forja.app.core.designsystem.components.CoachStep
import com.forja.app.core.designsystem.Surface1
import com.forja.app.core.designsystem.Surface2
import com.forja.app.core.designsystem.components.PrimaryButton
import com.forja.app.core.designsystem.components.SectionLabel
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
 * Muzica la Antrenament (4.4): rândul „Muzică” din hub (deasupra lui „Începe sesiunea”), foaia Mix/Noi/Vechi/Apreciate,
 * sesiunea live cu discul pe video și banda de sub inelul pauzei — pe telefonul de referință și pe S23.
 */
abstract class WorkoutMusicShotsBase(private val suffix: String) {

    private val exercises = listOf(
        ExerciseEntity(1, "Genuflexiuni cu bara", 4, 8, "62,5", "KG", "", "", ""),
        ExerciseEntity(2, "Împins la piept", 4, 10, "40", "KG", "", "", "")
    )
    private val live = LiveState(exercises = exercises, planName = "Forță", exPos = 0, setNo = 2, startedAt = 1L, totalSetsDone = 1)
    private val rest = live.copy(resting = true, restLeft = 62)

    @Composable
    private fun Hub(state: WorkoutMusicState, disc: DiscUi = WorkoutMusicSamples.discIdle) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            SectionLabel("Azi · Forță")
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(86.dp).clip(RoundedCornerShape(5.dp)).background(Surface1))
            Spacer(Modifier.height(18.dp))
            WorkoutMusicRow(state, disc, onToggle = {}, onOpenSheet = {}, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            PrimaryButton("Începe sesiunea", onClick = {}, modifier = Modifier.fillMaxWidth())
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

    // ───────────── Hubul întreg (unde cade rândul „Muzică” față de pliu) ─────────────
    @Test fun hubFull() = shot("workout_hub$suffix") {
        WorkoutHubContent(plans, 0, today, WorkoutMusicSamples.music, WorkoutMusicSamples.discIdle, HubActions())
    }

    /** Prima vizită: hubul derulat până la rând (cum face WorkoutScreen cât ghidajul e nevăzut), cu ghidajul pe el. */
    @Test fun hubGuide() = shot("workout_hub_guide$suffix") {
        CoachMarksHost(
            steps = listOf(CoachStep("antrenament.muzica", "Muzica ta pornește odată cu sesiunea.", MascotState.Happy)),
            active = true,
            onFinish = {}
        ) {
            WorkoutHubContent(
                plans, 0, today, WorkoutMusicSamples.music, WorkoutMusicSamples.discIdle, HubActions(),
                scroll = rememberScrollState(Int.MAX_VALUE)
            )
        }
    }

    // ───────────── Hub: rândul „Muzică” ─────────────
    @Test fun hubMix() = shot("workout_hub_music_mix$suffix", fullScreen = false) { Hub(WorkoutMusicSamples.music) }
    @Test fun hubPlaying() = shot("workout_hub_music_playing$suffix", fullScreen = false) { Hub(WorkoutMusicSamples.music, WorkoutMusicSamples.discPlaying) }
    @Test fun hubOff() = shot("workout_hub_music_off$suffix", fullScreen = false) { Hub(WorkoutMusicSamples.musicOff) }
    @Test fun hubCold() = shot("workout_hub_music_cold$suffix", fullScreen = false) { Hub(WorkoutMusicSamples.cold) }
    @Test fun hubNoAccess() = shot("workout_hub_music_noaccess$suffix", fullScreen = false) { Hub(WorkoutMusicSamples.noAccess) }

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

    // ───────────── Discul și banda, în toate fazele ─────────────
    @Test fun discs() = shot("workout_music_discs$suffix", fullScreen = false) {
        val all = listOf(
            WorkoutMusicSamples.discIdle, WorkoutMusicSamples.discStarting, WorkoutMusicSamples.discPlaying,
            WorkoutMusicSamples.discPaused, WorkoutMusicSamples.discLiked, WorkoutMusicSamples.discNeedsTap, WorkoutMusicSamples.discFailed
        )
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { all.forEach { MusicDisc(it, 48.dp, overVideo = true) } }
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
