package com.forja.app.core.recovery

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.forja.app.ForjaApp
import com.forja.app.R
import com.forja.app.core.data.Prefs
import com.forja.app.core.network.InsightsApi
import com.forja.app.core.network.InsightsFailure
import com.forja.app.core.sync.CollectionSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Înrolarea acestui telefon la găsire: contul, identitatea față de site și secretul (64 hex). */
data class RecoveryDevice(val owner: String, val id: String, val secret: String)

/**
 * „Telefonul meu” (4.4) — telefonul e ținta, nu căutătorul.
 *
 * Nu mai există buton, nume de scris sau bifă separată: cât e semnat contractul v3, telefonul se înrolează singur
 * ([ensureEnrolled], `basis: "contract"`) și își lasă pe site ultima poziție și bateria, la fiecare bătaie a serviciului
 * contractului ([Finder]). Căutarea și soneria se pornesc de pe site (`/insights#gasire`); telefonul le execută
 * ([LostPhoneService]) și arată atunci o notificare care nu se poate ascunde.
 *
 * Rute (worker-ul forja-insights, DESIGN-4.4 §3.4): /v2/recovery/devices/{id}/grant|beat|status|position|stop|revoke
 * (+ `poll` pentru serverele vechi, fără `lost_phone: 2` în /health). Serverul păstrează doar SHA-256 al secretului.
 */
object LostPhoneRecovery {
    /** Canalul notificărilor de căutare și sonerie — importanță mare (apare peste ecran), fără sunet propriu. */
    const val CHANNEL = "finder"
    /** Canalul vechi (4.0–4.3) al serviciului permanent; dispare la prima pornire a lui 4.4. */
    private const val OLD_CHANNEL = "recovery"

    // Fișierul și cheile rămân cele din versiunea online: telefoanele deja înrolate își păstrează identitatea și secretul.
    private const val PREF = "forja_lost_phone_v23"
    private const val ENROLL_RETRY_MS = 10 * 60_000L
    private const val HEALTH_TTL_MS = 6 * 3600_000L

    private val enrolling = Mutex()

    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** Contul conectat acum sau null. */
    fun owner(c: Context): String? = try {
        ForjaApp.from(c).auth.currentUid
    } catch (_: Exception) {
        try { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid } catch (_: Exception) { null }
    }

    /** Înrolarea activă a contului curent — null dacă telefonul nu e înrolat sau contul s-a schimbat. */
    fun device(c: Context): RecoveryDevice? {
        val p = prefs(c)
        val owner = p.getString("owner", null) ?: return null
        if (!p.getBoolean("enabled", false) || owner != owner(c)) return null
        val id = p.getString("id", null) ?: return null
        val secret = p.getString("secret", null) ?: return null
        return RecoveryDevice(owner, id, secret)
    }

    fun enabled(c: Context): Boolean = device(c) != null

    /** Scos de pe site („Scoate telefonul”): nu se mai înrolează singur până la „Probă” sau o semnătură nouă. */
    fun removed(c: Context): Boolean = prefs(c).getBoolean("removed", false)

    /** Numele sub care apare pe site (se schimbă de pe site): numele dat telefonului în Android, altfel marca și modelul. */
    fun defaultName(c: Context? = null): String {
        val named = try {
            c?.let { Settings.Global.getString(it.contentResolver, Settings.Global.DEVICE_NAME) }
        } catch (_: Exception) { null }
        val fallback = listOf(Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }, Build.MODEL.orEmpty())
            .filter { it.isNotBlank() }.joinToString(" ")
        return (named?.takeIf { it.isNotBlank() } ?: fallback).trim().ifBlank { "Telefonul meu" }.take(40)
    }

    fun exactPermission(c: Context): Boolean =
        ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun locationPermission(c: Context): Boolean = exactPermission(c) ||
        ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun backgroundPermission(c: Context): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Notificările FORJA sunt permise în Android (permisiunea pe 13+ și comutatorul aplicației). */
    fun appNotices(c: Context): Boolean {
        val m = c.getSystemService(NotificationManager::class.java) ?: return false
        val permitted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return permitted && m.areNotificationsEnabled()
    }

    /** Canalul găsirii nu e mut. */
    fun channelOn(c: Context): Boolean {
        val m = c.getSystemService(NotificationManager::class.java) ?: return false
        return m.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    /**
     * Telefonul poate arăta notificarea de căutare. Fără ea nu pornește nicio urmărire (cine ține telefonul trebuie
     * s-o vadă); soneria se aude oricum. Un canal mut NU mai oprește găsirea: site-ul primește `notification_missing`.
     */
    fun notices(c: Context): Boolean = appNotices(c) && channelOn(c)

    /** Creează canalul găsirii (idempotent) și șterge canalul permanent din versiunile vechi. */
    fun ensureChannel(c: Context) {
        try {
            val m = c.getSystemService(NotificationManager::class.java) ?: return
            if (m.getNotificationChannel(CHANNEL) == null) {
                m.createNotificationChannel(
                    NotificationChannel(CHANNEL, c.getString(R.string.notif_channel_finder), NotificationManager.IMPORTANCE_HIGH).apply {
                        // Soneria o pune FinderRinger (canalul de alarmă), nu notificarea.
                        setSound(null, null)
                        enableVibration(false)
                        lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    }
                )
            }
            if (m.getNotificationChannel(OLD_CHANNEL) != null) m.deleteNotificationChannel(OLD_CHANNEL)
        } catch (_: Exception) { }
    }

    /** Un apel către site pentru acest telefon; refuză dacă înrolarea s-a schimbat între timp. */
    suspend fun call(c: Context, d: RecoveryDevice, path: String, body: JsonObject): JsonObject {
        check(device(c) == d) { "Găsirea s-a oprit sau contul s-a schimbat." }
        val result = InsightsApi.json("/v2/recovery/devices/${d.id}/$path", body, "POST")
        check(device(c) == d) { "Găsirea s-a oprit." }
        return result
    }

    /**
     * Versiunea găsirii pe server (`lost_phone` din /health, ținută 6 h). 2 = bătăi, sonerie, înrolare prin contract;
     * 1 = serverul vechi (poll + consimțământ separat). Fără răspuns: ultima valoare știută, altfel 2.
     */
    suspend fun serverVersion(c: Context, fresh: Boolean = false): Int {
        val p = prefs(c)
        val known = p.getInt("health_v", 0)
        if (!fresh && known > 0 && System.currentTimeMillis() - p.getLong("health_at", 0) < HEALTH_TTL_MS) return known
        val got = try {
            withTimeout(10_000) { InsightsApi.json("/health") }["lost_phone"]?.jsonPrimitive?.intOrNull
        } catch (e: CancellationException) {
            if (e is kotlinx.coroutines.TimeoutCancellationException) null else throw e
        } catch (_: Exception) { null }
        if (got != null) {
            p.edit().putInt("health_v", got).putLong("health_at", System.currentTimeMillis()).apply()
            return got
        }
        return if (known > 0) known else 2
    }

    /**
     * Înrolarea automată, sub contractul v3: idempotentă (refolosește identitatea și secretul existente), fără nimic
     * de apăsat. `force` = cerută de utilizator („Probă”): trece peste „scos de pe site” și peste pauza de reîncercare.
     */
    suspend fun ensureEnrolled(c: Context, force: Boolean = false): RecoveryDevice? = enrolling.withLock {
        device(c)?.let { return@withLock it }
        if (!CollectionSettings.contractOn(c)) return@withLock null
        val owner = owner(c) ?: return@withLock null
        val p = prefs(c)
        val now = System.currentTimeMillis()
        if (!force && (removed(c) || now - p.getLong("enroll_try_at", 0) < ENROLL_RETRY_MS)) return@withLock null
        p.edit().putLong("enroll_try_at", now).apply()

        // Același cont și o identitate rămasă (înrolare 4.0–4.3 sau o pauză): o refolosim, serverul o recunoaște după secret.
        val reuse = p.getString("owner", null) == owner && p.getString("id", null) != null && p.getString("secret", null) != null
        val id = if (reuse) p.getString("id", null)!! else UUID.randomUUID().toString()
        val secret = if (reuse) p.getString("secret", null)!! else
            ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val name = p.getString("name", null)?.takeIf { it.isNotBlank() } ?: defaultName(c)
        val body = if (serverVersion(c) >= 2) buildJsonObject {
            put("name", name)
            put("secret", secret)
            put("basis", "contract")
            put("contract_version", Prefs.CONTRACT_VERSION)
        } else buildJsonObject {
            // Serverul vechi cere consimțământul explicit; contractul v3 e acel consimțământ.
            put("name", name)
            put("secret", secret)
            put("consent", true)
        }
        try {
            withTimeout(30_000) { InsightsApi.json("/v2/recovery/devices/$id/grant", body, "POST") }
        } catch (e: InsightsFailure) {
            // 409: altă activare pe același id (sau cinci telefoane) — data viitoare pornim cu o identitate nouă.
            if (e.code == 409 && reuse) p.edit().remove("id").remove("secret").apply()
            p.edit().putString("error", e.message).apply()
            return@withLock null
        } catch (e: CancellationException) {
            if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            p.edit().putString("error", "Site-ul nu a răspuns.").apply()
            return@withLock null
        } catch (_: Exception) {
            p.edit().putString("error", "Fără internet.").apply()
            return@withLock null
        }
        // Între timp contul s-a schimbat sau contractul s-a revocat: nu lăsăm o înrolare fără stăpân pe site.
        if (owner(c) != owner || !CollectionSettings.contractOn(c)) {
            queue(c, RecoveryDevice(owner, id, secret), "revoke", null)
            return@withLock null
        }
        p.edit()
            .putString("owner", owner)
            .putString("id", id)
            .putString("secret", secret)
            .putString("name", name)
            .putBoolean("enabled", true)
            .remove("removed").remove("error").remove("command")
            .commit()
        device(c)
    }

    /**
     * La deschiderea aplicației (ON_START): canalul găsirii există, canalul vechi dispare; dacă e semnat contractul
     * și telefonul nu e încă înrolat (a semnat înainte de 4.4), se înrolează acum.
     */
    fun onAppStart(c: Context) {
        ensureChannel(c)
        val app = c.applicationContext as? ForjaApp ?: return
        if (!CollectionSettings.contractOn(c) || enabled(c)) return
        app.appScope.launch { try { ensureEnrolled(app) } catch (_: Exception) { } }
    }

    /** Site-ul nu mai știe telefonul (404) sau are altă activare (403): uităm înrolarea și nu revenim singuri. */
    internal fun markRemoved(c: Context) {
        prefs(c).edit().putBoolean("enabled", false).putBoolean("removed", true).remove("id").remove("secret")
            .remove("command").apply()
    }

    /** Uită înrolarea local (fără apel la site). */
    fun clear(c: Context) {
        prefs(c).edit().putBoolean("enabled", false).remove("id").remove("secret").remove("command")
            .remove("beat_ok_at").remove("beat_at").remove("battery").remove("error").remove("sent_fix_at")
            .remove("handled").commit()
    }

    /** Contract revocat: oprește tot pe loc și scoate telefonul de pe site (cu reîncercare, cât mai e contul). */
    fun disable(c: Context) {
        val d = device(c)
        Finder.disarm(c)
        LostPhoneService.end(c)
        clear(c)
        prefs(c).edit().remove("removed").apply()
        if (d != null) queue(c, d, "revoke", null)
    }

    /**
     * Ieșirea din cont (se apelează ÎNAINTE de signOut): revocarea pleacă acum, cât mai există token (cel mult 1,5 s).
     * Fără răspuns, înregistrarea de pe site expiră singură în 30 de zile.
     */
    suspend fun logout(c: Context) {
        val d = device(c)
        Finder.disarm(c)
        LostPhoneService.end(c)
        if (d != null) {
            try {
                withTimeout(1_500) {
                    InsightsApi.json("/v2/recovery/devices/${d.id}/revoke", buildJsonObject { put("secret", d.secret) }, "POST")
                }
            } catch (e: CancellationException) {
                if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            } catch (_: Exception) { }
        }
        clear(c)
        prefs(c).edit().remove("owner").remove("name").remove("removed").remove("enroll_try_at").apply()
    }

    /** Oprire/revocare trimisă la site cu reîncercare (WorkManager). */
    internal fun queue(c: Context, d: RecoveryDevice, action: String, command: String?) {
        try {
            val request = OneTimeWorkRequestBuilder<RecoveryRevokeWorker>()
                .setInputData(
                    workDataOf(
                        "owner" to d.owner, "id" to d.id, "secret" to d.secret,
                        "action" to action, "command" to command
                    )
                )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(c).enqueueUniqueWork(
                "recovery-${d.id}-$action-${command.orEmpty()}", ExistingWorkPolicy.KEEP, request
            )
        } catch (_: Exception) { }
    }
}

/** Revocare / oprire trimisă la site cu reîncercare; 401/403/404/409 înseamnă „deja făcut” — nu mai insistăm. */
class RecoveryRevokeWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        val uid = inputData.getString("owner") ?: return Result.failure()
        if (uid != LostPhoneRecovery.owner(applicationContext)) return Result.success()
        val id = inputData.getString("id") ?: return Result.failure()
        val secret = inputData.getString("secret") ?: return Result.failure()
        val action = inputData.getString("action") ?: return Result.failure()
        val command = inputData.getString("command")
        // „stop” fără comandă nu are ce opri (serverul ar răspunde 400 la nesfârșit).
        if (action == "stop" && command == null) return Result.failure()
        val body = buildJsonObject {
            put("secret", secret)
            if (action == "stop") put("command", command)
        }
        return try {
            withTimeout(60_000) { InsightsApi.json("/v2/recovery/devices/$id/$action", body, "POST") }
            Result.success()
        } catch (e: InsightsFailure) {
            when (e.code) {
                401, 403, 404, 409 -> Result.success()   // deja făcut / înrolare schimbată
                400 -> Result.failure()                  // cerere respinsă definitiv — reîncercarea nu ajută
                else -> Result.retry()
            }
        } catch (e: CancellationException) {
            if (e is kotlinx.coroutines.TimeoutCancellationException) Result.retry() else throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
