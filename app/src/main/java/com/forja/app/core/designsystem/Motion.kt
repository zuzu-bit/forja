package com.forja.app.core.designsystem

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import android.provider.Settings

// Cele 3 arcuri „mișcare umană" din handoff.
object Springs {
    // tap/press, chips, switch, bounce, pop numerale
    fun <T> snappy(): SpringSpec<T> = spring(dampingRatio = 0.60f, stiffness = 700f)
    // carduri, sheets, count-up, tranziții ecran
    fun <T> natural(): SpringSpec<T> = spring(dampingRatio = 0.72f, stiffness = 380f)
    // respirație, ambient, hartă
    fun <T> gentle(): SpringSpec<T> = spring(dampingRatio = 0.92f, stiffness = 170f)

    fun <T> snappyVisibility(): SpringSpec<T> = spring(dampingRatio = 0.60f, stiffness = 700f, visibilityThreshold = null)
}

val LocalReducedMotion = compositionLocalOf { false }

@Composable
fun rememberSystemReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (_: Exception) { false }
    }
}
