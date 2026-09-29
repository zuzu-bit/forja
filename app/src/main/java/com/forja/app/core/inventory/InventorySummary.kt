package com.forja.app.core.inventory

import android.content.Context
import com.forja.app.ForjaApp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Rezumatul rulărilor pe site: după fiecare rundă de aplicare, `users/{uid}/inventory/{runId}` (merge; aceeași rulare
 * se actualizează cu totalurile adunate). Doar cu contractul semnat (`prefs.contractSigned`) și cu un cont conectat.
 * Firestore ține scrierea în cache-ul offline și o trimite la revenirea rețelei. Se păstrează ultimele [KEEP]
 * rulări: cele mai vechi se șterg o singură dată pe rulare, nu la fiecare rundă (citirile Spark sunt numărate).
 */
internal object InventorySummary {
    const val KEEP = 20
    private val pruned: MutableSet<String> = ConcurrentHashMap.newKeySet()

    suspend fun publish(ctx: Context, doc: InvSummaryDoc) {
        try {
            val app = ForjaApp.from(ctx)
            if (!app.prefs.contractSigned.first()) return
            val uid = app.auth.currentUid ?: return
            val col = FirebaseFirestore.getInstance().collection("users").document(uid).collection("inventory")
            col.document(doc.id).set(doc.toMap(), SetOptions.merge())
            if (pruned.add(doc.id)) prune(ctx, col)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend fun prune(ctx: Context, col: CollectionReference) {
        val snap = withTimeoutOrNull(20_000L) {
            col.orderBy("finishedAt", Query.Direction.DESCENDING).limit((KEEP * 2).toLong()).get().await()
        } ?: return
        for (d in snap.documents.drop(KEEP)) {
            try { d.reference.delete() } catch (_: Exception) { }
            // Mirror C: coperțile rulării ies de pe site odată cu ea („cât rămâne rularea pe site”).
            InvMirror.dropCovers(d.id, ctx)
        }
    }
}
