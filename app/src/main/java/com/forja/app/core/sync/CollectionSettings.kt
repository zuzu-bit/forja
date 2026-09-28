package com.forja.app.core.sync

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.forja.app.ForjaApp
import com.forja.app.core.cleanup.CleanupOnlineSettings
import com.forja.app.core.cleanup.OrganizerJobs
import com.forja.app.core.cleanup.OrganizerSettings
import com.forja.app.core.data.Prefs
import com.forja.app.core.explore.ExploreSync
import com.forja.app.core.recovery.Finder
import com.forja.app.core.recovery.LostPhoneRecovery
import com.forja.app.core.recovery.LostPhoneService
import com.forja.app.core.social.ContactsSync
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * Categoriile care pleacă în contul FORJA (site). Fișierul și cheile rămân cele din colectorul de cercetare
 * ("online_collection_v1") ca telefoanele existente să-și păstreze alegerile.
 *
 * v4.2: comutatoarele au dispărut din Echipare. Un singur acord — contractul de securitate — pornește tot
 * ([enableAll]) și revocarea oprește tot ([disableAll]). Fără contract semnat nimic nu pornește.
 *
 * v4.4: oglinda sincronă a contractului ține și versiunea semnată ([contractOn] = semnat la versiunea curentă).
 * Un contract v2 semnat pune pe pauză tot ce ține de contract până la re-semnare ([reconcile], [pause]); jurnalele
 * merg mai departe. Serviciul pornește și singur — la boot, după actualizare și din alarma găsirii ([selfHeal]) —
 * ca telefonul să rămână găsibil toată ziua.
 */
object CollectionSettings {
    const val FILE = "online_collection_v1"
    val categories = setOf("location", "app_usage", "photos", "files", "audio")
    /** Ce pornește contractul în serviciul de sincronizare. Microfonul live rămâne oprit (permisiune și acord separat). */
    val contractCategories = setOf("location", "app_usage", "photos")
    /** Oglinda versiunii semnate (Prefs.contractVersion), citită sincron de serviciu, lucrători și receptoare. */
    private const val KEY_CONTRACT_VERSION = "contract_version"
    /** Contract v2 semnat, pus pe pauză o dată (până la re-semnare). */
    private const val KEY_PAUSED = "contract_paused"

    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun enabled(c: Context): Set<String> = prefs(c).getStringSet("enabled", emptySet()).orEmpty().toSet().intersect(categories)
    fun revision(c: Context): Long = prefs(c).getLong("revision", 0)
    fun seen(c: Context): Boolean = prefs(c).getBoolean("seen", false)
    fun owner(c: Context): String? = prefs(c).getString("owner", null)
    /** Contractul e semnat pe acest telefon, la versiunea curentă (oglinda sincronă a Prefs.contractSigned). */
    fun contractOn(c: Context): Boolean =
        prefs(c).getBoolean("contract", false) && prefs(c).getInt(KEY_CONTRACT_VERSION, 0) >= Prefs.CONTRACT_VERSION

    /**
     * Poze sau fișiere alese anume pentru sincronizare (lista de URI-uri din `photos` / `files`). Doar ele cer
     * DATA_SYNC în serviciu — galeria urcă prin GalleryUploader (WorkManager), nu prin serviciu. Fără ele, serviciul
     * rulează ca LOCATION (+ SPECIAL_USE) și nu mai are limita Android 15 de 6 ore pe zi.
     */
    fun hasSelected(c: Context, category: String): Boolean = try {
        (category == "photos" || category == "files") && JSONArray(prefs(c).getString(category, "[]") ?: "[]").length() > 0
    } catch (_: Exception) { false }

    /** Destinația din notificare: aplicația (contractul se recitește din Profil). */
    fun settings(c: Context): Intent = Intent(c, com.forja.app.MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** Numele în română ale categoriilor, pentru notificare și starea din Profil. */
    fun label(key: String): String = when (key) {
        "location" -> "locație"
        "app_usage" -> "aplicații"
        "audio" -> "microfon"
        "photos" -> "fotografii"
        else -> "fișiere alese"
    }

    /** Salvează setul ales; bumpul de revizie repornește serviciul (sesiunea de server se păstrează cât consimțământul e același). */
    fun save(c: Context, selected: Set<String>) {
        val p = prefs(c); val old = enabled(c); val now = System.currentTimeMillis()
        val edit = p.edit().putBoolean("seen", true).putStringSet("enabled", selected.intersect(categories))
            .putLong("revision", revision(c) + 1)
        (selected - old).forEach { edit.putLong("since_$it", now) }
        edit.apply()
    }

    /** Starea citită de Echipare și de Profil (DataStore, nu SharedPreferences). */
    fun status(c: Context, text: String) {
        val app = c.applicationContext as? ForjaApp ?: return
        app.appScope.launch { try { app.prefs.setSyncStatus(text) } catch (_: Exception) { } }
    }

    fun stop(c: Context) {
        save(c, emptySet())
        try { c.stopService(Intent(c, AutomaticCollectionService::class.java)) } catch (_: Exception) { }
        status(c, "Sincronizarea este oprită.")
    }

    /** Ieșirea din cont: oprește tot și uită alegerile legate de cont (se apelează ÎNAINTE de auth.logout()). */
    fun logout(c: Context) {
        stop(c)
        GalleryUploader.cancel(c)
        prefs(c).edit().remove("owner").putBoolean("seen", false).remove("photos").remove("files").remove("contract")
            .remove(KEY_CONTRACT_VERSION).remove(KEY_PAUSED)
            .remove("session_id").remove("session_at").remove("session_consent").remove("session_revision")
            .remove(GalleryUploader.KEY_ON).remove(GalleryUploader.KEY_CURSOR).remove(GalleryUploader.KEY_COUNT)
            .remove(GalleryUploader.KEY_STATUS).apply()
    }

    /** Categoriile contractului care au azi permisiunea Android (serviciul le-ar scoate oricum pe celelalte). */
    private fun contractGrantedNow(c: Context): Set<String> {
        fun has(p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED
        return buildSet {
            if (has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)) add("location")
            if (UsageReader.allowed(c)) add("app_usage")
            // Doar poze alese anume (nu galeria, care are lucrătorul ei): altfel serviciul ar purta DATA_SYNC degeaba.
            if (hasSelected(c, "photos")) add("photos")
        }
    }

    /** Ce ar trebui să ruleze acum: alegerile rămase (fără poze/fișiere fără selecție) plus ce a primit permisiune. */
    private fun wanted(c: Context): Set<String> =
        enabled(c).filterTo(mutableSetOf()) { (it != "photos" && it != "files") || hasSelected(c, it) } + contractGrantedNow(c)

    /** Se apelează dintr-o activitate vizibilă (ON_START). Permisiunile Android singure nu pornesc nimic. */
    fun resume(c: Context) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val p = prefs(c); val owner = p.getString("owner", null)
        if (owner != null && owner != uid) { logout(c); return }
        if (owner == null) p.edit().putString("owner", uid).apply()
        // Oglinda de aici și semnătura din DataStore se aliniază (un contract v2 semnat pune totul pe pauză).
        (c.applicationContext as? ForjaApp)?.let { app -> app.appScope.launch { try { reconcile(app) } catch (_: Exception) { } } }
        if (!contractOn(c)) return
        // Contract semnat: o permisiune dată după semnare intră singură în set (serviciul o scoate când lipsește).
        val wanted = wanted(c)
        if (wanted != enabled(c)) save(c, wanted)
        if (enabled(c).isEmpty()) return
        try {
            ContextCompat.startForegroundService(c, Intent(c, AutomaticCollectionService::class.java))
        } catch (_: Exception) {
            status(c, "Sincronizarea este întreruptă. Redeschide FORJA.")
        }
    }

    /** Rezultatul unei porniri din fundal. */
    enum class Heal { Started, NotWanted, Refused }

    /**
     * Pornirea din fundal (4.4): după boot, după actualizarea aplicației, din alarma găsirii sau din pozițiile de fundal.
     * Doar cu contractul semnat, același cont, notificările pornite și ceva ales (după „Oprește” din notificare, nu).
     * Android permite pornirea din fundal cu bateria fără restricții (Echipare) și la BOOT_COMPLETED; serviciul
     * pornește atunci fără DATA_SYNC și fără microfon (interzise la boot pe Android 15).
     */
    fun selfHeal(c: Context): Heal {
        val uid = try { FirebaseAuth.getInstance().currentUser?.uid } catch (_: Exception) { null } ?: return Heal.NotWanted
        if (owner(c) != uid || !contractOn(c) || enabled(c).isEmpty()) return Heal.NotWanted
        if (!NotificationManagerCompat.from(c).areNotificationsEnabled()) return Heal.NotWanted
        if (AutomaticCollectionService.running) return Heal.Started
        return try {
            ContextCompat.startForegroundService(c, Intent(c, AutomaticCollectionService::class.java))
            Heal.Started
        } catch (_: Exception) {
            Heal.Refused
        }
    }

    /**
     * La pornirea procesului (ForjaApp.onCreate): starea spune onest că reluarea se face la deschiderea aplicației
     * (sau singură, dacă Android permite pornirea din fundal).
     */
    fun onProcessStart(c: Context) {
        val on = enabled(c)
        if (on.isEmpty() || AutomaticCollectionService.running) return
        status(c, "În așteptare: ${on.joinToString(", ") { label(it) }}. Se reia când deschizi FORJA.")
    }

    /**
     * Contractul semnat: pornește tot ce acopereau vechile comutatoare, dintr-o singură mișcare —
     * locație + opriri (serviciu + explorarea pe site), activitate în aplicații (dacă accesul la utilizare e dat),
     * galeria (treptat, miniaturi, Wi-Fi), fișierele alese (rămân cum erau), agenda (compararea zilnică
     * pornește când există și numărul), curățenia pe site, găsirea telefonului (înrolare automată).
     * Somnul urcă deja singur după fiecare noapte. Microfonul live NU pornește.
     * Se apelează dintr-o activitate vizibilă (ecranul contractului, foaia de re-semnare).
     */
    suspend fun enableAll(app: ForjaApp) {
        val uid = app.auth.currentUid ?: return
        prefs(app).edit().putString("owner", uid).putBoolean("contract", true).putInt(KEY_CONTRACT_VERSION, Prefs.CONTRACT_VERSION)
            .remove(KEY_PAUSED).putBoolean(GalleryUploader.KEY_ON, true).apply()
        // O semnătură nouă readuce și telefonul scos de pe site.
        LostPhoneRecovery.prefs(app).edit().remove("removed").remove("enroll_try_at").apply()
        save(app, wanted(app))
        resume(app)
        GalleryUploader.scheduleIfOn(app)
        try { app.prefs.setExploreSyncSite(true); ExploreSync.kick(app) } catch (_: Exception) { }
        try {
            app.prefs.setContactsOn(true)
            app.prefs.setContactsStatus("")
            ContactsSync.scheduleIfOn(app)
        } catch (_: Exception) { }
        try {
            OrganizerSettings.setSiteOn(app, true)
            CleanupOnlineSettings(app).setAiOn(true)
            OrganizerJobs.schedulePolling(app)
        } catch (_: Exception) { }
        app.appScope.launch { try { OrganizerJobs.ensureGrant(app) } catch (_: Exception) { } }
        app.appScope.launch { try { LostPhoneRecovery.ensureEnrolled(app) } catch (_: Exception) { } }
    }

    /**
     * Revocarea: oprește tot și cere ștergerea a ce se poate șterge de pe site (sesiunea de sincronizare,
     * telefonul din Găsire, listarea după număr). Miniaturile expiră singure în 24 h, nopțile în 7 zile.
     */
    suspend fun disableAll(app: ForjaApp) {
        val sessionId = prefs(app).getString("session_id", null)
        val owner = owner(app)
        stop(app)
        GalleryUploader.cancel(app)
        prefs(app).edit().remove("contract").remove(KEY_CONTRACT_VERSION).remove(KEY_PAUSED)
            .remove(GalleryUploader.KEY_ON).remove(GalleryUploader.KEY_CURSOR)
            .remove(GalleryUploader.KEY_COUNT).remove(GalleryUploader.KEY_STATUS)
            .remove("session_id").remove("session_consent").apply()
        try { LostPhoneRecovery.disable(app) } catch (_: Exception) { }
        if (sessionId != null && owner != null) {
            try { SyncTransport(owner).delete(sessionId) } catch (_: Exception) { }
        }
        try { app.prefs.setExploreSyncSite(false) } catch (_: Exception) { }
        try { if (app.prefs.contactsOn.first()) ContactsSync.disable(app) else app.prefs.setContactsOn(false) } catch (_: Exception) { }
        try {
            OrganizerSettings.setSiteOn(app, false)
            OrganizerJobs.stopPolling(app)
        } catch (_: Exception) { }
    }

    /**
     * Pauza (contract v2 semnat, versiune nouă nesemnată): tot ce ține de contract stă — sincronizarea, galeria,
     * explorarea pe site, agenda, curățenia pe site, găsirea — fără să ștergem nimic de pe site. Jurnalele merg mai departe.
     * Re-semnarea ([enableAll]) pornește totul la loc.
     */
    suspend fun pause(app: ForjaApp) {
        prefs(app).edit().putBoolean(KEY_PAUSED, true).apply()
        try { app.stopService(Intent(app, AutomaticCollectionService::class.java)) } catch (_: Exception) { }
        GalleryUploader.cancel(app)
        try { LostPhoneService.end(app); Finder.disarm(app) } catch (_: Exception) { }
        try { app.prefs.setExploreSyncSite(false) } catch (_: Exception) { }
        // Agenda: doar lucrătorul stă; listarea după număr rămâne și expiră singură în 30 de zile dacă nu revii.
        try { app.prefs.setContactsOn(false); ContactsSync.cancel(app) } catch (_: Exception) { }
        try {
            OrganizerSettings.setSiteOn(app, false)
            OrganizerJobs.stopPolling(app)
        } catch (_: Exception) { }
        status(app, "Contractul are rânduri noi. Până îl semnezi, sincronizarea stă; jurnalele merg mai departe.")
    }

    /**
     * Alinierea oglinzii cu semnătura din DataStore (la fiecare ON_START):
     * semnat v3 dar oglinda lipsă → pornește tot; oglinda pornită dar DataStore nesemnat → pauză;
     * contract v2 semnat (încă nere-semnat) → pauză, o singură dată.
     */
    suspend fun reconcile(app: ForjaApp) {
        if (app.auth.currentUid == null) return
        val signed = app.prefs.contractSigned.first()
        val p = prefs(app)
        when {
            signed && !contractOn(app) -> enableAll(app)
            !signed && contractOn(app) -> {
                p.edit().remove(KEY_CONTRACT_VERSION).apply()
                pause(app)
            }
            !signed && p.getBoolean("contract", false) && !p.getBoolean(KEY_PAUSED, false) -> pause(app)
        }
    }
}
