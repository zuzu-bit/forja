package com.forja.app.core.data

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.forja.app.BuildConfig
import com.google.firebase.auth.FirebaseAuth

/** Explicit online choices are separate from Android grants and bound to one account. */
object CollectionSettings {
    const val FILE = "online_collection_v1"
    val categories = setOf("location", "app_usage", "photos", "files", "audio")
    fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getStringSet("enabled", emptySet()).orEmpty().toSet().intersect(categories)
    fun revision(c: Context) = prefs(c).getLong("revision", 0)
    fun seen(c: Context) = prefs(c).getBoolean("seen", false)
    fun settings(c: Context) = Intent().setClassName(c, "com.forja.app.feature.research.ResearchExportActivity")
    fun save(c: Context, selected: Set<String>) {
        val p = prefs(c); val old = enabled(c); val now = System.currentTimeMillis()
        val edit = p.edit().putBoolean("seen", true).putStringSet("enabled", selected.intersect(categories))
            .putLong("revision", revision(c) + 1)
        (selected - old).forEach { edit.putLong("since_$it", now) }
        edit.apply()
    }
    fun stop(c: Context) {
        save(c, emptySet())
        c.stopService(Intent().setClassName(c, "com.forja.app.feature.research.AutomaticCollectionService"))
        prefs(c).edit().putString("status", "Colectarea este oprită.").apply()
    }
    fun logout(c: Context) {
        if (!BuildConfig.RESEARCH_MODE) return
        stop(c)
        prefs(c).edit().remove("owner").putBoolean("seen", false).remove("photos").remove("files").apply()
    }
    /** Call only from a visible Activity. Android grants alone never enable a category. */
    fun resume(c: Context) {
        if (!BuildConfig.RESEARCH_MODE || !seen(c)) return
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val p = prefs(c); val owner = p.getString("owner", null)
        if (owner != null && owner != uid) { logout(c); return }
        if (owner == null) p.edit().putString("owner", uid).apply()
        if (enabled(c).isEmpty()) return
        try {
            ContextCompat.startForegroundService(c, Intent().setClassName(c, "com.forja.app.feature.research.AutomaticCollectionService"))
        } catch (_: Exception) { p.edit().putString("status", "Sincronizarea este întreruptă. Redeschide Permisiuni și sincronizare.").apply() }
    }
}
