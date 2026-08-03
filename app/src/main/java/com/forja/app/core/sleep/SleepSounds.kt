package com.forja.app.core.sleep

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.forja.app.R
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Sunete de adormit — fișiere reale, redate în buclă din aplicație (merg și fără internet).
 * Sursă: freesound.org — inchadney, felix.blume (CC0); D W, Corsica_S, mystiscool, RHumphries (CC BY).
 */
object SleepSounds {
    /** Ce sună acum (null = nimic) — pentru UI. */
    val current = MutableStateFlow<String?>(null)
    /** Temporizatorul ales, în minute (0 = fără oprire). */
    val timerMinutes = MutableStateFlow(30)

    private val rawFor = mapOf(
        "rain" to R.raw.snd_rain,
        "storm" to R.raw.snd_storm,
        "wind" to R.raw.snd_wind,
        "stream" to R.raw.snd_stream,
        "fire" to R.raw.snd_fire,
        "forest" to R.raw.snd_forest
    )

    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var stopRunnable: Runnable? = null

    fun toggle(context: Context, type: String, minutes: Int = timerMinutes.value) {
        if (current.value == type) stop() else start(context, type, minutes)
    }

    fun start(context: Context, type: String, minutes: Int = timerMinutes.value) {
        val res = rawFor[type] ?: return
        val appCtx = context.applicationContext
        main.post {
            stopInternal()
            val p = ExoPlayer.Builder(appCtx).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.parse("android.resource://${appCtx.packageName}/$res")))
                repeatMode = Player.REPEAT_MODE_ONE
                volume = 1f
                setWakeMode(C.WAKE_MODE_LOCAL)   // continuă cu ecranul stins
                playWhenReady = true
                prepare()
            }
            player = p
            current.value = type
            scheduleTimer(minutes)
        }
    }

    /** Schimbă temporizatorul; dacă un sunet cântă deja, reprogramează oprirea. */
    fun setTimer(minutes: Int) {
        timerMinutes.value = minutes
        if (current.value != null) main.post { scheduleTimer(minutes) }
    }

    fun stop() {
        main.post { stopInternal() }
    }

    private fun scheduleTimer(minutes: Int) {
        stopRunnable?.let { main.removeCallbacks(it) }
        stopRunnable = null
        if (minutes > 0) {
            val r = Runnable { stopInternal() }
            stopRunnable = r
            main.postDelayed(r, minutes * 60_000L)
        }
    }

    private fun stopInternal() {
        stopRunnable?.let { main.removeCallbacks(it) }
        stopRunnable = null
        try { player?.release() } catch (_: Exception) { }
        player = null
        current.value = null
    }
}
