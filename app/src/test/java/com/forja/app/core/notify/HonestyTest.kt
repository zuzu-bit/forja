package com.forja.app.core.notify

import com.forja.app.core.notify.NudgeFixtures.clock
import com.forja.app.core.notify.NudgeFixtures.rich
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariantele de onestitate (notifications-design.md §E.9 + blocantele B1, B5 și corecturile 4, 7, 9):
 * tonul nu ascunde niciodată ce face aplicația, nu îngroapă divulgarea încărcării și nu manipulează prin vină.
 */
class HonestyTest {

    /** Etichetele din CollectionSettings.label (aceleași ca în contract). */
    private val label: (String) -> String = { key ->
        when (key) {
            "location" -> "locație"
            "app_usage" -> "aplicații"
            "audio" -> "microfon"
            "photos" -> "fotografii"
            else -> "fișiere alese"
        }
    }

    // ───────────── Notificarea permanentă ─────────────

    @Test
    fun collapsedFormAlwaysSaysWhatIsUploadedAndHowToStop() {
        val line = Nudge.pick(NudgeContext.SyncOngoing, rich(14), emptyList())
        val t = SyncCopy.compose(setOf("photos", "location", "app_usage"), line, label)
        assertEquals("Urcă în cont: locație, aplicații, fotografii", t.collapsed)
        assertEquals("Sincronizare activă", t.subText)
        assertEquals("Oprește", t.action)
        assertTrue(t.big.endsWith("Urcă în contul tău FORJA: locație, aplicații, fotografii. Oprești de aici. Revoci din Profil → Contract."))
        assertEquals(line!!.title, t.title)
        assertTrue(t.big.startsWith(line.body))
    }

    @Test
    fun everyRotatingLineKeepsTheHonestLine() {
        val configured = setOf("location", "app_usage", "photos")
        for (h in 0..23) for (d in listOf(clock(h), rich(h))) {
            val line = Nudge.pick(NudgeContext.SyncOngoing, d, emptyList())
            val t = SyncCopy.compose(configured, line, label)
            assertTrue("ora $h", t.collapsed.startsWith("Urcă în cont: "))
            assertTrue("ora $h", t.big.contains("Oprești de aici. Revoci din Profil → Contract."))
            assertEquals("Oprește", t.action)
        }
    }

    @Test
    fun finderOnlyHeaderDoesNotClaimSync() {
        // Serviciul rulează doar pentru găsire (permisiuni lipsă, notificări oprite): antetul nu spune „Sincronizare activă”.
        val t = SyncCopy.compose(setOf("finder"), null, label)
        assertEquals("Doar găsirea telefonului", t.subText)
        assertEquals(SyncCopy.SUB_FINDER, SyncCopy.subText(setOf("finder")))
        assertEquals("Sincronizare activă", SyncCopy.subText(setOf("location", "finder")))
        assertEquals("Oprește", t.action)
    }

    @Test
    fun categoriesComeOnlyFromWhatRunsNoDefaultList() {
        val t = SyncCopy.compose(setOf("location"), null, label)
        assertEquals("Urcă în cont: locație", t.collapsed)
        assertFalse(t.big.contains("fotografii"))
        val files = SyncCopy.compose(setOf("files", "photos"), null, label)
        assertEquals("Urcă în cont: fotografii, fișiere alese", files.collapsed)
    }

    @Test
    fun microphoneIsNeverHiddenBehindAJoke() {
        val line = Nudge.pick(NudgeContext.SyncOngoing, rich(20), emptyList())!!
        val t = SyncCopy.compose(setOf("location", "audio"), line, label)
        assertEquals(SyncCopy.MIC_TITLE, t.title)
        assertEquals("Microfon + sincronizare", t.subText)
        assertTrue(t.collapsed.contains("microfon"))
        assertEquals(SyncCopy.MIC_TITLE, t.publicTitle)
        // Titlul cald nu se pierde: deschide forma extinsă, înaintea replicii (S-d: „Loc nou: X. Ai stat acolo 2 h.”).
        assertTrue(t.big.startsWith(line.title + " " + line.body))
    }

    @Test
    fun microphoneKeepsThePlaceThatTheLineTalksAbout() {
        val d = NudgeFixtures.rich(15).copy(newPlaceToday = PlaceView("Parcul Titan", 120, 1, 25))
        val line = Nudge.pick(NudgeBank.sync.filter { it.id == "S-d" }, d, emptyList(), 0L)!!
        val t = SyncCopy.compose(setOf("location", "audio"), line, label)
        assertTrue(t.big, t.big.startsWith("Loc nou: Parcul Titan. Ai stat acolo 2 h."))
    }

    @Test
    fun syncCountsCarryTheTimeTheyWereCounted() {
        // Replica permanentă stă ore întregi: orice număr al zilei de azi spune „la 14:05” (corectura 4).
        val todayCounts = setOf("km", "bilant", "copaci", "mese_azi", "focus_min", "ramas", "seturi")
        for (t in NudgeBank.sync) {
            if (t.placeholders.any { it in todayCounts }) assertTrue("${t.id} fără {ora}", "ora" in t.placeholders)
        }
    }

    @Test
    fun lockScreenVersionKeepsTheDisclosureButNoPersonalData() {
        val line = Nudge.pick(NudgeContext.SyncOngoing, rich(20), emptyList())!!
        val t = SyncCopy.compose(setOf("location", "app_usage", "photos"), line, label)
        assertEquals("Urcă în cont: locație, aplicații, fotografii", t.publicText)
        assertEquals(SyncCopy.PUBLIC_TITLE, t.publicTitle)
        assertFalse(t.publicTitle.contains("Lana"))
    }

    @Test
    fun permanentLineHasNoStaleFactsNoFriendsNoWatching() {
        // Corectura 4: se reînnoiește cel mult o dată la 3 h — nimic „acum”, nicio distanță până la un prieten.
        // Corectura 9: fără „veghează” pe serviciul care urcă locația noaptea.
        val stale = Regex("""\bacum\b|până acum|ultima oră|în \d+ de minute""", RegexOption.IGNORE_CASE)
        for (t in NudgeBank.sync) {
            val text = t.title + " " + t.body
            assertFalse(t.id, stale.containsMatchIn(text))
            assertFalse(t.id, text.contains("veghe", ignoreCase = true))
            assertTrue(t.id, t.placeholders.none { it in setOf("prieten", "distanta", "stare_prieten") })
        }
    }

    @Test
    fun stoppingIsNotRevoking() {
        // „Oprești … din Profil → Contract” ar confunda oprirea cu revocarea (care șterge).
        val h = SyncCopy.honest("locație")
        assertTrue(h.contains("Oprești de aici."))
        assertTrue(h.contains("Revoci din Profil → Contract."))
    }

    // ───────────── Somnul: mereu „estimat” ─────────────

    @Test
    fun everySleepTextSaysEstimated() {
        val sleepKeys = setOf("somn_h", "profund", "acoperire", "evenimente")
        val sleepy = NudgeBank.all.filter { it.context == NudgeContext.SleepReport || it.placeholders.any { k -> k in sleepKeys } }
        assertTrue(sleepy.size >= 8)
        for (t in sleepy) {
            val bodies = listOf(t.body) + t.voices.values
            for (b in bodies) assertTrue("${t.id}: „$b”", Regex("estim(at|are)", RegexOption.IGNORE_CASE).containsMatchIn(b))
        }
    }

    // ───────────── Mesele: fără rușine ─────────────

    @Test
    fun mealTextsNeverShameOrWeigh() {
        val mealKeys = setOf("kcal_ramase", "mese_azi", "serie_mese", "mese_gasite")
        val meals = NudgeBank.all.filter {
            it.context == NudgeContext.MealLog || it.placeholders.any { k -> k in mealKeys } || it.id.startsWith("13.") ||
                it.id in setOf("3.2", "R4", "R5")
        }
        assertTrue(meals.size >= 8)
        val forbidden = Regex("""\bprea\b|\bpeste\b|ai sărit|ai depășit|compens|greutat|cântar|kilogram|vinovat|cheat""", RegexOption.IGNORE_CASE)
        for (t in meals) for (text in listOf(t.title, t.body) + t.voices.values) {
            assertFalse("${t.id}: „$text”", forbidden.containsMatchIn(text))
        }
    }

    @Test
    fun kcalOnlyWhenPositiveAndAtLeastOneMeal() {
        val t = NudgeBank.midday.first { it.id == "3.1" }
        assertEquals(null, Nudge.render(t, clock(13).copy(mealsToday = 0, kcalLeft = 900)))
        assertEquals(null, Nudge.render(t, clock(13).copy(mealsToday = 2, kcalLeft = 0)))
        assertEquals(null, Nudge.render(t, clock(13).copy(mealsToday = 2, kcalLeft = null)))
        assertTrue(Nudge.render(t, clock(13).copy(mealsToday = 2, kcalLeft = 640))!!.body.contains("640 kcal"))
    }

    // ───────────── Prieteni și contract ─────────────

    @Test
    fun newFriendKeepsTheMapDisclosure() {
        for (t in NudgeBank.newFriend) assertTrue(t.id, t.body.contains("Sunteți prieteni și vă vedeți pe hartă."))
    }

    @Test
    fun contactsLineDoesNotClaimNumbersStayOnThePhone() {
        // Numerele pleacă (amprentate pe server); doar numele rămân pe telefon.
        val t = NudgeBank.permission.first { it.id == "15.7" }
        assertFalse(t.body.contains("numerele", ignoreCase = true))
        assertTrue(t.body.contains("Numele rămân pe telefon."))
    }

    @Test
    fun theLastComebackIsReallyTheLast() {
        val c7 = NudgeBank.comeback.first { it.id == "C7" }
        var s = NudgeState()
        val r = Nudge.render(c7, clock(13).copy(comebackStep = 14))!!
        s = NudgeRules.onPosted(s, r, Channels.COACH, NudgeFixtures.at(13), 20_000L, "comeback")
        assertTrue(s.silentUntilOpen)
        assertEquals(0, NudgeRules.comebackStep(60, s.comebackSteps + 14))
    }

    // ───────────── Id-uri distincte (corectura 7) ─────────────

    @Test
    fun notificationIdsNeverCollide() {
        val fixed = listOf(
            NotifIds.SLEEP_FGS, NotifIds.GO_FGS, NotifIds.FOCUS_FGS, NotifIds.ALARM, NotifIds.SLEEP_REPORT, NotifIds.SYNC_FGS,
            NotifIds.SYNC_PAUSED, NotifIds.MEALS_FOUND, NotifIds.BEDTIME, NotifIds.COACH, NotifIds.FOCUS_DONE, NotifIds.MILESTONE,
            NotifIds.LAPTOP, NotifIds.LOST_PHONE, NotifIds.INVENTORY_PROGRESS, NotifIds.INVENTORY_RESULT
        )
        assertEquals(fixed.size, fixed.toSet().size)
        assertEquals(35, NotifIds.SLEEP_REPORT)
        assertTrue(NotifIds.MEALS_FOUND != NotifIds.SLEEP_REPORT)
        val places = (0L..2_000L).map { NotifIds.place(it) }.toSet()
        val friends = listOf("a", "uid-1", "Zz9", "0").flatMap { listOf(NotifIds.newFriend(it), NotifIds.friendNear(it)) }
        assertTrue(places.all { it in 5000..5999 })
        assertTrue(friends.all { it in 6000..7999 })
        assertTrue(fixed.none { it in places || it in friends })
    }
}
