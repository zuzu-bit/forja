package com.forja.app.core.social

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.forja.app.ForjaApp
import com.forja.app.MainActivity
import com.forja.app.core.network.InsightsApi
import com.forja.app.core.network.InsightsFailure
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.concurrent.TimeUnit

/** O potrivire din agendă, salvată local (Prefs.contactMatches). Numele e cel din agenda MEA; nu pleacă nicăieri. */
@Serializable
data class ContactMatch(
    val uid: String,
    /** Numele din agenda ta. */
    val name: String,
    /** Numele lui din FORJA (ce a publicat el). */
    val forjaName: String = "",
    /** Reciproc: și el te are în agendă → prietenie creată singură. */
    val mutual: Boolean,
    /** Numărul lui e verificat prin SMS pe site (altfel doar declarat). */
    val verified: Boolean,
    val at: Long
)

/** Lucrătorul periodic (24 h) sau la cerere: agenda → loturi ≤ 200 → /contacts/match → prietenii reciproce. */
class ContactsSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = ForjaApp.from(applicationContext)
        return when (ContactsSync.sync(app)) {
            ContactsSync.Outcome.DONE, ContactsSync.Outcome.SKIPPED, ContactsSync.Outcome.REJECTED -> Result.success()
            ContactsSync.Outcome.RETRY -> if (runAttemptCount < 4) Result.retry() else Result.failure()
        }
    }
}

/**
 * „Pot fi găsit după număr” oprit fără net: DELETE-ul listării de pe site se reia cu net, până reușește.
 * Se oprește singur dacă între timp ai pornit comutatorul la loc (listarea e din nou dorită) sau nu mai ai cont
 * (fără token nu există DELETE; site-ul uită listarea singur în cel mult 30 de zile).
 */
class ContactsUnregisterWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = ForjaApp.from(applicationContext)
        if (app.prefs.contactsOn.first()) return Result.success()
        if (app.auth.currentUid == null) return Result.failure()
        return try {
            InsightsApi.json(Discovery.PATH, null, method = "DELETE", headers = Discovery.headers(app))
            Result.success()
        } catch (e: InsightsFailure) {
            // 401 (token dispărut) sau alt 4xx nu se repară repetând.
            if (e.code in 400..499 && e.code != 408 && e.code != 429) Result.failure() else again()
        } catch (_: Exception) {
            again()
        }
    }

    private fun again(): Result = if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()

    private companion object { const val MAX_ATTEMPTS = 12 }
}

/**
 * „Prieteni din agendă” (ca la Telegram): comparăm numerele din agendă cu amprentele de pe site. Reciprocitatea
 * (eu îl am pe el, el mă are pe mine) creează prietenia singură; restul apar la „Din agendă” cu „Trimite-i codul tău”.
 * Numele și numerele NU se scriu pe server și nu apar în jurnale; pe server rămân doar amprente HMAC.
 */
object ContactsSync {
    enum class Outcome { DONE, SKIPPED, RETRY, REJECTED }

    const val WORK_PERIODIC = "contacts-sync"
    const val WORK_NOW = "contacts-sync-now"
    const val WORK_UNREGISTER = "contacts-unregister"
    private const val PATH_MATCH = "/v2/social/contacts/match"
    private const val BATCH = 200
    private const val PERIOD_H = 24L
    private const val NOTIF_BASE = 4200

    private val json = InsightsApi.json
    private val listSerializer = ListSerializer(ContactMatch.serializer())

    fun decode(raw: String): List<ContactMatch> =
        if (raw.isBlank()) emptyList() else try { json.decodeFromString(listSerializer, raw) } catch (_: Exception) { emptyList() }

    fun encode(list: List<ContactMatch>): String = json.encodeToString(listSerializer, list)

    private fun connected(): Constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Poate porni: comutator pornit, număr (declarat sau verificat), cont, permisiune. */
    suspend fun ready(app: ForjaApp): Boolean =
        app.prefs.contactsOn.first() && app.auth.currentUid != null && ContactsReader.granted(app) &&
            (PhoneNumbers.isValid(app.prefs.phoneDeclared.first()) || Discovery.verifiedPhone() != null)

    /** La fiecare ON_START: programează lucrătorul zilnic dacă e cazul; altfel anulează ce a rămas. Idempotent. */
    fun scheduleIfOn(context: Context) {
        val app = ForjaApp.from(context)
        app.appScope.launch {
            try {
                if (!ready(app)) { cancel(app); return@launch }
                // Comutatorul e pornit, deci listarea e dorită: nicio ștergere amânată nu mai are voie să ruleze.
                cancelUnregisterRetry(app)
                val req = PeriodicWorkRequestBuilder<ContactsSyncWorker>(PERIOD_H, TimeUnit.HOURS)
                    .setConstraints(connected())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                    .build()
                WorkManager.getInstance(app).enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
            } catch (_: Exception) { }
        }
    }

    /** O sincronizare acum (după „Gata” sau „Sincronizează acum”). */
    fun runNow(context: Context) {
        try {
            val req = OneTimeWorkRequestBuilder<ContactsSyncWorker>()
                .setConstraints(connected())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.REPLACE, req)
        } catch (_: Exception) { }
    }

    /** Anulează lucrătorii de sincronizare (nu și ștergerea amânată a listării — aceea rămâne până reușește). */
    fun cancel(context: Context) {
        try {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(WORK_PERIODIC)
            wm.cancelUniqueWork(WORK_NOW)
        } catch (_: Exception) { }
    }

    /** DELETE-ul listării se reia cu net (backoff exponențial), o singură coadă. */
    private fun scheduleUnregisterRetry(context: Context) {
        try {
            val req = OneTimeWorkRequestBuilder<ContactsUnregisterWorker>()
                .setConstraints(connected())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_UNREGISTER, ExistingWorkPolicy.REPLACE, req)
        } catch (_: Exception) { }
    }

    private fun cancelUnregisterRetry(context: Context) {
        try { WorkManager.getInstance(context).cancelUniqueWork(WORK_UNREGISTER) } catch (_: Exception) { }
    }

    /** Uită comutatorul, potrivirile, momentul și starea ultimei sincronizări (numărul rămâne — se șterge doar la ieșirea din cont). */
    private suspend fun forgetLocal(app: ForjaApp) {
        app.prefs.setContactsOn(false)
        app.prefs.setContactMatches("")
        app.prefs.setContactsSyncedAt(0L)
        app.prefs.setContactsStatus("")
    }

    /**
     * DELETE-ul listării, așteptat cel mult [maxWaitMs]: cererea continuă în fundal și după (pe site e idempotentă),
     * dar ecranul nu stă după o rețea care nu răspunde. false = neconfirmat.
     */
    private suspend fun unregisterWithin(app: ForjaApp, maxWaitMs: Long): Boolean {
        val delete = app.appScope.async { Discovery.unregister(app) }
        return withTimeoutOrNull(maxWaitMs) { delete.await() } ?: false
    }

    /**
     * Comutatorul închis: lucrătorul anulat, potrivirile șterse local, DELETE discovery pe site. Prieteniile deja făcute rămân.
     * Întoarce false când site-ul nu a confirmat ștergerea — atunci DELETE-ul se reia singur cu net ([ContactsUnregisterWorker])
     * și, oricum, listarea expiră pe site în cel mult 30 de zile. Apelantul spune adevărul în funcție de rezultat.
     */
    suspend fun disable(app: ForjaApp): Boolean {
        cancel(app)
        forgetLocal(app)
        val ok = unregisterWithin(app, 8_000L)
        if (ok) cancelUnregisterRetry(app) else scheduleUnregisterRetry(app)
        return ok
    }

    /**
     * La ieșirea din cont: DELETE pe site cât timp mai există token (așteptat cel mult 1,5 s — după signOut nu se mai
     * poate reîncerca, listarea expiră singură în 30 de zile), apoi totul uitat local — și numărul.
     */
    suspend fun logout(app: ForjaApp) {
        cancel(app)
        cancelUnregisterRetry(app)
        if (app.prefs.contactsOn.first()) unregisterWithin(app, 1_500L)
        forgetLocal(app)
        app.prefs.setPhoneDeclared("")
    }

    /**
     * Sincronizarea propriu-zisă. Fără READ_CONTACTS nu citește nimic; fără număr nu pornește.
     * Pentru `mutual`/`friend` creează prietenia Firestore (idempotent) și anunță o singură dată pe prieten NOU —
     * un prieten vechi regăsit în agendă primește doar eticheta „din agendă”, nu o notificare.
     */
    suspend fun sync(app: ForjaApp): Outcome {
        if (!app.prefs.contactsOn.first()) return Outcome.SKIPPED
        val uid = app.auth.currentUid ?: return Outcome.SKIPPED
        val headers = Discovery.headers(app)
        if (headers.isEmpty() && Discovery.verifiedPhone() == null) {
            app.prefs.setContactsStatus("Fără număr. Scrie-l în Echipare.")
            return Outcome.SKIPPED
        }
        if (!ContactsReader.granted(app)) {
            app.prefs.setContactsStatus("Agenda nu are permisiune. Bifează în Echipare.")
            return Outcome.SKIPPED
        }
        val own = setOfNotNull(app.prefs.phoneDeclared.first().takeIf { PhoneNumbers.isValid(it) }, Discovery.verifiedPhone())
        val contacts = ContactsReader.read(app).filter { it.number !in own }
        if (contacts.isEmpty()) {
            app.prefs.setContactsSyncedAt(System.currentTimeMillis())
            app.prefs.setContactsStatus("Agenda e goală sau fără numere valide.")
            return Outcome.DONE
        }
        val previous = decode(app.prefs.contactMatches.first()).associateBy { it.uid }
        val lastSync = app.prefs.contactsSyncedAt.first()
        val found = LinkedHashMap<String, ContactMatch>()
        val now = System.currentTimeMillis()
        var reregistered = false
        val batches = contacts.chunked(BATCH)
        var i = 0
        while (i < batches.size) {
            val batch = batches[i]
            val resp = try {
                post(batch, headers)
            } catch (e: InsightsFailure) {
                // Listarea a expirat sau a fost ștearsă (de pe site): o refacem o singură dată, apoi continuăm.
                if (e.code == 403 && !reregistered) {
                    reregistered = true
                    val r = Discovery.register(app)
                    if (r.isFailure) {
                        app.prefs.setContactsStatus(Discovery.humanError(r.exceptionOrNull() ?: e))
                        return Outcome.REJECTED
                    }
                    continue
                }
                app.prefs.setContactsStatus(e.message ?: "Serverul FORJA a răspuns cu ${e.code}.")
                return if (e.code in 400..499 && e.code != 408 && e.code != 429) Outcome.REJECTED else Outcome.RETRY
            } catch (_: Exception) {
                app.prefs.setContactsStatus("Fără conexiune. Reîncercăm mai târziu.")
                return Outcome.RETRY
            }
            for (element in resp["matches"]?.jsonArray.orEmpty()) {
                val o = element as? JsonObject ?: continue
                val otherUid = o["id"]?.jsonPrimitive?.contentOrNull ?: continue
                if (otherUid == uid) continue
                val index = o["index"]?.jsonPrimitive?.intOrNull ?: continue
                val entry = batch.getOrNull(index) ?: continue
                val friend = o["friend"]?.jsonPrimitive?.booleanOrNull ?: false
                val mutual = (o["mutual"]?.jsonPrimitive?.booleanOrNull ?: false) || friend
                found[otherUid] = ContactMatch(
                    uid = otherUid,
                    name = entry.name,
                    forjaName = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    mutual = mutual,
                    verified = o["verified"]?.jsonPrimitive?.booleanOrNull ?: false,
                    at = previous[otherUid]?.at ?: now
                )
            }
            i++
        }
        // Reciprocitate → prietenia în Firestore (ambii o văd prin friendsFlow), o notificare per prieten nou.
        // „Nou” = creată acum de mine, sau apărută (de la el) după ultima mea sincronizare. La prima sincronizare (sau
        // fără instantaneu) prieteniile deja existente sunt vechi: nimeni nu află că „X e pe FORJA” după luni de prietenie.
        val before = try { withTimeoutOrNull(5_000L) { app.friends.friendshipsSince(uid) } } catch (_: Exception) { null }
        for (m in found.values.filter { it.mutual }) {
            val created = try { app.friends.addFriendDirect(uid, m.uid) } catch (_: Exception) { false }
            val since = before?.get(m.uid)
            val appeared = before != null && (since == null || (lastSync > 0 && since > lastSync))
            if (previous[m.uid]?.mutual != true && (created || appeared)) notifyNewFriend(app, m)
        }
        app.prefs.setContactMatches(encode(found.values.toList()))
        app.prefs.setContactsSyncedAt(now)
        val mutualCount = found.values.count { it.mutual }
        app.prefs.setContactsStatus(
            when {
                found.isEmpty() -> "Niciun contact cu FORJA încă. Comparăm zilnic."
                else -> "${found.size} din agendă au FORJA · $mutualCount reciproc"
            }
        )
        return Outcome.DONE
    }

    private suspend fun post(batch: List<ContactEntry>, headers: Map<String, String>): JsonObject {
        val body = buildJsonObject {
            putJsonArray("numbers") { batch.forEach { add(it.number) } }
            put("consent", true)
        }
        return InsightsApi.json(PATH_MATCH, body, headers = headers)
    }

    /** „Ana din agenda ta e pe FORJA” — canalul „social”, o dată pe prieten. Fără număr în text. */
    private fun notifyNewFriend(context: Context, m: ContactMatch) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val first = m.name.trim().split(' ').firstOrNull { it.isNotBlank() } ?: "Un contact"
        val text = "$first din agenda ta e pe FORJA. Sunteți prieteni și vă vedeți pe hartă."
        val pi = PendingIntent.getActivity(
            context, 21, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, "social")
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .setContentTitle("Camarad nou")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try { NotificationManagerCompat.from(context).notify(NOTIF_BASE + (m.uid.hashCode() and 0x3FF), n) } catch (_: Exception) { }
    }
}
