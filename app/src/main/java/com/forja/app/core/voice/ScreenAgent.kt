package com.forja.app.core.voice

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.forja.app.core.detox.AccessibilityLink
import com.forja.app.core.detox.ForjaGuardService
import kotlinx.coroutines.delay

/** Un element de pe ecran, așa cum îl vede serviciul de accesibilitate. */
data class ScreenItem(
    val node: AccessibilityNodeInfo,
    val text: String,
    val clickable: Boolean,
    val editable: Boolean,
    val focused: Boolean,
    val bounds: Rect
)

/**
 * Operațiile pe ecran, pe înțelesul executorului de comenzi. Trec prin singurul serviciu de accesibilitate FORJA
 * ([ForjaGuardService] — același ca pentru Focus/Detox): citesc ecranul DOAR când o comandă vocală o cere.
 */
object ScreenAgent {

    private const val MAX_SPOKEN = 900

    /** Serviciul FORJA e pornit în Setări → Accesibilitate. */
    fun isEnabled(context: Context): Boolean = ForjaGuardService.instance != null || ForjaGuardService.isEnabled(context)

    /** Serviciul e pornit în setări ȘI conectat acum (poate vedea ecranul). */
    fun isConnected(): Boolean = ForjaGuardService.instance != null

    /** Pagina serviciului FORJA din Accesibilitate (sau lista, pe telefoanele fără pagină directă). */
    fun openSettings(context: Context): Boolean = AccessibilityLink.open(context)

    fun foregroundPackage(): String = ForjaGuardService.foregroundPackage

    /** Așteaptă până când aplicația [pkg] ajunge în față (cel mult [timeoutMs]). */
    suspend fun waitForPackage(pkg: String, timeoutMs: Long = 5000): Boolean {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            if (rootPackage() == pkg || foregroundPackage() == pkg) return true
            delay(150)
        }
        return rootPackage() == pkg || foregroundPackage() == pkg
    }

    private fun rootPackage(): String = try { ForjaGuardService.instance?.rootInActiveWindow?.packageName?.toString() ?: "" } catch (_: Exception) { "" }

    /** Așteaptă să apară conținut pe ecran (o aplicație abia pornită are nevoie de o clipă să se deseneze). */
    suspend fun waitForContent(timeoutMs: Long = 5000, minItems: Int = 3): Boolean {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            if (items().count { it.text.isNotBlank() } >= minItems) return true
            delay(200)
        }
        return false
    }

    private suspend fun root(): AccessibilityNodeInfo? {
        val svc = ForjaGuardService.instance ?: return null
        repeat(8) {
            val r = try { svc.rootInActiveWindow } catch (_: Exception) { null }
            if (r != null) return r
            delay(120)
        }
        return null
    }

    /** Toate elementele vizibile, în ordinea de pe ecran (sus → jos), cu textul lor. */
    suspend fun items(): List<ScreenItem> {
        val r = root() ?: return emptyList()
        val out = ArrayList<ScreenItem>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 60) return
            try {
                if (n.isVisibleToUser) {
                    val text = n.text?.toString()?.trim().orEmpty()
                    val desc = n.contentDescription?.toString()?.trim().orEmpty()
                    val hint = if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString()?.trim().orEmpty() else ""
                    val label = when {
                        text.isNotEmpty() -> text
                        desc.isNotEmpty() -> desc
                        hint.isNotEmpty() -> hint
                        else -> ""
                    }
                    val b = Rect(); n.getBoundsInScreen(b)
                    if (label.isNotEmpty() || n.isEditable || n.isClickable) {
                        out += ScreenItem(n, label, n.isClickable, n.isEditable, n.isFocused, b)
                    }
                }
                for (i in 0 until n.childCount) walk(try { n.getChild(i) } catch (_: Exception) { null }, depth + 1)
            } catch (_: Exception) { }
        }
        walk(r, 0)
        return out.sortedWith(compareBy({ it.bounds.top / 48 }, { it.bounds.left }))
    }

    /** Primele câteva lucruri de pe ecran — pentru „nu am găsit X, dar văd: …”. */
    suspend fun glance(): String {
        val seen = HashSet<String>()
        return items().filter { it.text.isNotBlank() }.map { it.text.replace(Regex("\\s+"), " ").trim() }
            .filter { seen.add(VoiceText.normalize(it)) }.take(6).joinToString(", ") { it.take(40) }.ifBlank { "nimic citibil" }
    }

    /** Ce scrie pe ecran, ca text de citit cu voce (fără dubluri, scurtat la [maxChars]). */
    suspend fun read(maxChars: Int = MAX_SPOKEN): String {
        val list = items().filter { it.text.isNotBlank() }
        if (list.isEmpty()) return ""
        val seen = HashSet<String>()
        val sb = StringBuilder()
        for (it in list) {
            val t = it.text.replace(Regex("\\s+"), " ").trim().let { x -> if (x.length > 300) x.take(300) + "…" else x }
            val key = VoiceText.normalize(t)
            if (key.isEmpty() || !seen.add(key)) continue
            // Strict sub limită: TextToSpeech aruncă în tăcere textele peste ~4000 de caractere.
            val room = maxChars - sb.length
            if (room <= 0) { sb.append("…"); break }
            if (sb.isNotEmpty()) sb.append(". ")
            sb.append(if (t.length > room) t.take(room) + "…" else t)
        }
        return sb.toString()
    }

    /** Cel mai potrivit element pentru ce a rostit utilizatorul („abonează-te”, „primul rezultat”, „play”). */
    suspend fun find(target: String): ScreenItem? {
        val q = VoiceText.normalize(target)
        val list = items()
        if (Regex("^(?:primul|prima|first|the first)(?: rezultat| element| video| videoclip| clip| link| result| item| one)?$").matches(q)) {
            val top = (list.maxOfOrNull { it.bounds.bottom } ?: 2000) * 0.15
            return list.firstOrNull { it.clickable && it.text.isNotBlank() && it.bounds.top > top }
        }
        var best: ScreenItem? = null
        var bestScore = 0
        for (it in list) {
            val label = VoiceText.normalize(it.text)
            if (label.isEmpty()) continue
            val s = when {
                label == q -> 100
                label.startsWith(q) || q.startsWith(label) && label.length >= 3 -> 85
                label.contains(q) -> 70
                q.length >= 5 && q.split(" ").all { w -> w.length < 3 || label.contains(w) } -> 60
                else -> 0
            } + (if (it.clickable) 5 else 0)
            if (s > bestScore) { bestScore = s; best = it }
        }
        return if (bestScore >= 60) best else null
    }

    /** Apasă elementul: acțiunea de click (pe el sau pe strămoșul care primește click), altfel o atingere în centru. */
    suspend fun tap(item: ScreenItem): Boolean {
        var n: AccessibilityNodeInfo? = item.node
        var hops = 0
        while (n != null && hops < 6) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = try { n.parent } catch (_: Exception) { null }
            hops++
        }
        return tapAt(item.bounds.centerX().toFloat(), item.bounds.centerY().toFloat())
    }

    fun tapAt(x: Float, y: Float): Boolean {
        val svc = ForjaGuardService.instance ?: return false
        return try {
            val path = Path().apply { moveTo(x, y) }
            val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build()
            svc.dispatchGesture(g, null, null)
        } catch (_: Exception) { false }
    }

    /** Scrie în câmpul focalizat (sau în primul câmp de text de pe ecran). */
    suspend fun type(text: String): Boolean {
        val list = items()
        val field = list.firstOrNull { it.editable && it.focused } ?: list.firstOrNull { it.editable } ?: return false
        return setText(field.node, text)
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean = try {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    } catch (_: Exception) { false }

    /** Enter / „caută” în câmpul focalizat (Android 11+ are acțiunea IME; mai jos, doar un buton vizibil). */
    suspend fun enter(): Boolean {
        val list = items()
        val field = list.firstOrNull { it.editable && it.focused } ?: list.firstOrNull { it.editable }
        if (field != null && Build.VERSION.SDK_INT >= 30) {
            try { if (field.node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) return true } catch (_: Exception) { }
        }
        // Un buton de căutare vizibil (niciodată „Trimite”: într-un chat ar expedia mesajul)
        val btn = list.firstOrNull { it.clickable && Regex("^(?:search|cauta|cautare|go|done|ok)$").matches(VoiceText.normalize(it.text)) }
        if (btn != null) return tap(btn)
        return false
    }

    enum class SearchResult { NO_SEARCH, SUBMITTED, TYPED_ONLY }

    /** Caută în aplicația din față: apasă pe „căutare”, scrie, Enter. Nu scrie în câmpuri care nu sunt de căutare. */
    suspend fun search(query: String): SearchResult {
        var list = items()
        var field = list.firstOrNull { it.editable && looksLikeSearch(it) }
        if (field == null) {
            val affordance = list.firstOrNull { it.clickable && looksLikeSearch(it) } ?: list.firstOrNull { looksLikeSearch(it) }
            if (affordance != null) {
                tap(affordance)
                // După apăsarea pe lupă, câmpul care primește focus e cel de căutare; îl așteptăm până la 2 s.
                var tries = 0
                while (field == null && tries++ < 10) {
                    delay(200)
                    list = items()
                    field = list.firstOrNull { it.editable && it.focused } ?: list.firstOrNull { it.editable && looksLikeSearch(it) }
                }
            }
        }
        if (field == null) return SearchResult.NO_SEARCH
        if (!setText(field.node, query)) return SearchResult.NO_SEARCH
        delay(350)
        return if (enter()) SearchResult.SUBMITTED else SearchResult.TYPED_ONLY
    }

    private fun looksLikeSearch(it: ScreenItem): Boolean {
        val label = VoiceText.normalize(it.text)
        val id = try { it.node.viewIdResourceName?.lowercase().orEmpty() } catch (_: Exception) { "" }
        val hint = try { if (Build.VERSION.SDK_INT >= 26) VoiceText.normalize(it.node.hintText?.toString().orEmpty()) else "" } catch (_: Exception) { "" }
        return label.contains("caut") || label.contains("search") || label.contains("find") ||
            hint.contains("caut") || hint.contains("search") ||
            id.contains("search") || id.contains("query") || id.contains("find")
    }

    /** Derulează lista / pagina din față: cel mai mare element derulabil (de regulă lista verticală, nu rândul de filtre). */
    suspend fun scroll(down: Boolean): Boolean {
        val r = root() ?: return false
        val candidates = ArrayList<Pair<AccessibilityNodeInfo, Long>>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 60) return
            try {
                if (n.isScrollable && n.isVisibleToUser) {
                    val b = Rect(); n.getBoundsInScreen(b)
                    val area = b.width().toLong() * b.height().toLong()
                    candidates += n to (if (b.height() >= b.width()) area * 2 else area)
                }
                for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
            } catch (_: Exception) { }
        }
        walk(r, 0)
        for ((node, _) in candidates.sortedByDescending { it.second }) {
            val ok = try { node.performAction(if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) } catch (_: Exception) { false }
            if (ok) return true
        }
        // Gest: tragem cu degetul pe mijlocul ecranului
        val svc = ForjaGuardService.instance ?: return false
        val m = svc.resources.displayMetrics
        val x = m.widthPixels / 2f
        val (y1, y2) = if (down) m.heightPixels * 0.70f to m.heightPixels * 0.30f else m.heightPixels * 0.30f to m.heightPixels * 0.70f
        return try {
            val path = Path().apply { moveTo(x, y1); lineTo(x, y2) }
            svc.dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 350)).build(), null, null)
        } catch (_: Exception) { false }
    }

    fun back(): Boolean = ForjaGuardService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) ?: false
    fun home(): Boolean = ForjaGuardService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) ?: false

    /** Dimensiunea ecranului real (px), pentru așezarea coordonatelor venite de pe site. */
    fun screenSize(): Pair<Int, Int>? {
        val svc = ForjaGuardService.instance ?: return null
        val m = svc.resources.displayMetrics
        return if (m.widthPixels > 0 && m.heightPixels > 0) m.widthPixels to m.heightPixels else null
    }

    /** O glisare între două puncte (px reali), pentru „Ecranul pe site” (tragerea pe imagine, swipe din terminal). */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val svc = ForjaGuardService.instance ?: return false
        return try {
            val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
            val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(20, 10_000))).build()
            svc.dispatchGesture(g, null, null)
        } catch (_: Exception) { false }
    }

    /** O tastă de sistem: back · home · recents · notifications · quick_settings · lock. */
    fun globalKey(name: String): Boolean {
        val svc = ForjaGuardService.instance ?: return false
        val action = when (name) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "lock" -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else return false
            else -> return false
        }
        return try { svc.performGlobalAction(action) } catch (_: Exception) { false }
    }

    /** Numele aplicației din față, pentru „În YouTube: …”. */
    fun foregroundAppLabel(context: Context): String {
        val pkg = rootPackage().ifBlank { foregroundPackage() }
        if (pkg.isBlank()) return ""
        return try {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg.substringAfterLast('.') }
    }
}
