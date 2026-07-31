package com.forja.app.core.data

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.time.LocalDate

/** Un prieten, cu starea lui live — onest: doar ce a publicat el. */
data class Friend(
    val uid: String,
    val name: String,
    val state: String,          // idle · walk · run · ride · sleep · gym · ghost · off
    val lat: Double?,
    val lng: Double?,
    val speedMps: Double,
    val locUpdatedAt: Long,
    val weekKm: Double,
    val ghost: Boolean,
    val lastActivityType: String? = null,
    val lastActivityKm: Double = 0.0,
    val lastActivityAt: Long = 0L
)

class FriendsRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    private fun friendshipId(a: String, b: String) = listOf(a, b).sorted().joinToString("_")

    /** Adaugă un prieten prin codul lui de invitație. Prietenia e reciprocă, imediată. */
    suspend fun addFriendByCode(myUid: String, code: String): Result<String> {
        val clean = code.trim().uppercase().removePrefix("FORJA-")
        if (clean.length < 4) return Result.failure(IllegalArgumentException("Codul e prea scurt."))
        val inv = db.collection("inviteCodes").document(clean).get().await()
        val otherUid = inv.getString("uid")
            ?: return Result.failure(IllegalArgumentException("Cod necunoscut. Verifică-l cu prietenul tău."))
        if (otherUid == myUid) return Result.failure(IllegalArgumentException("Acesta e chiar codul tău."))
        val id = friendshipId(myUid, otherUid)
        val existing = db.collection("friendships").document(id).get().await()
        if (existing.exists()) return Result.failure(IllegalArgumentException("Sunteți deja prieteni."))
        db.collection("friendships").document(id).set(
            mapOf("members" to listOf(myUid, otherUid).sorted(), "since" to System.currentTimeMillis())
        ).await()
        val other = db.collection("users").document(otherUid).get().await()
        return Result.success(other.getString("name") ?: "Prieten nou")
    }

    suspend fun removeFriend(myUid: String, otherUid: String) {
        db.collection("friendships").document(friendshipId(myUid, otherUid)).delete().await()
    }

    /** Flow live cu prietenii + starea lor publicată. */
    fun friendsFlow(myUid: String): Flow<List<Friend>> = callbackFlow {
        var userRegs: List<ListenerRegistration> = emptyList()
        val cache = LinkedHashMap<String, Friend>()

        val friendshipsReg = db.collection("friendships")
            .whereArrayContains("members", myUid)
            .addSnapshotListener { snap, _ ->
                val uids = snap?.documents
                    ?.mapNotNull { d -> (d.get("members") as? List<*>)?.mapNotNull { it as? String } }
                    ?.flatten()?.filter { it != myUid }?.distinct()
                    ?: emptyList()
                userRegs.forEach { it.remove() }
                cache.keys.retainAll(uids.toSet())
                if (uids.isEmpty()) trySend(emptyList())
                userRegs = uids.map { uid ->
                    db.collection("users").document(uid).addSnapshotListener { u, _ ->
                        if (u != null && u.exists()) {
                            val ghostUntil = u.getLong("ghostUntil") ?: 0L
                            val ghost = ghostUntil == -1L || ghostUntil > System.currentTimeMillis()
                            cache[uid] = Friend(
                                uid = uid,
                                name = u.getString("name") ?: "Prieten",
                                state = if (ghost) "ghost" else (u.getString("state") ?: "idle"),
                                lat = if (ghost) null else u.getDouble("lat"),
                                lng = if (ghost) null else u.getDouble("lng"),
                                speedMps = u.getDouble("speedMps") ?: 0.0,
                                locUpdatedAt = u.getLong("locUpdatedAt") ?: 0L,
                                weekKm = u.getDouble("weekKm") ?: 0.0,
                                ghost = ghost,
                                lastActivityType = u.getString("lastActivityType"),
                                lastActivityKm = u.getDouble("lastActivityKm") ?: 0.0,
                                lastActivityAt = u.getLong("lastActivityAt") ?: 0L
                            )
                            trySend(cache.values.toList())
                        }
                    }
                }
            }
        awaitClose {
            friendshipsReg.remove()
            userRegs.forEach { it.remove() }
        }
    }

    /** Energie (kudos): un fulger per prieten per zi. Fără server de push — apare live când prietenul are FORJA deschis. */
    suspend fun sendEnergy(fromUid: String, fromName: String, toUid: String): Boolean {
        val day = LocalDate.now().toString()
        val id = "${toUid}_${day}_$fromUid"
        val doc = db.collection("energy").document(id).get().await()
        if (doc.exists()) return false
        db.collection("energy").document(id).set(
            mapOf("to" to toUid, "from" to fromUid, "fromName" to fromName, "day" to day, "at" to System.currentTimeMillis())
        ).await()
        return true
    }

    /** Ascultă energia primită azi — pentru toast „X ți-a trimis energie". */
    fun energyFlow(myUid: String): Flow<String> = callbackFlow {
        val day = LocalDate.now().toString()
        val startAt = System.currentTimeMillis()
        val reg = db.collection("energy")
            .whereEqualTo("to", myUid)
            .whereEqualTo("day", day)
            .addSnapshotListener { snap, _ ->
                snap?.documentChanges?.forEach { ch ->
                    if (ch.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                        val at = ch.document.getLong("at") ?: 0L
                        if (at >= startAt) {
                            val name = ch.document.getString("fromName") ?: "Un prieten"
                            trySend("$name ți-a trimis energie ⚡")
                        }
                    }
                }
            }
        awaitClose { reg.remove() }
    }

    /** Mod fantomă: 1h · până mâine 07:00 · nelimitat (-1) · oprit (0). */
    suspend fun setGhost(myUid: String, untilMillis: Long) {
        db.collection("users").document(myUid)
            .set(mapOf("ghostUntil" to untilMillis), SetOptions.merge()).await()
    }

    suspend fun publishWeekKm(myUid: String, km: Double) {
        db.collection("users").document(myUid)
            .set(mapOf("weekKm" to km), SetOptions.merge()).await()
    }
}
