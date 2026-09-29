package com.forja.app.core.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Documentele zilei ale oglinzii minții: exact formele citite de server/site/sec-mind.mjs, fără text privat. */
class MindDocsTest {
    private val now = 1_790_000_000_000L
    private val zone = ZoneId.of("Europe/Bucharest")

    @Test fun hitsAndRulesJsonRoundTripAndRefuseBadNames() {
        val j = MindDocs.addHit(MindDocs.addHit("{}", "com.instagram.android"), "com.instagram.android")
        assertEquals(mapOf("com.instagram.android" to 2), MindDocs.parseHits(j))
        assertEquals(j, MindDocs.addHit(j, "nu e pachet"))
        assertEquals(listOf("a.b", "c.d"), MindDocs.parseRules(MindDocs.rulesJson(listOf("a.b", "c.d", "a.b", "x y"))))
    }

    @Test fun forestAndPackHistoriesKeep40DaysAndOnlyKnownPacks() {
        val today = LocalDate.of(2026, 9, 28).toEpochDay()
        var f = MindDocs.forestEncode(mapOf(today - 50 to (9 to 9), today to (3 to 1)), today)
        assertEquals(mapOf(today to (3 to 1)), MindDocs.forestDecode(f))
        var h = MindDocs.addPackHit(null, today, "03")
        h = MindDocs.addPackHit(h, today, "03")
        h = MindDocs.addPackHit(h, today, "text prins de pe ecran")
        assertEquals(mapOf("03" to 2, "own" to 1), MindDocs.hitsDecode(h)[today])
        assertFalse("never the text", h.contains("ecran"))
        h = MindDocs.addPackHit(h, today + 45, "01")
        assertEquals(setOf(today + 45), MindDocs.hitsDecode(h).keys)
    }

    @Test fun focusDocSumsMinutesHitsAndLabels() {
        val s1 = MindDocs.Session(now - 3_600_000, now - 600_000, "focus", 60, listOf("com.instagram.android"), true, false, mapOf("com.instagram.android" to 7), "timer")
        val s2 = MindDocs.Session(now - 500_000, null, "detox", 30, emptyList(), false, false, mapOf("com.x" to 1), null)
        val d = MindDocs.focusDoc("2026-09-28", listOf(s2, s1), 3 to 1, mapOf("com.instagram.android" to "Instagram"), now)
        assertEquals(50, d["focusMin"]); assertEquals(8, d["detoxMin"])
        assertEquals(3, d["grown"]); assertEquals(1, d["withered"])
        assertEquals(mapOf("com.instagram.android" to 7, "com.x" to 1), d["hits"])
        assertEquals(mapOf("com.instagram.android" to "Instagram", "com.x" to "com.x"), d["labels"])
        @Suppress("UNCHECKED_CAST") val sessions = d["sessions"] as List<Map<String, Any?>>
        assertEquals(listOf("focus", "detox"), sessions.map { it["kind"] })
    }

    @Test fun focusDocCapsAnOrphanedOpenSession() {
        // Deschisă acum 10 h, planificată 60 min, serviciul oprit de Android după 30 min: nu crește peste plan.
        val orphan = MindDocs.Session(now - 36_000_000, null, "focus", 60, emptyList(), false, false, emptyMap(), null)
        var d = MindDocs.focusDoc("2026-09-28", listOf(orphan), null, emptyMap(), now, mapOf("focus" to now - 34_200_000))
        assertEquals(60, d["focusMin"])
        // Încă în post (atins acum 10 s), prelungită peste plan: numără până acum.
        val live = MindDocs.Session(now - 7_200_000, null, "detox", 60, emptyList(), false, false, emptyMap(), null)
        d = MindDocs.focusDoc("2026-09-28", listOf(live), null, emptyMap(), now, mapOf("detox" to now - 10_000))
        assertEquals(120, d["detoxMin"])
    }

    @Test fun detoxDocCountsOnlyPacks() {
        val d = MindDocs.detoxDoc("2026-09-28", mapOf("02" to 2, "own" to 1, "zz" to 9), true, true, now - 86_400_000, 1, now)
        assertEquals(3, d["interceptions"]); assertEquals(mapOf("02" to 2, "own" to 1), d["byPack"])
        assertEquals(setOf("date", "updatedAt", "interceptions", "byPack", "guardOn", "addictionOn", "streakStart", "slips"), d.keys)
    }

    @Test fun wordsDocListsWordsAndWholePacksOnly() {
        val d = MindDocs.wordsDoc("pariuri\ncasino\nPariuri\n  \nruletă", "Pentru mine.", mapOf("02" to listOf("pariuri", "casino"), "03" to listOf("porn", "xxx")), now)
        assertEquals(true, d["onSite"]); assertEquals(listOf("pariuri", "casino", "ruletă"), d["words"])
        assertEquals(listOf("02"), d["packs"]); assertEquals("Pentru mine.", d["letter"])
    }

    @Test fun nudgesDocDropsPrivateText() {
        val d = MindDocs.nudgesDoc("2026-09-28", listOf(
            MindDocs.NudgeItem(now, "S1", "SleepReport", "sleep", "Ai dormit 6 h", "profund 1 h", true, "opened"),
            MindDocs.NudgeItem(now - 1, "F1", "FocusDone", "coach", "Postul s-a încheiat.", "50 min", false, "tapped"),
        ), now)
        @Suppress("UNCHECKED_CAST") val items = d["items"] as List<Map<String, Any?>>
        assertEquals(listOf("FocusDone", "SleepReport"), items.map { it["ctx"] })
        assertNull(items[1]["title"]); assertNull(items[1]["body"])
        assertFalse(d.toString().contains("dormit"))
        assertEquals("Postul s-a încheiat.", items[0]["title"])
    }

    @Test fun listensDocCountsPlaysSkipsAndForja() {
        val d = MindDocs.listensDoc("2026-09-28", listOf(
            MindDocs.Listen(now, "A", "X", "Spotify", 180, false, false, "music"),
            MindDocs.Listen(now + 1, "B", "Y", null, 12, true, true, null),
            MindDocs.Listen(now + 2, "C", "Z", "Spotify", 240, true, false, "music"),
            MindDocs.Listen(now + 3, " ", "Z", null, 240, true, false, null),
        ), now)
        assertEquals(listOf(7, 2, 1, 1), listOf(d["minutes"], d["count"], d["skips"], d["forja"]))
        @Suppress("UNCHECKED_CAST") val items = d["items"] as List<Map<String, Any?>>
        assertEquals(listOf("user", "forja", "forja"), items.map { it["src"] }); assertEquals("skip", items[1]["event"])
    }

    @Test fun signatureIgnoresUpdatedAtAndRecentDaysStartToday() {
        val a = MindDocs.breathDoc("2026-09-28", listOf(MindDocs.Breath(now, now + 60_000, "4-4-4-4", 3, 60, true)), now)
        val b = MindDocs.breathDoc("2026-09-28", listOf(MindDocs.Breath(now, now + 60_000, "4-4-4-4", 3, 60, true)), now + 5)
        assertEquals(MindDocs.signature(a), MindDocs.signature(b))
        val days = MindDocs.recentDays(now, zone)
        assertEquals(MindDocs.DAYS_BACK, days.size); assertEquals(MindDocs.dayKey(now, zone), days.first())
        assertTrue(days.first() > days.last())
    }
}
