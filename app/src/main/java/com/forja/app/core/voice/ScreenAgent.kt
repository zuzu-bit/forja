package com.forja.app.core.voice

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

/**
 * „FORJA · Comenzi pe ecran”: serviciul de accesibilitate prin care asistentul vocal vede ce e pe ecranul
 * aplicației din față și lucrează în ea — citește, apasă, scrie, caută, derulează, înapoi.
 *
 * Citește ecranul DOAR când o comandă vocală o cere; nu ține jurnal, nu trimite nimic nicăieri.
 * Trebuie pornit de utilizator din Setări → Accesibilitate (Android nu lasă nicio aplicație să-l pornească singură).
 */
class VoiceScreenService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { if (it.isNotBlank()) foregroundPackage = it }
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: VoiceScreenService? = null
            private set
        @Volatile var foregroundPackage: String = ""
            private set
    }
}

/** Un element de pe ecran, așa cum îl vede serviciul de accesibilitate. */
data class ScreenItem(
    val node: AccessibilityNodeInfo,
    val text: String,
    val clickable: Boolean,
    val editable: Boolean,
    val focused: Boolean,
    val bounds: Rect
)

/** Operațiile pe ecran, pe înțelesul executorului de comenzi. */
object ScreenAgent {

    private const val MAX_SPOKEN = 900

    fun isEnabled(context: Context): Boolean {
        if (VoiceScreenService.instance != null) return true
        return try {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(context, VoiceScreenService::class.java)
            enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        } catch (_: Exception) { false }
    }

    /** Serviciul e pornit în setări ȘI conectat acum (poate vedea ecranul). */
    fun isConnected(): Boolean = VoiceScreenService.instance != null

    fun openSettings(context: Context): Boolean = try {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) { false }

    fun foregroundPackage(): String = VoiceScreenService.foregroundPackage

    /** Așteaptă până când aplicația [pkg] ajunge în față (cel mult [timeoutMs]). */
    suspend fun waitForPackage(pkg: String, timeoutMs: Long = 5000): Boolean {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            if (foregroundPackage() == pkg || rootPackage() == pkg) return true
            delay(150)
        }
        return foregroundPackage() == pkg || rootPackage() == pkg
    }

    private fun rootPackage(): String = try { VoiceScreenService.instance?.rootInActiveWindow?.packageName?.toString() ?: "" } catch (_: Exception) { "" }

    private suspend fun root(): AccessibilityNodeInfo? {
        val svc = VoiceScreenService.instance ?: return null
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
            val t = it.text.replace(Regex("\\s+"), " ").trim()
            val key = VoiceText.normalize(t)
            if (key.isEmpty() || !seen.add(key)) continue
            if (sb.isNotEmpty()) sb.append(". ")
            sb.append(t)
            if (sb.length > maxChars) { sb.append("…"); break }
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
        val svc = VoiceScreenService.instance ?: return false
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

    /** Enter / „caută” în câmpul focalizat. */
    suspend fun enter(): Boolean {
        val list = items()
        val field = list.firstOrNull { it.editable && it.focused } ?: list.firstOrNull { it.editable }
        if (field != null && Build.VERSION.SDK_INT >= 30) {
            try { if (field.node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) return true } catch (_: Exception) { }
        }
        // Un buton de căutare / trimitere vizibil
        val btn = list.firstOrNull { it.clickable && Regex("^(?:search|cauta|cautare|caută|căutare|go|done|trimite|send|ok)$").matches(VoiceText.normalize(it.text)) }
        if (btn != null) return tap(btn)
        return false
    }

    /** Caută în aplicația din față: apasă pe „căutare”, scrie, Enter. */
    suspend fun search(query: String): Boolean {
        var list = items()
        var field = list.firstOrNull { it.editable && it.focused } ?: list.firstOrNull { it.editable && looksLikeSearch(it) }
        if (field == null) {
            val affordance = list.firstOrNull { it.clickable && looksLikeSearch(it) } ?: list.firstOrNull { looksLikeSearch(it) }
            if (affordance != null) {
                tap(affordance)
                delay(700)
                list = items()
                field = list.firstOrNull { it.editable && it.focused } ?: list.firstOrNull { it.editable }
            }
        }
        if (field == null) field = list.firstOrNull { it.editable }
        if (field == null) return false
        if (!setText(field.node, query)) return false
        delay(350)
        if (!enter()) {
            // Unele aplicații caută singure după scriere; altele au nevoie de Enter prin tastatură — încercăm gestul pe lupă.
            delay(300)
        }
        return true
    }

    private fun looksLikeSearch(it: ScreenItem): Boolean {
        val label = VoiceText.normalize(it.text)
        val id = try { it.node.viewIdResourceName?.lowercase().orEmpty() } catch (_: Exception) { "" }
        return label.contains("caut") || label.contains("search") || label.contains("cautare") ||
            id.contains("search") || id.contains("query")
    }

    /** Derulează lista / pagina din față. */
    suspend fun scroll(down: Boolean): Boolean {
        val r = root() ?: return false
        var target: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || target != null || depth > 60) return
            try {
                if (n.isScrollable && n.isVisibleToUser) { target = n; return }
                for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
            } catch (_: Exception) { }
        }
        walk(r, 0)
        target?.let {
            val ok = it.performAction(if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            if (ok) return true
        }
        // Gest: tragem cu degetul pe mijlocul ecranului
        val svc = VoiceScreenService.instance ?: return false
        val m = svc.resources.displayMetrics
        val x = m.widthPixels / 2f
        val (y1, y2) = if (down) m.heightPixels * 0.70f to m.heightPixels * 0.30f else m.heightPixels * 0.30f to m.heightPixels * 0.70f
        return try {
            val path = Path().apply { moveTo(x, y1); lineTo(x, y2) }
            svc.dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 350)).build(), null, null)
        } catch (_: Exception) { false }
    }

    fun back(): Boolean = VoiceScreenService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) ?: false
    fun home(): Boolean = VoiceScreenService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) ?: false

    /** Numele aplicației din față, pentru „În YouTube: …”. */
    fun foregroundAppLabel(context: Context): String {
        val pkg = foregroundPackage().ifBlank { rootPackage() }
        if (pkg.isBlank()) return ""
        return try {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg.substringAfterLast('.') }
    }
}
