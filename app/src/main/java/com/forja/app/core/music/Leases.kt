package com.forja.app.core.music

/** Împrumutul: „muzica asta a pornit-o FORJA, pentru …”. Doar cine ține împrumutul are voie să o oprească. */
class Lease internal constructor(val id: Long, val source: MusicSource)

/**
 * Cine a pornit muzica acum (workout-music.md §4.3). Un singur împrumut odată: Antrenamentul îl ia peste Inventar.
 * Kotlin pur.
 *
 * - Sfârșitul unui inventar în timpul antrenamentului nu oprește muzica de sală ([inventoryMayPause]): nici cea pornită
 *   de FORJA, nici a ei (a pornit-o singură, a schimbat lista, a apăsat Play din ecranul Muzică).
 * - La finalul antrenamentului se oprește doar muzica pornită de FORJA ([release] întoarce true doar dacă împrumutul
 *   mai e al lui — dacă ea a pornit altceva între timp, împrumutul a căzut).
 */
class LeaseBook {
    private var seq = 0L
    private var current: Lease? = null

    /** O sesiune de Antrenament e în curs (cu sau fără muzica pornită de FORJA). */
    var workoutLive: Boolean = false

    fun acquire(source: MusicSource): Lease {
        // Muzica de sală rămâne a Antrenamentului: un Play din ecranul Muzică în timpul sesiunii nu-i ia împrumutul.
        val cur = current
        if (source == MusicSource.INVENTORY && cur != null && cur.source == MusicSource.WORKOUT) return cur
        val l = Lease(++seq, source)
        current = l
        return l
    }

    fun owner(): MusicSource? = current?.source

    fun holds(lease: Lease?): Boolean = lease != null && current?.id == lease.id

    /** Eliberează împrumutul; true = era încă al lui (muzica e a FORJA, se poate opri). */
    fun release(lease: Lease?): Boolean {
        if (!holds(lease)) return false
        current = null
        return true
    }

    /** A preluat ea (altă listă, alt player): nimeni nu mai are voie să oprească muzica. */
    fun drop() {
        current = null
    }

    fun current(): Lease? = current

    /**
     * Finalul unui inventar are voie să pună pauză? Niciodată cât ține un antrenament — fie sesiunea e marcată live
     * ([workoutLive]), fie împrumutul curent e al Antrenamentului (muzica de sală pornită la „Începe sesiunea”, chiar
     * înainte ca ecranul live să seteze workoutLive). Lista de antrenament pornită cu vocea fără sesiune (4.9:
     * „pornește un playlist”) nu ia împrumutul WORKOUT, deci se oprește ca orice muzică.
     */
    fun inventoryMayPause(): Boolean = !workoutLive && current?.source != MusicSource.WORKOUT
}
