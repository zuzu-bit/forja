package com.forja.app.core.inventory

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import com.forja.app.feature.inventory.photoPickerStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Destinațiile Inventarului 4.4: rădăcinile acceptate de MediaStore, traducerea selectorului, căile, locul de sosire.
 * 4.4.2: cu „Acces la toate fișierele” orice dosar din memoria internă (în afară de rădăcină și de Android/…).
 */
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
        assertEquals(MediaRoots.DEFAULT, MediaRoots.forItem("Android/media/com.whatsapp/", video = false, sdk = 34))
        assertEquals(MediaRoots.DEFAULT, MediaRoots.forItem("", video = false, sdk = 34))
        // 4.4.2: un dosar ales cu acces complet rămâne cum e (și pentru video): MediaProvider îl primește de la un „manager”;
        // fără acces, mutarea eșuează cu motivul `dir`, nu se mută în tăcere în altă parte.
        assertEquals("Download/", MediaRoots.forItem("Download/", video = false, sdk = 34))
        assertEquals("Documents/Poze/", MediaRoots.forItem("Documents/Poze/", video = true, sdk = 36))
        assertEquals("Documents/Poze/", MediaRoots.forItem("Documents/Poze/", video = true, sdk = 29))
    }

    @Test fun withFullAccessAnyTopFolderButAndroid() {
        assertEquals("Documents/Poze/", MediaRoots.normalize("Documents/Poze", anyTop = true))
        assertEquals("Vacanțe 2023/", MediaRoots.normalize("/Vacanțe 2023/", anyTop = true))
        assertEquals("Download/Acte/", MediaRoots.normalize("Download//Acte", anyTop = true))
        // Pictures și DCIM rămân scrise ca de sistem; celelalte, cum le-a scris omul.
        assertEquals("Pictures/Vara/", MediaRoots.normalize("pictures/Vara", anyTop = true))
        assertEquals("DCIM/Camera/", MediaRoots.normalize("dcim/Camera", anyTop = true))
        assertEquals("Documents/Vacanțe/", MediaRoots.normalize("Documents/../Vacanțe", anyTop = true))
        assertEquals("Poze/", MediaRoots.normalize("Poze:*?", anyTop = true))
        assertNull(MediaRoots.normalize("Android/media/com.whatsapp", anyTop = true))
        assertNull(MediaRoots.normalize("android/data", anyTop = true))
        assertNull(MediaRoots.normalize("Android", anyTop = true))
        assertNull(MediaRoots.normalize("/", anyTop = true))
        assertNull(MediaRoots.normalize("a/b/c/d/e/f/g", anyTop = true))
        // Fără anyTop, regula din 4.4 (doar Pictures și DCIM) rămâne.
        assertNull(MediaRoots.normalize("Documents/Poze"))
    }

    @Test fun standardRootsWorkWithoutFullAccess() {
        assertTrue(MediaRoots.standard("Pictures/FORJA/"))
        assertTrue(MediaRoots.standard("DCIM/"))
        assertTrue(MediaRoots.standard("dcim/x"))
        assertFalse(MediaRoots.standard("Documents/Poze/"))
        assertFalse(MediaRoots.standard("Download/"))
        assertTrue("o rădăcină stricată cade pe implicită", MediaRoots.standard("Android/media/x/"))
    }

    @Test fun labelsForAnyFolder() {
        assertEquals("Poze", MediaRoots.optionLabel("Documents/Poze/"))
        assertEquals("Poze", MediaRoots.shortLabel("Documents/Poze/"))
        assertEquals("Documents", MediaRoots.shortLabel("Documents/"))
        assertEquals("Cameră", MediaRoots.shortLabel("DCIM/"))
        assertEquals("DOCUMENTS/POZE", MediaRoots.path("Documents/Poze/"))
        assertEquals("Documents/Poze", DestNames.path(InvDest.Media("Documents/Poze/"), null))
        assertEquals("Documents/Poze/", DestRec(mediaRoot = "Documents/Poze/").mediaRoot())
        assertEquals(MediaRoots.DEFAULT, DestRec(mediaRoot = "Android/data/x/").mediaRoot())
    }

    @Test fun thePickerStartsNextToTheCurrentDestination() {
        assertEquals("Pictures", photoPickerStart(InvDest.Media("Pictures/FORJA/")))
        assertEquals("Pictures", photoPickerStart(InvDest.Media("Pictures/")))
        assertEquals("Documents", photoPickerStart(InvDest.Media("Documents/Poze/")))
        assertEquals("Documents/Poze", photoPickerStart(InvDest.Media("Documents/Poze/Vara/")))
        assertEquals("Pictures", photoPickerStart(null))
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
        // 4.4.2: orice dosar din memoria internă (cu acces complet; fără el, confirmarea îl cere) — nu mai e refuzat.
        assertEquals("Download/Acte/", mediaRootFromTree(tree("primary:Download/Acte")))
        assertEquals("Documents/Poze/", mediaRootFromTree(tree("primary:Documents/Poze")))
        assertEquals("Vacanțe/", mediaRootFromTree(tree("primary:Vacanțe")))
        // Rădăcina memoriei și Android/… nu; nici cardul (alt volum) și nici alți furnizori.
        assertNull(mediaRootFromTree(tree("primary:")))
        assertNull(mediaRootFromTree(tree("primary:Android/media/com.whatsapp")))
        assertNull(mediaRootFromTree(tree("primary:Android")))
        assertNull(mediaRootFromTree(tree("1A2B-3C4D:Pictures")))   // cardul SD: alt volum
        assertNull(mediaRootFromTree(DocumentsContract.buildTreeDocumentUri("com.google.android.apps.docs.storage", "acc=1;doc=x")))
        // Un nume pe care curățarea l-ar schimba ar duce pozele în alt dosar, alături: refuzat („Alege alt dosar.”).
        assertNull(mediaRootFromTree(tree("primary:Documents/Facturi: 2024")))
        assertNull(mediaRootFromTree(tree("primary:.Private")))
        assertNull(mediaRootFromTree(tree("primary:Pictures/Poze.")))
        assertNull(mediaRootFromTree(tree("primary:Documents/" + "a".repeat(61))))
        // Scrierea sistemului pentru primul segment nu schimbă dosarul.
        assertEquals("Pictures/Vacanțe/", mediaRootFromTree(tree("primary:pictures/Vacanțe")))
        assertEquals("Documents/" + "a".repeat(60) + "/", mediaRootFromTree(tree("primary:Documents/" + "a".repeat(60))))
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
        assertEquals("În dosarul ales", DestNames.option(default))
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
