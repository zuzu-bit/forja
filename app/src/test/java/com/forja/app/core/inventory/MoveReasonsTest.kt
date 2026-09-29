package com.forja.app.core.inventory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 4.4.2 (Kotlin pur): de ce n-a mutat Android o poză. Proprietarul din RELATIVE_PATH (Android/media/<pachet>/, ca la
 * WhatsApp), clasificarea excepțiilor MediaProvider (mesajele din AOSP android16-release), rândurile de jurnal fără nume
 * sau căi și ce aplică o rundă cu acces complet.
 */
class MoveReasonsTest {
    private val self = "com.forja.app.research"

    // ───────────── proprietarul, din cale ─────────────

    @Test fun whatsappPhotosBelongToWhatsapp() {
        assertEquals("com.whatsapp", MediaOwners.ownerOf("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"))
        assertEquals("com.whatsapp", MediaOwners.ownerOf("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent/"))
        // FileUtils.PATTERN_OWNED_PATH e (?i): și cu alte litere mari / mici, și cu o bară în față.
        assertEquals("org.telegram.messenger", MediaOwners.ownerOf("android/Media/org.telegram.messenger/Telegram/Telegram Images/"))
        assertEquals("com.whatsapp", MediaOwners.ownerOf("/Android/media/com.whatsapp/"))
        assertEquals("com.whatsapp", MediaOwners.ownerOf("Android/media/com.whatsapp"))
    }

    @Test fun ordinaryFoldersHaveNoOwner() {
        assertNull(MediaOwners.ownerOf("DCIM/Camera/"))
        assertNull(MediaOwners.ownerOf("Pictures/Screenshots/"))
        assertNull(MediaOwners.ownerOf("Download/"))
        assertNull(MediaOwners.ownerOf(""))
        assertNull(MediaOwners.ownerOf("Android/media/"))
        assertNull(MediaOwners.ownerOf("Pictures/Android/media/com.whatsapp/"))   // doar la rădăcina memoriei
    }

    @Test fun fromItsOwnFolderForjaMovesWithoutFullAccess() {
        assertNull(MediaOwners.foreignOwner("Android/media/com.forja.app.research/Poze/", self))
        assertEquals("com.whatsapp", MediaOwners.foreignOwner("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/", self))
    }

    // ───────────── excepțiile MediaProvider → motiv ─────────────

    @Test fun mediaProviderMessagesMapToReasons() {
        val owned = IllegalArgumentException(
            "Changing ownership from /storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/IMG-1.jpg " +
                "to /storage/emulated/0/Pictures/FORJA/Capturi/IMG-1.jpg not allowed"
        )
        assertEquals(MoveReason.Owned, MoveReasons.classify(owned, ownedByOther = true))
        assertEquals(MoveReason.Owned, MoveReasons.classify(owned, ownedByOther = false))
        val dir = IllegalArgumentException(
            "Primary directory Documents not allowed for content://media/external_primary/images/media/42; allowed directories are [DCIM, Pictures]"
        )
        assertEquals(MoveReason.Dir, MoveReasons.classify(dir, ownedByOther = false))
        val volume = IllegalArgumentException("Changing volume from /storage/1A2B-3C4D/DCIM/a.jpg to /storage/emulated/0/Pictures/a.jpg not allowed")
        assertEquals(MoveReason.Volume, MoveReasons.classify(volume, ownedByOther = false))
    }

    @Test fun withoutAKnownMessageTheOwnerDecides() {
        assertEquals(MoveReason.Denied, MoveReasons.classify(SecurityException("no access"), ownedByOther = false))
        assertEquals(MoveReason.Owned, MoveReasons.classify(SecurityException("no access"), ownedByOther = true))
        assertEquals(MoveReason.Owned, MoveReasons.classify(IllegalStateException("x"), ownedByOther = true))
        assertEquals(MoveReason.Error, MoveReasons.classify(IllegalStateException("Failed to create directory"), ownedByOther = false))
        assertEquals("IllegalStateException", MoveReasons.errorClass(IllegalStateException("x")))
    }

    @Test fun whichReasonsNeedFullAccess() {
        assertTrue(MoveReason.Owned.needsAccess)
        assertTrue(MoveReason.Dir.needsAccess)
        assertTrue(MoveReason.Denied.needsAccess)
        for (r in listOf(MoveReason.Volume, MoveReason.Gone, MoveReason.Mismatch, MoveReason.Error)) assertFalse(r.name, r.needsAccess)
    }

    @Test fun theShownReasonIsTheMostCommonThenTheFixable() {
        assertEquals(MoveReason.Owned, MoveReasons.main(mapOf(MoveReason.Owned to 6, MoveReason.Error to 1)))
        assertEquals(MoveReason.Error, MoveReasons.main(mapOf(MoveReason.Owned to 1, MoveReason.Error to 5)))
        assertEquals(MoveReason.Owned, MoveReasons.main(mapOf(MoveReason.Error to 2, MoveReason.Owned to 2)))   // egalitate
        assertEquals(MoveReason.Dir, MoveReasons.main(mapOf(MoveReason.Gone to 3, MoveReason.Dir to 3)))
        assertNull(MoveReasons.main(emptyMap()))
        assertNull(MoveReasons.main(mapOf(MoveReason.Owned to 0)))
    }

    // ───────────── jurnalul: coduri, fără nume ─────────────

    @Test fun onlyStandardFolderNamesReachTheLog() {
        assertEquals("DCIM", MoveDiag.topDir("DCIM/Camera/"))
        assertEquals("Pictures", MoveDiag.topDir("pictures/Vacanțe Lana 2023/"))
        assertEquals("Download", MoveDiag.topDir("Download/"))
        assertEquals("Documents", MoveDiag.topDir("Documents/Poze de la mama/"))
        assertEquals("Android/media", MoveDiag.topDir("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"))
        assertEquals("other", MoveDiag.topDir("Vacanțe Lana 2023/"))
        assertEquals("other", MoveDiag.topDir("Ioana și Mihai/"))
        assertEquals("root", MoveDiag.topDir(""))
        assertEquals("image", MoveDiag.type("image/jpeg", video = false))
        assertEquals("video", MoveDiag.type("image/jpeg", video = true))
        assertEquals("other", MoveDiag.type("application/pdf", video = false))
    }

    @Test fun failNotesCarryNoNamesTitlesFoldersOrPaths() {
        val privateBits = listOf("Vacanțe", "Lana", "IMG", "whatsapp", "WhatsApp", "Images", "mama", "/storage", "Capturi")
        val fails = listOf(
            MoveDiag.fail(MoveReason.Owned, "image/jpeg", false, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/", "Pictures/FORJA/Capturi/"),
            MoveDiag.fail(MoveReason.Dir, "video/mp4", true, "DCIM/Camera/", "Vacanțe Lana/2023/"),
            MoveDiag.fail(MoveReason.Error, "image/png", false, "Pictures/Screenshots/", "Documents/Poze de la mama/", "IllegalStateException"),
            MoveDiag.failTrash(MoveReason.Error, "image/jpeg", false, "DCIM/Camera/")
        )
        val notes = MoveDiag.failNotes(fails, mgr = false)
        assertEquals("why=owned t=image src=Android/media dst=Pictures mgr=0", notes[0])
        assertEquals("why=dir t=video src=DCIM dst=other mgr=0", notes[1])
        assertEquals("why=error:IllegalStateException t=image src=Pictures dst=Documents mgr=0", notes[2])
        assertEquals("why=error t=image src=DCIM dst=trash mgr=0", notes[3])
        val shape = Regex("^why=[a-z]+(:[A-Za-z0-9_$.]+)? t=[a-z]+ src=[A-Za-z/]+ dst=[A-Za-z/]+ mgr=[01]$")
        for (n in notes) {
            assertTrue(n, shape.matches(n))
            assertTrue(n, n.length <= 120)
            for (p in privateBits) assertFalse("„$p” în: $n", n.contains(p))
        }
    }

    @Test fun atMostTwelveFailRowsAndOneSummaryWithCountsPerReason() {
        val fails = List(30) { MoveDiag.fail(if (it < 20) MoveReason.Owned else MoveReason.Dir, "image/jpeg", false, "DCIM/", "Pictures/") }
        assertEquals(MoveDiag.MAX_FAIL_ROWS, MoveDiag.failNotes(fails, mgr = true).size)
        assertEquals(12, MoveDiag.MAX_FAIL_ROWS)
        val sum = MoveDiag.sumNote(moved = 3, trashed = 1, reasons = MoveDiag.counts(fails), mgr = false)
        assertEquals("moved=3 trash=1 owned=20 dir=10 mgr=0", sum)
        assertEquals("moved=9 trash=1 mgr=1", MoveDiag.sumNote(9, 1, emptyMap(), mgr = true))
    }

    // ───────────── ce aplică o rundă ─────────────

    private data class It(val id: String)
    private val todo = listOf(It("a"), It("b"), It("c"))

    @Test fun withFullAccessARoundMovesEverythingWithoutAGrant() {
        assertEquals(todo, ApplySelect.moves(todo, { it.id }, grant = null, manager = true, sdk = 36))
        assertEquals(todo, ApplySelect.moves(todo, { it.id }, grant = listOf("a"), manager = true, sdk = 36))
        assertEquals(listOf("x", "y"), ApplySelect.trash(listOf("x", "y"), grant = null, manager = true, sdk = 36))
    }

    @Test fun withoutFullAccessOnlyTheGrantedChunkMoves() {
        assertEquals(emptyList<It>(), ApplySelect.moves(todo, { it.id }, grant = null, manager = false, sdk = 36))
        assertEquals(listOf(It("b")), ApplySelect.moves(todo, { it.id }, grant = listOf("b", "z"), manager = false, sdk = 36))
        assertEquals(listOf("y"), ApplySelect.trash(listOf("x", "y"), grant = listOf("y", "q"), manager = false, sdk = 36))
        assertEquals(emptyList<String>(), ApplySelect.trash(listOf("x"), grant = null, manager = false, sdk = 36))
    }

    @Test fun belowAndroid11EverythingIsDirect() {
        // API 29: fără dialoguri (și fără acces complet): mutări directe, gunoiul direct.
        assertEquals(todo, ApplySelect.moves(todo, { it.id }, grant = null, manager = false, sdk = 29))
        assertEquals(listOf("x"), ApplySelect.trash(listOf("x"), grant = null, manager = false, sdk = 29))
    }
}
