package com.forja.app.core.recovery

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.forja.app.core.designsystem.ForjaTheme
import com.forja.app.feature.recovery.FoundContent
import kotlinx.coroutines.delay
import java.lang.ref.WeakReference

/**
 * „GĂSIRE · Aici sunt.” — ecranul soneriei pornite de pe site. Apare peste ecranul blocat (full-screen intent),
 * fără să ceară deblocarea: cine găsește telefonul îl poate opri, dar nu intră în el.
 * „Am găsit telefonul” (sau o tastă de volum) oprește soneria și închide comanda pe site.
 */
class FoundActivity : ComponentActivity() {

    companion object {
        @Volatile private var current: WeakReference<FoundActivity>? = null

        fun intent(c: Context): Intent = Intent(c, FoundActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)

        /** Comanda s-a terminat (oprită din site, timp expirat): ecranul pleacă singur. */
        fun close() {
            val a = current?.get() ?: return
            a.runOnUiThread { if (!a.found) a.finish() }
        }
    }

    @Volatile private var found = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (LostPhoneService.active?.kind != FinderCommand.Kind.Ring) { finish(); return }
        current = WeakReference(this)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            ForjaTheme {
                // „Înapoi” nu închide soneria pe tăcute: butonul e calea.
                BackHandler { }
                var done by remember { mutableStateOf(false) }
                var left by remember { mutableLongStateOf(secondsLeft()) }
                LaunchedEffect(done) {
                    if (done) {
                        delay(1_600)
                        finish()
                        return@LaunchedEffect
                    }
                    while (true) {
                        left = secondsLeft()
                        if (LostPhoneService.active == null) { finish(); break }
                        delay(500)
                    }
                }
                FoundContent(
                    secondsLeft = left.toInt(),
                    found = done,
                    onFound = {
                        if (!done) {
                            found = true
                            done = true
                            LostPhoneService.found(this@FoundActivity)
                        }
                    }
                )
            }
        }
    }

    private fun secondsLeft(): Long {
        val a = LostPhoneService.active ?: return 0L
        return ((a.ringEndsAt - System.currentTimeMillis()).coerceAtLeast(0L) + 999L) / 1000L
    }

    /** Tastele de volum opresc soneria, ca la orice telefon care sună. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) && FinderRinger.ringing) {
            FinderRinger.stop(this)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }
}
