package com.forja.app.core.detox

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.forja.app.ForjaApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Paznicul „Detox de adicție" — accountability tool, ca Covenant Eyes/BlockerX.
 *
 * Diferența etică față de orice supraveghere: aici e telefonul TĂU, tu pornești
 * paznicul ca să te ajuți pe TINE, iar TOTUL rămâne pe telefon — cuvintele-declanșator
 * și conținutul ecranului NU pleacă nicăieri, nu ating niciun server. Consimțit,
 * local, terapeutic. Când prinde tentația, întâmpină cu blândețe, nu cu rușine.
 */
class ForjaGuardService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var on = false
    @Volatile private var userWords: List<String> = emptyList()
    private var lastIntervene = 0L
    private var essentials: Set<String> = emptySet()

    override fun onServiceConnected() {
        val app = ForjaApp.from(this)
        essentials = buildEssentials()
        scope.launch { app.prefs.detoxOn.collect { on = it } }
        scope.launch { app.prefs.detoxWords.collect { userWords = parseWords(it) } }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!on || event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg.contains("forja")) return
        if (pkg in essentials) return
        val now = System.currentTimeMillis()
        if (now - lastIntervene < 3500) return

        val texts = ArrayList<String>()
        event.text?.forEach { texts.add(it.toString()) }

        // Doar la schimbarea ferestrei citim (ușor) și conținutul — restul e ce s-a tastat.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            try {
                rootInActiveWindow?.let { root ->
                    collectText(root, texts, 0)
                    root.recycle()
                }
            } catch (_: Exception) { }
        }

        val hay = texts.joinToString(" ").lowercase()
        if (hay.isBlank()) return
        if (matches(hay)) {
            lastIntervene = now
            try { performGlobalAction(GLOBAL_ACTION_BACK) } catch (_: Exception) { }
            try {
                startActivity(
                    Intent(this, DetoxBlockActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
            } catch (_: Exception) { }
        }
    }

    private fun matches(hay: String): Boolean {
        for (d in DOMAINS) if (hay.contains(d)) return true
        for (b in BUILT_IN) if (hay.contains(b)) return true
        for (w in userWords) if (w.length >= 3 && hay.contains(w)) return true
        return false
    }

    private fun collectText(node: AccessibilityNodeInfo, out: ArrayList<String>, depth: Int) {
        if (depth > 30 || out.size > 100) return
        node.text?.let { if (!TextUtils.isEmpty(it)) out.add(it.toString()) }
        node.contentDescription?.let { if (!TextUtils.isEmpty(it)) out.add(it.toString()) }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            collectText(c, out, depth + 1)
            c.recycle()
        }
    }

    private fun buildEssentials(): Set<String> {
        val s = mutableSetOf(packageName, "com.android.settings", "com.android.systemui")
        try {
            packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
            )?.activityInfo?.packageName?.let { s.add(it) }
        } catch (_: Exception) { }
        try {
            packageManager.resolveActivity(Intent(Intent.ACTION_DIAL), 0)
                ?.activityInfo?.packageName?.let { s.add(it) }
        } catch (_: Exception) { }
        return s
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun parseWords(s: String): List<String> =
            s.split("\n", ",").map { it.trim().lowercase() }.filter { it.length >= 3 }

        // Blocklist minimă, pe telefon. Cea mai mare parte o dau cuvintele setate de user.
        private val DOMAINS = listOf(
            "pornhub", "xvideos", "xnxx", "xhamster", "redtube", "youporn",
            "onlyfans", "brazzers", "spankbang", "chaturbate", "stripchat", "fansly"
        )
        private val BUILT_IN = listOf("porn", "xxx", "nsfw", "hentai")

        /** E pornit serviciul de accesibilitate FORJA? (nu se poate porni programatic) */
        fun isEnabled(context: Context): Boolean {
            return try {
                val expected = ComponentName(context, ForjaGuardService::class.java).flattenToString()
                val enabled = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                enabled.split(':').any { it.equals(expected, ignoreCase = true) }
            } catch (_: Exception) { false }
        }
    }
}
