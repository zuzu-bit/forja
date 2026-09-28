package com.forja.app.core.notify

import com.forja.app.core.notify.NudgeFixtures.clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ritmul (notifications-design.md §E, testele 4–5 din §H): liniște, plafon, auto-reglare, revenire, praguri. */
class NudgeRulesTest {

    private val day = 20_740L
    private val hour = 3600_000L

    private fun rendered(ctx: NudgeContext, id: String = "x") = Rendered(id, ctx, "T.", "B.", NudgePose.Happy, false, false)

    // ───────────── Orele de liniște 22:00–08:00 ─────────────

    @Test
    fun quietHoursAreTenPmToEightAm() {
        assertFalse(NudgeRules.isQuiet(21, 59))
        assertTrue(NudgeRules.isQuiet(22, 0))
        assertTrue(NudgeRules.isQuiet(2, 30))
        assertTrue(NudgeRules.isQuiet(7, 59))
        assertFalse(NudgeRules.isQuiet(8, 0))
    }

    @Test
    fun inQuietHoursOnlyBedtimeMakesASound() {
        for (ctx in NudgeContext.entries) {
            val audible = NudgeRules.audibleNow(ctx, 23, 0)
            assertEquals(ctx.name, ctx == NudgeContext.Bedtime, audible)
            assertTrue(ctx.name, NudgeRules.audibleNow(ctx, 12, 0))
        }
        assertEquals(QuietPolicy.Pass, NudgeRules.quietPolicy(NudgeContext.Bedtime))
        for (ctx in NudgeContext.COACH) assertEquals(ctx.name, QuietPolicy.Drop, NudgeRules.quietPolicy(ctx))
        assertEquals(QuietPolicy.Drop, NudgeRules.quietPolicy(NudgeContext.FriendNear))
        assertEquals(QuietPolicy.Drop, NudgeRules.quietPolicy(NudgeContext.NewPlace))
    }

    @Test
    fun noCoachMessageInQuietHours() {
        for (h in listOf(22, 23, 0, 5, 7)) {
            assertNull("ora $h", NudgeRules.planCoach(NudgeState(), clock(h), day))
        }
        assertNotNull(NudgeRules.planCoach(NudgeState(), clock(9), day))
    }

    // ───────────── Ferestre, plafon, 90 de minute ─────────────

    @Test
    fun windowsOfTheDay() {
        assertEquals(Window.Morning, NudgeRules.window(8, 0))
        assertEquals(Window.Morning, NudgeRules.window(10, 29))
        assertNull(NudgeRules.window(10, 30))
        assertEquals(Window.Midday, NudgeRules.window(12, 0))
        assertEquals(Window.Evening, NudgeRules.window(19, 0))
        assertEquals(Window.Evening, NudgeRules.window(21, 44))
        assertNull(NudgeRules.window(21, 45))
        assertNull(NudgeRules.window(16, 0))
    }

    @Test
    fun atMostThreeCoachMessagesADayAndThreeHoursApart() {
        val now = NudgeFixtures.at(20)
        val three = NudgeState(coachDay = day, coachCount = 3, lastCoachAt = now - 5 * hour)
        assertTrue(NudgeRules.coachBlocked(three, now, day))
        val two = NudgeState(coachDay = day, coachCount = 2, lastCoachAt = now - 2 * hour)
        assertTrue(NudgeRules.coachBlocked(two, now, day))
        val ok = two.copy(lastCoachAt = now - 3 * hour)
        assertFalse(NudgeRules.coachBlocked(ok, now, day))
        // Plafonul e pe zi: ieri nu contează.
        assertFalse(NudgeRules.coachBlocked(three.copy(coachDay = day - 1), now, day))

        var s = NudgeState()
        repeat(3) { i -> s = NudgeRules.onPosted(s, rendered(NudgeContext.Morning, "m$i"), Channels.COACH, now + i * 4 * hour, day) }
        assertEquals(3, s.coachCount)
        assertTrue(NudgeRules.coachBlocked(s, now + 12 * hour, day))
    }

    @Test
    fun nothingWithinNinetyMinutesOfOpeningTheApp() {
        val d = clock(9, 30)
        assertNull(NudgeRules.planCoach(NudgeState(lastOpen = d.now - 30 * 60_000L), d, day))
        assertNotNull(NudgeRules.planCoach(NudgeState(lastOpen = d.now - 91 * 60_000L), d, day))
    }

    @Test
    fun oneMessagePerWindowAndTheRightContext() {
        val d = clock(13)
        val plan = NudgeRules.planCoach(NudgeState(), d, day)!!
        assertEquals(NudgeContext.Midday, plan.context)
        val after = NudgeRules.onPosted(NudgeState(), rendered(NudgeContext.Midday), Channels.COACH, d.now - 4 * hour, day, plan.windowKey)
        assertNull(NudgeRules.planCoach(after, d, day))
    }

    @Test
    fun streakRiskTakesTheEveningWindow() {
        val d = clock(20).copy(streaks = listOf(Streak(StreakKind.Meals, 6, doneToday = false)))
        assertEquals(NudgeContext.StreakRisk, NudgeRules.planCoach(NudgeState(), d, day)!!.context)
        val safe = clock(20).copy(streaks = listOf(Streak(StreakKind.Meals, 6, doneToday = true)))
        assertEquals(NudgeContext.Evening, NudgeRules.planCoach(NudgeState(), safe, day)!!.context)
        val short = clock(20).copy(streaks = listOf(Streak(StreakKind.Meals, 2, doneToday = false)))
        assertEquals(NudgeContext.Evening, NudgeRules.planCoach(NudgeState(), short, day)!!.context)
    }

    @Test
    fun theMealStreakIsNeverRaisedAfterHalfPastNine() {
        val meals = listOf(Streak(StreakKind.Meals, 9, doneToday = false))
        assertEquals(NudgeContext.StreakRisk, NudgeRules.planCoach(NudgeState(), clock(21, 29).copy(streaks = meals), day)!!.context)
        // 21:30–21:45: fereastra de seară e încă deschisă, dar seria de mese tace — trece la bilanț.
        assertEquals(NudgeContext.Evening, NudgeRules.planCoach(NudgeState(), clock(21, 30).copy(streaks = meals), day)!!.context)
        assertEquals(NudgeContext.Evening, NudgeRules.planCoach(NudgeState(), clock(21, 40).copy(streaks = meals), day)!!.context)
        // Seria de instrucție poate vorbi până la capătul ferestrei.
        val workout = listOf(Streak(StreakKind.Workout, 9, doneToday = false))
        assertEquals(NudgeContext.StreakRisk, NudgeRules.planCoach(NudgeState(), clock(21, 40).copy(streaks = workout), day)!!.context)
    }

    // ───────────── Auto-reglarea ─────────────

    @Test
    fun threeIgnoredInARowHalveTheContext() {
        var s = NudgeState()
        repeat(3) { i -> s = NudgeRules.onDismissed(s, NudgeContext.Morning.name, "m$i") }
        assertEquals(1, s.halvings[NudgeContext.Morning.name])
        val even = day - Math.floorMod(day, 2L)
        assertTrue(NudgeRules.contextDue(s, NudgeContext.Morning, even))
        assertFalse(NudgeRules.contextDue(s, NudgeContext.Morning, even + 1))
        // Celelalte contexte nu sunt atinse.
        assertTrue(NudgeRules.contextDue(s, NudgeContext.Evening, even + 1))
        // Încă trei: o zi din patru.
        repeat(3) { i -> s = NudgeRules.onDismissed(s, NudgeContext.Morning.name, "n$i") }
        assertEquals(2, s.halvings[NudgeContext.Morning.name])
    }

    @Test
    fun unopenedMessageCountsAsIgnoredAfterThreeHours() {
        val t0 = NudgeFixtures.at(9)
        var s = NudgeRules.onPosted(NudgeState(), rendered(NudgeContext.Morning, "a"), Channels.COACH, t0, day, "morning")
        s = NudgeRules.resolvePending(s, t0 + 2 * hour)
        assertNotNull(s.pending)
        s = NudgeRules.resolvePending(s, t0 + 3 * hour + 1)
        assertNull(s.pending)
        assertEquals(1, s.ignores[NudgeContext.Morning.name])
    }

    @Test
    fun openingTheAppAfterAMessageRecoversTheContext() {
        val t0 = NudgeFixtures.at(9)
        var s = NudgeState(halvings = mapOf(NudgeContext.Morning.name to 2), ignores = mapOf(NudgeContext.Morning.name to 2))
        s = NudgeRules.onPosted(s, rendered(NudgeContext.Morning, "a"), Channels.COACH, t0, day, "morning")
        s = NudgeRules.onOpened(s, t0 + 40 * 60_000L)
        assertNull(s.pending)
        assertEquals(0, s.ignores[NudgeContext.Morning.name])
        assertEquals(1, s.halvings[NudgeContext.Morning.name])
    }

    @Test
    fun blockedChannelIsNotAnIgnore() {
        // Un canal oprit nu produce „pending”: nimic de rezolvat, nimic de penalizat.
        val s = NudgeRules.resolvePending(NudgeState(), NudgeFixtures.at(12))
        assertTrue(s.ignores.isEmpty())
    }

    // ───────────── Revenirea: ziua 2, 7, 14, apoi tăcere ─────────────

    @Test
    fun comebackStepsThenSilence() {
        assertEquals(0, NudgeRules.comebackStep(1, emptyList()))
        assertEquals(2, NudgeRules.comebackStep(2, emptyList()))
        assertEquals(0, NudgeRules.comebackStep(3, listOf(2)))
        assertEquals(7, NudgeRules.comebackStep(8, listOf(2)))
        assertEquals(14, NudgeRules.comebackStep(20, listOf(2, 7)))
        assertEquals(0, NudgeRules.comebackStep(40, listOf(2, 7, 14)))
    }

    @Test
    fun whileAwayOnlyComebackSpeaks() {
        val away = clock(9).copy(absentDays = 3, comebackStep = 0)
        assertNull(NudgeRules.planCoach(NudgeState(), away, day))
        val due = clock(13).copy(absentDays = 7, comebackStep = 7)
        assertEquals(NudgeContext.Comeback, NudgeRules.planCoach(NudgeState(), due, day)!!.context)
    }

    @Test
    fun lastMessagePromiseMeansSilenceUntilTheNextOpen() {
        val t = NudgeFixtures.at(13)
        var s = NudgeRules.onPosted(NudgeState(), rendered(NudgeContext.Comeback, "C7"), Channels.COACH, t, day, "comeback")
        assertTrue(s.silentUntilOpen)
        assertNull(NudgeRules.planCoach(s, clock(9).copy(now = t + 30 * hour), day + 1))
        s = NudgeRules.onOpened(s, t + 40 * hour)
        assertFalse(s.silentUntilOpen)
        assertTrue(s.comebackSteps.isEmpty())
    }

    // ───────────── Permisiuni: ≤ 1/săptămână, oprit după 2 refuzuri ─────────────

    @Test
    fun permissionAtMostWeeklyAndStopsAfterTwoIgnores() {
        val now = NudgeFixtures.at(13)
        var s = NudgeState()
        assertTrue(NudgeRules.permissionDue(s, "bg_location", now))
        s = NudgeRules.onPermission(s, "bg_location", now)
        assertFalse(NudgeRules.permissionDue(s, "bg_location", now + 6 * 24 * hour))
        assertTrue(NudgeRules.permissionDue(s, "bg_location", now + 7 * 24 * hour))
        s = NudgeRules.onPosted(s, rendered(NudgeContext.Permission, "15.2"), Channels.COACH, now, day)
        s = NudgeRules.onDismissed(s, NudgeContext.Permission.name, "15.2")
        s = NudgeRules.onDismissed(s, NudgeContext.Permission.name, "15.2")
        assertEquals(2, s.perms["bg_location"]!!.ignores)
        assertFalse(NudgeRules.permissionDue(s, "bg_location", now + 30 * 24 * hour))
    }

    // ───────────── Locul nou: o singură veste ─────────────

    @Test
    fun aPlaceAnnouncedOnItsOwnChannelIsNotRepeatedByTheSyncLine() {
        val dayOf: (Long) -> Long = { it / (24 * hour) }
        val now = day * 24 * hour + 15 * hour
        val place = Rendered("11.2", NudgeContext.NewPlace, "Un loc nou pe hartă.", "B.", NudgePose.Happy, false, false)
        assertFalse(NudgeRules.placeAnnounced(NudgeState(), day, dayOf))
        // Alt canal sau altă zi nu contează.
        var s = NudgeRules.onPosted(NudgeState(), rendered(NudgeContext.Morning), Channels.COACH, now, day)
        s = NudgeRules.onPosted(s, place, Channels.EXPLORE, now - 24 * hour, day - 1)
        assertFalse(NudgeRules.placeAnnounced(s, day, dayOf))
        s = NudgeRules.onPosted(s, place, Channels.EXPLORE, now, day)
        assertTrue(NudgeRules.placeAnnounced(s, day, dayOf))
        // SyncNotice.refresh scoate atunci locul din date: replicile „Loc nou: …” (S-d, S-d2) nu mai pot fi alese.
        for (h in 12..21) {
            val d = NudgeFixtures.rich(h).copy(newPlaceToday = null)
            val r = Nudge.pick(NudgeContext.SyncOngoing, d, emptyList())
            assertFalse("ora $h: ${r?.id}", r?.id in setOf("S-d", "S-d2"))
        }
    }

    // ───────────── Prieteni: ≤ 1/prieten/zi, ≤ 2/zi ─────────────

    @Test
    fun friendCaps() {
        var s = NudgeState()
        assertTrue(NudgeRules.friendAllowed(s, "a", day))
        s = NudgeRules.onFriend(s, "a", day)
        assertFalse(NudgeRules.friendAllowed(s, "a", day))
        assertTrue(NudgeRules.friendAllowed(s, "b", day))
        s = NudgeRules.onFriend(s, "b", day)
        assertFalse(NudgeRules.friendAllowed(s, "c", day))
        assertTrue(NudgeRules.friendAllowed(s, "a", day + 1))
    }

    // ───────────── Seriile: praguri și record, o singură dată ─────────────

    @Test
    fun streaksFollowTheMealRule() {
        val today = 100L
        assertEquals(Streak(StreakKind.Walk, 3, true, 0), Streaks.fromDays(StreakKind.Walk, today, setOf(98, 99, 100)))
        assertEquals(Streak(StreakKind.Walk, 2, false, 0), Streaks.fromDays(StreakKind.Walk, today, setOf(98, 99)))
        assertEquals(Streak(StreakKind.Walk, 0, false, 4), Streaks.fromDays(StreakKind.Walk, today, setOf(95, 96, 97, 98)))
        assertEquals(Streak(StreakKind.Walk, 0, false, 0), Streaks.fromDays(StreakKind.Walk, today, emptySet()))
    }

    @Test
    fun milestoneOncePerStreak() {
        var s = NudgeState()
        val seven = listOf(Streak(StreakKind.Meals, 7, doneToday = true))
        s = NudgeRules.trackRuns(s, seven, day)
        val ms = NudgeRules.milestoneDue(s, seven)
        assertEquals(7, ms!!.current)
        s = NudgeRules.onMilestone(s, ms, record = false)
        assertNull(NudgeRules.milestoneDue(s, seven))
        // Nu se anunță înainte de bifa de azi.
        assertNull(NudgeRules.milestoneDue(NudgeState(), listOf(Streak(StreakKind.Meals, 7, doneToday = false))))
        // Seria se rupe și reîncepe: pragul de 3 se anunță din nou.
        s = NudgeRules.trackRuns(s, listOf(Streak(StreakKind.Meals, 0, false)), day + 2)
        val three = listOf(Streak(StreakKind.Meals, 3, doneToday = true))
        s = NudgeRules.trackRuns(s, three, day + 5)
        assertEquals(3, NudgeRules.milestoneDue(s, three)!!.current)
    }

    @Test
    fun recordOncePerStreakNeverOnFirstSight() {
        var s = NudgeState()
        // Prima observare a unei serii de 12: nu e „record” (nu știm ce a fost înainte).
        s = NudgeRules.trackRuns(s, listOf(Streak(StreakKind.Walk, 12, true)), day)
        assertNull(NudgeRules.recordDue(s, listOf(Streak(StreakKind.Walk, 12, true))))
        // Se rupe; o serie nouă trece de 12.
        s = NudgeRules.trackRuns(s, listOf(Streak(StreakKind.Walk, 0, false)), day + 2)
        var d = day + 3
        var r: Pair<Streak, Int>? = null
        for (n in 1..13) {
            val st = listOf(Streak(StreakKind.Walk, n, true))
            s = NudgeRules.trackRuns(s, st, d)
            r = NudgeRules.recordDue(s, st)
            if (r != null) break
            d++
        }
        assertEquals(13, r!!.first.current)
        assertEquals(12, r.second)
        s = NudgeRules.onMilestone(s, r.first, record = true)
        val next = listOf(Streak(StreakKind.Walk, 15, true))
        s = NudgeRules.trackRuns(s, next, d + 2)
        assertNull(NudgeRules.recordDue(s, next))
    }

    @Test
    fun comebackNamesTheLastStreakNotTheLongest() {
        var s = NudgeState()
        assertEquals(0, NudgeRules.oldStreak(s))
        // O serie de mers de 30 de zile se încheie…
        s = NudgeRules.trackRuns(s, listOf(Streak(StreakKind.Walk, 30, true)), day)
        s = NudgeRules.trackRuns(s, listOf(Streak(StreakKind.Walk, 0, false)), day + 2)
        assertEquals(30, NudgeRules.oldStreak(s))
        // …apoi una de mese de 4 zile, mai târziu: „Ultima serie” e cea de 4, nu recordul de 30.
        s = NudgeRules.trackRuns(s, listOf(Streak(StreakKind.Meals, 4, true)), day + 10)
        assertEquals(30, NudgeRules.oldStreak(s))
        s = NudgeRules.trackRuns(s, listOf(Streak(StreakKind.Meals, 0, false)), day + 12)
        assertEquals(4, NudgeRules.oldStreak(s))
        // O serie nouă văzută peste una încă vie (fără tură în ziua ruperii) o închide pe cea veche.
        s = NudgeRules.trackRuns(s, listOf(Streak(StreakKind.Workout, 6, true)), day + 20)
        s = NudgeRules.trackRuns(s, listOf(Streak(StreakKind.Workout, 2, true)), day + 30)
        assertEquals(6, NudgeRules.oldStreak(s))
        // Cea mai lungă de dinainte rămâne pentru record.
        assertEquals(30, s.runs[StreakKind.Walk.name]!!.prevBest)
    }

    // ───────────── Culcarea: re-armarea nu anulează amintirea de diseară ─────────────

    @Test
    fun rearmingKeepsTonightsReminderUntilItFires() {
        val zone = NudgeFixtures.zone
        val bed = 23 * 60                                   // stingerea 23:00 → amintirea 22:30
        val tonight = NudgeFixtures.at(22, 30)
        // Înainte de 22:30: diseară.
        assertEquals(tonight, Bedtime.nextTrigger(NudgeFixtures.at(21, 0), bed, zone))
        // 22:33, amintirea armată n-a sunat încă (Doze): rămâne diseară (setWindow cu un moment trecut sună imediat).
        assertEquals(tonight, Bedtime.nextTrigger(NudgeFixtures.at(22, 33), bed, zone, armed = tonight, fired = 0L))
        // A sunat: mâine.
        val tomorrow = NudgeFixtures.at(22, 30, day = 15)
        assertEquals(tomorrow, Bedtime.nextTrigger(NudgeFixtures.at(22, 33), bed, zone, armed = tonight, fired = tonight))
        // Prea târziu (după stingere + 15 min): mâine, chiar dacă n-a sunat.
        assertEquals(tomorrow, Bedtime.nextTrigger(NudgeFixtures.at(23, 20), bed, zone, armed = tonight, fired = 0L))
        // Neamânată niciodată (reminderul abia pornit la 22:33): nu sună pe loc, ci mâine.
        assertEquals(tomorrow, Bedtime.nextTrigger(NudgeFixtures.at(22, 33), bed, zone))
        // Ora s-a schimbat între timp: amintirea veche nu se păstrează.
        assertEquals(NudgeFixtures.at(23, 30), Bedtime.nextTrigger(NudgeFixtures.at(22, 33), bed + 60, zone, armed = tonight, fired = 0L))
    }

    @Test
    fun rearmingAcrossMidnightKeepsTheReminderOfTheNightBefore() {
        val zone = NudgeFixtures.zone
        val bed = 0 * 60 + 10                               // stingerea 00:10 → amintirea 23:40
        val armed = NudgeFixtures.at(23, 40)
        val now = NudgeFixtures.at(0, 5, day = 15)
        assertEquals(armed, Bedtime.nextTrigger(now, bed, zone, armed = armed, fired = 0L))
        assertEquals(NudgeFixtures.at(23, 40, day = 15), Bedtime.nextTrigger(now, bed, zone, armed = armed, fired = armed))
    }
}
