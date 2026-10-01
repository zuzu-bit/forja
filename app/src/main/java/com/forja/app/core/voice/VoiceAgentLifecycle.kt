package com.forja.app.core.voice

/** Pure lifecycle decisions shared by the Android service and JVM tests. */
internal object VoiceAgentLifecycle {
    sealed interface Event {
        data object ScreenOff : Event
        data class UserPresent(val autoListen: Boolean, val locked: Boolean) : Event
        data object Stop : Event
        /** False for callbacks from an abandoned/replaced audio focus request. */
        data class AudioFocusLost(val currentRequest: Boolean) : Event
    }

    enum class Effect { NONE, PREPARE_LISTENING, ABORT_ACTION, PAUSE_VOICE, CLOSE_SESSION }

    data class Transition(
        val state: VoiceAgentState,
        val effect: Effect = Effect.NONE,
        val reason: String? = null
    )

    fun reduce(state: VoiceAgentState, event: Event): Transition = when (event) {
        Event.ScreenOff -> abort(state, "device_locked", "Telefon blocat. Deblochează-l pentru a continua.")
        is Event.UserPresent -> when {
            !state.running || event.locked -> Transition(state)
            event.autoListen -> Transition(state, Effect.PREPARE_LISTENING)
            else -> Transition(state.copy(message = "Telefon deblocat. Apasă Ascultă din notificare."))
        }
        Event.Stop -> abort(state, "agent_stopped", "Agentul vocal este oprit.").let {
            it.copy(state = it.state.copy(running = false), effect = Effect.CLOSE_SESSION)
        }
        is Event.AudioFocusLost -> when {
            !state.running || !event.currentRequest -> Transition(state)
            // YouTube takes focus when playback starts; keep UI verification alive.
            state.busy -> Transition(state.copy(listening = false,
                message = "Acțiunea continuă. Folosește notificarea FORJA pentru anulare."), Effect.PAUSE_VOICE)
            else -> abort(state, "audio_focus_lost",
                "Microfon întrerupt de altă aplicație. Apasă Ascultă pentru a continua.")
        }
    }

    private fun abort(state: VoiceAgentState, reason: String, message: String) = Transition(
        state.copy(listening = false, busy = false, message = message, transcript = ""),
        Effect.ABORT_ACTION, reason
    )
}
