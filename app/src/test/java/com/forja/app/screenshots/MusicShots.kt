package com.forja.app.screenshots

import android.app.Application
import com.forja.app.feature.inventory.MusicActions
import com.forja.app.feature.inventory.MusicUiState
import com.forja.app.feature.inventory.MusicWaitContent
import com.forja.app.feature.inventory.MusicWaitSamples
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Ecranul Muzică din Inventar (S3c, 4.4): fiecare stare a pornirii, pe telefonul de referință și pe S23-ul Lanei.
 * Cântă · pe pauză · nimic arătat cu o carte audio alături (nu eroul) · Pornește… · Deschide Spotify · Nu a pornit ·
 * Play fără efect · cartea cântă · fără acces (liniște / se aude / Deschide Spotify) · cele mai pline stări (TOP +
 * „Nu a pornit.”, „Deschide Spotify” + cartea care cântă).
 */
abstract class MusicShotsBase(private val suffix: String) {
    private fun s3c(name: String, state: MusicUiState) = shot("music_s3c_$name$suffix") { MusicWaitContent(state, MusicActions()) }

    @Test fun playing() = s3c("playing", MusicWaitSamples.playing)
    @Test fun paused() = s3c("paused", MusicWaitSamples.paused)
    @Test fun idle() = s3c("idle", MusicWaitSamples.idle)
    @Test fun idleBook() = s3c("idle_book", MusicWaitSamples.idleBook)
    @Test fun starting() = s3c("starting", MusicWaitSamples.starting)
    @Test fun needsTap() = s3c("needs_tap", MusicWaitSamples.needsTap)
    @Test fun failed() = s3c("failed", MusicWaitSamples.failed)
    @Test fun resumeStarting() = s3c("resume_starting", MusicWaitSamples.resumeStarting)
    @Test fun resumeFailed() = s3c("resume_failed", MusicWaitSamples.resumeFailed)
    @Test fun pausedNeedsTap() = s3c("paused_needs_tap", MusicWaitSamples.pausedNeedsTap)
    @Test fun failedUnknown() = s3c("failed_unknown", MusicWaitSamples.failedUnknown)

    /** 4.4.1: nu a pornit și nu e nimic de deschis — fără buton de deschis, doar „Încearcă din nou”. */
    @Test fun failedNothing() = s3c("failed_nothing", MusicWaitSamples.failedNothing)
    @Test fun resumeFailedNothing() = s3c("resume_failed_nothing", MusicWaitSamples.resumeFailedNothing)

    /** Cazurile cele mai pline (rânduri în plus sub comenzi): pe S23 coloana trebuie să încapă fără derulare. */
    @Test fun resumeFailedTop() = s3c("resume_failed_top", MusicWaitSamples.resumeFailedTop)
    @Test fun pausedNeedsTapBook() = s3c("paused_needs_tap_book", MusicWaitSamples.pausedNeedsTapBook)
    @Test fun bookPlaying() = s3c("book_playing", MusicWaitSamples.bookPlaying)
    @Test fun noAccess() = s3c("noaccess", MusicWaitSamples.noAccess)
    @Test fun noAccessPlaying() = s3c("noaccess_playing", MusicWaitSamples.noAccessPlaying)
    @Test fun noAccessNeedsTap() = s3c("noaccess_needs_tap", MusicWaitSamples.noAccessNeedsTap)
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE, application = Application::class)
class MusicShots : MusicShotsBase("")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE_S23, application = Application::class)
class MusicShotsS23 : MusicShotsBase("_s23")
