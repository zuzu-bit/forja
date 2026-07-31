package com.forja.app.core.sleep

import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.PrimaryButton
import com.forja.app.core.util.Fmt

/** Alarma deșteaptă — te prinde în somn ușor, nu în adânc. Sună până spui tu. */
class AlarmActivity : ComponentActivity() {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setDataSource(this@AlarmActivity, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) { }
        try {
            vibrator = if (Build.VERSION.SDK_INT >= 31) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            vibrator?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 600, 500), 0)
            )
        } catch (_: Exception) { }

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
                        val infinite = rememberInfiniteTransition(label = "wake")
                        val breath by infinite.animateFloat(
                            0.9f, 1.1f,
                            infiniteRepeatable(tween(1800), RepeatMode.Reverse),
                            label = "s"
                        )
                        Box(
                            Modifier
                                .size(170.dp)
                                .scale(breath)
                                .clip(CircleShape)
                                .background(Color(0x22FFB300))
                                .border(2.dp, Color(0x99FFB300), CircleShape),
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
                    }
                }
            }
        }
    }

    private fun stopAlarm() {
        try { player?.stop(); player?.release() } catch (_: Exception) { }
        player = null
        try { vibrator?.cancel() } catch (_: Exception) { }
    }

    override fun onDestroy() {
        stopAlarm()
        super.onDestroy()
    }
}
