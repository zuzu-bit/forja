package com.forja.app.core.voice

import com.forja.app.core.voice.VoiceAgentLifecycle.Effect
import com.forja.app.core.voice.VoiceAgentLifecycle.Event
import org.junit.Assert.*
import org.junit.Test

class VoiceAgentLifecycleTest {
    private val active = VoiceAgentState(running = true, listening = true, busy = true,
        message = "Executing", transcript = "YouTube command")

    @Test fun screenOffCancelsCurrentActionAndClearsCaptureWithoutClosingService() {
        val result = VoiceAgentLifecycle.reduce(active, Event.ScreenOff)
        assertEquals(Effect.ABORT_ACTION, result.effect)
        assertEquals("device_locked", result.reason)
        assertTrue(result.state.running)
        assertFalse(result.state.busy)
        assertFalse(result.state.listening)
        assertEquals("", result.state.transcript)
    }

    @Test fun screenOffAlsoCancelsListeningBeforeAnActionExists() {
        val result = VoiceAgentLifecycle.reduce(active.copy(busy = false), Event.ScreenOff)
        assertEquals(Effect.ABORT_ACTION, result.effect)
        assertFalse(result.state.listening)
        assertFalse(result.state.busy)
    }

    @Test fun userPresentPreparesListeningWhenAutoListenEnabledAndUnlocked() {
        val idle = active.copy(busy = false, listening = false, transcript = "")
        val result = VoiceAgentLifecycle.reduce(idle, Event.UserPresent(autoListen = true, locked = false))
        assertEquals(Effect.PREPARE_LISTENING, result.effect)
        // The Android recognizer, rather than the unlock event, marks capture as listening.
        assertEquals(idle, result.state)
    }

    @Test fun userPresentDoesNotListenWhenAutoListenDisabled() {
        val idle = active.copy(busy = false, listening = false)
        val result = VoiceAgentLifecycle.reduce(idle, Event.UserPresent(autoListen = false, locked = false))
        assertEquals(Effect.NONE, result.effect)
        assertFalse(result.state.listening)
        assertFalse(result.state.busy)
        assertTrue(result.state.running)
        assertTrue(result.state.message.contains("Ascultă"))
    }

    @Test fun userPresentCannotStartAStoppedServiceEvenWithAutoListen() {
        for (autoListen in listOf(false, true)) {
            val stopped = VoiceAgentState()
            val result = VoiceAgentLifecycle.reduce(stopped, Event.UserPresent(autoListen, locked = false))
            assertEquals(Effect.NONE, result.effect)
            assertEquals(stopped, result.state)
        }
    }

    @Test fun userPresentCannotListenWhileKeyguardStillReportsLocked() {
        for (autoListen in listOf(false, true)) {
            val result = VoiceAgentLifecycle.reduce(active, Event.UserPresent(autoListen, locked = true))
            assertEquals(Effect.NONE, result.effect)
            assertEquals(active, result.state)
        }
    }

    @Test fun stopCancelsActionAndClosesSessionImmediately() {
        val result = VoiceAgentLifecycle.reduce(active, Event.Stop)
        assertEquals(Effect.CLOSE_SESSION, result.effect)
        assertEquals("agent_stopped", result.reason)
        assertFalse(result.state.running)
        assertFalse(result.state.listening)
        assertFalse(result.state.busy)
        assertEquals("", result.state.transcript)
    }

    @Test fun stopIsIdempotentForAnAlreadyClosedSession() {
        val first = VoiceAgentLifecycle.reduce(active, Event.Stop)
        val second = VoiceAgentLifecycle.reduce(first.state, Event.Stop)
        assertEquals(first, second)
    }

    @Test fun unlockAfterScreenOffRespectsAutoListenSetting() {
        val locked = VoiceAgentLifecycle.reduce(active, Event.ScreenOff).state
        assertEquals(Effect.NONE, VoiceAgentLifecycle.reduce(locked,
            Event.UserPresent(autoListen = false, locked = false)).effect)
        assertEquals(Effect.PREPARE_LISTENING, VoiceAgentLifecycle.reduce(locked,
            Event.UserPresent(autoListen = true, locked = false)).effect)
    }

    @Test fun unlockAfterStopCannotReopenSession() {
        val stopped = VoiceAgentLifecycle.reduce(active, Event.Stop).state
        val result = VoiceAgentLifecycle.reduce(stopped, Event.UserPresent(autoListen = true, locked = false))
        assertEquals(Effect.NONE, result.effect)
        assertEquals(stopped, result.state)
    }

    @Test fun focusLossDuringListeningAbortsCaptureAndClearsFlags() {
        val result = VoiceAgentLifecycle.reduce(active.copy(busy = false), Event.AudioFocusLost(true))
        assertEquals(Effect.ABORT_ACTION, result.effect)
        assertEquals("audio_focus_lost", result.reason)
        assertTrue(result.state.running)
        assertFalse(result.state.listening)
        assertFalse(result.state.busy)
        assertEquals("", result.state.transcript)
    }

    @Test fun focusLossDuringPreparationAlsoAbortsPendingWork() {
        val result = VoiceAgentLifecycle.reduce(active.copy(busy = false, listening = false),
            Event.AudioFocusLost(true))
        assertEquals(Effect.ABORT_ACTION, result.effect)
        assertFalse(result.state.listening)
        assertFalse(result.state.busy)
    }

    @Test fun focusLossDuringUiActionPausesVoiceButKeepsVerificationBusy() {
        val result = VoiceAgentLifecycle.reduce(active, Event.AudioFocusLost(true))
        assertEquals(Effect.PAUSE_VOICE, result.effect)
        assertNull(result.reason)
        assertTrue(result.state.running)
        assertTrue(result.state.busy)
        assertFalse(result.state.listening)
        assertEquals(active.transcript, result.state.transcript)
    }

    @Test fun staleFocusLossDoesNotInterruptANewerSession() {
        for (busy in listOf(false, true)) {
            val state = active.copy(busy = busy)
            val result = VoiceAgentLifecycle.reduce(state, Event.AudioFocusLost(false))
            assertEquals(Effect.NONE, result.effect)
            assertEquals(state, result.state)
        }
    }

    @Test fun focusLossAfterStopCannotRestoreBusyOrListening() {
        val stopped = VoiceAgentLifecycle.reduce(active, Event.Stop).state
        val result = VoiceAgentLifecycle.reduce(stopped, Event.AudioFocusLost(true))
        assertEquals(Effect.NONE, result.effect)
        assertEquals(stopped, result.state)
    }

    @Test fun screenOffAfterFocusLossStillCancelsUiVerification() {
        val paused = VoiceAgentLifecycle.reduce(active, Event.AudioFocusLost(true)).state
        val result = VoiceAgentLifecycle.reduce(paused, Event.ScreenOff)
        assertEquals(Effect.ABORT_ACTION, result.effect)
        assertFalse(result.state.busy)
        assertFalse(result.state.listening)
    }
}
