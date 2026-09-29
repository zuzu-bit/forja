package com.forja.app.core.sync

import com.forja.app.core.sync.MirrorPlan.Candidate
import com.forja.app.core.sync.MirrorPlan.Kind
import com.forja.app.core.sync.MirrorPlan.Remote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Oglinda (pachetul C): id-urile stabile și planul unei treceri, fără Android. */
class MirrorPlanTest {
    private fun photo(name: String, album: String, at: Long, size: Long = 3_000_000) =
        Candidate(MirrorPlan.mediaKey(Kind.Photo, name, at, size), Kind.Photo, name, album, "image/jpeg", at, size, "content://m/$name")
    private fun video(name: String, size: Long) = Candidate(MirrorPlan.mediaKey(Kind.Video, name, 5, size), Kind.Video, name, "Camera", "video/mp4", 5, size, "content://v/$name")
    private fun doc(name: String, album: String, size: Long = 1000) =
        Candidate(MirrorPlan.docKey(name, size, 7), Kind.File, name, album, "application/pdf", 7, size, "content://d/$name")

    @Test fun stableIdsAreUuidV4ShapedAndIgnoreTheAlbum() {
        val a = photo("IMG_1.jpg", "Camera", 100)
        val moved = a.copy(album = "Pictures/FORJA/Munte")
        assertEquals(a.id, moved.id)
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(a.id))
        assertNotEquals(a.id, photo("IMG_1.jpg", "Camera", 101).id)
        assertEquals(MirrorPlan.stableId("x"), MirrorPlan.stableId("x"))
    }

    @Test fun docAlbumIsTheChosenFolderPlusTheSubfolder() {
        assertEquals("Documents/Organizate/Facturi", MirrorPlan.docAlbum("Documents", "Organizate/Facturi/enel.pdf"))
        assertEquals("Download", MirrorPlan.docAlbum("Download", "lista.txt"))
        assertEquals("Documente", MirrorPlan.docAlbum("", "a.pdf"))
    }

    @Test fun needsDependOnKindAndVideoSize() {
        assertEquals("ft", MirrorPlan.needs(photo("a", "C", 1)))
        assertEquals("ftp", MirrorPlan.needs(video("v", 10_000_000)))
        assertEquals("a long video keeps only the poster", "tp", MirrorPlan.needs(video("v", 90_000_000)))
        assertEquals("f", MirrorPlan.needs(doc("d", "D")))
    }

    @Test fun planUploadsNewestFirstMovesClaimsAndDeletesOnlyOwnScannedCopies() {
        val new1 = photo("new1", "Camera", 300)
        val new2 = photo("new2", "Camera", 900)
        val done = photo("done", "Munte", 200)
        val half = photo("half", "Camera", 100)
        val foreign = photo("foreign", "Camera", 50)
        val gone = photo("gone", "Camera", 10)
        val big = doc("film.pdf", "Download", 30L * 1024 * 1024)
        val remote = listOf(
            Remote(done.id, "Camera", "photo", "ft", true),       // Inventarul l-a mutat în „Munte”
            Remote(half.id, "Camera", "photo", "f", true),        // lipsește miniatura
            Remote(foreign.id, "Camera", "photo", "ft", false),   // urcat de pe alt telefon (sau înainte de reinstalare)
            Remote("11111111-1111-4111-8111-111111111111", "Camera", "photo", "ft", true),   // șters de pe telefon
            Remote("22222222-2222-4222-8222-222222222222", "Camera", "photo", "ft", false),  // al altui telefon: rămâne
            Remote("33333333-3333-4333-8333-333333333333", "Documents", "file", "f", true)   // documentele n-au fost citite
        )
        val p = MirrorPlan.plan(listOf(new1, new2, done, half, foreign, gone, big), remote, setOf(gone.id), setOf("photo"))
        assertEquals(listOf(new2.id, new1.id, half.id), p.upload.map { it.id })
        assertEquals(listOf(done.id to "Munte"), p.move)
        assertEquals(listOf(foreign.id), p.claim)
        assertEquals(listOf("11111111-1111-4111-8111-111111111111"), p.delete)
        assertEquals(2, p.mirrored); assertEquals(3, p.waiting); assertEquals(1, p.tooBig)
    }

    @Test fun nothingIsDeletedFromAGroupThatWasNotRead() {
        val remote = listOf(Remote("11111111-1111-4111-8111-111111111111", "Camera", "photo", "ft", true))
        assertTrue(MirrorPlan.plan(emptyList(), remote, emptySet(), emptySet()).delete.isEmpty())
        assertEquals(1, MirrorPlan.plan(emptyList(), remote, emptySet(), setOf("photo")).delete.size)
        assertTrue("videos read in full do not make photos deletable", MirrorPlan.plan(emptyList(), remote, emptySet(), setOf("video")).delete.isEmpty())
    }

    private fun uuid(n: Int) = "%08d-0000-4000-8000-%012d".format(n, n)

    @Test fun docsAreDeletedOnlyFromFoldersReadInFull() {
        val remote = listOf(
            Remote(uuid(1), "Documents/Facturi", "file", "f", true),
            Remote(uuid(2), "Download", "file", "f", true),
            Remote(uuid(3), "Arhivă veche", "file", "f", true))
        // „Download” și-a pierdut permisiunea: doar ce era în „Documents” se poate șterge; un folder necunoscut rămâne.
        val p = MirrorPlan.plan(emptyList(), remote, emptySet(), emptySet(), docTrees = setOf("Documents"), knownTrees = setOf("Documents", "Download"))
        assertEquals(listOf(uuid(1)), p.delete)
        assertEquals("Documents/Facturi", MirrorPlan.treeOf("Documents/Facturi", setOf("Documents", "Documents/Facturi")))
        assertEquals(null, MirrorPlan.treeOf("Documentsx/a", setOf("Documents")))
    }

    @Test fun trashedItemsKeepTheirSiteCopyAndDoNotUpload() {
        val t = photo("IMG_9.jpg", "Camera", 5).copy(trashed = true)
        val p = MirrorPlan.plan(listOf(t), listOf(Remote(t.id, "Camera", "photo", "ft", true)), emptySet(), setOf("photo"))
        assertTrue(p.delete.isEmpty()); assertTrue(p.upload.isEmpty())
        assertEquals("IMG_9.jpg", MirrorPlan.untrashedName(".trashed-1790000000-IMG_9.jpg"))
        assertEquals("a.trashed-1-b.jpg", MirrorPlan.untrashedName("a.trashed-1-b.jpg"))
    }

    @Test fun aMassDisappearanceIsHeldNotDeleted() {
        val remote = (1..100).map { Remote(uuid(it), "Camera", "photo", "ft", true) }
        val kept = (1..60).map { photo("p$it", "Camera", it.toLong()) }
        val remoteKept = kept.map { Remote(it.id, "Camera", "photo", "ft", true) }
        val p = MirrorPlan.plan(kept, remote + remoteKept, emptySet(), setOf("photo"))
        assertTrue(p.delete.isEmpty()); assertEquals(100, p.held)
        val few = MirrorPlan.plan(kept, remote.take(20) + remoteKept, emptySet(), setOf("photo"))
        assertEquals(20, few.delete.size); assertEquals(0, few.held)
    }
}
