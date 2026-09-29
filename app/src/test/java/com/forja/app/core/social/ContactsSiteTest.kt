package com.forja.app.core.social

import com.forja.app.core.data.AuthRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Rezumatul agendei pentru site (settings/contacts): fără numele din agendă, fără numere. Numele social trimis la profil. */
class ContactsSiteTest {
    private val found = listOf(
        ContactMatch(uid = "u2", name = "Mama (agendă)", forjaName = "Ioana", mutual = false, verified = true, at = 5),
        ContactMatch(uid = "u1", name = "Bogdan serviciu", forjaName = "Bogdan", mutual = true, verified = false, at = 3),
    )

    @Test fun summaryHasCountsAndForjaNamesOnly() {
        val s = ContactsSync.siteSummary("ok", 312, found, 1_000L)
        assertEquals(1_000L, s["syncedAt"]); assertEquals("ok", s["status"]); assertEquals(312, s["compared"])
        assertEquals(2, s["found"]); assertEquals(1, s["mutual"])
        @Suppress("UNCHECKED_CAST") val matches = s["matches"] as List<Map<String, Any>>
        assertEquals(listOf("u1", "u2"), matches.map { it["uid"] })
        assertEquals(setOf("uid", "forjaName", "mutual", "verified"), matches[0].keys)
        val text = s.toString()
        assertFalse("agenda names never leave the phone", text.contains("Mama") || text.contains("serviciu"))
    }

    @Test fun summaryKeepsAtMost50() {
        val many = List(80) { ContactMatch(uid = "u$it", name = "n", forjaName = "F$it", mutual = false, verified = false, at = 0) }
        @Suppress("UNCHECKED_CAST") val matches = ContactsSync.siteSummary("ok", 80, many, 0)["matches"] as List<Any>
        assertEquals(50, matches.size)
    }

    @Test fun socialNameIsTrimmedCleanedAndCapped() {
        assertEquals("Lana", AuthRepository.socialName("  Lana \u0007 "))
        assertEquals(40, AuthRepository.socialName("x".repeat(90))!!.length)
        assertNull(AuthRepository.socialName("   "))
    }
}
