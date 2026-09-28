package com.forja.app.core.inventory

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Destinațiile Inventarului 4.4: rădăcinile acceptate de MediaStore, traducerea selectorului, căile, locul de sosire. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DestinationsTest {

    private val ext = TreePaths.EXTERNAL
    private fun tree(id: String): Uri = DocumentsContract.buildTreeDocumentUri(ext, id)

    @Test fun normalizeKeepsOnlyPicturesAndDcim() {
        assertEquals("Pictures/FORJA/", MediaRoots.normalize("Pictures/FORJA"))
        assertEquals("Pictures/", MediaRoots.normalize("pictures/"))
        assertEquals("DCIM/FORJA/", MediaRoots.normalize("/DCIM//FORJA/"))
        assertEquals("Pictures/Vacanțe/", MediaRoots.normalize("Pictures/../Vacanțe"))
        assertNull(MediaRoots.normalize("Download/FORJA"))
        assertNull(MediaRoots.normalize("FORJA"))
        assertNull(MediaRoots.normalize(""))
    }

    @Test fun videosFollowTheAllowList() {
        // API 30+: un video poate sta lângă pozele lui în Pictures.
        assertEquals("Pictures/FORJA/", MediaRoots.forItem("Pictures/FORJA/", video = true, sdk = 30))
        // API 29: Pictures nu primește video → Movies, cu restul căii păstrat.
        assertEquals("Movies/FORJA/", MediaRoots.forItem("Pictures/FORJA/", video = true, sdk = 29))
        assertEquals("DCIM/FORJA/", MediaRoots.forItem("DCIM/FORJA/", video = true, sdk = 29))
        assertEquals("Pictures/FORJA/", MediaRoots.forItem("Pictures/FORJA/", video = false, sdk = 29))
        // O rădăcină stricată nu ajunge niciodată la MediaStore: cade pe implicită.
        assertEquals(MediaRoots.DEFAULT, MediaRoots.forItem("Download/", video = false, sdk = 34))
    }

    @Test fun labelsAndPaths() {
        assertEquals("Galerie · FORJA", MediaRoots.optionLabel("Pictures/FORJA/"))
        assertEquals("Direct în Galerie", MediaRoots.optionLabel("Pictures/"))
        assertEquals("Lângă Cameră", MediaRoots.optionLabel("DCIM/FORJA/"))
        assertEquals("Vacanțe", MediaRoots.optionLabel("Pictures/Vacanțe/"))
        assertEquals("FORJA", MediaRoots.shortLabel("DCIM/FORJA/"))
        assertEquals("Galerie", MediaRoots.shortLabel("Pictures/"))
        assertEquals("PICTURES/VACANȚE", MediaRoots.path("Pictures/Vacanțe/"))
    }

    @Test fun pickerTreeBecomesARelativePath() {
        assertEquals("Pictures/Vacanțe/", mediaRootFromTree(tree("primary:Pictures/Vacanțe")))
        assertEquals("DCIM/", mediaRootFromTree(tree("primary:DCIM")))
        assertNull(mediaRootFromTree(tree("primary:Download/Acte")))
        assertNull(mediaRootFromTree(tree("1A2B-3C4D:Pictures")))   // cardul SD: alt volum
        assertNull(mediaRootFromTree(DocumentsContract.buildTreeDocumentUri("com.google.android.apps.docs.storage", "acc=1;doc=x")))
    }

    @Test fun treeRelations() {
        val docs = tree("primary:Documents")
        assertTrue(TreePaths.isInside(tree("primary:Documents/Arhivă"), docs))
        assertFalse(TreePaths.isInside(tree("primary:Documents"), docs))
        assertFalse(TreePaths.isInside(tree("primary:DocumentsVechi"), docs))
        assertTrue(TreePaths.same(docs, tree("primary:Documents")))
        assertEquals("Documents", TreePaths.label(docs))
        assertEquals("Memoria internă", TreePaths.label(tree("primary:")))
        assertEquals("/storage/emulated/0/Documents/Organizate", TreePaths.storagePath(ext, "primary:Documents/Organizate"))
        assertEquals("/storage/1A2B-3C4D/Poze", TreePaths.storagePath(ext, "1A2B-3C4D:Poze"))
        assertNull(TreePaths.storagePath("com.other", "primary:x"))
        assertEquals("…/2024/Acte/Organizate", TreePaths.tail("Download/Scan/2024/Acte/Organizate"))
    }

    @Test fun docsDestinationNames() {
        val source = tree("primary:Documents")
        val default = InvDest.Tree(null, "Organizate")
        assertEquals("Organizate", DestNames.short(default))
        assertEquals("În folderul ales", DestNames.option(default))
        assertEquals("Documents/Organizate", DestNames.path(default, source))
        val custom = InvDest.Tree(tree("primary:Documents/Arhivă"), "")
        assertEquals("Arhivă", DestNames.short(custom))
        assertEquals("Documents/Arhivă", DestNames.path(custom, source))
        assertEquals("Pictures/FORJA", DestNames.path(InvDest.Media("Pictures/FORJA/"), null))
    }

    @Test fun landingsMergeAcrossRounds() {
        val root = InvPlace(null, "Pictures/FORJA")
        val a = Landing(InvKind.Photos, root, InvPlace(null, "Pictures/FORJA/Munte"), setOf("Munte"), first = Uri.parse("content://m/1"), bucketId = 7L)
        val same = Landing(InvKind.Photos, root, InvPlace(null, "Pictures/FORJA/Munte"), setOf("Munte"), first = Uri.parse("content://m/2"), bucketId = 7L)
        val other = Landing(InvKind.Photos, root, InvPlace(null, "Pictures/FORJA/Plajă"), setOf("Plajă"), bucketId = 9L)
        assertEquals("Pictures/FORJA/Munte", a.merge(same).target.label)
        assertEquals(Uri.parse("content://m/1"), a.merge(same).first)
        val both = a.merge(other)
        assertEquals("Pictures/FORJA", both.target.label)
        assertNull(both.bucketId)
        assertEquals(a, a.merge(null))
    }
}
