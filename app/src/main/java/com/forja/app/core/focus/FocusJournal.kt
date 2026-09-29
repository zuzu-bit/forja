package com.forja.app.core.focus

import android.content.Context
import com.forja.app.ForjaApp
import com.forja.app.core.data.db.FocusSessionEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Jurnalul sesiunilor de concentrare (Room `focus_sessions`): câte una deschisă pe fel — „focus” (aplicații consemnate)
 * și „detox” (detoxul digital, tot telefonul în pauză). [FocusMonitorService] le deschide, numără încercările de a deschide
 * o aplicație oprită și le închide (timer · user · system). Id-urile deschise stau în SharedPreferences, ca o sesiune
 * rămasă deschisă de un serviciu oprit de Android să fie închisă la următoarea pornire, la ultima clipă văzută.
 */
object FocusJournal {
    private const val FILE = "forja_focus_journal"
    /** Serviciul atinge jurnalul la ~20 s ([TOUCH_MS]); după 90 s fără atingere sesiunea e a unui serviciu oprit. */
    const val STALE_MS = 90_000L
    const val TOUCH_MS = 20_000L
    private val lock = Mutex()

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun key(kind: String) = "open_$kind"

    fun openId(c: Context, kind: String): Long = prefs(c).getLong(key(kind), 0L)

    /** Deschide sesiunea `kind` dacă nu e deja una deschisă. Întoarce id-ul. */
    suspend fun ensureOpen(app: ForjaApp, kind: String, plannedMin: Int, rules: List<String>): Long = lock.withLock {
        val cur = openId(app, kind)
        if (cur != 0L) return@withLock cur
        val now = System.currentTimeMillis()
        val id = app.db.focusSessionDao().insert(
            FocusSessionEntity(startAt = now, kind = kind, plannedMin = plannedMin.coerceAtLeast(0), rules = MindDocs.rulesJson(rules))
        )
        prefs(app).edit().putLong(key(kind), id).putLong("seen_$kind", now).apply()
        id
    }

    /** O încercare de a deschide `pkg` cât sesiunea `kind` e deschisă. */
    suspend fun hit(app: ForjaApp, kind: String, pkg: String) = lock.withLock {
        val id = openId(app, kind); if (id == 0L) return@withLock
        val s = app.db.focusSessionDao().byId(id) ?: return@withLock
        app.db.focusSessionDao().update(s.copy(blockHits = MindDocs.addHit(s.blockHits, pkg)))
    }

    /** Ultima clipă văzută a sesiunii `kind` deschise (0 = niciuna). */
    fun seen(c: Context, kind: String): Long = prefs(c).getLong("seen_$kind", 0L)

    /** Copacii crescuți când s-a deschis sesiunea focus (supraviețuiește repornirii serviciului), -1 = necunoscut. */
    fun grownAtOpen(c: Context): Int = prefs(c).getInt("grown_focus", -1)
    fun setGrownAtOpen(c: Context, v: Int) { prefs(c).edit().putInt("grown_focus", v).apply() }

    /** Serviciul e încă în post: ultima clipă văzută (pentru închiderea „system” după o oprire bruscă). */
    fun touch(c: Context) {
        val now = System.currentTimeMillis()
        prefs(c).edit().apply { for (k in listOf("focus", "detox")) if (openId(c, k) != 0L) putLong("seen_$k", now) }.apply()
    }

    /** Închide sesiunea `kind`, dacă e deschisă. */
    suspend fun end(app: ForjaApp, kind: String, endedBy: String, grown: Boolean = false, withered: Boolean = false, at: Long = System.currentTimeMillis()) = lock.withLock {
        val id = openId(app, kind); if (id == 0L) return@withLock
        prefs(app).edit().remove(key(kind)).apply()
        val s = app.db.focusSessionDao().byId(id) ?: return@withLock
        app.db.focusSessionDao().update(s.copy(endAt = at.coerceAtLeast(s.startAt), endedBy = endedBy, grown = grown, withered = withered))
    }

    /** La pornirea serviciului: ce a rămas deschis de la un serviciu oprit de Android se închide la ultima clipă văzută. */
    suspend fun closeStale(app: ForjaApp) {
        val now = System.currentTimeMillis()
        for (k in listOf("focus", "detox")) {
            if (openId(app, k) == 0L) continue
            val seen = prefs(app).getLong("seen_$k", 0L)
            if (now - seen > STALE_MS) end(app, k, "system", at = if (seen > 0) seen else now)
        }
    }
}
