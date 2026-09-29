package com.forja.app.core.inventory

import android.app.Application
import android.app.PendingIntent
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.MediaStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 4.4.2: aplicarea pozelor cu „Acces la toate fișierele” și motivele eșecurilor, pe un MediaStore fals care aplică
 * regulile din MediaProvider (AOSP android16-release): fără acces complet, poze doar în DCIM/Pictures
 * (ensureFileColumns, „Primary directory … not allowed”), nicio ieșire din Android/media/<alt pachet>/
 * (isUpdateAllowedForOwnedPath, „Changing ownership … not allowed”), IS_TRASHED doar prin dialogul coșului; cu acces
 * complet (isCallingPackageManager) toate trec, fără ferestre.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ManagerApplyTest {
    class Row(var rel: String, var trashed: Boolean = false)

    class FakeMedia : ContentProvider() {
        val rows = HashMap<Long, Row>()
        /** Aplicația ține MANAGE_EXTERNAL_STORAGE. */
        var manager = false
        /** Id-uri pentru care actualizarea aruncă altceva (disc plin, bază stricată). */
        var broken = emptySet<Long>()
        val asks = mutableListOf<String>()
        val updates = mutableListOf<String>()

        override fun onCreate() = true

        private fun idOf(uri: Uri): Long? = uri.lastPathSegment?.toLongOrNull()

        override fun query(uri: Uri, projection: Array<String>?, queryArgs: Bundle?, cancellationSignal: CancellationSignal?): Cursor {
            val match = queryArgs?.getInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_DEFAULT) ?: MediaStore.MATCH_DEFAULT
            return select(
                uri, projection, queryArgs?.getString(ContentResolver.QUERY_ARG_SQL_SELECTION),
                queryArgs?.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS), match == MediaStore.MATCH_INCLUDE
            )
        }

        override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor =
            select(uri, projection, selection, selectionArgs, includeTrashed = false)

        private fun select(uri: Uri, projection: Array<String>?, sel: String?, args: Array<String>?, includeTrashed: Boolean): Cursor {
            val cols = projection ?: arrayOf(MediaStore.MediaColumns._ID)
            val c = MatrixCursor(cols)
            val ids = when {
                idOf(uri) != null -> listOf(idOf(uri)!!)
                sel?.contains(MediaStore.MediaColumns.DISPLAY_NAME) == true -> emptyList()   // nicio coliziune de nume
                else -> args.orEmpty().mapNotNull { it.toLongOrNull() }
            }
            for (id in ids) {
                val r = rows[id] ?: continue
                if (r.trashed && !includeTrashed) continue   // coșul nu se vede în interogarea implicită
                c.addRow(Array<Any?>(cols.size) { i ->
                    when (cols[i]) {
                        MediaStore.MediaColumns._ID -> id
                        MediaStore.MediaColumns.RELATIVE_PATH -> r.rel
                        MediaStore.MediaColumns.IS_TRASHED -> if (r.trashed) 1 else 0
                        MediaStore.MediaColumns.VOLUME_NAME -> "external_primary"
                        MediaStore.MediaColumns.BUCKET_ID -> 7L
                        else -> null
                    }
                })
            }
            return c
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int {
            val id = idOf(uri) ?: return 0
            val r = rows[id] ?: return 0
            if (r.trashed || values == null) return 0
            if (id in broken) throw IllegalStateException("database is locked")
            if (values.containsKey(MediaStore.MediaColumns.IS_TRASHED)) {
                if (!manager) throw SecurityException("com.forja.app.research has no access to $uri")
                r.trashed = values.getAsInteger(MediaStore.MediaColumns.IS_TRASHED) == 1
                updates += "$id:trash"
                return 1
            }
            val rel = values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH) ?: return 0
            val top = rel.substringBefore('/')
            if (!manager && top !in setOf("DCIM", "Pictures")) {
                throw IllegalArgumentException("Primary directory $top not allowed for $uri; allowed directories are [DCIM, Pictures]")
            }
            if (!manager && r.rel.startsWith("Android/media/", ignoreCase = true)) {
                throw IllegalArgumentException("Changing ownership from /storage/emulated/0/${r.rel}IMG-$id.jpg to /storage/emulated/0/${rel}IMG-$id.jpg not allowed")
            }
            r.rel = rel
            updates += "$id:$rel"
            return 1
        }

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            asks += method
            if (method != "create_write_request" && method != "create_trash_request") return null
            return Bundle().apply { putParcelable("result", PendingIntent.getActivity(context, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)) }
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    }

    private val ctx get() = RuntimeEnvironment.getApplication()

    @Before fun noPlanBefore() = runBlocking { Inventory.clear(ctx) }
    @After fun noPlanAfter() = runBlocking { Inventory.clear(ctx) }

    private val wa = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"

    private fun media(vararg rows: Pair<Long, String>): FakeMedia =
        Robolectric.setupContentProvider(FakeMedia::class.java, MediaStore.AUTHORITY).apply { for ((id, rel) in rows) this.rows[id] = Row(rel) }

    private fun item(id: Long, rel: String) = ItemRec(
        id = "m:$id", uri = "content://media/external/images/media/$id", kind = InvKind.Photos, takenAt = 0L, bytes = 1L,
        mime = "image/jpeg", name = "IMG-$id Vacanță Lana.jpg", mediaId = id, relPath = rel
    )

    private fun doc(moves: List<ItemRec>, trash: List<ItemRec>, root: String) = PlanDoc(
        folders = if (moves.isEmpty()) emptyList() else listOf(FolderRec("f1", "Vacanță", "sea", moves.map { it.id })),
        trash = PlanDoc().trash.copy(itemIds = trash.map { it.id }),
        dest = DestRec(mediaRoot = root)
    )

    /** Un plan de poze gata, pe disc (ca după o repornire), încărcat în Inventory. */
    private fun seed(moves: List<ItemRec>, trash: List<ItemRec>, root: String = MediaRoots.DEFAULT) = runBlocking {
        val runId = "rmanager"
        InventoryStore.writeRun(ctx, RunFile(RunMeta(runId, InvKind.Photos, createdAt = 1L, stage = InvStage.Ready), doc(moves, trash, root)))
        InventoryStore.writeItems(ctx, runId, moves + trash)
        InventoryStore.setLatest(ctx, runId)
        assertNotNull("planul salvat se încarcă", Inventory.load(ctx))
    }

    // ───────────── cu acces complet ─────────────

    @Test fun withFullAccessEverythingMovesWithoutADialogAndTheTrashIsDoneHere() = runBlocking {
        // Planul Lanei, pe scurt: o poză din Cameră, două din WhatsApp, una la gunoi.
        val fake = media(1L to "DCIM/Camera/", 2L to wa, 3L to wa, 9L to "DCIM/Camera/").apply { manager = true }
        seed(moves = listOf(item(1, "DCIM/Camera/"), item(2, wa), item(3, wa)), trash = listOf(item(9, "DCIM/Camera/")))
        assertEquals(PendingOwners(3, mapOf("com.whatsapp" to 2)), Inventory.pendingOwners(ctx))
        val r = Inventory.apply(ctx, manager = true) { _, _ -> }
        assertEquals(3, r.moved)
        assertEquals(1, r.trashed)
        assertEquals(0, r.failed)
        assertEquals("nicio fereastră de acord", emptyList<String>(), fake.asks)
        assertEquals("Pictures/FORJA/Vacanță/", fake.rows.getValue(2).rel)
        assertEquals("Pictures/FORJA/Vacanță/", fake.rows.getValue(3).rel)
        assertTrue("la coș prin IS_TRASHED = 1", fake.rows.getValue(9).trashed)
        assertTrue("9:trash" in fake.updates)
        assertNull("planul gol dispare", Inventory.plan.value)
    }

    @Test fun withFullAccessAnyFolderIsADestination() = runBlocking {
        val fake = media(1L to "DCIM/Camera/", 2L to wa).apply { manager = true }
        seed(moves = listOf(item(1, "DCIM/Camera/"), item(2, wa)), trash = emptyList(), root = "Documents/Poze de la mama/")
        val r = Inventory.apply(ctx, manager = true) { _, _ -> }
        assertEquals(2, r.moved)
        assertEquals("Documents/Poze de la mama/Vacanță/", fake.rows.getValue(1).rel)
        assertEquals("Documents/Poze de la mama/Vacanță/", fake.rows.getValue(2).rel)
        assertEquals("Documents/Poze de la mama/Vacanță", r.landing?.target?.label)
    }

    @Test fun withFullAccessAFailureStillHasItsReasonAndStaysInThePlan() = runBlocking {
        val fake = media(1L to "DCIM/Camera/", 2L to "DCIM/Camera/", 9L to "DCIM/").apply { manager = true; broken = setOf(2L, 9L) }
        seed(moves = listOf(item(1, "DCIM/Camera/"), item(2, "DCIM/Camera/")), trash = listOf(item(9, "DCIM/")))
        val r = Inventory.apply(ctx, manager = true) { _, _ -> }
        assertEquals(1, r.moved)
        assertEquals(2, r.failed)
        assertEquals(MoveReason.Error, r.failures.getValue("m:2").reason)
        assertEquals("IllegalStateException", r.failures.getValue("m:2").error)
        assertEquals("trash", r.failures.getValue("m:9").dst)
        val plan = Inventory.plan.value!!
        assertEquals(listOf("m:2"), plan.folders.single().itemIds)
        assertEquals(listOf("m:9"), plan.trash.itemIds)
        assertFalse(fake.rows.getValue(9).trashed)
    }

    // ───────────── fără acces complet: fluxul din 4.4.1, cu motive ─────────────

    @Test fun withoutFullAccessNothingMovesWithoutAGrant() = runBlocking {
        val fake = media(1L to "DCIM/Camera/", 9L to "DCIM/")
        seed(moves = listOf(item(1, "DCIM/Camera/")), trash = listOf(item(9, "DCIM/")))
        val r = Inventory.apply(ctx) { _, _ -> }
        assertEquals(0, r.moved)
        assertEquals(0, r.trashed)
        assertEquals(emptyList<String>(), fake.updates)
        assertEquals(listOf("m:1"), Inventory.plan.value!!.folders.single().itemIds)
    }

    @Test fun withAGrantWhatsappPhotosFailAsOwnedAndTheRestMoves() = runBlocking {
        // Ce s-a întâmplat pe S23: acordul de scriere dat pentru toate 9, doar cele din afara WhatsApp s-au mutat.
        val fake = media(1L to "DCIM/Camera/", 2L to wa, 3L to wa)
        val moves = listOf(item(1, "DCIM/Camera/"), item(2, wa), item(3, wa))
        val out = InventoryApply.photos(ctx, doc(moves, emptyList(), MediaRoots.DEFAULT), moves.associateBy { it.id },
            grantMoves = moves.map { it.id }, grantTrash = null, step = { _, _, _ -> })
        assertEquals(1, out.result.moved)
        assertEquals(2, out.result.failed)
        assertEquals(setOf("m:2", "m:3"), out.result.failures.keys)
        for (f in out.result.failures.values) {
            assertEquals(MoveReason.Owned, f.reason)
            assertEquals(MoveFail(MoveReason.Owned, "image", "Android/media", "Pictures"), f)
        }
        assertEquals("Pictures/FORJA/Vacanță/", fake.rows.getValue(1).rel)
        assertEquals(wa, fake.rows.getValue(2).rel)
        assertEquals("rămân în plan (de reîncercat cu acces complet)", setOf("m:1"), out.removed)
    }

    @Test fun withAGrantAFolderOutsidePicturesFailsAsDir() = runBlocking {
        media(1L to "DCIM/Camera/")
        val moves = listOf(item(1, "DCIM/Camera/"))
        val out = InventoryApply.photos(ctx, doc(moves, emptyList(), "Documents/Poze/"), moves.associateBy { it.id },
            grantMoves = listOf("m:1"), grantTrash = null, step = { _, _, _ -> })
        assertEquals(0, out.result.moved)
        assertEquals(MoveFail(MoveReason.Dir, "image", "DCIM", "Documents"), out.result.failures.getValue("m:1"))
        // Jurnalul: doar coduri și dosare standard, niciun nume.
        val note = MoveDiag.failNote(out.result.failures.getValue("m:1"), mgr = false)
        assertEquals("why=dir t=image src=DCIM dst=Documents mgr=0", note)
        assertFalse(note.contains("Poze") || note.contains("Vacanță") || note.contains("IMG"))
    }

    @Test fun anItemDeletedMeanwhileLeavesThePlanAsGone() = runBlocking {
        media(1L to "DCIM/Camera/")   // 5 nu mai există
        val moves = listOf(item(1, "DCIM/Camera/"), item(5, "DCIM/Camera/"))
        val out = InventoryApply.photos(ctx, doc(moves, emptyList(), MediaRoots.DEFAULT), moves.associateBy { it.id },
            grantMoves = moves.map { it.id }, grantTrash = null, step = { _, _, _ -> })
        assertEquals(1, out.result.moved)
        assertEquals(MoveReason.Gone, out.result.failures.getValue("m:5").reason)
        assertTrue("iese din plan", "m:5" in out.removed)
    }

    @Test fun anItemAlreadyInPlaceCountsAsMovedWhateverTheCase() = runBlocking {
        val fake = media(1L to "pictures/forja/vacanță/")
        val moves = listOf(item(1, "pictures/forja/vacanță/"))
        val out = InventoryApply.photos(ctx, doc(moves, emptyList(), MediaRoots.DEFAULT), moves.associateBy { it.id },
            grantMoves = null, grantTrash = null, step = { _, _, _ -> })
        assertEquals(1, out.result.moved)
        assertEquals("fără nicio actualizare", emptyList<String>(), fake.updates)
    }
}
