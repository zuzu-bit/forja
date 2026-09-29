package com.forja.app.core.inventory

import android.app.Application
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.MediaStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 4.4.1: pozele din „De aruncat” aflate deja în coș (acordul dat într-o aplicare întreruptă) nu mai cer fereastra
 * Android a doua oară. Iese din dialog doar ce MediaStore arată sigur la coș (IS_TRASHED = 1); orice îndoială (poză
 * negăsită, eroare, furnizor care ignoră argumentul de coș) lasă cererea ca înainte.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TrashSkipTest {
    /** MediaStore fals: _id → is_trashed. Fără argumentul de coș (sau ignorat), ca MediaProvider: coșul nu se vede. */
    class FakeMedia : ContentProvider() {
        var rows: Map<Long, Int> = emptyMap()
        var honorsTrashArg = true
        var fail = false
        var lastArgs: Bundle? = null
        var queries = 0

        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<String>?, queryArgs: Bundle?, cancellationSignal: CancellationSignal?): Cursor {
            if (fail) throw IllegalStateException("media indisponibil")
            queries++
            lastArgs = queryArgs
            val match = queryArgs?.getInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_DEFAULT) ?: MediaStore.MATCH_DEFAULT
            val include = honorsTrashArg && (match == MediaStore.MATCH_INCLUDE || match == MediaStore.MATCH_ONLY)
            val cols = projection ?: arrayOf(MediaStore.MediaColumns._ID)
            val c = MatrixCursor(cols)
            for (id in queryArgs?.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS).orEmpty().map { it.toLong() }) {
                val trashed = rows[id] ?: continue
                if (trashed == 1 && !include) continue
                c.addRow(Array<Any>(cols.size) { i -> if (cols[i] == MediaStore.MediaColumns.IS_TRASHED) trashed else id })
            }
            return c
        }

        override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
    }

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun media(rows: Map<Long, Int>) = Robolectric.setupContentProvider(FakeMedia::class.java, MediaStore.AUTHORITY).apply { this.rows = rows }
    private fun item(id: Long) = ItemRec(id = "m:$id", uri = "content://media/external/images/media/$id", kind = InvKind.Photos, takenAt = 0L, bytes = 1L, mediaId = id)

    @Test fun onlyItemsMarkedTrashedLeaveTheDialog() {
        val fake = media(mapOf(1L to 0, 2L to 1, 3L to 1))   // 4 nu se mai găsește deloc (șters sau fără acces)
        val trashed = MediaMover.trashedIds(ctx, listOf(1L, 2L, 3L, 4L))
        assertEquals(setOf(2L, 3L), trashed)
        assertEquals(MediaStore.MATCH_INCLUDE, fake.lastArgs?.getInt(MediaStore.QUERY_ARG_MATCH_TRASHED))
        // Fereastra coșului le mai cere doar pe 1 și 4 (4 = necunoscut: se cere, ca înainte).
        assertEquals(listOf(1L, 4L), InventoryApply.stillToTrash((1L..4L).map(::item), trashed).map { it.mediaId })
    }

    @Test fun wholeChunkAlreadyInTheTrashNeedsNoDialog() {
        media(mapOf(7L to 1))
        val chunk = listOf(item(7L))
        assertEquals(emptyList<ItemRec>(), InventoryApply.stillToTrash(chunk, MediaMover.trashedIds(ctx, chunk.map { it.mediaId })))
    }

    @Test fun aProviderThatIgnoresTheTrashArgumentSkipsNothing() {
        // Fără IS_TRASHED = 1 citit din rând, o poză vizibilă nu poate părea aruncată (și scoasă din plan fără coș).
        media(mapOf(1L to 0, 2L to 1)).honorsTrashArg = false
        assertEquals(emptySet<Long>(), MediaMover.trashedIds(ctx, listOf(1L, 2L)))
    }

    @Test fun aQueryErrorSkipsNothing() {
        media(mapOf(1L to 1)).fail = true
        val trashed = MediaMover.trashedIds(ctx, listOf(1L))
        assertEquals(emptySet<Long>(), trashed)
        assertEquals(listOf(1L), InventoryApply.stillToTrash(listOf(item(1L)), trashed).map { it.mediaId })
    }

    @Test fun aFullChunkIsQueriedInPiecesUnderTheSqlLimit() {
        val fake = media((1L..500L).associateWith { if (it % 2 == 0L) 1 else 0 })
        val trashed = MediaMover.trashedIds(ctx, (1L..500L).toList())
        assertEquals(250, trashed.size)
        assertEquals(2, fake.queries)   // 400 + 100 (SQLite primește cel mult 999 de parametri)
    }
}
