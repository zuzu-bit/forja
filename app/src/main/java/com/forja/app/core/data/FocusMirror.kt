package com.forja.app.core.data

import android.content.Context
import com.forja.app.ForjaApp
import com.forja.app.core.detox.DetoxPacks
import com.forja.app.core.detox.ForjaGuardService
import com.forja.app.core.focus.MindDocs
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Oglinda pentru site (mirror D): users/{uid}/focus/{YYYY-MM-DD} (sesiunile de concentrare și detox digital, pădurea zilei,
 * aplicațiile consemnate și încercările), detox/{YYYY-MM-DD} (opririle paznicului pe pachete, niciodată textul; seria,
 * revenirile) și breath/{YYYY-MM-DD}. Cuvintele și scrisoarea ajung în detox/words DOAR cu Prefs.detoxWordsOnSite; când
 * acordul se oprește, documentul se șterge.
 * Pleacă doar cu contractul v4 (Prefs.contractAtLeast(4)); revocarea le șterge prin [SiteMirror.forget], pentru că
 * numele sunt deja în [SiteMirror.MIRRORED]. Se rescriu doar zilele schimbate (amprenta pe cont + semnătură, ca o
 * semnătură nouă să retrimită tot). Scrierile trec prin cache-ul offline Firestore.
 */
object FocusMirror {
    /** Numele din [SiteMirror.MIRRORED] pe care le scrie oglinda aceasta. */
    val PATHS: List<String> = listOf("focus", "detox", "breath")

    private const val FILE = "forja_mind_mirror"
    private const val SETTLE_MS = 5_000L
    private const val DAY_MS = 86_400_000L

    private val started = AtomicBoolean(false)
    private val lock = Mutex()

    private data class Key(val uid: String, val signedAt: Long)

    /** Pornește ascultătorii o singură dată pe proces. */
    fun start(app: ForjaApp) {
        if (!started.compareAndSet(false, true)) return
        val since = System.currentTimeMillis() - MindDocs.DAYS_BACK * DAY_MS
        val who: Flow<Key?> = combine(authUid(), app.prefs.contractAtLeast(4), app.prefs.contractSignedAt) { uid, v4, at ->
            if (uid != null && v4) Key(uid, at) else null
        }.distinctUntilChanged()

        // Stăpânul urmelor de pe telefon (ascultări, Casca, detox, pădure): orice cont care intră le preia sau le golește,
        // chiar fără contractul v4, ca un cont nou să nu moștenească urmele celui dinainte.
        app.appScope.launch {
            try { authUid().collect { uid -> if (uid != null) MindOwner.claim(app, uid) } } catch (_: Exception) { }
        }

        // Zilele: sesiunile, respirația, pădurea, opririle paznicului, seria.
        app.appScope.launch {
            try {
                val journals = combine(app.db.focusSessionDao().since(since), app.db.breathSessionDao().since(since), app.prefs.focusForestHistory.distinctUntilChanged()) { a, b, c -> Triple(a, b, c) }.distinctUntilChanged()
                val detox = combine(app.prefs.detoxHits, app.prefs.detoxOn, app.prefs.detoxStreakStart, app.prefs.detoxSlips, guardEnabled(app)) { h, on, st, sl, g -> listOf(h, on, st, sl, g) }.distinctUntilChanged()
                combine(who, journals, detox) { k, _, _ -> k }.collectLatest { k ->
                    if (k == null) return@collectLatest
                    delay(SETTLE_MS)
                    withContext(NonCancellable) { daysPass(app, k) }
                }
            } catch (_: Exception) { }
        }

        // Cuvintele și scrisoarea: doar cu acordul separat; oprit → documentul pleacă de pe site.
        app.appScope.launch {
            try {
                combine(who, app.prefs.detoxWordsOnSite, app.prefs.detoxWords, app.prefs.detoxLetter) { k, on, w, l -> k?.let { Triple(it, on, w to l) } }
                    .distinctUntilChanged().collectLatest { t ->
                        if (t == null) return@collectLatest
                        delay(SETTLE_MS)
                        withContext(NonCancellable) { wordsPass(app, t.first, t.second, t.third.first, t.third.second) }
                    }
            } catch (_: Exception) { }
        }
    }

    private fun store(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Scrie documentul doar dacă s-a schimbat de la ultima trimitere pentru această semnătură. */
    internal fun writeIfChanged(c: Context, k: String, uid: String, collection: String, docId: String, doc: Map<String, Any?>) {
        val sig = MindDocs.signature(doc)
        val p = store(c)
        if (p.getString(k, null) == sig) return
        FirebaseFirestore.getInstance().collection("users").document(uid).collection(collection).document(docId).set(doc)
        p.edit().putString(k, sig).apply()
    }

    private suspend fun daysPass(app: ForjaApp, k: Key) = lock.withLock {
        try {
            if (MindOwner.claim(app, k.uid)) return@withLock // golite: fluxurile se schimbă și aduc o trecere nouă
            // Rândurile Room ale altui cont nu pleacă: claim le golește înainte de citire (nu ne bizuim pe SiteMirror).
            withContext(Dispatchers.IO) { Journals.claim(app, k.uid) }
            // O sesiune rămasă de la un serviciu oprit de Android se închide aici (la ultima clipă văzută), nu doar la
            // următoarea pornire a lui Focus.
            try { com.forja.app.core.focus.FocusJournal.closeStale(app) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val days = MindDocs.recentDays(now, zone)
            val from = LocalDate.parse(days.last()).atStartOfDay(zone).toInstant().toEpochMilli()
            val sessions = app.db.focusSessionDao().between(from, now + DAY_MS)
            val breaths = app.db.breathSessionDao().between(from, now + DAY_MS)
            val forest = app.prefs.focusForestHistory.first()
            val hits = app.prefs.detoxHits.first()
            val detoxOn = app.prefs.detoxOn.first()
            val streak = app.prefs.detoxStreakStart.first()
            val slips = app.prefs.detoxSlips.first()
            val guardOn = ForjaGuardService.isEnabled(app)
            val seen = listOf("focus", "detox").associateWith { com.forja.app.core.focus.FocusJournal.seen(app, it) }
            val labels = labelsOf(app, sessions.flatMap { MindDocs.parseRules(it.rules) + MindDocs.parseHits(it.blockHits).keys }.toSet())
            val prefix = "${k.uid}:${k.signedAt}"
            for (date in days) {
                val epoch = LocalDate.parse(date).toEpochDay()
                val daySessions = sessions.filter { MindDocs.dayKey(it.startAt, zone) == date }.map {
                    MindDocs.Session(it.startAt, it.endAt, it.kind, it.plannedMin, MindDocs.parseRules(it.rules), it.grown, it.withered, MindDocs.parseHits(it.blockHits), it.endedBy)
                }
                val tree = forest[epoch]
                if (daySessions.isNotEmpty() || (tree != null && (tree.first > 0 || tree.second > 0)))
                    writeIfChanged(app, "$prefix:focus:$date", k.uid, "focus", date, MindDocs.focusDoc(date, daySessions, tree, labels, now, seen, com.forja.app.core.focus.FocusJournal.STALE_MS))
                val dayBreath = breaths.filter { MindDocs.dayKey(it.startAt, zone) == date }.map {
                    MindDocs.Breath(it.startAt, it.endAt, it.pattern, it.cycles, it.durationS, it.completed)
                }
                if (dayBreath.isNotEmpty()) writeIfChanged(app, "$prefix:breath:$date", k.uid, "breath", date, MindDocs.breathDoc(date, dayBreath, now))
                val packs = hits[epoch].orEmpty()
                // Azi: ziua poartă și starea paznicului și seria, chiar fără opriri.
                if (packs.isNotEmpty() || (date == days.first() && (detoxOn || streak > 0)))
                    writeIfChanged(app, "$prefix:detox:$date", k.uid, "detox", date, MindDocs.detoxDoc(date, packs, guardOn, detoxOn, streak, slips, now))
            }
            prune(app, prefix, days.last())
        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }

    private suspend fun wordsPass(app: ForjaApp, k: Key, on: Boolean, words: String, letter: String) = lock.withLock {
        try {
            if (MindOwner.claim(app, k.uid)) return@withLock
            val key = "${k.uid}:${k.signedAt}:detox:words"
            val ref = FirebaseFirestore.getInstance().collection("users").document(k.uid).collection("detox").document("words")
            if (on && (words.isNotBlank() || letter.isNotBlank())) {
                writeIfChanged(app, key, k.uid, "detox", "words", MindDocs.wordsDoc(words, letter, DetoxPacks.BY_CODE, System.currentTimeMillis()))
            } else if (store(app).contains(key) || !on) {
                // Acordul oprit (sau lista golită): documentul nu mai are voie să stea pe site.
                ref.delete()
                store(app).edit().remove(key).apply()
            }
        } catch (_: Exception) { }
    }

    /** Amprentele zilelor ieșite din fereastră și ale semnăturilor vechi nu mai trebuie păstrate. */
    private fun prune(c: Context, prefix: String, oldest: String) {
        val p = store(c)
        val stale = p.all.keys.filter { key ->
            val date = key.substringAfterLast(':')
            (!key.startsWith("$prefix:") && !key.endsWith(":words")) || (date.length == 10 && date < oldest)
        }
        if (stale.isNotEmpty()) p.edit().apply { stale.forEach { remove(it) } }.apply()
    }

    private fun labelsOf(c: Context, pkgs: Set<String>): Map<String, String> = pkgs.associateWith { pkg ->
        try { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString().take(80) } catch (_: Exception) { pkg }
    }

    /** Paznicul (serviciul de accesibilitate) pornit sau oprit din Setări: o schimbare rescrie detox/{azi}.guardOn. */
    private fun guardEnabled(c: Context): Flow<Boolean> = callbackFlow {
        val uri = android.provider.Settings.Secure.getUriFor(android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(ForjaGuardService.isEnabled(c)) }
        }
        c.contentResolver.registerContentObserver(uri, false, observer)
        trySend(ForjaGuardService.isEnabled(c))
        awaitClose { c.contentResolver.unregisterContentObserver(observer) }
    }.distinctUntilChanged()

    private fun authUid(): Flow<String?> = callbackFlow {
        val auth = FirebaseAuth.getInstance()
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.uid) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }.distinctUntilChanged()
}

/**
 * Al cui cont sunt urmele minții ținute pe telefon, fără ownerUid (ca Journals pentru Room): ascultările (MusicStats),
 * jurnalul Căștii (NudgeStore), opririle paznicului, pădurea pe zile, seria, cuvintele și scrisoarea (Prefs).
 * Primul cont știut le preia (instalările vechi: contul conectat acum); alt cont care intră le golește înainte ca vreo
 * oglindă să le citească. Întoarce true dacă a golit.
 */
internal object MindOwner {
    private const val FILE = "forja_mind_owner"
    private const val KEY = "owner"
    private val lock = Mutex()

    suspend fun claim(app: ForjaApp, uid: String): Boolean = lock.withLock {
        val p = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val previous = p.getString(KEY, null)
        if (previous == uid) return@withLock false
        val foreign = previous != null
        if (foreign) withContext(NonCancellable + Dispatchers.IO) {
            try { com.forja.app.core.music.MusicStats.clearHistory(app) } catch (_: Exception) { }
            try { com.forja.app.core.notify.NudgeStore.clearLog(app) } catch (_: Exception) { }
            try { app.prefs.clearMindTraces() } catch (_: Exception) { }
            try { app.getSharedPreferences("forja_mind_mirror", Context.MODE_PRIVATE).edit().clear().apply() } catch (_: Exception) { }
        }
        p.edit().putString(KEY, uid).commit()
        foreign
    }
}
