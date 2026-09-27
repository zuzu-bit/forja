package com.forja.app.core.recovery

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
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
import com.forja.app.core.network.InsightsApi
import com.forja.app.core.network.InsightsFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Înrolarea acestui telefon la găsire: contul care a activat-o, identitatea față de site și secretul (64 hex). */
data class RecoveryDevice(val owner: String, val id: String, val secret: String)

/**
 * „Telefonul meu” — găsirea telefonului pierdut din panoul online, cu același cont.
 *
 * Opt-in explicit: nimic nu pornește fără bifa de consimțământ și butonul „Activează găsirea”.
 * Telefonul răspunde doar unei comenzi date de tine din panou (5, 15 sau 30 de minute), apoi tace.
 * Pleacă de pe telefon doar poziția GPS, precizia și bateria — și doar cât durează o căutare.
 *
 * Rute (worker-ul forja-insights): /v2/recovery/devices/{id}/grant|poll|status|position|stop|revoke.
 * Serverul păstrează doar SHA-256 al secretului.
 */
object LostPhoneRecovery {
    /** Canalul de notificări al serviciului — creat de serviciu, nu de ForjaApp. */
    const val CHANNEL = "recovery"

    // Fișierul și cheile rămân cele din versiunea online (telefoanele deja înrolate își păstrează alegerea).
    private const val PREF = "forja_lost_phone_v23"

    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** Contul conectat acum (AuthRepository.currentUid) sau null. */
    fun owner(c: Context): String? = try {
        ForjaApp.from(c).auth.currentUid
    } catch (_: Exception) {
        com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
    }

    /** Înrolarea activă — null dacă găsirea e oprită sau contul s-a schimbat. */
    fun device(c: Context): RecoveryDevice? {
        val p = prefs(c)
        val owner = p.getString("owner", null) ?: return null
        if (!p.getBoolean("enabled", false) || owner != owner(c)) return null
        val id = p.getString("id", null) ?: return null
        val secret = p.getString("secret", null) ?: return null
        return RecoveryDevice(owner, id, secret)
    }

    fun enabled(c: Context): Boolean = device(c) != null

    /** Numele sub care apare telefonul în panou (implicit producător + model). */
    fun name(c: Context): String =
        prefs(c).getString("name", null)?.takeIf { it.isNotBlank() } ?: defaultName()

    fun defaultName(): String = (Build.MANUFACTURER.orEmpty() + " " + Build.MODEL.orEmpty()).trim().take(60)

    /** Ultima stare scrisă de serviciu (o propoziție), sau gol. */
    fun status(c: Context): String = prefs(c).getString("status", "").orEmpty()

    /** Identificatorul comenzii în curs (căutare activă) sau null. */
    fun activeCommand(c: Context): String? = prefs(c).getString("command", null)

    fun exactPermission(c: Context): Boolean =
        ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun backgroundPermission(c: Context): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Notificările sunt permise și canalul găsirii nu e mut — altfel serviciul nu poate rămâne vizibil. */
    fun notices(c: Context): Boolean {
        val m = c.getSystemService(NotificationManager::class.java) ?: return false
        val permitted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val channelOk = m.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        return m.areNotificationsEnabled() && permitted && channelOk
    }

    /** Un apel către site pentru acest telefon; refuză dacă înrolarea s-a schimbat între timp. */
    suspend fun call(
        c: Context,
        d: RecoveryDevice,
        path: String,
        body: JsonObject? = null,
        method: String = if (body == null) "GET" else "POST"
    ): JsonObject {
        check(device(c) == d) { "Găsirea telefonului s-a oprit sau contul s-a schimbat." }
        val result = InsightsApi.json("/v2/recovery/devices/${d.id}/$path", body, method)
        check(device(c) == d) { "Găsirea telefonului s-a oprit." }
        return result
    }

    /**
     * Activează găsirea: identitate nouă (UUID) + secret de 32 de octeți aleatori (64 hex), POST grant cu consimțământ,
     * apoi înrolarea se scrie local și serviciul pornește. La eșec, revocarea se pune la coadă.
     */
    suspend fun activate(c: Context, name: String) {
        check(exactPermission(c) && notices(c)) { "Permite locația precisă și notificările în Android." }
        check(device(c) == null) { "Găsirea este deja activată." }
        val owner = checkNotNull(owner(c)) { "Conectează-te în FORJA." }
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val d = RecoveryDevice(owner, UUID.randomUUID().toString(), secret)
        val cleanName = name.trim().take(60).ifBlank { defaultName() }
        val body = buildJsonObject {
            put("name", cleanName)
            put("secret", d.secret)
            put("consent", true)
        }
        try {
            InsightsApi.json("/v2/recovery/devices/${d.id}/grant", body, "POST")
            check(owner(c) == d.owner && exactPermission(c) && notices(c)) { "Permisiunile s-au schimbat în timpul activării." }
            prefs(c).edit()
                .putString("owner", d.owner)
                .putString("id", d.id)
                .putString("secret", d.secret)
                .putString("name", cleanName)
                .putBoolean("enabled", true)
                .remove("blocked")
                .remove("command")
                .putString("status", "Pregătit pentru cererile tale din panou · GPS oprit")
                .commit()
            resume(c)
        } catch (e: Exception) {
            queue(c, d, "revoke", null)
            throw e
        }
    }

    /**
     * Repornește serviciul dacă găsirea e activată și permisiunile sunt la locul lor.
     * După restart (boot = true) Android cere locația „Tot timpul” pentru un serviciu de locație pornit din fundal.
     */
    fun resume(c: Context, boot: Boolean = false) {
        if (device(c) == null || LostPhoneService.running) return
        if (boot && !backgroundPermission(c)) return
        if (!exactPermission(c) || !notices(c)) return
        try {
            ContextCompat.startForegroundService(c, Intent(c, LostPhoneService::class.java))
        } catch (_: Exception) {
            prefs(c).edit().putString("status", "Deschide FORJA pentru a reconecta găsirea telefonului.").apply()
        }
    }

    /** Uită înrolarea local (fără apel la site). */
    fun clear(c: Context) {
        prefs(c).edit().putBoolean("enabled", false).remove("command").remove("secret").remove("status").commit()
    }

    /** Dezactivează găsirea: local imediat, revocarea pe site cu reîncercare (WorkManager). */
    fun disable(c: Context) {
        val d = device(c)
        clear(c)
        try { c.stopService(Intent(c, LostPhoneService::class.java)) } catch (_: Exception) { }
        if (d != null) queue(c, d, "revoke", null)
    }

    /** Oprește doar căutarea curentă; găsirea rămâne activată. */
    fun stopSearch(c: Context) {
        val d = device(c) ?: return
        val command = prefs(c).getString("command", null) ?: return
        prefs(c).edit().putString("blocked", command).remove("command").commit()
        LostPhoneService.current?.stopSearchLocally()
        queue(c, d, "stop", command)
    }

    private fun queue(c: Context, d: RecoveryDevice, action: String, command: String?) {
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: InsightsFailure) {
            when (e.code) {
                401, 403, 404, 409 -> Result.success()   // deja făcut / înrolare schimbată
                400 -> Result.failure()                  // cerere respinsă definitiv — reîncercarea nu ajută
                else -> Result.retry()
            }
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
