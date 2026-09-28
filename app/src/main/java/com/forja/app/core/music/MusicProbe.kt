package com.forja.app.core.music

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Proba ascunsă (music-start.md §7.3): fiecare treaptă o dată, pe playerul ei, cu pauză și 2 s între ele. Rezultatele
 * intră în jurnal ([MusicLog], cu want = „probe”) și în tabelul învățat, apoi se trimit serverului.
 *
 * Treptele invizibile merg singure, una după alta. Cele vizibile (aduc Spotify în față) se fac câte una, la atingere:
 * se revine cu Înapoi, iar rezultatul se vede la întoarcere.
 */
internal object MusicProbe {

    enum class Status { WAITING, RUNNING, DONE }

    data class Row(val rung: Rung, val status: Status = Status.WAITING, val result: DiagResult? = null, val ms: Long? = null, val note: String? = null)

    val INVISIBLE = listOf(Rung.S_PLAY, Rung.S_ANY, Rung.S_TOP, Rung.S_LIKED, Rung.K_TOKEN, Rung.K_PLAY)
    val VISIBLE = listOf(Rung.V_TRACK, Rung.V_LIKED_PLAY, Rung.V_PFS_DATA, Rung.V_PFS_ANY, Rung.V_PFS_TOP)

    private val _rows = MutableStateFlow((INVISIBLE + VISIBLE).map { Row(it) })
    val rows: StateFlow<List<Row>> = _rows.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun reset() {
        _rows.value = (INVISIBLE + VISIBLE).map { Row(it) }
    }

    private fun set(rung: Rung, row: (Row) -> Row) {
        _rows.value = _rows.value.map { if (it.rung == rung) row(it) else it }
    }

    /** Treptele invizibile, pe rând. Înainte: totul pe pauză. */
    suspend fun runInvisible(context: Context, pkg: String) {
        if (_running.value) return
        _running.value = true
        try {
            MusicStarter.pause(context)
            delay(1_500)
            for (r in INVISIBLE) {
                runOne(context, r, pkg)
                MusicStarter.pause(context)
                delay(2_000)
            }
        } finally {
            _running.value = false
        }
    }

    /** O treaptă vizibilă (o atingere). Rezultatul vine când FORJA revine în față. */
    suspend fun runVisible(context: Context, rung: Rung, pkg: String) {
        if (_running.value) return
        _running.value = true
        try {
            MusicStarter.pause(context)
            delay(600)
            runOne(context, rung, pkg)
        } finally {
            _running.value = false
        }
    }

    private suspend fun runOne(context: Context, rung: Rung, pkg: String) {
        set(rung) { it.copy(status = Status.RUNNING, result = null, ms = null, note = null) }
        val before = MusicLog.events.value.size
        MusicStarter.start(context, Want.Probe(rung, pkg), MusicSource.PROBE, tap = true)
        // Până se termină încercarea (reușită, eșec, deschis în player): cel mult 25 s.
        withTimeoutOrNull(25_000L) {
            delay(300)
            while (MusicStarter.busy || MusicStarter.state.value is StartState.Starting) delay(200)
        }
        val mine = MusicLog.events.value.drop(before.coerceAtMost(MusicLog.events.value.size))
            .lastOrNull { it.want == "probe" && it.rung == rung.id }
        set(rung) {
            it.copy(status = Status.DONE, result = mine?.result ?: DiagResult.TIMEOUT, ms = mine?.ms, note = mine?.err ?: mine?.kind?.wire)
        }
    }
}
