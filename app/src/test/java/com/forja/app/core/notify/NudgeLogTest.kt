package com.forja.app.core.notify

import android.app.Application
import android.content.Context
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Jurnalul Căștii (mirror D): mesajele private fără text, rezultatul pe ultimul mesaj potrivit. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NudgeLogTest {
    private val c: Context = RuntimeEnvironment.getApplication()

    @Test fun privateMessagesAreLoggedWithoutTextAndOutcomesLand() {
        val now = System.currentTimeMillis()
        NudgeStore.logPosted(c, Rendered("S1", NudgeContext.SleepReport, "Ai dormit 6 h 10", "profund 1 h", NudgePose.Talking, true, true), "sleep", now - 2000)
        NudgeStore.logPosted(c, Rendered("F1", NudgeContext.FocusDone, "Postul s-a încheiat.", "50 min", NudgePose.Talking, false, false), "coach", now - 1000)
        NudgeStore.logOutcome(c, "tapped", ctx = "FocusDone")
        NudgeStore.logOutcome(c, "dismissed", id = "S1")
        val log = NudgeStore.log(c)
        assertEquals(listOf("SleepReport", "FocusDone"), log.map { it.ctx })
        assertNull(log[0].title); assertNull(log[0].body); assertEquals(true, log[0].private)
        assertFalse(NudgeStore.prefs(c).all.values.joinToString().contains("dormit"))
        assertEquals(listOf("dismissed", "tapped"), log.map { it.outcome })
        assertEquals("Postul s-a încheiat.", log[1].title)
    }
}
