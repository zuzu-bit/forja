package com.forja.app.core.music

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Jurnalul trimis la /v1/diag/music: exact câmpurile din DESIGN-4.4 §3.5, cel mult 50, fără titluri. */
class DiagCodecTest {
    private val e = AttemptEvent(
        at = 1_700_000_000_000L, want = "mymusic", rung = "S_TOP", pkg = SPOTIFY, ver = "9.0.62",
        kind = MediaKind.MUSIC, result = DiagResult.WRONG_TRACK, ms = 2_400, err = "via:search"
    )

    @Test fun bodyHasExactlyTheContractFields() {
        val o = Json.parseToJsonElement(DiagCodec.body("samsung SM-S911B · sdk 35", "4.4 (66)", listOf(e))).jsonObject
        assertEquals(setOf("device", "app", "events"), o.keys)
        val ev = (o["events"] as JsonArray)[0].jsonObject
        assertEquals(setOf("at", "want", "rung", "pkg", "ver", "kind", "result", "ms", "err"), ev.keys)
        assertEquals("music", ev["kind"]!!.jsonPrimitive.content)
        assertEquals("wrong_track", ev["result"]!!.jsonPrimitive.content)
        assertEquals("2400", ev["ms"]!!.jsonPrimitive.content)
    }

    @Test fun nullsStayNullAndBatchesAreCapped() {
        val bare = e.copy(pkg = null, ver = null, kind = null, err = null)
        val o = Json.parseToJsonElement(DiagCodec.body("d", "a", List(80) { bare })).jsonObject
        val events = o["events"] as JsonArray
        assertEquals(DiagCodec.MAX_EVENTS, events.size)
        val ev = events[0].jsonObject
        assertTrue(ev["pkg"] is JsonNull)
        assertTrue(ev["kind"] is JsonNull)
        assertTrue(ev["err"] is JsonNull)
    }

    @Test fun roundTripForTheLocalJournal() {
        assertEquals(e, DiagCodec.parse(DiagCodec.line(e)))
        assertEquals(null, DiagCodec.parse("{nu e json"))
    }

    @Test fun everyWireValueIsInTheServerWhitelist() {
        val wants = setOf("resume", "mymusic", "top", "workout", "probe")
        assertTrue(listOf(Want.Resume("x"), Want.MyMusic, Want.Top, Want.Workout(null), Want.Probe(Rung.S_PLAY, SPOTIFY)).all { it.wire in wants })
        val results = setOf("ok", "refused", "timeout", "wrong_kind", "wrong_track", "error", "skipped", "needs_tap")
        assertEquals(results, DiagResult.entries.map { it.wire }.toSet())
        assertEquals(setOf("music", "spoken", "video", "unknown"), MediaKind.entries.map { it.wire }.toSet())
        assertFalse(DiagCodec.line(e).contains("Marș"))
    }
}
