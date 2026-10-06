package com.forja.app.core.mirror

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** „Ecranul pe site” (5.1): traducerea comenzilor serverului în acțiuni, cu coordonatele pe ecranul real. Fără Android. */
class ScreenMirrorProtocolTest {
    private val W = 1080
    private val H = 2340

    private fun cmd(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject(build)
    private fun act(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = ScreenMirrorProtocol.action(cmd(build), W, H)

    @Test fun fractionsLandOnTheRealScreen() {
        val a = act { put("kind", "tap"); put("x", 0.5); put("y", 0.25); put("unit", "frac") } as MirrorAction.Tap
        assertEquals(540, a.x)
        assertEquals(585, a.y)
    }

    @Test fun pixelsArePassedThroughLikeAdb() {
        val a = act { put("kind", "tap"); put("x", 540.0); put("y", 1200.0); put("unit", "px") } as MirrorAction.Tap
        assertEquals(540, a.x)
        assertEquals(1200, a.y)
    }

    @Test fun coordinatesAreClampedInsideTheScreen() {
        val a = act { put("kind", "tap"); put("x", 1.0); put("y", 1.0); put("unit", "frac") } as MirrorAction.Tap
        assertEquals(W - 1, a.x)
        assertEquals(H - 1, a.y)
        val b = act { put("kind", "tap"); put("x", 99999.0); put("y", 0.0); put("unit", "px") } as MirrorAction.Tap
        assertEquals(W - 1, b.x)
        assertEquals(0, b.y)
    }

    @Test fun swipeKeepsItsDurationWithinBounds() {
        val a = act { put("kind", "swipe"); put("x1", 0.5); put("y1", 0.8); put("x2", 0.5); put("y2", 0.2); put("ms", 600); put("unit", "frac") } as MirrorAction.Swipe
        assertEquals(540, a.x1); assertEquals(1872, a.y1); assertEquals(540, a.x2); assertEquals(468, a.y2); assertEquals(600L, a.ms)
        val slow = act { put("kind", "swipe"); put("x1", 0.0); put("y1", 0.0); put("x2", 1.0); put("y2", 1.0); put("ms", 99999); put("unit", "frac") } as MirrorAction.Swipe
        assertEquals(ScreenMirrorProtocol.MAX_SWIPE_MS, slow.ms)
        val noMs = act { put("kind", "swipe"); put("x1", 0.1); put("y1", 0.1); put("x2", 0.2); put("y2", 0.2); put("unit", "frac") } as MirrorAction.Swipe
        assertEquals(300L, noMs.ms)
    }

    @Test fun keysOnlyWhenNamed() {
        assertEquals("back", (act { put("kind", "key"); put("name", "back") } as MirrorAction.Key).name)
        assertEquals("quick_settings", (act { put("kind", "key"); put("name", "quick_settings") } as MirrorAction.Key).name)
        assertTrue(act { put("kind", "key"); put("name", "power") } is MirrorAction.Unsupported)
    }

    @Test fun textOpenSayCarryTheirPayload() {
        assertEquals("Salut, Ana", (act { put("kind", "type"); put("text", "Salut, Ana") } as MirrorAction.Type).text)
        assertEquals("com.spotify.music", (act { put("kind", "open"); put("app", "com.spotify.music") } as MirrorAction.Open).app)
        assertEquals("ce grad am", (act { put("kind", "say"); put("text", "ce grad am") } as MirrorAction.Say).text)
    }

    @Test fun scrollDefaultsDown() {
        assertEquals(false, (act { put("kind", "scroll"); put("dir", "up") } as MirrorAction.Scroll).down)
        assertEquals(true, (act { put("kind", "scroll"); put("dir", "down") } as MirrorAction.Scroll).down)
        assertEquals(true, (act { put("kind", "scroll") } as MirrorAction.Scroll).down)
    }

    @Test fun plainVerbsAndUnknownKind() {
        assertTrue(act { put("kind", "read") } is MirrorAction.Read)
        assertTrue(act { put("kind", "apps") } is MirrorAction.Apps)
        assertTrue(act { put("kind", "shot") } is MirrorAction.Shot)
        assertTrue(act { put("kind", "info") } is MirrorAction.Info)
        assertTrue(act { put("kind", "dance") } is MirrorAction.Unsupported)
        assertTrue(act { put("kind", "tap"); put("x", 0.5) } is MirrorAction.Unsupported)
    }

    @Test fun frameSizeKeepsAspectAndTarget() {
        val (w, h) = ScreenMirrorProtocol.frameSize(1080, 2340, 360)
        assertEquals(360, w); assertEquals(780, h)
        // Un ecran deja mic nu se mărește.
        assertEquals(300 to 600, ScreenMirrorProtocol.frameSize(300, 600, 360))
    }

    @Test fun appWord() {
        assertEquals("youtube", ScreenMirrorProtocol.appWord("com.google.android.youtube"))
        assertEquals("FORJA", ScreenMirrorProtocol.appWord("com.forja.app.research"))
        assertEquals("", ScreenMirrorProtocol.appWord(null))
    }
}
