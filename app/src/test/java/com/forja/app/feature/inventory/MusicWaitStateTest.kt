package com.forja.app.feature.inventory

import com.forja.app.core.music.FailReason
import com.forja.app.core.music.MusicKind
import com.forja.app.core.music.Rung
import com.forja.app.core.music.StartState
import com.forja.app.core.music.Step
import com.forja.app.core.music.Want
import org.junit.Assert.assertEquals
import org.junit.Test

/** Ecranul Muzică din Inventar (4.4.1): „Nu a pornit.” fără nimic de deschis nu mai arată un buton care nu deschide nimic. */
class MusicWaitStateTest {

    @Test fun failedWithNothingToOpenKeepsOnlyRetry() {
        assertEquals(StartUi.Failed(null), StartState.Failed(FailReason.NO_PLAYER, null).toUi())
        assertEquals(StartUi.Failed("Deschide Spotify"), StartState.Failed(FailReason.TIMEOUT, Step(Rung.O_LIKED_PAGE, MusicKind.SPOTIFY)).toUi())
        assertEquals(StartUi.Failed("Deschide playerul"), StartState.Failed(FailReason.TIMEOUT, Step(Rung.O_LAUNCH, MusicKind.YT_MUSIC)).toUi())
        assertEquals(StartUi.NeedsTap("Deschide Spotify"), StartState.NeedsTap(Step(Rung.V_LIKED_PLAY, MusicKind.SPOTIFY), Want.MyMusic).toUi())
        assertEquals(StartUi.Failed(null), MusicWaitSamples.failedNothing.start)
    }
}
