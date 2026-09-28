package com.forja.app.core.sync

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.forja.app.ForjaApp
import com.forja.app.core.cleanup.CleanupOnlineSettings
import com.forja.app.core.cleanup.OrganizerJobs
import com.forja.app.core.cleanup.OrganizerSettings
import com.forja.app.core.explore.ExploreSync
import com.forja.app.core.social.ContactsSync
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Categoriile care pleacă în contul FORJA (site). Fișierul și cheile rămân cele din colectorul de cercetare
 * ("online_collection_v1") ca telefoanele existente să-și păstreze alegerile.
 *
 * v4.2: comutatoarele au dispărut din Echipare. Un singur acord — contractul de securitate — pornește tot
 * ([enableAll]) și revocarea oprește tot ([disableAll]). Fără contract semnat nimic nu pornește.
 */
object CollectionSettings {
    const val FILE = "online_collection_v1"
    val categories = setOf("location", "app_usage", "photos", "files", "audio")
    /** Ce pornește contractul în serviciul de sincronizare. Microfonul live rămâne oprit (permisiune și acord separat). */
    val contractCategories = setOf("location", "app_usage", "photos")

    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun enabled(c: Context): Set<String> = prefs(c).getStringSet("enabled", emptySet()).orEmpty().toSet().intersect(categories)
    fun revision(c: Context): Long = prefs(c).getLong("revision", 0)
    fun seen(c: Context): Boolean = prefs(c).getBoolean("seen", false)
    fun owner(c: Context): String? = prefs(c).getString("owner", null)
    /** Contractul e semnat pe acest telefon (oglinda sincronă a Prefs.contractSigned, citită de serviciu și de lucrători). */
    fun contractOn(c: Context): Boolean = prefs(c).getBoolean("contract", false)

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
            add("photos")
        }
    }

    /** Se apelează doar dintr-o activitate vizibilă (ON_START). Permisiunile Android singure nu pornesc nimic. */
    fun resume(c: Context) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val p = prefs(c); val owner = p.getString("owner", null)
        if (owner != null && owner != uid) { logout(c); return }
        if (owner == null) p.edit().putString("owner", uid).apply()
        // Contract semnat: o permisiune dată după semnare intră singură în set (serviciul o scoate când lipsește).
        if (contractOn(c)) {
            val wanted = enabled(c) + contractGrantedNow(c)
            if (wanted != enabled(c)) save(c, wanted)
        }
        if (enabled(c).isEmpty()) return
        try {
            ContextCompat.startForegroundService(c, Intent(c, AutomaticCollectionService::class.java))
        } catch (_: Exception) {
            status(c, "Sincronizarea este întreruptă. Redeschide FORJA.")
        }
    }

    /**
     * La pornirea procesului (ForjaApp.onCreate): serviciul nu poate porni singur din fundal pe Android 14+,
     * așa că starea spune onest că reluarea se face la deschiderea aplicației.
     */
    fun onProcessStart(c: Context) {
        val on = enabled(c)
        if (on.isEmpty()) return
        status(c, "În așteptare: ${on.joinToString(", ") { label(it) }}. Se reia când deschizi FORJA.")
    }

    /**
     * Contractul semnat: pornește tot ce acopereau vechile comutatoare, dintr-o singură mișcare —
     * locație + opriri (serviciu + explorarea pe site), activitate în aplicații (dacă accesul la utilizare e dat),
     * galeria întreagă (treptat, miniaturi, Wi-Fi), fișierele alese (rămân cum erau), agenda (compararea zilnică
     * pornește când există și numărul), curățenia pe site (copii + analiză cu model). Somnul urcă deja singur
     * după fiecare noapte. Microfonul live NU pornește.
     * Se apelează dintr-o activitate vizibilă (ecranul contractului).
     */
    suspend fun enableAll(app: ForjaApp) {
        val uid = app.auth.currentUid ?: return
        prefs(app).edit().putString("owner", uid).putBoolean("contract", true).putBoolean(GalleryUploader.KEY_ON, true).apply()
        save(app, enabled(app) + contractGrantedNow(app))
        resume(app)
        GalleryUploader.scheduleIfOn(app)
        try { app.prefs.setExploreSyncSite(true); ExploreSync.kick(app) } catch (_: Exception) { }
        ExploreSync.schedule(app)
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
    }

    /**
     * Revocarea: oprește tot și cere ștergerea a ce se poate șterge de pe site (sesiunea de sincronizare,
     * listarea după număr). Copiile de curățenie și miniaturile expiră singure în 24 h, nopțile în 7 zile.
     */
    suspend fun disableAll(app: ForjaApp) {
        val sessionId = prefs(app).getString("session_id", null)
        val owner = owner(app)
        stop(app)
        GalleryUploader.cancel(app)
        prefs(app).edit().remove("contract").remove(GalleryUploader.KEY_ON).remove(GalleryUploader.KEY_CURSOR)
            .remove(GalleryUploader.KEY_COUNT).remove(GalleryUploader.KEY_STATUS)
            .remove("session_id").remove("session_consent").apply()
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
}
