package com.forja.app.screenshots

import android.app.Application
import com.forja.app.core.music.DiagResult
import com.forja.app.core.music.MusicKind
import com.forja.app.core.music.MusicProbe
import com.forja.app.core.music.Rung
import com.forja.app.feature.probe.ProbeActions
import com.forja.app.feature.probe.ProbeContent
import com.forja.app.feature.probe.ProbeUi
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Proba ascunsă a muzicii (Profil → 5 atingeri pe versiune): înainte de probă, după ea, fără acces. */
abstract class ProbeShotsBase(private val suffix: String) {

    private val players = listOf(MusicKind.SPOTIFY to "Spotify", MusicKind.YT_MUSIC to "YouTube Music")

    private val before = ProbeUi(
        access = true,
        keyTarget = MusicKind.SPOTIFY,
        players = players,
        selected = MusicKind.SPOTIFY,
        sessions = listOf("Spotify · muzică · pauză · 9.0.62", "storytel · vorbit · pauză"),
        rows = (MusicProbe.INVISIBLE + MusicProbe.VISIBLE).map { MusicProbe.Row(it) },
        running = false
    )

    private val after = before.copy(
        rows = (MusicProbe.INVISIBLE + MusicProbe.VISIBLE).map { r ->
            when (r) {
                Rung.S_PLAY -> MusicProbe.Row(r, MusicProbe.Status.DONE, DiagResult.OK, 1_180)
                Rung.S_ANY -> MusicProbe.Row(r, MusicProbe.Status.DONE, DiagResult.OK, 2_040)
                Rung.S_TOP -> MusicProbe.Row(r, MusicProbe.Status.DONE, DiagResult.WRONG_TRACK, 3_900, "via:search")
                Rung.S_LIKED -> MusicProbe.Row(r, MusicProbe.Status.DONE, DiagResult.TIMEOUT, 6_000)
                Rung.K_TOKEN -> MusicProbe.Row(r, MusicProbe.Status.DONE, DiagResult.SKIPPED, note = "keyTarget:com.spotify.music")
                Rung.K_PLAY -> MusicProbe.Row(r, MusicProbe.Status.DONE, DiagResult.OK, 4_300)
                Rung.V_LIKED_PLAY -> MusicProbe.Row(r, MusicProbe.Status.DONE, DiagResult.OK, 5_100)
                else -> MusicProbe.Row(r)
            }
        },
        upload = "Jurnalul a plecat la FORJA.",
        journal = listOf(
            "probe · V_LIKED_PLAY · music · ok · 5100 ms",
            "probe · K_PLAY · music · ok · 4300 ms",
            "probe · S_LIKED · music · timeout · 6000 ms",
            "probe · S_TOP · music · wrong_track · 3900 ms · via:search"
        )
    )

    private val noAccess = before.copy(access = false, keyTarget = null, sessions = emptyList())

    @Test fun probeBefore() = shot("probe$suffix") { ProbeContent(before, ProbeActions()) }
    @Test fun probeAfter() = shot("probe_after$suffix") { ProbeContent(after, ProbeActions()) }
    @Test fun probeNoAccess() = shot("probe_noaccess$suffix") { ProbeContent(noAccess, ProbeActions()) }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE, application = Application::class)
class ProbeShots : ProbeShotsBase("")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE_S23, application = Application::class)
class ProbeShotsS23 : ProbeShotsBase("_s23")
