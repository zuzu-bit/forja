package com.forja.app.core.notify

import kotlinx.serialization.Serializable

/*
 * Ritmul Cascăi (notifications-design.md §E + corecturile 2, 9, 10) — reguli pure, fără Android, testate pe JVM.
 * Starea ([NudgeState]) se ține pe telefon (NudgeStore, JSON); funcțiile de aici o citesc și întorc starea nouă.
 */

@Serializable
data class SentRec(val id: String, val ctx: String, val channel: String, val at: Long)

/** Ultimul mesaj „coach” trimis, încă nerezolvat: deschis (a mers) sau ignorat (swipe / fără deschidere în 3 h). */
@Serializable
data class PendingRec(val id: String, val ctx: String, val at: Long)

/** Un subiect de permisiune (contract, locația „tot timpul”, agenda): ≤ 1 pe săptămână, oprit după 2 refuzuri. */
@Serializable
data class PermRec(val lastAt: Long = 0L, val ignores: Int = 0)

/** Seria curentă a unui fel (instrucție, rație, mers), ca pragurile și recordul să se anunțe o singură dată. */
@Serializable
data class RunRec(
    /** Ziua (epochDay) în care a început seria curentă. */
    val start: Long = 0L,
    /** Cea mai lungă serie de dinaintea celei curente. */
    val prevBest: Int = 0,
    /** Valoarea curentă. */
    val last: Int = 0,
    /** Ultimul prag anunțat pentru seria curentă. */
    val milestone: Int = 0,
    /** Recordul seriei curente a fost anunțat. */
    val record: Boolean = false
)

/** Un mesaj reținut în orele de liniște (camarad nou), trimis după 08:00. */
@Serializable
data class HeldRec(
    val ctx: String, val id: String, val title: String, val body: String, val pose: String,
    val private: Boolean, val localOnly: Boolean, val at: Long, val notifId: Int, val channel: String
)

@Serializable
data class NudgeState(
    val sent: List<SentRec> = emptyList(),
    val coachDay: Long = -1L,
    val coachCount: Int = 0,
    val lastCoachAt: Long = 0L,
    /** Fereastra (dimineață/prânz/seară/serie) → ziua în care a vorbit deja. */
    val windows: Map<String, Long> = emptyMap(),
    /** Context → mesaje ignorate la rând. */
    val ignores: Map<String, Int> = emptyMap(),
    /** Context → de câte ori s-a înjumătățit frecvența (0 = zilnic, 1 = o zi din două, …). */
    val halvings: Map<String, Int> = emptyMap(),
    val pending: PendingRec? = null,
    val lastOpen: Long = 0L,
    val comebackSteps: List<Int> = emptyList(),
    /** După „Ultimul mesaj, promit”: tăcere pentru tot ce nu e eveniment, până la următoarea deschidere. */
    val silentUntilOpen: Boolean = false,
    val runs: Map<String, RunRec> = emptyMap(),
    val friendDays: Map<String, Long> = emptyMap(),
    val friendDay: Long = -1L,
    val friendCount: Int = 0,
    val perms: Map<String, PermRec> = emptyMap(),
    /** Contractul a fost semnat măcar o dată pe acest telefon: după Revocă, zero mesaje despre contract. */
    val everSigned: Boolean = false,
    val held: List<HeldRec> = emptyList()
)

/** Fereastra zilei în care Casca are voie să vorbească. */
enum class Window(val key: String, val context: NudgeContext, val fromMin: Int, val toMin: Int) {
    Morning("morning", NudgeContext.Morning, 8 * 60, 10 * 60 + 30),
    Midday("midday", NudgeContext.Midday, 12 * 60, 14 * 60 + 30),
    Evening("evening", NudgeContext.Evening, 19 * 60, 21 * 60 + 45)
}

/** Ce trimite lucrătorul acum. */
data class CoachPlan(val context: NudgeContext, val windowKey: String)

object NudgeRules {
    const val QUIET_FROM_MIN = 22 * 60
    const val QUIET_TO_MIN = 8 * 60
    const val COACH_PER_DAY = 3
    const val COACH_GAP_MS = 3 * 3600_000L
    /** Nimic dacă aplicația a fost deschisă în ultimele 90 de minute. */
    const val OPEN_QUIET_MS = 90 * 60_000L
    /** Un mesaj nedeschis în 3 h (fără swipe) contează ca ignorat. */
    const val PENDING_TTL_MS = 3 * 3600_000L
    const val FRIENDS_PER_DAY = 2
    const val IGNORES_TO_HALVE = 3
    const val MAX_HALVINGS = 3
    const val PERM_EVERY_MS = 7L * 24 * 3600_000L
    const val PERM_MAX_IGNORES = 2
    const val HELD_TTL_MS = 18 * 3600_000L
    val COMEBACK_STEPS = listOf(2, 7, 14)
    val MILESTONES = listOf(3, 7, 14, 30, 50, 100, 365)
    private const val SENT_PER_CHANNEL = Nudge.RECENT_PER_CHANNEL

    // ───────────── Orele de liniște (22:00–08:00) ─────────────

    fun isQuiet(hour: Int, minute: Int): Boolean {
        val m = hour * 60 + minute
        return m >= QUIET_FROM_MIN || m < QUIET_TO_MIN
    }

    fun quietPolicy(ctx: NudgeContext): QuietPolicy = when (ctx) {
        NudgeContext.Bedtime -> QuietPolicy.Pass
        NudgeContext.SleepReport, NudgeContext.FocusDone, NudgeContext.MealLog, NudgeContext.SyncStopped,
        NudgeContext.SyncOngoing -> QuietPolicy.Silent
        NudgeContext.NewFriend -> QuietPolicy.Hold
        else -> QuietPolicy.Drop
    }

    /** Poate pleca acum cu sunet? (în liniște: doar culcarea). */
    fun audibleNow(ctx: NudgeContext, hour: Int, minute: Int): Boolean =
        !isQuiet(hour, minute) || quietPolicy(ctx) == QuietPolicy.Pass

    // ───────────── Fereastra, plafonul, auto-reglarea ─────────────

    fun window(hour: Int, minute: Int): Window? {
        val m = hour * 60 + minute
        return Window.entries.firstOrNull { m >= it.fromMin && m < it.toMin }
    }

    /** ≤ 3 mesaje „coach” pe zi și ≥ 3 h între ele. */
    fun coachBlocked(s: NudgeState, now: Long, day: Long): Boolean {
        val count = if (s.coachDay == day) s.coachCount else 0
        return count >= COACH_PER_DAY || (s.lastCoachAt > 0 && now - s.lastCoachAt < COACH_GAP_MS)
    }

    /** După 3 mesaje ignorate la rând, contextul vorbește o zi din două (apoi din patru, din opt). */
    fun contextDue(s: NudgeState, ctx: NudgeContext, day: Long): Boolean {
        val h = (s.halvings[ctx.name] ?: 0).coerceIn(0, MAX_HALVINGS)
        val period = 1L shl h
        return Math.floorMod(day, period) == 0L
    }

    /** Pasul de revenire datorat (2, 7, 14), cel mai mare ≤ zilele de absență și netrimis încă. */
    fun comebackStep(absentDays: Int, sentSteps: List<Int>): Int {
        if (sentSteps.contains(14)) return 0
        return COMEBACK_STEPS.filter { it <= absentDays && it !in sentSteps }.maxOrNull() ?: 0
    }

    fun absentDays(lastOpen: Long, now: Long, dayOf: (Long) -> Long): Int =
        if (lastOpen <= 0L) 0 else (dayOf(now) - dayOf(lastOpen)).toInt().coerceAtLeast(0)

    /**
     * Ce mesaj „coach” pleacă acum, sau null. Ordinea (§E.1): revenirea > seria în pericol (seara) > permisiunea (la prânz,
     * dacă e un subiect datorat) > fereastra zilei. Evenimentele (loc, prieten, raport, prag) nu trec pe aici.
     */
    fun planCoach(s: NudgeState, d: NudgeData, day: Long): CoachPlan? {
        if (isQuiet(d.hour, d.minute)) return null
        if (s.lastOpen > 0 && d.now - s.lastOpen < OPEN_QUIET_MS) return null
        if (s.silentUntilOpen) return null
        if (coachBlocked(s, d.now, day)) return null
        if (d.comebackStep > 0) return CoachPlan(NudgeContext.Comeback, "comeback")
        if (d.absentDays >= 2) return null
        val w = window(d.hour, d.minute) ?: return null
        if (s.windows[w.key] == day) return null
        if (w == Window.Evening && d.risk != null && contextDue(s, NudgeContext.StreakRisk, day)) {
            return CoachPlan(NudgeContext.StreakRisk, w.key)
        }
        if (w == Window.Midday && d.permission != null) return CoachPlan(NudgeContext.Permission, w.key)
        if (!contextDue(s, w.context, day)) return null
        return CoachPlan(w.context, w.key)
    }

    /** Subiectul de permisiune datorat: nu mai des de o dată pe săptămână, oprit după 2 ignorări. */
    fun permissionDue(s: NudgeState, subject: String, now: Long): Boolean {
        val r = s.perms[subject] ?: PermRec()
        return r.ignores < PERM_MAX_IGNORES && (r.lastAt == 0L || now - r.lastAt >= PERM_EVERY_MS)
    }

    // ───────────── Serii: praguri și record, o singură dată pe serie ─────────────

    /** Ține seria curentă la zi (începutul ei, cea mai bună dinainte). */
    fun trackRuns(s: NudgeState, streaks: List<Streak>, day: Long): NudgeState {
        val runs = s.runs.toMutableMap()
        for (st in streaks) {
            val key = st.kind.name
            val old = runs[key] ?: RunRec()
            if (st.current <= 0) {
                if (old.last > 0) runs[key] = RunRec(start = 0L, prevBest = maxOf(old.prevBest, old.last))
                continue
            }
            val start = if (st.doneToday) day - st.current + 1 else day - st.current
            runs[key] = if (old.start != start || old.last == 0) {
                RunRec(start = start, prevBest = maxOf(old.prevBest, if (old.start != start) old.last else 0), last = st.current)
            } else old.copy(last = st.current)
        }
        return s.copy(runs = runs)
    }

    /** Pragul atins azi și neanunțat (cel mai mare, pe seria cea mai lungă). */
    fun milestoneDue(s: NudgeState, streaks: List<Streak>): Streak? =
        streaks.filter { st ->
            st.doneToday && st.current in MILESTONES && (s.runs[st.kind.name]?.milestone ?: 0) != st.current
        }.maxByOrNull { it.current }

    /** Recordul personal bătut azi (în afara pragurilor), anunțat o singură dată pe serie. */
    fun recordDue(s: NudgeState, streaks: List<Streak>): Pair<Streak, Int>? =
        streaks.mapNotNull { st ->
            val r = s.runs[st.kind.name] ?: return@mapNotNull null
            if (st.doneToday && st.current !in MILESTONES && r.prevBest >= 3 && st.current > r.prevBest && !r.record) st to r.prevBest else null
        }.maxByOrNull { it.first.current }

    fun onMilestone(s: NudgeState, st: Streak, record: Boolean): NudgeState {
        val r = s.runs[st.kind.name] ?: RunRec()
        val upd = if (record) r.copy(record = true) else r.copy(milestone = st.current)
        return s.copy(runs = s.runs + (st.kind.name to upd))
    }

    /** Cea mai lungă serie încheiată (pentru „Ultima serie: 12 zile.” la revenire). */
    fun oldStreak(s: NudgeState): Int = s.runs.values.maxOfOrNull { maxOf(it.prevBest, it.last) } ?: 0

    // ───────────── Prieteni: ≤ 1/prieten/zi, ≤ 2/zi ─────────────

    fun friendAllowed(s: NudgeState, uid: String, day: Long): Boolean =
        s.friendDays[uid] != day && (s.friendDay != day || s.friendCount < FRIENDS_PER_DAY)

    fun onFriend(s: NudgeState, uid: String, day: Long): NudgeState = s.copy(
        friendDays = (s.friendDays.filterValues { it >= day - 2 } + (uid to day)),
        friendDay = day,
        friendCount = if (s.friendDay == day) s.friendCount + 1 else 1
    )

    // ───────────── Trimis, deschis, ignorat ─────────────

    fun recent(s: NudgeState, channel: String): List<Nudge.Sent> =
        s.sent.filter { it.channel == channel }.map { Nudge.Sent(it.id, it.at) }

    /** Reține trimiterea (anti-repetare), iar pentru „coach”: plafonul, fereastra și mesajul în așteptare. */
    fun onPosted(s: NudgeState, r: Rendered, channel: String, now: Long, day: Long, windowKey: String? = null): NudgeState {
        val sent = (s.sent + SentRec(r.id, r.context.name, channel, now))
            .groupBy { it.channel }.values.flatMap { it.takeLast(SENT_PER_CHANNEL) }
            .sortedBy { it.at }
        var next = s.copy(sent = sent)
        if (r.context.coach) {
            // Mesajul anterior încă nedeschis contează ca ignorat.
            next = resolvePending(next, now, force = true)
            next = next.copy(
                coachDay = day,
                coachCount = (if (s.coachDay == day) s.coachCount else 0) + 1,
                lastCoachAt = now,
                pending = PendingRec(r.id, r.context.name, now),
                windows = if (windowKey != null) next.windows + (windowKey to day) else next.windows
            )
            if (r.context == NudgeContext.Comeback && r.id == "C7") next = next.copy(silentUntilOpen = true)
        }
        return next
    }

    /** Aplicația s-a deschis: mesajul în așteptare a mers, revenirea se resetează, tăcerea după „promit” se ridică. */
    fun onOpened(s: NudgeState, now: Long): NudgeState {
        var next = s
        val p = s.pending
        if (p != null && now - p.at <= PENDING_TTL_MS) {
            val h = (s.halvings[p.ctx] ?: 0)
            next = next.copy(
                pending = null,
                ignores = next.ignores + (p.ctx to 0),
                halvings = next.halvings + (p.ctx to (h - 1).coerceAtLeast(0))
            )
        }
        return next.copy(lastOpen = now, comebackSteps = emptyList(), silentUntilOpen = false)
    }

    /** Mesajul a fost atins (ecoul de pe Panou): contextul nu mai e „ignorat”. */
    fun onTapped(s: NudgeState, ctx: String): NudgeState {
        val p = s.pending
        val mine = p != null && p.ctx == ctx
        val h = s.halvings[ctx] ?: 0
        return s.copy(
            pending = if (mine) null else p,
            ignores = s.ignores + (ctx to 0),
            halvings = if (mine) s.halvings + (ctx to (h - 1).coerceAtLeast(0)) else s.halvings
        )
    }

    /** Swipe pe un mesaj: ignorat. */
    fun onDismissed(s: NudgeState, ctx: String, id: String): NudgeState {
        val p = s.pending
        val cleared = if (p != null && p.id == id) s.copy(pending = null) else s
        val c = NudgeContext.entries.firstOrNull { it.name == ctx } ?: return cleared
        return if (c.coach) ignore(cleared, c.name, id) else cleared
    }

    /** Mesajul în așteptare nedeschis în 3 h (sau înlocuit de altul) contează ca ignorat. */
    fun resolvePending(s: NudgeState, now: Long, force: Boolean = false): NudgeState {
        val p = s.pending ?: return s
        if (!force && now - p.at < PENDING_TTL_MS) return s
        return ignore(s.copy(pending = null), p.ctx, p.id)
    }

    private fun ignore(s0: NudgeState, ctx: String, id: String): NudgeState {
        val s = permSubject(id)?.let { onPermissionIgnored(s0, it) } ?: s0
        val n = (s.ignores[ctx] ?: 0) + 1
        return if (n >= IGNORES_TO_HALVE) {
            val h = ((s.halvings[ctx] ?: 0) + 1).coerceAtMost(MAX_HALVINGS)
            s.copy(ignores = s.ignores + (ctx to 0), halvings = s.halvings + (ctx to h))
        } else s.copy(ignores = s.ignores + (ctx to n))
    }

    /** Subiectul unui mesaj de permisiune, după id-ul variantei din bancă. */
    fun permSubject(id: String): String? = when (id) {
        "15.1" -> "contract"
        "15.2" -> "bg_location"
        "15.7" -> "contacts"
        else -> null
    }

    fun onComeback(s: NudgeState, step: Int): NudgeState = s.copy(comebackSteps = (s.comebackSteps + step).distinct())

    fun onPermission(s: NudgeState, subject: String, now: Long): NudgeState {
        val r = s.perms[subject] ?: PermRec()
        return s.copy(perms = s.perms + (subject to r.copy(lastAt = now)))
    }

    /** Un mesaj de permisiune ignorat (swipe sau fără deschidere) — după 2, subiectul tace. */
    fun onPermissionIgnored(s: NudgeState, subject: String): NudgeState {
        val r = s.perms[subject] ?: PermRec()
        return s.copy(perms = s.perms + (subject to r.copy(ignores = r.ignores + 1)))
    }
}

/**
 * Seriile de instrucție și de mers, după aceeași regulă ca seria de mese (NutritionPrefs.streak): zilele consecutive
 * cu ceva notat; azi contează dacă e bifat, altfel seria stă pe ieri și expiră la miezul nopții. Astfel „seria în
 * pericol” spune adevărul.
 */
object Streaks {
    /** Seria din zilele bifate (epochDay). `brokeAt` = seria care s-a oprit ieri (ieri gol, alaltăieri bifat). */
    fun fromDays(kind: StreakKind, today: Long, days: Set<Long>): Streak {
        val doneToday = today in days
        var d = if (doneToday) today else today - 1
        var n = 0
        while (d in days) { n++; d-- }
        var broke = 0
        if (!doneToday && (today - 1) !in days) {
            var b = today - 2
            while (b in days) { broke++; b-- }
        }
        return Streak(kind, n, doneToday, broke)
    }

    /** Aceeași socoteală, cu o întrebare pe zi (cel mult `max` zile înapoi) — pentru jurnalele din Room. */
    suspend fun walk(kind: StreakKind, today: Long, max: Int = 400, has: suspend (Long) -> Boolean): Streak {
        val doneToday = has(today)
        var d = if (doneToday) today else today - 1
        var n = 0
        while (n < max && has(d)) { n++; d-- }
        var broke = 0
        if (!doneToday && n == 0) {
            var b = today - 2
            while (broke < max && has(b)) { broke++; b-- }
        }
        return Streak(kind, n, doneToday, broke)
    }
}
