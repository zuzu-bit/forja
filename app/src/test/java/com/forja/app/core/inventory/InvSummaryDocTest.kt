package com.forja.app.core.inventory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rezumatul rulării pentru site (DESIGN-4.4 §3.3): forma exactă a documentului Firestore, fără Android. */
class InvSummaryDocTest {

    private fun doc(folders: List<InvSummaryDoc.Folder>, kind: String = "photos", freed: Long? = 1_000L) = InvSummaryDoc(
        id = "r1", kind = kind, startedAt = 10L, finishedAt = 20L, appVersion = "4.4",
        scopeMode = "last", scopeN = 500, scopeLabel = "Ultimele 500",
        destLabel = "Galerie · FORJA", destPath = "PICTURES/FORJA",
        folders = folders, trashCount = 3, trashBytes = 1_000L, moved = 12, failed = 1, freedBytes = freed
    )

    @Test fun keysMatchTheContract() {
        val m = doc(listOf(InvSummaryDoc.Folder("Munte", 4, 400L))).toMap()
        assertEquals(
            setOf("id", "kind", "startedAt", "finishedAt", "appVersion", "scope", "dest", "folders", "trash", "moved", "failed", "freedBytes"),
            m.keys
        )
        assertEquals(mapOf("mode" to "last", "n" to 500, "label" to "Ultimele 500"), m["scope"])
        assertEquals(mapOf("label" to "Galerie · FORJA", "path" to "PICTURES/FORJA"), m["dest"])
        // Câmp cu câmp: literalii dintr-un mapOf(Int, Long) își pot schimba tipul la inferență, iar Map.equals compară și tipul.
        val trash = m["trash"] as Map<*, *>
        assertEquals(3, trash["count"])
        assertEquals(1_000L, trash["bytes"])
        val folders = m["folders"] as List<*>
        assertEquals(1, folders.size)
        val f = folders.single() as Map<*, *>
        assertEquals("Munte", f["name"])
        assertEquals(4, f["count"])
        assertEquals(400L, f["bytes"])
        assertEquals(12, m["moved"])
        assertEquals(1, m["failed"])
    }

    @Test fun foldersLargestFirstAtMostSixtyWithoutEmptyOnes() {
        val many = (1..70).map { InvSummaryDoc.Folder("D$it", it, it * 10L) } + InvSummaryDoc.Folder("Gol", 0, 0L)
        @Suppress("UNCHECKED_CAST")
        val out = doc(many).toMap()["folders"] as List<Map<String, Any?>>
        assertEquals(InvSummaryDoc.MAX_FOLDERS, out.size)
        assertEquals("D70", out.first()["name"])
        assertEquals("D11", out.last()["name"])
        assertTrue(out.none { it["name"] == "Gol" })
    }

    @Test fun docsHaveNoFreedBytesAndLongTextIsCut() {
        val m = doc(listOf(InvSummaryDoc.Folder("x".repeat(200), 1, 1L)), kind = "docs", freed = null).toMap()
        assertNull(m["freedBytes"])
        assertTrue(m.containsKey("freedBytes"))
        @Suppress("UNCHECKED_CAST")
        val name = (m["folders"] as List<Map<String, Any?>>).single()["name"] as String
        assertEquals(InvSummaryDoc.MAX_TEXT, name.length)
    }

    @Test fun countUsesNonBreakingThousands() {
        assertEquals("500", InvSummaryDoc.count(500))
        assertEquals("1 000", InvSummaryDoc.count(1_000))
        assertEquals("12 480", InvSummaryDoc.count(12_480))
    }
}
