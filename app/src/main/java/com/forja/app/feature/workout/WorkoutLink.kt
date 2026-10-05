package com.forja.app.feature.workout

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Puntea dintre „Hei FORJA” și sesiunea de antrenament (4.9). Starea live stă în [WorkoutViewModel], al activității;
 * de aici o pot citi comenzile vocale („rezumat”, „unde am ajuns”), iar cererile lor („pauză”, „am terminat seria”,
 * „următorul exercițiu”) ajung la ViewModel când există. O pornire cerută înainte să se deschidă ecranul așteaptă aici
 * până îl creează navigarea.
 */
object WorkoutLink {
    sealed class Request {
        /** Pornește sesiunea planului [planIdx] (null = cel ales în hub). */
        data class Start(val planIdx: Int?) : Request()
        object Pause : Request()
        object Resume : Request()
        object FinishSet : Request()
        object SkipRest : Request()
        object AddRest : Request()
        object NextExercise : Request()
        object End : Request()
    }

    private val _live = MutableStateFlow<LiveState?>(null)
    /** Sesiunea în curs (null = niciuna), oglindită de ViewModel la fiecare schimbare. */
    val live: StateFlow<LiveState?> = _live.asStateFlow()

    private val _requests = MutableSharedFlow<Request>(extraBufferCapacity = 8)
    val requests: SharedFlow<Request> = _requests.asSharedFlow()

    /** Pornirea cerută și clipa ei ([SystemClock.elapsedRealtime]): o pornire veche nu mai pornește nimic. */
    @Volatile private var pendingStart: Pair<Request.Start, Long>? = null

    /** Doar ViewModel-ul. */
    fun publish(state: LiveState?) { _live.value = state }

    /** Un antrenament e pornit acum (și neterminat)? */
    fun active(): Boolean = _live.value?.let { !it.finished } == true

    fun request(r: Request) {
        if (r is Request.Start) pendingStart = r to SystemClock.elapsedRealtime()
        _requests.tryEmit(r)
    }

    /** ViewModel-ul abia creat ia pornirea rămasă în așteptare (dacă n-a prins-o prin [requests]) — doar una proaspătă. */
    fun takePendingStart(maxAgeMs: Long = 15_000L): Request.Start? {
        val p = pendingStart ?: return null
        pendingStart = null
        return if (SystemClock.elapsedRealtime() - p.second <= maxAgeMs) p.first else null
    }

    /** Pornirea a fost preluată: nu mai așteaptă pe nimeni. */
    fun clearPendingStart() { pendingStart = null }
}
