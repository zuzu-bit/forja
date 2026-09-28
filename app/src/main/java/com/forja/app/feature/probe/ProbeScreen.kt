package com.forja.app.feature.probe

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forja.app.core.designsystem.Accent
import com.forja.app.core.designsystem.Accent2
import com.forja.app.core.designsystem.BodySmall
import com.forja.app.core.designsystem.BodyStrong
import com.forja.app.core.designsystem.Error
import com.forja.app.core.designsystem.OnAccent
import com.forja.app.core.designsystem.Positive
import com.forja.app.core.designsystem.StrokeCardStrong
import com.forja.app.core.designsystem.Surface0
import com.forja.app.core.designsystem.Surface2
import com.forja.app.core.designsystem.TextDim
import com.forja.app.core.designsystem.TextPrimary
import com.forja.app.core.designsystem.TextSecondary
import com.forja.app.core.designsystem.TitleModule
import com.forja.app.core.designsystem.components.ForjaCard
import com.forja.app.core.designsystem.components.MonoButton
import com.forja.app.core.designsystem.components.PrimaryButton
import com.forja.app.core.designsystem.components.SecondaryButton
import com.forja.app.core.designsystem.components.SectionLabel
import com.forja.app.core.designsystem.components.StampLabel
import com.forja.app.core.designsystem.components.pressable
import com.forja.app.core.designsystem.monoLabel
import com.forja.app.core.music.DiagResult
import com.forja.app.core.music.MediaKind
import com.forja.app.core.music.Music
import com.forja.app.core.music.MusicCue
import com.forja.app.core.music.MusicIcons
import com.forja.app.core.music.MusicKind
import com.forja.app.core.music.MusicLog
import com.forja.app.core.music.MusicProbe
import com.forja.app.core.music.MusicStarter
import com.forja.app.core.music.PState
import com.forja.app.core.music.Rung
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Ce arată ecranul Probă (fără Android, pentru captură). */
internal data class ProbeUi(
    val access: Boolean,
    val keyTarget: String?,
    val players: List<Pair<String, String>>,
    val selected: String?,
    val sessions: List<String>,
    val rows: List<MusicProbe.Row>,
    val running: Boolean,
    val upload: String? = null,
    val journal: List<String> = emptyList()
)

internal data class ProbeActions(
    val onBack: () -> Unit = {},
    val onPlayer: (String) -> Unit = {},
    val onRunAll: () -> Unit = {},
    val onRunVisible: (Rung) -> Unit = {},
    val onUpload: () -> Unit = {},
    val onCopy: () -> Unit = {},
    val onBeep: () -> Unit = {},
    val onForget: () -> Unit = {},
    val onAccess: () -> Unit = {}
)

/**
 * Proba ascunsă (Profil → 5 atingeri pe versiune): fiecare treaptă de pornire o dată, pe playerul ei, iar jurnalul
 * (fără titluri) pleacă la serverul FORJA. Spune pe telefonul ei ce merge: comanda de sesiune, tasta media, saltul.
 */
@Composable
fun ProbeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rows by MusicProbe.rows.collectAsState()
    val running by MusicProbe.running.collectAsState()
    val events by MusicLog.events.collectAsState()
    var snap by remember { mutableStateOf(MusicStarter.snapshotNow()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var upload by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        Music.ensureStarted(context)
        MusicLog.ensureLoaded(context)
        while (true) {
            snap = MusicStarter.snapshotNow()
            delay(2_000)
        }
    }
    val players = (snap.installed + snap.sessions.map { it.pkg }.filter { MusicKind.isMusicPlayer(it, snap.historyPkgs) })
        .distinct()
        .map { it to (MusicKind.MUSIC_APPS[it] ?: it.substringAfterLast('.').replaceFirstChar { c -> c.uppercase() }) }
    val pkg = selected ?: snap.preferredPkg ?: players.firstOrNull()?.first ?: MusicKind.SPOTIFY

    ProbeContent(
        ProbeUi(
            access = snap.access,
            keyTarget = snap.keyTarget,
            players = players,
            selected = pkg,
            sessions = snap.sessions.map { s ->
                val name = MusicKind.MUSIC_APPS[s.pkg] ?: s.pkg.substringAfterLast('.')
                "$name · ${kindWord(s.kind)} · ${stateWord(s.state)}" + (snap.versions[s.pkg]?.let { " · $it" } ?: "")
            },
            rows = rows,
            running = running,
            upload = upload,
            journal = events.takeLast(30).reversed().map { e ->
                listOfNotNull(e.want, e.rung, e.pkg?.substringAfterLast('.'), e.result.wire, "${e.ms} ms", e.err).joinToString(" · ")
            }
        ),
        ProbeActions(
            onBack = onBack,
            onPlayer = { selected = it; MusicProbe.reset() },
            onRunAll = {
                upload = null
                scope.launch {
                    MusicProbe.runInvisible(context, pkg)
                    upload = uploadText(MusicLog.upload(context))
                }
            },
            onRunVisible = { r ->
                upload = null
                scope.launch {
                    MusicProbe.runVisible(context, r, pkg)
                    upload = uploadText(MusicLog.upload(context))
                }
            },
            onUpload = { scope.launch { upload = uploadText(MusicLog.upload(context)) } },
            onCopy = { copy(context, MusicLog.text()) ; upload = "Jurnalul e copiat." },
            onBeep = { scope.launch { MusicCue.play(context, MusicCue.Cue.BEEP, duck = true) } },
            onForget = {
                scope.launch {
                    MusicStarter.resetLearned(context)
                    MusicProbe.reset()
                    upload = "Tabelul învățat e gol."
                }
            },
            onAccess = {
                try { context.startActivity(Music.accessIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {
                    try { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
                }
            }
        )
    )
}

private fun uploadText(r: MusicLog.Upload): String = when (r) {
    MusicLog.Upload.SENT -> "Jurnalul a plecat la FORJA."
    MusicLog.Upload.NOTHING -> "Nimic nou de trimis."
    MusicLog.Upload.NO_CONTRACT -> "Semnează contractul ca să-l trimiți."
    MusicLog.Upload.NO_ACCOUNT -> "Intră în cont ca să-l trimiți."
    MusicLog.Upload.FAILED -> "Nu a plecat. Încearcă din nou."
}

private fun copy(context: Context, text: String) {
    try {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("FORJA · muzică", text))
    } catch (_: Exception) { }
}

private fun kindWord(k: MediaKind) = when (k) {
    MediaKind.MUSIC -> "muzică"
    MediaKind.SPOKEN -> "vorbit"
    MediaKind.VIDEO -> "video"
    MediaKind.UNKNOWN -> "necunoscut"
}

private fun stateWord(s: PState) = when (s) {
    PState.PLAYING -> "cântă"
    PState.PAUSED -> "pauză"
    PState.STOPPED -> "oprit"
    PState.BUFFERING, PState.CONNECTING, PState.SKIPPING -> "se încarcă"
    PState.ERROR -> "eroare"
    PState.NONE -> "gol"
}

/** Ecranul Probă, fără Android. */
@Composable
internal fun ProbeContent(ui: ProbeUi, actions: ProbeActions, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .background(Surface0)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Surface2)
                    .border(1.dp, StrokeCardStrong, RoundedCornerShape(8.dp))
                    .pressable(actions.onBack)
                    .semantics { role = Role.Button; contentDescription = "Înapoi" },
                contentAlignment = Alignment.Center
            ) {
                Icon(MusicIcons.Back, null, tint = TextPrimary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.weight(1f))
            StampLabel("PROBĂ · MUZICĂ", appear = false)
        }
        Spacer(Modifier.height(14.dp))
        Text("Pornirea muzicii.", style = TitleModule)
        Spacer(Modifier.height(16.dp))

        // Ce vede FORJA acum.
        ForjaCard(Modifier.fillMaxWidth()) {
            InfoLine("ACCES", if (ui.access) "da" else "nu", if (ui.access) Positive else Error)
            InfoLine("TASTA MEDIA", ui.keyTarget ?: "—")
            if (ui.sessions.isEmpty()) InfoLine("SESIUNI", "niciuna")
            ui.sessions.forEachIndexed { i, s -> InfoLine(if (i == 0) "SESIUNI" else "", s) }
            if (!ui.access) {
                Spacer(Modifier.height(10.dp))
                ActionChip("Dă acces", onClick = actions.onAccess)
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("Playerul")
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((pkg, name) in ui.players) {
                val on = pkg == ui.selected
                Text(
                    name,
                    style = BodyStrong.copy(fontSize = 13.sp, color = if (on) OnAccent else TextSecondary),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (on) Accent.copy(alpha = 0.45f) else Surface2)
                        .border(1.dp, if (on) Accent2 else StrokeCardStrong, RoundedCornerShape(6.dp))
                        .pressable({ actions.onPlayer(pkg) })
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        SectionLabel("Fără salt · pe rând")
        Spacer(Modifier.height(8.dp))
        ForjaCard(Modifier.fillMaxWidth(), padding = 4.dp) {
            ui.rows.filter { it.rung in MusicProbe.INVISIBLE }.forEach { RungRow(it, null, ui.running) }
        }
        Spacer(Modifier.height(10.dp))
        PrimaryButton(if (ui.running) "Rulează…" else "Pornește proba", onClick = actions.onRunAll, enabled = !ui.running, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(18.dp))
        SectionLabel("Cu salt în player · una pe atingere")
        Spacer(Modifier.height(8.dp))
        ForjaCard(Modifier.fillMaxWidth(), padding = 4.dp) {
            ui.rows.filter { it.rung in MusicProbe.VISIBLE }.forEach { RungRow(it, { actions.onRunVisible(it.rung) }, ui.running) }
        }

        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Trimite jurnalul", onClick = actions.onUpload)
            SecondaryButton("Copiază", onClick = actions.onCopy)
        }
        ui.upload?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = BodySmall.copy(color = TextSecondary))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MonoButton("Sunet peste muzică", onClick = actions.onBeep)
            MonoButton("Șterge învățarea", onClick = actions.onForget)
        }

        if (ui.journal.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            SectionLabel("Jurnal")
            Spacer(Modifier.height(8.dp))
            for (line in ui.journal) {
                Text(line, style = monoLabel(9, 0.02f).copy(color = TextDim), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 3.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * O singură coloană de valori pentru ambele carduri: cardul de sus (padding 14) și rândurile treptelor (4 + 10) au
 * aceeași margine, iar eticheta are aceeași lățime (încape „V_LIKED_PLAY” și la font mărit).
 */
private val LabelColumn = 108.dp

@Composable
private fun InfoLine(label: String, value: String, tint: Color = TextPrimary) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = monoLabel(9, 0.12f).copy(color = TextDim), modifier = Modifier.width(LabelColumn).padding(top = 2.dp))
        Text(value, style = BodyStrong.copy(fontSize = 13.sp, color = tint), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RungRow(row: MusicProbe.Row, onRun: (() -> Unit)?, running: Boolean) {
    // Rândul crește la două rânduri de rezultat („sărit · keyTarget:…” nu se mai taie în identificator).
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(row.rung.id, style = monoLabel(10, 0.06f).copy(color = TextPrimary), modifier = Modifier.width(LabelColumn))
        val (text, color) = when (row.status) {
            MusicProbe.Status.WAITING -> "—" to TextDim
            MusicProbe.Status.RUNNING -> "rulează…" to Accent2
            MusicProbe.Status.DONE -> resultWord(row) to when (row.result) {
                DiagResult.OK -> Positive
                DiagResult.WRONG_TRACK, DiagResult.SKIPPED, DiagResult.NEEDS_TAP -> TextSecondary
                else -> Error
            }
        }
        Text(text, style = BodySmall.copy(color = color), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (onRun != null) {
            Spacer(Modifier.width(8.dp))
            ActionChip("Probează", onClick = onRun, enabled = !running)
        }
    }
}

/**
 * Butonul mic de acțiune al probei („Dă acces”, „Probează”): margine olive și fond olive stins, ca să se vadă că se
 * apasă lângă „Pornește proba”; cât rulează proba, gri și fără atingere.
 */
@Composable
private fun ActionChip(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .clip(shape)
            .background(if (enabled) Accent.copy(alpha = 0.28f) else Surface2)
            .border(1.dp, if (enabled) Accent2 else StrokeCardStrong, shape)
            .then(if (enabled) Modifier.pressable(onClick) else Modifier)
            .semantics {
                role = Role.Button
                if (!enabled) disabled()
            }
            .padding(vertical = 10.dp, horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = monoLabel(11, 0.10f).copy(color = if (enabled) OnAccent else TextDim), maxLines = 1)
    }
}

private fun resultWord(row: MusicProbe.Row): String {
    val base = when (row.result) {
        DiagResult.OK -> "merge"
        DiagResult.REFUSED -> "refuzat"
        DiagResult.TIMEOUT -> "nimic"
        DiagResult.WRONG_KIND -> "alt fel"
        DiagResult.WRONG_TRACK -> "altă piesă"
        DiagResult.ERROR -> "eroare"
        DiagResult.SKIPPED -> "sărit"
        DiagResult.NEEDS_TAP -> "cere atingere"
        null -> "—"
    }
    return listOfNotNull(base, row.ms?.takeIf { it > 0 }?.let { "$it ms" }, row.note).joinToString(" · ")
}
