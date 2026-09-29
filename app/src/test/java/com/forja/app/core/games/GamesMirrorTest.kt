package com.forja.app.core.games

import com.forja.app.core.data.GamesMirror
import com.forja.app.core.data.db.GamePlayEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

/** Pagina unui joc pe site (mirror C): nivelurile, stelele, jocurile recente, cât ai jucat în total și azi. */
class GamesMirrorTest {
    @Test fun docSummarisesProgressAndPlays() {
        val zone = ZoneId.of("Europe/Bucharest")
        val now = 1_790_000_000_000L
        val p = GameProgress(unlocked = 4, stars = mapOf(1 to 3, 2 to 2, 3 to 0), best = mapOf(1 to 900), endlessBest = 0)
        val plays = listOf(GamePlayEntity(id = 1, at = now - 3_600_000L, game = "zid", level = 3, outcome = "lost", score = 400, durationS = 120),
            GamePlayEntity(id = 2, at = now - 3 * 86_400_000L, game = "zid", level = 2, outcome = "won", stars = 2, score = 800, durationS = 300))
        val d = GamesMirror.doc(GameId.Zid, p, plays, now, zone)
        assertEquals(15, d["levels"]); assertEquals(4, d["unlocked"]); assertEquals(2, d["cleared"]); assertEquals(5, d["starsTotal"])
        assertEquals(mapOf("1" to 3, "2" to 2), d["stars"])
        assertEquals(420L, d["playedS"]); assertEquals(120L, d["playedToday"]); assertEquals(now - 3_600_000L, d["lastAt"])
        @Suppress("UNCHECKED_CAST") assertEquals(3, ((d["plays"] as List<Map<String, Any?>>)[0])["level"])
        assertNull(GamesMirror.doc(GameId.Asalt, GameProgress(), emptyList(), now, zone)["endlessBest"])
    }
}
