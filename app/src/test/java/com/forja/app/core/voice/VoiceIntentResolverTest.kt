package com.forja.app.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceIntentResolverTest {
    @Test fun `Romanian explicit YouTube search preserves the requested words`() {
        val intent = VoiceIntentResolver.resolve("FORJA, deschide YouTube și caută documentarul Planeta Albastră.")
        assertEquals("documentarul Planeta Albastră", intent?.query)
        assertEquals(VoiceIntent.YOUTUBE_PACKAGE, intent?.packageName)
    }

    @Test fun `Romanian without accents and explicit trailing action are supported`() {
        assertEquals("Planeta Albastra", VoiceIntentResolver.resolve(
            "Forja te rog deschide aplicatia YouTube si cauta Planeta Albastra si deschide rezultatul"
        )?.query)
    }

    @Test fun `English and explicit target after search are supported`() {
        assertEquals("Blue Planet", VoiceIntentResolver.resolve("FORJA, open YouTube and search for Blue Planet")?.query)
        assertEquals("Planeta noastră", VoiceIntentResolver.resolve("Caută Planeta noastră pe YouTube")?.query)
        assertEquals("Planeta noastră", VoiceIntentResolver.resolve("Pe YouTube caută Planeta noastră")?.query)
    }

    @Test fun `unsupported apps vague actions and empty searches cannot arm automation`() {
        listOf("deschide YouTube", "caută Planeta Albastră", "deschide banca și plătește", "deschide browser și caută X",
            "deschide YouTube și caută ", "deschide YouTube și cumpără X", "Ai putea găsi pe YouTube ceva?")
            .forEach { assertNull(VoiceIntentResolver.resolve(it)) }
    }

    @Test fun `oversized and control character commands are refused`() {
        assertNull(VoiceIntentResolver.resolve("deschide YouTube și caută " + "a".repeat(251)))
        assertNull(VoiceIntentResolver.resolve("deschide YouTube și caută Planet\u0000Earth"))
    }

    @Test fun `named trailing result must agree with requested search`() {
        assertEquals("documentarul Planeta Albastră", VoiceIntentResolver.resolve(
            "deschide YouTube și caută documentarul Planeta Albastră și deschide rezultatul Planeta Albastră"
        )?.query)
        assertNull(VoiceIntentResolver.resolve("deschide YouTube și caută Planeta Albastră și deschide rezultatul Alt Film"))
    }

    @Test fun `explicit cancellation supports Romanian and English but not incidental words`() {
        listOf("FORJA, anulează!", "oprește", "Stop", "forja cancel", "FORJA oprește comanda")
            .forEach { assertTrue(VoiceIntentResolver.isCancel(it)) }
        listOf("YouTube stop motion", "nu anulează", "stop this video and search", "deschide YouTube și caută stop")
            .forEach { assertFalse(VoiceIntentResolver.isCancel(it)) }
    }
}
