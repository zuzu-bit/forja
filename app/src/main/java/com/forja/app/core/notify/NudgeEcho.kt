package com.forja.app.core.notify

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.forja.app.MainActivity
import com.forja.app.core.designsystem.Body
import com.forja.app.core.designsystem.LocalReducedMotion
import com.forja.app.core.designsystem.TextSecondary
import com.forja.app.core.designsystem.components.MascotHat
import com.forja.app.core.designsystem.components.MascotSays
import com.forja.app.core.designsystem.components.MascotState
import com.forja.app.core.designsystem.components.pressable

/** Replica cu care Casca te-a chemat (din extra-urile notificării atinse). */
data class Echo(val id: String, val context: String, val pose: NudgePose, val title: String, val body: String)

/**
 * Trucul Duo — continuitatea: atingi mesajul, iar pe Panou te așteaptă Casca în aceeași poză, cu aceeași replică.
 * Apare o singură dată (extra-urile se consumă), dispare la atingere. Fără mesaj atins, nu ocupă loc.
 */
@Composable
fun NudgeEcho(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val tick = (activity as? MainActivity)?.intentTick ?: 0
    var echo by remember { mutableStateOf<Echo?>(null) }
    var shown by remember { mutableStateOf<Echo?>(null) }
    LaunchedEffect(tick) {
        val e = consume(activity?.intent) ?: return@LaunchedEffect
        echo = e; shown = e
        Nudges.onTapped(context, e.context)
    }
    val reduced = LocalReducedMotion.current
    AnimatedVisibility(
        visible = echo != null,
        enter = if (reduced) EnterTransition.None else fadeIn() + expandVertically(),
        exit = if (reduced) ExitTransition.None else fadeOut() + shrinkVertically()
    ) {
        shown?.let { NudgeEchoCard(it, modifier, onDismiss = { echo = null }) }
    }
}

/** Cardul: mascota cu cască în poza mesajului, titlul în bulă, textul dedesubt. Atingerea îl închide. */
@Composable
fun NudgeEchoCard(echo: Echo, modifier: Modifier = Modifier, onDismiss: () -> Unit = {}) {
    val state = MascotState.entries.firstOrNull { it.name == echo.pose.name } ?: MascotState.Talking
    Column(modifier.fillMaxWidth().pressable(onDismiss)) {
        MascotSays(text = echo.title, state = state, hat = MascotHat.Helmet, size = 52.dp)
        if (echo.body.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(echo.body, style = Body.copy(color = TextSecondary), modifier = Modifier.padding(start = 62.dp, end = 4.dp))
        }
    }
}

/** Citește și șterge extra-urile mesajului atins (o singură afișare, și după rotirea ecranului). */
private fun consume(intent: Intent?): Echo? {
    intent ?: return null
    val id = intent.getStringExtra(Notifier.EXTRA_ID) ?: return null
    val title = intent.getStringExtra(Notifier.EXTRA_TITLE).orEmpty()
    val body = intent.getStringExtra(Notifier.EXTRA_BODY).orEmpty()
    val ctx = intent.getStringExtra(Notifier.EXTRA_CTX).orEmpty()
    val pose = NudgePose.entries.firstOrNull { it.name == intent.getStringExtra(Notifier.EXTRA_POSE) } ?: NudgePose.Talking
    listOf(Notifier.EXTRA_ID, Notifier.EXTRA_TITLE, Notifier.EXTRA_BODY, Notifier.EXTRA_CTX, Notifier.EXTRA_POSE, Notifier.EXTRA_AT)
        .forEach { intent.removeExtra(it) }
    if (title.isBlank()) return null
    return Echo(id, ctx, pose, title, body)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
