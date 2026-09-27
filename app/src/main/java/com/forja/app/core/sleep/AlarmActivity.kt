package com.forja.app.core.sleep

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.PrimaryButton
import com.forja.app.core.designsystem.components.SecondaryButton
import com.forja.app.core.util.Fmt

/** Alarma deșteaptă — te prinde în somn ușor, nu în adânc. Sună până spui tu. */
class AlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Peste ecranul blocat, cu ecranul aprins: API 27+ are metode; pe 26 rămân flag-urile de fereastră.
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try {
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        } catch (_: Exception) { }

        // Sunetele de adormit tac; sună alarma (un singur player pentru serviciu + activitate).
        SleepSounds.stop()
        AlarmRinger.start(this)

        setContent {
            ForjaTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Surface0),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 28.dp)
                    ) {
                        val reduced = LocalReducedMotion.current
                        val infinite = rememberInfiniteTransition(label = "wake")
                        val breathAnim by infinite.animateFloat(
                            0.9f, 1.1f,
                            infiniteRepeatable(tween(1800), RepeatMode.Reverse),
                            label = "s"
                        )
                        val breath = if (reduced) 1f else breathAnim
                        Box(
                            Modifier
                                .size(170.dp)
                                .scale(breath)
                                .clip(CircleShape)
                                .background(Color(0x226F855A))
                                .border(2.dp, Color(0x996F855A), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(Fmt.clock(System.currentTimeMillis()), style = heroNumeral(40))
                        }
                        Spacer(Modifier.height(30.dp))
                        Text(
                            "Bună dimineața.",
                            style = TitleModule.copy(fontSize = 30.sp),
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Te-am prins în somn ușor — de-asta e mai blând.",
                            style = Body.copy(fontSize = 15.sp),
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(34.dp))
                        PrimaryButton(
                            text = "M-am trezit",
                            onClick = {
                                stopAlarm()
                                SleepTrackService.stop(this@AlarmActivity)
                                finish()
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(12.dp))
                        SecondaryButton(
                            text = "Încă 10 minute",
                            onClick = {
                                stopAlarm()
                                SleepTrackService.snooze(this@AlarmActivity)
                                finish()
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
    }

    override fun onStop() {
        visible = false
        super.onStop()
    }

    private fun stopAlarm() {
        AlarmRinger.stop()
        try { NotificationManagerCompat.from(this).cancel(SleepTrackService.ALARM_NOTIF_ID) } catch (_: Exception) { }
    }

    override fun onDestroy() {
        stopAlarm()
        super.onDestroy()
    }

    companion object {
        /** true cât timp ecranul alarmei e la vedere — serviciul verifică asta ca să nu rămână alarma mută. */
        @Volatile
        var visible: Boolean = false
            private set
    }
}
