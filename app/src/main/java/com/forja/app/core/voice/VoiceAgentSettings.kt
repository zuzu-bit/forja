package com.forja.app.core.voice

import android.content.Context

object VoiceAgentSettings {
    private const val PREFERENCES = "voice_agent"
    private const val AUTO_LISTEN = "auto_listen_after_unlock"

    /** Opt-in only; this setting cannot start a service or enable Accessibility. */
    fun isAutoListenEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(AUTO_LISTEN, false)

    fun setAutoListenEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean(AUTO_LISTEN, enabled).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
