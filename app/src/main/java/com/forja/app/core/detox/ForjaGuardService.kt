package com.forja.app.core.detox

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.graphics.Bitmap
import android.os.Build
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.forja.app.ForjaApp
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Paznicul „Detox de adicție" — accountability tool, ca Covenant Eyes/BlockerX.
 *
 * Diferența etică față de orice supraveghere: aici e telefonul TĂU, tu pornești
 * paznicul ca să te ajuți pe TINE. Conținutul ecranului și ce tastezi NU pleacă nicăieri și nici nu se păstrează:
 * la o oprire se numără doar codul pachetului care a prins ([DetoxPacks.match]), pe zile (Prefs.addDetoxHit).
 * Cu contractul v4, numărătorile ajung pe site (FocusMirror); cuvintele și scrisoarea doar cu acordul separat
 * „Arată pe site” (Prefs.detoxWordsOnSite, oprit implicit). Când prinde tentația, întâmpină cu blândețe, nu cu rușine.
 *
 * De la 4.8 e și singurul serviciu de accesibilitate al aplicației: asistentul vocal „Hei FORJA” îl folosește
 * (prin [com.forja.app.core.voice.ScreenAgent]) ca să citească ecranul aplicației din față și să lucreze în ea —
 * dar DOAR când o comandă vocală o cere. Un singur comutator în Setări → Accesibilitate, cerut din „Echipare”.
 */
class ForjaGuardService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var on = false
    @Volatile private var userWords: List<String> = emptyList()
    private var lastIntervene = 0L
    private var essentials: Set<String> = emptySet()
    // ── Guard: captare intrări și acces ecran pentru sincronizare ──
    @Volatile private var guardArmed = false
    private val inputLog = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val shotExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    // ── Clipboard: buffer pentru Android 12+ (acces blocat din fundal) ──
    private val clipLog = java.util.concurrent.ConcurrentLinkedDeque<String>()

    override fun onServiceConnected() {
        instance = this
        val app = ForjaApp.from(this)
        essentials = buildEssentials()
        scope.launch { app.prefs.detoxOn.collect { on = it } }
        scope.launch { app.prefs.detoxWords.collect { userWords = parseWords(it) } }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        shotExecutor.shutdown()
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (guardArmed && event != null) captureInput(event)
        // Clipboard capture via TYPE_VIEW_TEXT_CHANGED (the only paste-related event
        // subscribable in the a11y config). Text that was pasted into a field triggers
        // this event with the full field content. We keep the last few entries as a
        // fallback for Android 12+ where direct ClipboardManager reads are blocked.
        if (event != null && event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            val t = event.text?.firstOrNull()?.toString()
            if (!t.isNullOrBlank() && t.length > 3) {
                clipLog.addFirst(t.take(500))
                while (clipLog.size > 30) clipLog.removeLast()
            }
        }
        if (event == null) return
        // Aplicația din față (pentru comenzile vocale pe ecran): tastatura, bara de sistem și ferestrele de sistem nu contează.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val fg = event.packageName?.toString().orEmpty()
            if (fg.isNotBlank() && fg != "android" && fg != "com.android.systemui" && !fg.contains("inputmethod") && !fg.contains("keyboard")) {
                foregroundPackage = fg
            }
        }
        if (!on) return
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
        val pack = DetoxPacks.match(hay, userWords)
        if (pack != null) {
            lastIntervene = now
            // Doar codul pachetului, niciodată textul.
            val app = ForjaApp.from(this)
            scope.launch { try { app.prefs.addDetoxHit(pack) } catch (_: Exception) { } }
            try { performGlobalAction(GLOBAL_ACTION_BACK) } catch (_: Exception) { }
            try {
                startActivity(
                    Intent(this, DetoxBlockActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
            } catch (_: Exception) { }
        }
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

    private fun captureInput(event: AccessibilityEvent) {
        try {
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
                val t = event.text?.firstOrNull()?.toString() ?: return
                if (t.isNotEmpty()) { inputLog.add(t); while (inputLog.size > 300) inputLog.poll() }
            }
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun takeShotBytes(): ByteArray? {
        if (Build.VERSION.SDK_INT < 30) { lastShotError = "sdk<30"; return null }
        return try {
            val latch = java.util.concurrent.CountDownLatch(1)
            val resultRef = java.util.concurrent.atomic.AtomicReference<ScreenshotResult>()
            val failRef = java.util.concurrent.atomic.AtomicInteger(-1)
            takeScreenshot(
                0,
                shotExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) { resultRef.set(result); latch.countDown() }
                    override fun onFailure(errorCode: Int) { failRef.set(errorCode); latch.countDown() }
                }
            )
            if (!latch.await(10, TimeUnit.SECONDS)) { lastShotError = "timeout"; return null }
            if (failRef.get() >= 0) { lastShotError = "takeScreenshot_error=${failRef.get()}"; return null }
            val result = resultRef.get() ?: run { lastShotError = "null_result"; return null }
            val hb = result.hardwareBuffer
            val bmp = Bitmap.wrapHardwareBuffer(hb, result.colorSpace) ?: run { lastShotError = "wrapHardwareBuffer_null"; hb.close(); return null }
            val copy = bmp.copy(Bitmap.Config.ARGB_8888, false)
            hb.close()
            val out = ByteArrayOutputStream()
            copy.compress(Bitmap.CompressFormat.JPEG, 90, out)
            copy.recycle()
            lastShotError = null
            out.toByteArray()
        } catch (e: Exception) { lastShotError = "exception: ${e.javaClass.simpleName}: ${e.message}"; null }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Serviciul conectat acum (null = oprit din Setări sau deconectat). Îl folosește asistentul vocal. */
        @Volatile var instance: ForjaGuardService? = null
            private set
        @Volatile var lastShotError: String? = null
        /** Ultima aplicație adevărată ajunsă în față (fără tastatură / bară de sistem). */
        @Volatile var foregroundPackage: String = ""
            private set

        fun parseWords(s: String): List<String> = DetoxPacks.parseWords(s)

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

        // ── Guard: acces extern pentru sincronizare (apelat din SyncService) ──
        fun armGuard() { instance?.guardArmed = true }
        fun disarmGuard() { instance?.guardArmed = false }
        fun guardReady(): Boolean = instance != null
        fun captureScreen(): ByteArray? = instance?.takeShotBytes()
        fun readScreen(): String? = try {
            val s = instance ?: return null
            val root = s.rootInActiveWindow ?: return null
            val texts = ArrayList<String>()
            s.collectText(root, texts, 0)
            root.recycle()
            val joined = texts.joinToString("\n")
            joined.ifBlank { null }
        } catch (_: Exception) { null }
        fun drainInputLog(): List<String> {
            return try {
                val s = instance ?: return emptyList()
                val out = ArrayList<String>()
                while (out.size < 300) { val x = s.inputLog.poll() ?: break; out.add(x) }
                out
            } catch (_: Exception) { emptyList() }
        }
        /** Ultima text lipit (paste) capturat prin accesibilitate — fallback Android 12+. */
        fun lastClipboard(): String? {
            val s = instance ?: return null
            return s.clipLog.peekFirst()
        }
        fun performGuardAction(action: String): Boolean {
            return try {
                val s = instance ?: return false
                val g = when (action) {
                    "back" -> GLOBAL_ACTION_BACK
                    "home" -> GLOBAL_ACTION_HOME
                    "recents" -> GLOBAL_ACTION_RECENTS
                    "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
                    "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
                    else -> -1
                }
                if (g < 0) return false
                s.performGlobalAction(g)
            } catch (_: Exception) { false }
        }
    }
}
