package com.forja.app.core.recovery

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/*
 * Găsirea telefonului (4.4) — regulile, fără Android: starea din Profil, ce facem cu o comandă venită de pe site,
 * cât sună telefonul, textele cu plural românesc. Totul se testează pe JVM (FinderLogicTest).
 */

/** Starea rândului „Telefonul meu” din Profil — un singur cuvânt. */
enum class FinderState(val word: String) {
    /** Contract semnat, telefon înrolat, site-ul l-a văzut în ultimele 10 minute. */
    Guard("în gardă"),
    /** Contractul nu e semnat (sau are o versiune nouă nesemnată): telefonul nu e găsibil. */
    NoContract("fără contract"),
    /** Lipsește ceva din Android: locația „Tot timpul”, notificările, bateria fără restricții. */
    Incomplete("incomplet"),
    /** Totul e la locul lui, dar site-ul nu l-a mai văzut de 10 minute (sau niciodată). */
    NoLink("fără legătură"),
}

/** O comandă de pe site (§3.4): `locate` = Urmărește, `ring` = Sună. Serverul vechi (v1) nu trimite `kind` → locate. */
data class FinderCommand(
    val id: String,
    val kind: Kind,
    val createdAt: Long,
    val startBefore: Long,
    val until: Long,
    val phase: String,
    val minutes: Int?,
    val seconds: Int?,
) {
    enum class Kind(val wire: String) { Locate("locate"), Ring("ring") }

    /** Încă se poate porni: activă, sau în coadă cu fereastra de pornire deschisă; și nu a trecut de termen. */
    fun startable(now: Long): Boolean = until > now && (phase == "active" || startBefore > now)

    companion object {
        fun parse(o: JsonObject?): FinderCommand? {
            if (o == null) return null
            fun s(k: String) = o[k]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            fun l(k: String) = o[k]?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }
            val id = s("id")?.takeIf { it.isNotBlank() } ?: return null
            val kind = if (s("kind") == Kind.Ring.wire) Kind.Ring else Kind.Locate
            return FinderCommand(
                id = id,
                kind = kind,
                createdAt = l("created_at") ?: 0L,
                startBefore = l("start_before") ?: 0L,
                until = l("until") ?: 0L,
                phase = s("phase") ?: "queued",
                minutes = l("minutes")?.toInt(),
                seconds = l("seconds")?.toInt(),
            )
        }
    }
}

object FinderLogic {
    /** Fără o bătaie reușită în atâta timp, rândul spune „fără legătură”. */
    const val LINK_WINDOW_MS = 10 * 60_000L
    /** Soneria implicită și limitele ei (secunde). */
    const val RING_DEFAULT_S = 60
    const val RING_MIN_S = 10
    const val RING_MAX_S = 120

    fun state(contractOn: Boolean, prerequisitesOk: Boolean, lastOkAt: Long, now: Long): FinderState = when {
        !contractOn -> FinderState.NoContract
        !prerequisitesOk -> FinderState.Incomplete
        lastOkAt > 0 && now - lastOkAt in 0..LINK_WINDOW_MS -> FinderState.Guard
        else -> FinderState.NoLink
    }

    /**
     * Starea trimisă site-ului la fiecare bătaie (ordinea contează: întâi ce face telefonul acum, apoi ce-l împiedică).
     * Valorile sunt cele din §3.4: ready|locating|ringing|location_off|permission_missing|notification_missing.
     */
    fun beatStatus(
        ringing: Boolean,
        locating: Boolean,
        locationEnabled: Boolean,
        locationPermission: Boolean,
        notices: Boolean,
    ): String = when {
        ringing -> "ringing"
        locating -> "locating"
        !locationEnabled -> "location_off"
        !locationPermission -> "permission_missing"
        !notices -> "notification_missing"
        else -> "ready"
    }

    /** Ce facem cu comanda primită de la site, știind ce rulează acum pe telefon. */
    sealed interface Decision {
        /** Nimic de făcut (comandă deja închisă aici, expirată sau aceeași). */
        data object Keep : Decision
        /** Comanda curentă a dispărut de pe site (Oprește din site, expirată): oprim ce rulează. */
        data object StopActive : Decision
        /** Aceeași comandă, cu alt termen (Urmărește +10 min). */
        data class Extend(val command: FinderCommand) : Decision
        /** Comandă nouă: o pornim (și oprim una veche, dacă mai rulează). */
        data class Start(val command: FinderCommand) : Decision
    }

    fun decide(
        command: FinderCommand?,
        activeId: String?,
        activeUntil: Long,
        handled: Set<String>,
        now: Long,
    ): Decision {
        if (command == null) return if (activeId != null) Decision.StopActive else Decision.Keep
        if (command.id in handled) return if (activeId == command.id) Decision.StopActive else Decision.Keep
        if (command.id == activeId) {
            return when {
                command.until <= now -> Decision.StopActive
                command.until != activeUntil -> Decision.Extend(command)
                else -> Decision.Keep
            }
        }
        // Alt id decât ce rulează: pe site e altă comandă; cea veche nu mai are voie să continue.
        if (!command.startable(now)) return if (activeId != null) Decision.StopActive else Decision.Keep
        return Decision.Start(command)
    }

    /** Cât sună: `seconds` din comandă (implicit 60), între 10 și 120 s, dar nu dincolo de termenul comenzii. */
    fun ringMillis(command: FinderCommand, now: Long): Long {
        val wanted = (command.seconds ?: RING_DEFAULT_S).coerceIn(RING_MIN_S, RING_MAX_S) * 1000L
        val left = if (command.until > 0) command.until - now else wanted
        return wanted.coerceAtMost(left).coerceAtLeast(0L)
    }

    /** Intervalul următoarei bătăi (`next_s` de la server), ținut între 30 s și 15 min; implicit 60 s. */
    fun nextBeatMillis(nextS: Long?): Long = ((nextS ?: 60L).coerceIn(30L, 900L)) * 1000L

    /** „un minut”, „2 minute”, „20 de minute” — pluralul românesc pentru minute. */
    fun minutes(n: Int): String = when {
        n == 1 -> "un minut"
        n % 100 in 1..19 || n == 0 -> "$n minute"
        else -> "$n de minute"
    }

    /** „o secundă”, „45 de secunde”, „12 secunde”. */
    fun seconds(n: Int): String = when {
        n == 1 -> "o secundă"
        n % 100 in 1..19 || n == 0 -> "$n secunde"
        else -> "$n de secunde"
    }

    /** Minutele rămase până la `until`, rotunjite în sus (niciodată sub 1 cât comanda e vie). */
    fun minutesLeft(until: Long, now: Long): Int = (((until - now).coerceAtLeast(0L) + 59_999L) / 60_000L).toInt().coerceAtLeast(1)

    /** Textul notificării de căutare: „Poziția pleacă încă 10 minute.” */
    fun locateText(until: Long, now: Long): String = "Poziția pleacă încă ${minutes(minutesLeft(until, now))}."

    /** „VĂZUT ACUM 1 MIN”, „VĂZUT CHIAR ACUM”, „VĂZUT ACUM 3 H”, „ÎNCĂ NEVĂZUT” (majuscule, pentru linia mono). */
    fun seen(lastOkAt: Long, now: Long): String {
        if (lastOkAt <= 0L) return "ÎNCĂ NEVĂZUT"
        val min = ((now - lastOkAt).coerceAtLeast(0L)) / 60_000L
        return when {
            min < 1 -> "VĂZUT CHIAR ACUM"
            min < 60 -> "VĂZUT ACUM $min MIN"
            min < 48 * 60 -> "VĂZUT ACUM ${min / 60} H"
            else -> "VĂZUT ACUM ${min / (24 * 60)} ZILE"
        }
    }

    /** Linia mono din foaia Găsire: „ÎN GARDĂ · VĂZUT ACUM 1 MIN · 64%”. */
    fun statusLine(state: FinderState, lastOkAt: Long, battery: Int?, now: Long): String = buildList {
        add(state.word.uppercase(java.util.Locale("ro")))
        add(seen(lastOkAt, now))
        if (battery != null && battery in 0..100 && lastOkAt > 0) add("$battery%")
    }.joinToString(" · ")
}
