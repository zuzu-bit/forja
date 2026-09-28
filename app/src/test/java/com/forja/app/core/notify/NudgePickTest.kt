package com.forja.app.core.notify

import com.forja.app.core.notify.NudgeFixtures.clock
import com.forja.app.core.notify.NudgeFixtures.rich
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Alegerea și randarea (notifications-design.md §H, testele 1–3): determinism, anti-repetare, placeholdere lipsă. */
class NudgePickTest {

    private val pool = (1..5).map { Template("T$it", NudgeContext.Morning, "Titlul $it.", "Textul $it.", NudgePose.Happy) }
    private val day = 739_000L

    @Test
    fun sameSeedSameVariantNextSeedAnother() {
        val d = clock(9)
        val a = Nudge.pick(pool, d, emptyList(), day)
        val b = Nudge.pick(pool, d, emptyList(), day)
        val c = Nudge.pick(pool, d, emptyList(), day + 1)
        assertNotNull(a)
        assertEquals(a!!.id, b!!.id)
        assertNotEquals(a.id, c!!.id)
    }

    @Test
    fun ofDayIsStableAndWraps() {
        assertEquals("b", Nudge.ofDay(listOf("a", "b", "c"), 1))
        assertEquals("b", Nudge.ofDay(listOf("a", "b", "c"), 4))
        assertEquals("a", Nudge.ofDay(listOf("a", "b", "c"), -3))
        assertNull(Nudge.ofDay(emptyList<String>(), 3))
    }

    @Test
    fun recentVariantIsNotPickedAgainWithinSevenDays() {
        val d = clock(9)
        val first = Nudge.pick(pool, d, emptyList(), day)!!
        val recent = listOf(Nudge.Sent(first.id, d.now - 24 * 3600_000L))
        repeat(10) { k ->
            val next = Nudge.pick(pool, d, recent, day + k)!!
            assertNotEquals(first.id, next.id)
        }
        // Mai vechi de 7 zile: poate reveni.
        val old = listOf(Nudge.Sent(first.id, d.now - 8 * 24 * 3600_000L))
        assertEquals(first.id, Nudge.pick(pool, d, old, day)!!.id)
    }

    @Test
    fun exhaustedPoolFallsBackToTheOldestUseNotSilence() {
        val d = clock(9)
        val recent = pool.mapIndexed { i, t -> Nudge.Sent(t.id, d.now - (i + 1) * 3600_000L) }
        val r = Nudge.pick(pool, d, recent, day)
        assertNotNull(r)
        assertEquals("T5", r!!.id)
    }

    @Test
    fun missingOrZeroPlaceholderSkipsTheVariant() {
        val withKm = Template("K", NudgeContext.Midday, "{km} km azi.", "Bine.", NudgePose.Happy)
        val reserve = Template("R", NudgeContext.Midday, "Jumătatea zilei.", "O apă.", NudgePose.Thinking, reserve = true)
        val zero = clock(13).copy(kmToday = 0.0)
        assertEquals("R", Nudge.pick(listOf(withKm, reserve), zero, emptyList(), day)!!.id)
        val some = clock(13).copy(kmToday = 2.5)
        assertEquals("2,5 km azi.", Nudge.pick(listOf(withKm, reserve), some, emptyList(), day)!!.title)
        assertNull(Nudge.render(withKm, zero))
    }

    @Test
    fun personalVariantsWinOverReserves() {
        val d = rich(9)
        repeat(20) { k ->
            val r = Nudge.pick(NudgeContext.Morning, d, emptyList(), day + k)!!
            assertFalse("rezervă aleasă deși existau date: ${r.id}", r.id.startsWith("2.r"))
        }
    }

    @Test
    fun noRenderedTextKeepsBracesOrZeroKilometres() {
        val datas = listOf(clock(9), clock(13), clock(20), rich(9), rich(13), rich(20), rich(22))
        for (d in datas) for (t in NudgeBank.all) {
            val r = Nudge.render(t, d) ?: continue
            for (text in listOf(r.title, r.body)) {
                assertFalse("${t.id}: $text", text.contains('{') || text.contains('}'))
                assertFalse("${t.id}: $text", Regex("""(^|\s)0 (km|zile|mese|min)""").containsMatchIn(text))
            }
        }
    }

    @Test
    fun everyDayWindowHasAMessageEvenWithoutData() {
        for (ctx in listOf(NudgeContext.Morning, NudgeContext.Midday, NudgeContext.Evening, NudgeContext.Bedtime)) {
            assertNotNull(ctx.name, Nudge.pick(ctx, clock(if (ctx == NudgeContext.Bedtime) 22 else 12), emptyList()))
        }
        for (h in listOf(3, 9, 14, 19, 23)) {
            assertNotNull("ora $h", Nudge.pick(NudgeContext.SyncOngoing, clock(h), emptyList()))
        }
        assertNotNull(Nudge.pick(NudgeContext.SleepReport, clock(8).copy(sleep = SleepView(0)), emptyList()))
        assertNotNull(Nudge.pick(NudgeContext.NewFriend, clock(12).copy(friend = FriendView("u", "", null, null)), emptyList()))
    }

    @Test
    fun pluralAndOrdinalsAreRenderedInTheText() {
        val d = clock(9).copy(streaks = listOf(Streak(StreakKind.Meals, 20, doneToday = false)))
        val t = NudgeBank.morning.first { it.id == "2.3" }
        assertEquals("20 de zile la rând. Linia ține.", Nudge.render(t, d)!!.title)
        val one = clock(15).copy(place = PlaceView("", 300, 1, 25))
        val mark = NudgeBank.newPlace.first { it.id == "11.3" }
        assertEquals("Al 25-lea loc pe hartă.", Nudge.render(mark, one)!!.title)
        val revisit = clock(15).copy(place = PlaceView("Sala", 60, 3, 40, revisit = true))
        val again = NudgeBank.newPlace.first { it.id == "11.4" }
        assertEquals("E a 3-a oară aici. A devenit un loc al tău, se vede.", Nudge.render(again, revisit)!!.body)
    }

    @Test
    fun voiceChangesOnlyTheLineThatChangesMeaning() {
        val risk = clock(20).copy(streaks = listOf(Streak(StreakKind.Meals, 9, doneToday = false)))
        val r4 = NudgeBank.streakRisk.first { it.id == "R4" }
        assertEquals("O singură masă notată o ține. Orice masă. Nu judec farfuria.", Nudge.render(r4, risk)!!.body)
        assertEquals("O masă. O poză. Seria rămâne.", Nudge.render(r4, risk.copy(voice = Voice.Sergent))!!.body)
    }

    @Test
    fun angryOnlyForTheWorkoutStreakWithTheSergeantVoice() {
        val angry = NudgeBank.all.filter { it.pose == NudgePose.Angry }
        assertEquals(listOf("5.2"), angry.map { it.id })
        val t = angry.single()
        val workout = clock(20).copy(streaks = listOf(Streak(StreakKind.Workout, 5, doneToday = false)))
        assertNull(Nudge.render(t, workout))
        assertNotNull(Nudge.render(t, workout.copy(voice = Voice.Sergent)))
        val meals = clock(20, 0).copy(voice = Voice.Sergent, streaks = listOf(Streak(StreakKind.Meals, 5, doneToday = false)))
        assertNull(Nudge.render(t, meals))
    }

    @Test
    fun privateWhenPersonalAndLocalOnlyForSleepFriendsAndKcal() {
        val kcal = Nudge.render(NudgeBank.midday.first { it.id == "3.1" }, rich(13))!!
        assertTrue(kcal.private)
        assertTrue(kcal.localOnly)
        val reserve = Nudge.render(NudgeBank.midday.first { it.id == "3.r1" }, clock(13))!!
        assertFalse(reserve.private)
        val sleep = Nudge.pick(NudgeContext.SleepReport, clock(8).copy(sleep = SleepView(0)), emptyList())!!
        assertTrue(sleep.private)
        assertTrue(sleep.localOnly)
    }

    @Test
    fun publicVersionHasNoPersonalData() {
        for (ctx in NudgeContext.entries) {
            val pub = Nudge.publicOf(ctx, rich(12)) ?: continue
            assertTrue(ctx.name, Nudge.keysOf(NudgeBank.all.first { it.id == pub.id }.title).isEmpty())
            assertFalse(ctx.name, pub.title.contains("Lana") || pub.body.contains("Lana"))
        }
    }

    @Test
    fun friendMessagesNeverUseAFriendWithoutName() {
        val d = clock(12).copy(friend = FriendView("u1", "", 300, null))
        assertNull(Nudge.pick(NudgeContext.FriendNear, d, emptyList()))
        val ok = clock(12).copy(friend = FriendView("u1", "Ana Maria", 260, null))
        assertEquals("Ana e la 300 m.", Nudge.pick(NudgeContext.FriendNear, ok, emptyList())!!.title)
    }
}
