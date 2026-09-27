package com.forja.app.feature.cleanup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.forja.app.feature.sleep.LegacySleepScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Retains the published entry-point ABI while displaying the original sleep screen. */
@Composable fun SleepHub() {
    val context=LocalContext.current.applicationContext
    LaunchedEffect(context) {
        runCatching { SleepRuntime.read(context,"interruptedAfterRestart") }
        while(isActive) {
            try { SleepBridge.refresh(context) }
            catch(e:CancellationException) { throw e }
            catch(e:Exception) { SleepBridge.failed(context,e) }
            delay(15000)
        }
    }
    LegacySleepScreen()
}
