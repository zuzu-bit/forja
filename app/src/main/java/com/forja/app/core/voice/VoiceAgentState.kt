package com.forja.app.core.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class VoiceAgentState(
    val running: Boolean = false,
    val listening: Boolean = false,
    val busy: Boolean = false,
    val message: String = "Agentul vocal este oprit.",
    val transcript: String = ""
)

/** Observable process-local state; restarting Android never restarts microphone capture. */
object VoiceAgentRuntime {
    private val mutableState = MutableStateFlow(VoiceAgentState())
    val state: StateFlow<VoiceAgentState> = mutableState.asStateFlow()

    internal fun update(transform: (VoiceAgentState) -> VoiceAgentState) {
        mutableState.update(transform)
    }
}
