package com.forja.app.core.data

import android.os.Handler
import android.os.Looper
import com.forja.app.core.data.db.PlaceEntity
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
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
    val lastActivityAt: Long = 0L,
    /** E în familia mea: mă vede și când sunt fantomă (users/{me}.familyUids). */
    val family: Boolean = false,
    /** Poziția vine din familyLoc — prietenul e fantomă pentru ceilalți, dar m-a pus în familia lui. */
    val viaFamily: Boolean = false,
    /** Teritorii cucerite (users/{uid}.exploreCells) — pentru „Loc #k între prieteni”. */
    val exploreCells: Int = 0,
    /** Locuri cucerite (users/{uid}.placesCount). */
    val placesCount: Int = 0,
    /** Fotografia de profil (users/{uid}.photoUrl), dacă și-a pus una; altfel inițiale. */
    val photoUrl: String? = null,
    /** A venit din agendă (potrivire reciprocă a numerelor) — etichetă „din agendă”; se completează din Prefs.contactMatches. */
    val fromContacts: Boolean = false,
    /** Ce ascultă acum, „Titlu · Artist” (users/{uid}.nowPlaying), doar proaspăt (< 10 min) și niciodată în fantomă. */
    val nowPlaying: String? = null,
    /** Momentul publicării lui [nowPlaying] — ca ecranul să-l stingă singur după 10 minute. */
    val nowPlayingAt: Long = 0L
)

/** O melodie publicată e „acum” 10 minute; după aceea nu mai apare (MusicPresence o reîmprospătează la 5 min). */
const val NOW_PLAYING_FRESH_MS = 10 * 60_000L

/** „Titlu · Artist” cât timp e proaspăt; null altfel. */
fun Friend.listening(now: Long = System.currentTimeMillis()): String? =
    nowPlaying?.takeIf { now - nowPlayingAt < NOW_PLAYING_FRESH_MS }

/** Poziția unui prieten care m-a pus în familie — scrisă mereu, și în fantomă. */
data class FamilyLoc(
    val uid: String,
    val lat: Double,
    val lng: Double,
    val speedMps: Double,
    val state: String,
    val locUpdatedAt: Long
)

/** Un loc recomandat de un prieten (places/{id}, vizibil doar prietenilor lui). */
data class RecommendedPlace(
    val id: String,
    val ownerUid: String,
    val ownerName: String,
    val lat: Double,
    val lng: Double,
    val name: String,
    val stars: Int,
    val note: String,
    val at: Long,
    /** De câte ori a fost proprietarul aici (places/{id}.visits); 0 = necunoscut. */
    val visits: Int = 0
)

class FriendsRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    private fun friendshipId(a: String, b: String) = listOf(a, b).sorted().joinToString("_")

    private companion object {
        /** Cererile din agendă (4.4): `friendRequests/{de la}_{către}`. */
        const val REQUESTS = "friendRequests"
    }

    /**
     * Adaugă un prieten prin codul lui de invitație. Prietenia e reciprocă, imediată. Codul intră în document ca dovadă:
     * regulile din 4.4 cer ca el să fie codul din profilul lui de acum (cu regulile vechi e doar un câmp în plus).
     */
    suspend fun addFriendByCode(myUid: String, code: String): Result<String> {
        val clean = code.trim().uppercase().removePrefix("FORJA-")
        if (clean.length < 4) return Result.failure(IllegalArgumentException("Codul e prea scurt."))
        val inv = db.collection("inviteCodes").document(clean).get().await()
        val otherUid = inv.getString("uid")
            ?: return Result.failure(IllegalArgumentException("Cod necunoscut. Verifică-l cu prietenul tău."))
        if (otherUid == myUid) return Result.failure(IllegalArgumentException("Acesta e chiar codul tău."))
        if (!createFriendship(myUid, otherUid, clean)) {
            // Refuzat: fie prietenia există deja, fie codul nu mai e al lui (și-a primit unul nou).
            val exists = friendshipExists(myUid, otherUid)
            return Result.failure(
                IllegalArgumentException(if (exists == false) "Codul s-a schimbat. Cere-i codul nou." else "Sunteți deja prieteni.")
            )
        }
        val other = db.collection("users").document(otherUid).get().await()
        return Result.success(other.getString("name") ?: "Prieten nou")
    }

    /**
     * Prietenie directă, fără cod: potrivire reciprocă din agendă. Idempotentă. Întoarce true când a fost creată acum.
     *
     * Regulile din 4.4 cer dovada că și el vrea: cererea lui, `friendRequests/{el}_{eu}`, scrisă de telefonul lui la
     * aceeași potrivire. Deci: încercăm prietenia; dacă e refuzată și nu există încă, lăsăm cererea noastră, iar
     * telefonul lui o face la următoarea comparare a agendei (vezi [hasContactRequestSince]). Cu regulile vechi
     * prietenia se creează din prima, iar cererea (refuzată acolo) nici nu mai e nevoie.
     */
    suspend fun addFriendDirect(myUid: String, otherUid: String): Boolean {
        if (otherUid.isBlank() || otherUid == myUid) return false
        if (createFriendship(myUid, otherUid, null)) {
            forgetRequests(myUid, otherUid)
            return true
        }
        if (friendshipExists(myUid, otherUid) != true) requestFriendship(myUid, otherUid)
        return false
    }

    /**
     * Scrie `friendships/{a_b}` FĂRĂ să citească înainte: regulile refuză citirea unui document inexistent
     * (`resource` e null în `request.auth.uid in resource.data.members`), deci un `get()` ar da PERMISSION_DENIED
     * tocmai pentru prieteniile noi. Regulile permit `create` membrilor (cu dovadă) și interzic `update`, așa că
     * PERMISSION_DENIED la scriere înseamnă „există deja” sau „fără dovadă”. Întoarce true când a fost creată acum.
     */
    private suspend fun createFriendship(myUid: String, otherUid: String, code: String?): Boolean = try {
        val data = HashMap<String, Any>()
        data["members"] = listOf(myUid, otherUid).sorted()
        data["since"] = System.currentTimeMillis()
        if (!code.isNullOrBlank()) data["code"] = code
        db.collection("friendships").document(friendshipId(myUid, otherUid)).set(data).await()
        true
    } catch (e: FirebaseFirestoreException) {
        if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) false else throw e
    }

    /**
     * Există prietenia? Citirea merge doar când există (ești membru); lipsa dă PERMISSION_DENIED → false.
     * null = nu știm (fără net).
     */
    private suspend fun friendshipExists(myUid: String, otherUid: String): Boolean? = try {
        withTimeoutOrNull(5_000L) {
            db.collection("friendships").document(friendshipId(myUid, otherUid)).get().await().exists()
        }
    } catch (e: FirebaseFirestoreException) {
        if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) false else null
    } catch (_: Exception) {
        null
    }

    /** `friendRequests/{eu}_{el}`, cu ora serverului (regulile o cer; o cerere mai veche de 30 de zile nu mai contează). */
    private suspend fun requestFriendship(myUid: String, otherUid: String) {
        try {
            withTimeoutOrNull(5_000L) {
                db.collection(REQUESTS).document("${myUid}_$otherUid")
                    .set(mapOf("from" to myUid, "to" to otherUid, "at" to FieldValue.serverTimestamp()))
                    .await()
            }
        } catch (_: Exception) { /* regulile vechi nu cunosc cererile: acolo prietenia nici nu are nevoie de ele */ }
    }

    /** După prietenie, cererile nu mai au rost. Fără așteptare; eșecul nu contează. */
    private fun forgetRequests(myUid: String, otherUid: String) {
        try {
            db.collection(REQUESTS).document("${otherUid}_$myUid").delete()
            db.collection(REQUESTS).document("${myUid}_$otherUid").delete()
        } catch (_: Exception) { }
    }

    /**
     * Cineva din agenda mea a cerut prietenia după [sinceMs] (a intrat pe FORJA după ultima mea comparare)? Atunci
     * agenda se compară acum, nu la rularea zilnică, și prietenia se face azi. Cu regulile vechi: false.
     */
    suspend fun hasContactRequestSince(myUid: String, sinceMs: Long): Boolean = try {
        val snap = withTimeoutOrNull(5_000L) {
            db.collection(REQUESTS).whereEqualTo("to", myUid).limit(20).get().await()
        }
        snap?.documents?.any { (it.getTimestamp("at")?.toDate()?.time ?: 0L) > sinceMs } == true
    } catch (_: Exception) {
        false
    }

    /**
     * Prieteniile mele acum, dintr-o singură interogare completă: celălalt uid → `since` (0 dacă lipsește).
     * Pentru agendă: ca să anunțăm doar prieteniile cu adevărat noi, nu prietenii vechi regăsiți în agendă.
     */
    suspend fun friendshipsSince(myUid: String): Map<String, Long> {
        val snap = db.collection("friendships").whereArrayContains("members", myUid).get().await()
        val out = HashMap<String, Long>()
        for (d in snap.documents) {
            val members = (d.get("members") as? List<*>)?.mapNotNull { it as? String } ?: continue
            val since = d.getLong("since") ?: 0L
            for (m in members) if (m != myUid) out[m] = since
        }
        return out
    }

    suspend fun removeFriend(myUid: String, otherUid: String) {
        db.collection("friendships").document(friendshipId(myUid, otherUid)).delete().await()
    }

    /**
     * Flow live cu prietenii + starea lor publicată (+ steagul „familie” din documentul meu).
     *
     * Regulile noi deschid profilul unui prieten abia după ce prietenia e pe server (`exists()` acolo): o prietenie
     * creată chiar acum apare întâi din cache-ul local, cu scriere în așteptare, iar un ascultător de profil pornit atunci
     * ar fi refuzat și oprit de SDK. De aceea: ascultăm și schimbările de metadate, luăm doar prieteniile confirmate,
     * păstrăm ascultătorii existenți (doar diferența se schimbă) și refacem o dată, după 3 s și la următorul eveniment al
     * prieteniilor, un profil refuzat (și unul care nu există încă). Toate se ating pe firul principal, unde Firestore
     * livrează ascultătorii.
     */
    fun friendsFlow(myUid: String): Flow<List<Friend>> = callbackFlow {
        val userRegs = HashMap<String, ListenerRegistration>()
        val denied = HashSet<String>()
        val main = Handler(Looper.getMainLooper())
        val cache = LinkedHashMap<String, Friend>()
        var myFamily: Set<String> = emptySet()

        // Familia mea — ca fiecare prieten să știe dacă mă vede și în fantomă.
        val meReg = db.collection("users").document(myUid).addSnapshotListener { me, _ ->
            val fam = (me?.get("familyUids") as? List<*>)?.mapNotNull { it as? String }?.toSet() ?: emptySet()
            if (fam != myFamily) {
                myFamily = fam
                if (cache.isNotEmpty()) {
                    for (k in cache.keys.toList()) {
                        cache[k]?.let { cache[k] = it.copy(family = k in myFamily) }
                    }
                    trySend(cache.values.toList())
                }
            }
        }

        fun listen(uid: String) {
            userRegs[uid] = db.collection("users").document(uid).addSnapshotListener { u, e ->
                if (e != null) {
                    // Refuzat (prietenie încă neconfirmată, profil încă necreat): SDK-ul a oprit ascultătorul.
                    if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED && userRegs.containsKey(uid) && denied.add(uid)) {
                        main.postDelayed({
                            if (uid in denied && userRegs.containsKey(uid)) { userRegs.remove(uid)?.remove(); listen(uid) }
                        }, 3_000L)
                    }
                    return@addSnapshotListener
                }
                if (u != null && u.exists()) {
                    denied.remove(uid)
                    val ghostUntil = u.getLong("ghostUntil") ?: 0L
                    val ghost = ghostUntil == -1L || ghostUntil > System.currentTimeMillis()
                    // Ce ascultă: doar titlul și artistul, doar proaspăt, niciodată în fantomă.
                    val np = u.get("nowPlaying") as? Map<*, *>
                    val npTitle = (np?.get("title") as? String)?.trim().orEmpty()
                    val npArtist = (np?.get("artist") as? String)?.trim().orEmpty()
                    val npAt = (np?.get("at") as? Number)?.toLong() ?: 0L
                    val npFresh = !ghost && npTitle.isNotEmpty() &&
                        System.currentTimeMillis() - npAt < NOW_PLAYING_FRESH_MS
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
                        lastActivityAt = u.getLong("lastActivityAt") ?: 0L,
                        family = uid in myFamily,
                        exploreCells = (u.getLong("exploreCells") ?: 0L).toInt(),
                        placesCount = (u.getLong("placesCount") ?: 0L).toInt(),
                        photoUrl = u.getString("photoUrl")?.takeIf { it.isNotBlank() },
                        nowPlaying = if (npFresh) (if (npArtist.isNotEmpty()) "$npTitle · $npArtist" else npTitle) else null,
                        nowPlayingAt = if (npFresh) npAt else 0L
                    )
                    trySend(cache.values.toList())
                }
            }
        }

        val friendshipsReg = db.collection("friendships")
            .whereArrayContains("members", myUid)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snap, _ ->
                // Doar prieteniile ajunse pe server: pentru una încă în așteptare, regulile refuză profilul.
                val uids = snap?.documents
                    ?.filter { !it.metadata.hasPendingWrites() }
                    ?.mapNotNull { d -> (d.get("members") as? List<*>)?.mapNotNull { it as? String } }
                    ?.flatten()?.filter { it != myUid }?.toSet()
                    ?: emptySet()
                for (gone in userRegs.keys - uids) { userRegs.remove(gone)?.remove(); denied.remove(gone) }
                val before = cache.size
                cache.keys.retainAll(uids)
                for (uid in uids) {
                    when {
                        uid !in userRegs -> listen(uid)
                        // Refuzat mai devreme: prietenia s-a confirmat între timp sau profilul a apărut.
                        uid in denied -> { userRegs.remove(uid)?.remove(); listen(uid) }
                    }
                }
                if (uids.isEmpty()) trySend(emptyList())
                else if (cache.size != before) trySend(cache.values.toList())
            }
        awaitClose {
            meReg.remove()
            friendshipsReg.remove()
            main.removeCallbacksAndMessages(null)
            val regs = userRegs.values.toList()
            userRegs.clear()
            regs.forEach { it.remove() }
        }
    }

    /** Energie (kudos): un fulger per prieten per zi. Fără server de push — apare live când prietenul are FORJA deschis. */
    suspend fun sendEnergy(fromUid: String, fromName: String, toUid: String): Boolean {
        val day = LocalDate.now().toString()
        val id = "${toUid}_${day}_$fromUid"
        // Fără citire înainte: regulile de dinainte de 4.4 refuză citirea documentului lipsă, deci fulgerul nu pleca
        // niciodată. Scrierea directă merge cu ambele: a doua oară azi e un `update`, refuzat → „deja trimis”.
        return try {
            // Fără net scrierea rămâne în coadă și pleacă singură; nu ținem ecranul în așteptare.
            withTimeoutOrNull(8_000L) {
                db.collection("energy").document(id).set(
                    mapOf("to" to toUid, "from" to fromUid, "fromName" to fromName, "day" to day, "at" to System.currentTimeMillis())
                ).await()
            }
            true
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) false else throw e
        }
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

    // ── Familie: cei care te văd și în fantomă ──

    /**
     * Pune/scoate un prieten din familie (users/{me}.familyUids, arrayUnion/arrayRemove).
     * Dacă primește `prefs`, oglindește și local (publicatorii citesc de acolo, fără Firestore).
     * Returnează setul rezultat.
     */
    suspend fun setFamily(myUid: String, otherUid: String, on: Boolean, prefs: Prefs? = null): Set<String> {
        val op = if (on) FieldValue.arrayUnion(otherUid) else FieldValue.arrayRemove(otherUid)
        db.collection("users").document(myUid)
            .set(mapOf("familyUids" to op), SetOptions.merge()).await()
        val doc = db.collection("users").document(myUid).get().await()
        val result = (doc.get("familyUids") as? List<*>)?.mapNotNull { it as? String }?.toSet()
            ?: (if (on) setOf(otherUid) else emptySet())
        try { prefs?.setFamilyUids(result) } catch (_: Exception) { }
        syncFamilyAllowed(myUid, result)
        return result
    }

    /**
     * familyLoc/{me} urmează familia imediat, nu la următoarea poziție: fără nimeni în familie documentul se șterge (altfel
     * ultimul punct rămânea citibil de cel scos și site-ul îl arăta „Te au în familia lor”); altfel `allowed` = setul nou.
     */
    suspend fun syncFamilyAllowed(myUid: String, family: Set<String>) {
        val ref = db.collection("familyLoc").document(myUid)
        try {
            withTimeoutOrNull(8_000L) {
                if (family.isEmpty()) ref.delete().await() else ref.update("allowed", family.toList()).await()
            }
        } catch (_: Exception) { }   // update pe un document lipsă: nu era nimic de ascuns
    }

    /** Pozițiile prietenilor care m-au pus în familia lor — vin și când ei sunt fantomă. */
    fun familyLocFlow(myUid: String): Flow<Map<String, FamilyLoc>> = callbackFlow {
        val reg = db.collection("familyLoc")
            .whereArrayContains("allowed", myUid)
            .addSnapshotListener { snap, _ ->
                if (snap == null) return@addSnapshotListener
                val out = LinkedHashMap<String, FamilyLoc>()
                for (d in snap.documents) {
                    val lat = d.getDouble("lat") ?: continue
                    val lng = d.getDouble("lng") ?: continue
                    out[d.id] = FamilyLoc(
                        uid = d.id,
                        lat = lat, lng = lng,
                        speedMps = d.getDouble("speedMps") ?: 0.0,
                        state = d.getString("state") ?: "idle",
                        locUpdatedAt = d.getLong("locUpdatedAt") ?: 0L
                    )
                }
                trySend(out)
            }
        awaitClose { reg.remove() }
    }

    /**
     * Scrie familyLoc/{uid} — MEREU când familia nu e goală, inclusiv în fantomă.
     * Fire-and-forget: cache-ul Firestore o livrează și fără net.
     */
    fun writeFamilyLoc(
        uid: String, lat: Double, lng: Double, speedMps: Double, state: String,
        allowed: Collection<String>, at: Long = System.currentTimeMillis()
    ) {
        if (allowed.isEmpty()) return
        try {
            db.collection("familyLoc").document(uid).set(
                mapOf(
                    "lat" to lat, "lng" to lng,
                    "speedMps" to speedMps, "state" to state,
                    "locUpdatedAt" to at,
                    "allowed" to allowed.toList()
                )
            )
        } catch (_: Exception) { }
    }

    // ── Locuri recomandate prietenilor ──

    /**
     * Publică un loc al meu către toți prietenii (places/{id}, visibleTo = prietenii de acum).
     * Reutilizează documentul dacă locul a mai fost recomandat. Returnează id-ul.
     */
    suspend fun recommendPlace(myUid: String, myName: String, place: PlaceEntity, friendUids: List<String>): String {
        val ref = place.remoteId?.takeIf { it.isNotBlank() }?.let { db.collection("places").document(it) }
            ?: db.collection("places").document()
        ref.set(
            mapOf(
                "ownerUid" to myUid,
                "ownerName" to myName,
                "lat" to place.lat, "lng" to place.lng,
                "name" to place.name.trim().take(80),
                "stars" to place.stars.coerceIn(1, 5),
                "note" to place.note.trim().take(300),
                "at" to System.currentTimeMillis(),
                "visits" to place.visits.coerceAtLeast(1),
                "visibleTo" to friendUids.distinct().filter { it != myUid }.take(100)
            )
        ).await()
        return ref.id
    }

    /** Locurile recomandate mie de prieteni — live. */
    fun recommendedPlacesFlow(myUid: String): Flow<List<RecommendedPlace>> = callbackFlow {
        val reg = db.collection("places")
            .whereArrayContains("visibleTo", myUid)
            .addSnapshotListener { snap, _ ->
                if (snap == null) return@addSnapshotListener
                val list = snap.documents.mapNotNull { d ->
                    val lat = d.getDouble("lat") ?: return@mapNotNull null
                    val lng = d.getDouble("lng") ?: return@mapNotNull null
                    val owner = d.getString("ownerUid") ?: return@mapNotNull null
                    if (owner == myUid) return@mapNotNull null
                    RecommendedPlace(
                        id = d.id,
                        ownerUid = owner,
                        ownerName = d.getString("ownerName") ?: "Un prieten",
                        lat = lat, lng = lng,
                        name = d.getString("name") ?: "",
                        stars = (d.getLong("stars") ?: 0L).toInt(),
                        note = d.getString("note") ?: "",
                        at = d.getLong("at") ?: 0L,
                        visits = (d.getLong("visits") ?: 0L).toInt()
                    )
                }.sortedByDescending { it.at }
                trySend(list)
            }
        awaitClose { reg.remove() }
    }
}
