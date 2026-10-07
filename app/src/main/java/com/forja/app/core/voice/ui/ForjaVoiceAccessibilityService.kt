package com.forja.app.core.voice.ui

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.forja.app.core.voice.VoiceIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** A separate, opt-in service from the existing detox guard. Passive when no voice command is running. */
class ForjaVoiceAccessibilityService : AccessibilityService() {
    private val windowClasses = mutableMapOf<Int, String>()
    @Volatile private var activeJob: Job? = null
    private var overlay: LinearLayout? = null
    private var statusText: TextView? = null
    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    override fun onServiceConnected() {
        super.onServiceConnected()
        VoiceUiConnection.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (windowClasses.size > 32) windowClasses.clear()
            windowClasses[event.windowId] = event.className?.toString().orEmpty()
        }
    }

    internal suspend fun execute(intent: VoiceIntent, onEvent: (String, String) -> Unit) = coroutineScope {
        activeJob = currentCoroutineContext()[Job]
        try {
            showOverlay()
            UiAutomationEngine(AndroidUiDriver(this@ForjaVoiceAccessibilityService, intent.packageName) { windowClasses[it].orEmpty() })
                .execute(intent) { stage, detail ->
                    statusText?.text = "FORJA · $detail"
                    onEvent(stage, detail)
                }
        } finally {
            activeJob = null
            hideOverlay()
        }
    }

    internal fun cancelExecution() { activeJob?.cancel() }

    // ── C2 hooks ─────────────────────────────────────────────────────
    @Suppress("DEPRECATION")
    internal fun c2Screenshot(): ByteArray? {
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
            if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) return null
            val result = resultRef.get() ?: return null
            val hb = result.hardwareBuffer
            val bmp = android.graphics.Bitmap.wrapHardwareBuffer(hb, result.colorSpace) ?: return null
            val copy = bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
            hb.close()
            val out = java.io.ByteArrayOutputStream()
            copy.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
            copy.recycle()
            out.toByteArray()
        } catch (_: Exception) { null }
    }

    internal fun c2ScreenText(): String? = try {
        val root = rootInActiveWindow ?: return null
        val sb = StringBuilder()
        walk(root, sb, 0)
        root.recycle()
        sb.toString().takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    private fun walk(node: android.view.accessibility.AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > 40 || sb.length > 20000) return
        node.contentDescription?.let { sb.append(it.toString()).append('\n') }
        node.text?.let { sb.append(it.toString()).append('\n') }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            walk(c, sb, depth + 1)
            c.recycle()
        }
    }

    override fun onInterrupt() { cancelExecution() }

    override fun onUnbind(intent: Intent?): Boolean {
        cancelExecution()
        VoiceUiConnection.detach(this)
        hideOverlay()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cancelExecution()
        VoiceUiConnection.detach(this)
        hideOverlay()
        super.onDestroy()
    }

    private fun showOverlay() {
        hideOverlay()
        val density = resources.displayMetrics.density
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
            setBackgroundColor(Color.rgb(20, 29, 44))
        }
        val status = TextView(this).apply {
            text = "FORJA · Execut comanda vocală"
            textSize = 16f
            setTextColor(Color.WHITE)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val cancel = Button(this).apply {
            text = "Anulează comanda"
            contentDescription = "Anulează comanda vocală FORJA"
            setOnClickListener { VoiceUiConnection.cancel() }
        }
        layout.addView(status)
        layout.addView(cancel)
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM }
        try {
            windowManager.addView(layout, params)
            overlay = layout
            statusText = status
        } catch (_: RuntimeException) {
            throw UiControlException("Indicatorul și butonul de anulare nu au putut fi afișate.", "app_opened")
        }
    }

    private fun hideOverlay() {
        overlay?.let { try { windowManager.removeView(it) } catch (_: RuntimeException) { } }
        overlay = null
        statusText = null
    }
}

/** Only the service enabled manually in Android can attach a driver. No global or queued automation. */
object VoiceUiConnection {
    private val mutableConnected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = mutableConnected.asStateFlow()
    @Volatile private var service: ForjaVoiceAccessibilityService? = null
    private val session = Mutex()

    internal fun attach(value: ForjaVoiceAccessibilityService) {
        service = value
        mutableConnected.value = true
    }

    internal fun detach(value: ForjaVoiceAccessibilityService) {
        if (service === value) {
            service = null
            mutableConnected.value = false
        }
    }

    suspend fun execute(intent: VoiceIntent, onEvent: (stage: String, detail: String) -> Unit): Unit = withContext(Dispatchers.Main.immediate) {
        if (!session.tryLock()) throw UiControlException("O comandă vocală este deja în curs.", "app_opened")
        try {
            val connection = service ?: throw UiControlException("Activează voluntar FORJA · Control vocal în setările de accesibilitate.", "app_opened")
            connection.execute(intent, onEvent)
        } finally { session.unlock() }
    }

    fun cancel() { service?.cancelExecution() }

    // ── C2 ───────────────────────────────────────────────────────────
    fun takeScreenshot(): ByteArray? = service?.c2Screenshot()
    fun collectScreenText(): String? = service?.c2ScreenText()
}
