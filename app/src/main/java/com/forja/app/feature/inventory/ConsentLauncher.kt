package com.forja.app.feature.inventory

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.ContextWrapper
import android.content.IntentSender
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.forja.app.core.inventory.ConsentLog
import com.forja.app.core.music.DiagResult
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Cât așteptăm ca dialogul lansat să acopere ecranul (activitatea iese din RESUMED). De obicei durează sub 300 ms. */
internal const val CONSENT_COVER_MS = 2_000L

/** Un rând de jurnal cerut de lansator: treapta, rezultatul, milisecundele, nota. */
internal typealias ConsentTrace = (rung: String, result: DiagResult, ms: Long, note: String) -> Unit

/**
 * Lansează cererile porții de acord (dialogurile MediaStore), câte una, și raportează ce s-a întâmplat cu ele.
 *
 * - **Poarta e activitatea, nu ruta.** Dialogul se cere cu ACTIVITATEA în RESUMED (din fundal Android nu îl arată; iar
 *   un dialog nou nu pornește cât cel vechi încă acoperă ecranul). Ruta din NavHost poate sta STARTED cât Navigation
 *   crede că rulează o tranziție: 4.4 aștepta ruta și putea aștepta la nesfârșit.
 * - **O lansare pe încercare.** Efectul are cheia (id, încercare); o încercare deja lansată (ecran recreat cât dialogul
 *   e deschis) nu se mai lansează: rezultatul vine singur, lansatorul are aceeași cheie salvată.
 * - **Plasa.** Dialogul acoperă ecranul în câteva sute de ms; activitatea încă RESUMED după [CONSENT_COVER_MS] = n-a
 *   apărut → `onMissing(…, "timeout")`. O lansare care aruncă → `"launch"`.
 * - **Revenire fără răspuns.** Android livrează rezultatul ÎNAINTE de onResume: o încercare lansată, acoperită de
 *   dialog și tot fără răspuns la revenire și-a pierdut rezultatul → `onMissing(…, "resume")`. Un observator nou
 *   primește la înregistrare doar CREATE/START/RESUME, niciodată PAUSE: după o recreare nu declară nimic pierdut.
 */
@Composable
internal fun ConsentLauncher(
    consent: StateFlow<ConsentGate.Request<IntentSender>?>,
    onLaunched: (id: Long, attempt: Int) -> Unit,
    onMissing: (id: Long, attempt: Int, why: String) -> Unit,
    onResult: (ok: Boolean, sendFailed: Boolean) -> Unit,
    trace: ConsentTrace? = null,
    coverMs: Long = CONSENT_COVER_MS
) {
    val context = LocalContext.current
    val entry = LocalLifecycleOwner.current
    val host = remember(context, entry) { ((context.findActivity() as? LifecycleOwner) ?: entry).lifecycle }
    val log: ConsentTrace = remember(context, trace) { trace ?: { rung, result, ms, note -> ConsentLog.add(context, rung, result, ms, note) } }
    val launchedNow by rememberUpdatedState(onLaunched)
    val missingNow by rememberUpdatedState(onMissing)
    val logNow by rememberUpdatedState(log)

    // Contractul ținut minte: unul nou la fiecare recompunere ar reînregistra lansatorul (DisposableEffect pe contract).
    val contract = remember { ActivityResultContracts.StartIntentSenderForResult() }
    val launcher = rememberLauncherForActivityResult(contract) { res ->
        val sendFailed = res.data?.hasExtra(ActivityResultContracts.StartIntentSenderForResult.EXTRA_SEND_INTENT_EXCEPTION) == true
        onResult(res.resultCode == Activity.RESULT_OK, sendFailed)
    }

    val shown by consent.collectAsState()
    val key = shown
    LaunchedEffect(key?.id, key?.attempt) {
        val c = consent.value ?: return@LaunchedEffect
        if (c.id != key?.id || c.attempt != key.attempt) return@LaunchedEffect   // depășită: rulează cheia următoare
        if (c.launched || c.stuck) return@LaunchedEffect
        val t0 = SystemClock.uptimeMillis()
        host.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        val k = c.kind.code
        // Starea rutei alături de a activității: dacă 4.4 s-ar fi blocat la poartă, aici s-ar vedea „entry=STARTED”.
        logNow("${k}_GATE", DiagResult.OK, SystemClock.uptimeMillis() - t0, "${c.tag} host=${host.currentState} entry=${entry.lifecycle.currentState}")
        val t1 = SystemClock.uptimeMillis()
        try {
            launcher.launch(IntentSenderRequest.Builder(c.payload).build(), consentLaunchOptions())
        } catch (e: Exception) {
            logNow("${k}_LAUNCH", DiagResult.ERROR, SystemClock.uptimeMillis() - t1, "${c.tag} ${e.javaClass.simpleName}")
            missingNow(c.id, c.attempt, "launch")
            return@LaunchedEffect
        }
        // Abia după launch(): rezultatul vine oricum ca mesaj ulterior, iar o lansare eșuată nu intră în coadă.
        launchedNow(c.id, c.attempt)
        logNow("${k}_LAUNCH", DiagResult.OK, SystemClock.uptimeMillis() - t1, c.tag)
        val covered = withTimeoutOrNull(coverMs) {
            host.currentStateFlow.first { !it.isAtLeast(Lifecycle.State.RESUMED) }
        } != null
        logNow("${k}_SHOWN", if (covered) DiagResult.OK else DiagResult.TIMEOUT, SystemClock.uptimeMillis() - t1, c.tag)
        if (!covered) missingNow(c.id, c.attempt, "timeout")
    }

    DisposableEffect(host) {
        var coveredId = -1L
        var coveredAttempt = -1
        var pausedAt = 0L
        val obs = LifecycleEventObserver { _, e ->
            // Valoarea curentă a fluxului, nu starea compusă: rezultatul a schimbat-o chiar înainte de onResume.
            val c = consent.value
            when (e) {
                Lifecycle.Event.ON_PAUSE -> {
                    if (c != null && c.launched && !c.stuck) { coveredId = c.id; coveredAttempt = c.attempt } else coveredId = -1L
                    pausedAt = SystemClock.uptimeMillis()
                }
                Lifecycle.Event.ON_RESUME -> {
                    val lost = c?.takeIf { it.launched && !it.stuck && it.id == coveredId && it.attempt == coveredAttempt }
                    coveredId = -1L
                    if (lost != null) {
                        logNow("${lost.kind.code}_LOST", DiagResult.TIMEOUT, SystemClock.uptimeMillis() - pausedAt, lost.tag)
                        missingNow(lost.id, lost.attempt, "resume")
                    }
                }
                else -> Unit
            }
        }
        host.addObserver(obs)
        onDispose { host.removeObserver(obs) }
    }
}

/**
 * Voia FORJA (aplicație vizibilă) pentru PendingIntent-ul MediaStore (Android 14+: pornirea din fundal a activității
 * creatorului). Redundantă pe Android 15/16 publice, unde o lansare „for result” o primește singură, dar nu mai depinde
 * de asta (build-uri Android 14 timpurii, OEM). 4 = MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE (API 36).
 */
@SuppressLint("WrongConstant")
internal fun consentLaunchOptions(): ActivityOptionsCompat? = when {
    Build.VERSION.SDK_INT >= 36 -> ActivityOptionsCompat.makeBasic().setPendingIntentBackgroundActivityStartMode(4)
    Build.VERSION.SDK_INT >= 34 -> ActivityOptionsCompat.makeBasic()
        .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
    else -> null
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
