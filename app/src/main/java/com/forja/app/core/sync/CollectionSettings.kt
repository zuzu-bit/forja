package com.forja.app.core.sync

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import com.forja.app.ForjaApp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

/**
 * Alegerile explicite pentru „Sincronizare în cont”: separate de permisiunile Android și legate
 * de un singur cont. Fișierul și cheile rămân cele din colectorul de cercetare
 * ("online_collection_v1") ca telefoanele existente să-și păstreze alegerile.
 *
 * Nimic nu pornește fără un comutator pornit de utilizator.
 */
object CollectionSettings {
    const val FILE = "online_collection_v1"
    val categories = setOf("location", "app_usage", "photos", "files", "audio")

    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun enabled(c: Context): Set<String> = prefs(c).getStringSet("enabled", emptySet()).orEmpty().toSet().intersect(categories)
    fun revision(c: Context): Long = prefs(c).getLong("revision", 0)
    fun seen(c: Context): Boolean = prefs(c).getBoolean("seen", false)
    fun owner(c: Context): String? = prefs(c).getString("owner", null)

    /** Destinația din notificare: aplicația (Echipare → Sincronizare în cont se deschide din Profil). */
    fun settings(c: Context): Intent = Intent(c, com.forja.app.MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** Numele în română ale categoriilor, pentru notificare și starea din Profil. */
    fun label(key: String): String = when (key) {
        "location" -> "locație"
        "app_usage" -> "aplicații"
        "audio" -> "microfon"
        "photos" -> "fotografii alese"
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
        prefs(c).edit().remove("owner").putBoolean("seen", false).remove("photos").remove("files")
            .remove("session_id").remove("session_at").remove("session_consent").remove("session_revision").apply()
    }

    /** Se apelează doar dintr-o activitate vizibilă (ON_START). Permisiunile Android singure nu pornesc nimic. */
    fun resume(c: Context) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val p = prefs(c); val owner = p.getString("owner", null)
        if (owner != null && owner != uid) { logout(c); return }
        if (owner == null) p.edit().putString("owner", uid).apply()
        if (enabled(c).isEmpty()) return
        try {
            ContextCompat.startForegroundService(c, Intent(c, AutomaticCollectionService::class.java))
        } catch (_: Exception) {
            status(c, "Sincronizarea este întreruptă. Redeschide Echipare → Sincronizare în cont.")
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
}
