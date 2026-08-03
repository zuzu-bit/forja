package com.forja.app.feature.sleep

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.forja.app.ForjaApp
import com.forja.app.core.data.db.SleepEventEntity
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*
import com.forja.app.core.sleep.SleepTrackService
import com.forja.app.core.util.Fmt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/** Somn à la Sleep as Android: microfon local, hipnogramă pe cicluri, alarmă deșteaptă. */
@Composable
fun SleepScreen() {
    val context = LocalContext.current
    val app = remember { ForjaApp.from(context) }
    val scope = rememberCoroutineScope()
    val active by app.db.sleepDao().activeSession().collectAsState(initial = null)
    val last by app.db.sleepDao().lastFinished().collectAsState(initial = null)
    val week by app.db.sleepDao().finishedSince(Fmt.startOfDayMillis(6)).collectAsState(initial = emptyList())
    val toast = LocalToast.current

    val alarmEnabled by app.prefs.alarmEnabled.collectAsState(initial = false)
    val alarmHour by app.prefs.alarmHour.collectAsState(initial = 7)
    val alarmMinute by app.prefs.alarmMinute.collectAsState(initial = 0)
    val alarmWindow by app.prefs.alarmWindowMin.collectAsState(initial = 40)

    fun startSleepExtras() {
        // Plasa de siguranță (ca Gemini): alarma de sistem la ora-limită.
        if (alarmEnabled) {
            val ok = com.forja.app.core.sleep.SystemAlarm.set(
                context, alarmHour, alarmMinute,
                "FORJA · plasă de siguranță — trebuia să fii treaz"
            )
            if (ok) toast.show("Alarmă setată și în Ceasul telefonului, la %02d:%02d — plasă de siguranță.".format(alarmHour, alarmMinute))
        }
    }

    var hasMic by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasMic = granted
        SleepTrackService.start(context)
        startSleepExtras()
        toast.show(
            if (granted) "Noapte bună. Microfonul ascultă local — nimic nu pleacă de pe telefon."
            else "Noapte bună. Fără microfon: doar mișcarea se analizează."
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(SleepBg)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 120.dp)
    ) {
        // Header video dimineață + scor
        Box(Modifier.fillMaxWidth().height(252.dp)) {
            VideoSurface(
                url = "https://v.ftcdn.net/11/26/44/56/700_F_1126445619_bJBEc25rOq3b1ofF41h2oJgHrEOy7kVy_ST.mp4",
                posterUrl = "https://t3.ftcdn.net/jpg/04/70/98/78/500_F_470987805_jsREzUZZZNUDZ56fG4J9Cpz4UquN6zJg.jpg",
                modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.2f to Color.Transparent,
                            0.7f to Color(0xB30B111C),
                            1f to Color(0xFF0B111C)
                        )
                    )
            )
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(20.dp)
            ) {
                Text("Somn", style = TitleModule)
                Text("RAPORT DE DIMINEAȚĂ", style = monoLabel(9, 0.16f).copy(color = SleepRem))
            }
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val score = last?.score ?: 0
                ProgressRing(
                    progress = score / 100f,
                    ringSize = 96.dp,
                    strokeWidth = 7.dp,
                    track = Color(0x2E7896BE),
                    brush = Brush.linearGradient(listOf(SleepDeep, SleepRem))
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$score", style = heroNumeral(30))
                        Text(
                            when {
                                score >= 80 -> "ODIHNIT"
                                score >= 60 -> "DECENT"
                                score > 0 -> "OBOSIT"
                                else -> "—"
                            },
                            style = monoLabel(7, 0.14f).copy(color = SleepTextDim)
                        )
                    }
                }
                Spacer(Modifier.width(18.dp))
                Column {
                    last?.let { s ->
                        val min = (((s.endAt ?: s.startAt) - s.startAt) / 60000).toInt()
                        Text(Fmt.durationHm(min), style = heroNumeral(30))
                        Text(
                            "${Fmt.clock(s.startAt)} → ${Fmt.clock(s.endAt ?: s.startAt)}",
                            style = monoLabel(9, 0.10f).copy(color = SleepTextDim)
                        )
                    } ?: Text("Prima noapte cu FORJA\nte așteaptă.", style = Body.copy(color = SleepTextDim))
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Pornire/oprire sesiune
        if (active != null) {
            ForjaCard(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                fill = SleepCard, stroke = SleepStroke
            ) {
                Text("Sesiune de somn activă", style = BodyStrong)
                Spacer(Modifier.height(2.dp))
                Text(
                    "De la ${Fmt.clock(active!!.startAt)} · ${if (hasMic) "microfon + mișcare, analizate local" else "doar mișcare (fără microfon)"}.",
                    style = BodySmall.copy(color = SleepTextDim)
                )
                Spacer(Modifier.height(12.dp))
                PrimaryButton(
                    text = "M-am trezit",
                    onClick = {
                        SleepTrackService.stop(context)
                        toast.show("Raportul se pregătește…")
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            PrimaryButton(
                text = "Încep să dorm",
                onClick = {
                    if (hasMic) {
                        SleepTrackService.start(context)
                        startSleepExtras()
                        toast.show("Noapte bună. Lasă telefonul lângă tine, cu fața în jos.")
                    } else {
                        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            )
        }

        Spacer(Modifier.height(20.dp))

        // Sunete de adormit — fișiere reale, carduri mari cu imagini
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionLabel("Sunete de adormit", color = SleepTextDim)
            Spacer(Modifier.width(8.dp))
            InfoDot(
                title = "Despre sunete",
                text = "Sunete reale, redate din aplicație — merg și fără internet. Se opresc la finalul temporizatorului sau când apeși din nou.\n\nSursă (freesound.org): inchadney și felix.blume — CC0; D W, Corsica_S, mystiscool și RHumphries — CC BY."
            )
        }
        Spacer(Modifier.height(12.dp))

        // Temporizator — se oprește peste…
        val selTimer by com.forja.app.core.sleep.SleepSounds.timerMinutes.collectAsState()
        Row(Modifier.padding(horizontal = 20.dp)) {
            listOf(15 to "15 min", 30 to "30 min", 45 to "45 min", 60 to "1 oră", 0 to "∞").forEach { (m, lbl) ->
                val sel = selTimer == m
                Box(
                    Modifier.padding(end = 8.dp).clip(ChipShape)
                        .background(if (sel) TabPillActive else Color(0x1A7896BE))
                        .pressable({ com.forja.app.core.sleep.SleepSounds.setTimer(m) })
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) { Text(lbl, style = BodyStrong.copy(fontSize = 13.sp, color = if (sel) Accent2 else SleepTextDim)) }
            }
        }
        Spacer(Modifier.height(12.dp))

        val playingSound by com.forja.app.core.sleep.SleepSounds.current.collectAsState()
        val sounds = listOf(
            Triple("rain", "Ploaie", "snd_rain.jpg"),
            Triple("storm", "Furtună", "snd_storm.jpg"),
            Triple("wind", "Vânt", "snd_wind.jpg"),
            Triple("stream", "Pârâu", "snd_stream.jpg"),
            Triple("fire", "Foc", "snd_fire.jpg"),
            Triple("forest", "Pădure", "snd_forest.jpg")
        )
        Column(Modifier.padding(horizontal = 20.dp)) {
            sounds.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEachIndexed { idx, (key, label, img) ->
                        SoundCard(
                            label = label,
                            imageKey = img,
                            active = playingSound == key,
                            modifier = Modifier.weight(1f).padding(end = if (idx == 0) 10.dp else 0.dp),
                            onClick = { com.forja.app.core.sleep.SleepSounds.toggle(context, key) }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }

        Spacer(Modifier.height(10.dp))

        // Alarma circadiană — „treaz cel târziu la…"
        SectionLabel("Alarma circadiană", Modifier.padding(horizontal = 20.dp), color = SleepTextDim)
        Spacer(Modifier.height(10.dp))
        ForjaCard(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            fill = SleepCard, stroke = SleepStroke
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("TREAZ CEL TÂRZIU LA", style = monoLabel(8, 0.14f).copy(color = SleepTextDim))
                    Text(
                        "%02d:%02d".format(alarmHour, alarmMinute),
                        style = heroNumeral(34)
                    )
                    Text(
                        if (alarmEnabled)
                            "te trezesc la finalul unui ciclu de somn, cu cel mult $alarmWindow min înainte — niciodată mai târziu"
                        else "oprită",
                        style = BodyTiny.copy(color = SleepTextDim)
                    )
                }
                ForjaSwitch(checked = alarmEnabled, onCheckedChange = { on ->
                    scope.launch {
                        app.prefs.setAlarmEnabled(on)
                        if (on) toast.show("La culcare setez și alarma din Ceas la %02d:%02d — plasă de siguranță.".format(alarmHour, alarmMinute))
                    }
                })
            }
            Spacer(Modifier.height(10.dp))
            Row {
                listOf(6 to 30, 7 to 0, 7 to 30, 8 to 0).forEach { (h, m) ->
                    val sel = h == alarmHour && m == alarmMinute
                    Box(
                        Modifier
                            .padding(end = 8.dp)
                            .clip(ChipShape)
                            .background(if (sel) TabPillActive else Color(0x1A7896BE))
                            .pressable({ scope.launch { app.prefs.setAlarmTime(h, m) } })
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            "%02d:%02d".format(h, m),
                            style = BodyStrong.copy(fontSize = 13.sp, color = if (sel) Accent2 else SleepTextDim)
                        )
                    }
                }
                Box(
                    Modifier
                        .padding(end = 8.dp)
                        .clip(ChipShape)
                        .background(Color(0x1A7896BE))
                        .pressable({
                            scope.launch {
                                var m = alarmMinute + 15
                                var h = alarmHour
                                if (m >= 60) { m -= 60; h = (h + 1) % 24 }
                                app.prefs.setAlarmTime(h, m)
                            }
                        })
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("+15 min", style = BodyStrong.copy(fontSize = 13.sp, color = SleepTextDim))
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("FEREASTRA DE TREZIRE", style = monoLabel(8, 0.14f).copy(color = SleepTextDim))
            Spacer(Modifier.height(6.dp))
            Row {
                listOf(20, 30, 40).forEach { w ->
                    val sel = w == alarmWindow
                    Box(
                        Modifier
                            .padding(end = 8.dp)
                            .clip(ChipShape)
                            .background(if (sel) TabPillActive else Color(0x1A7896BE))
                            .pressable({ scope.launch { app.prefs.setAlarmWindowMin(w) } })
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            "$w min",
                            style = BodyStrong.copy(fontSize = 13.sp, color = if (sel) Accent2 else SleepTextDim)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // Rezumatul de dimineață — AI, două propoziții din cifre reale.
        if (!last?.summary.isNullOrBlank()) {
            ForjaCard(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                fill = SleepCard, stroke = SleepStroke
            ) {
                SectionLabel("Rezumatul dimineții", color = SleepTextDim)
                Spacer(Modifier.height(6.dp))
                Text(last!!.summary, style = Body.copy(color = TextPrimary, fontSize = 14.sp, lineHeight = 19.sp))
            }
            Spacer(Modifier.height(20.dp))
        }

        // Înregistrarea completă a nopții — disponibilă 24h, apoi dispare singură.
        last?.let { s ->
            if (s.recordedUntil > System.currentTimeMillis()) {
                NightRecordingCard(session = s, app = app)
                Spacer(Modifier.height(20.dp))
            }
        }

        // Hipnograma — ciclurile nopții
        SectionLabel("Ciclurile nopții", Modifier.padding(horizontal = 20.dp), color = SleepTextDim)
        Spacer(Modifier.height(10.dp))
        ForjaCard(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            fill = SleepCard, stroke = SleepStroke
        ) {
            val s = last
            if (s == null || s.phases.isBlank()) {
                Text("Hipnograma apare după prima noapte înregistrată.", style = BodySmall.copy(color = SleepTextDim))
            } else {
                Hypnogram(s.phases, Modifier.fillMaxWidth().height(96.dp))
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    PhaseLegend("Profund", Fmt.durationHm(s.deepMin), SleepDeep)
                    PhaseLegend("Ușor", Fmt.durationHm(s.lightMin), SleepLight)
                    PhaseLegend("REM", Fmt.durationHm(s.remMin), SleepRem)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Estimare din mișcare și cicluri de ~90 min · ${s.movements} mișcări",
                    style = monoLabel(8, 0.10f).copy(color = SleepTextDim)
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // Evenimentele nopții — cu clipuri de 5s
        SectionLabel("Noaptea ta · Evenimente", Modifier.padding(horizontal = 20.dp), color = SleepTextDim)
        Spacer(Modifier.height(10.dp))
        val lastId = last?.id
        if (lastId != null) {
            val events by app.db.sleepDao().eventsForSession(lastId).collectAsState(initial = emptyList())
            if (events.isEmpty()) {
                ForjaCard(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    fill = SleepCard, stroke = SleepStroke
                ) {
                    Text(
                        "Nicio noapte zgomotoasă înregistrată — sau microfonul n-a fost pornit.",
                        style = BodySmall.copy(color = SleepTextDim)
                    )
                }
            } else {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    events.forEach { ev -> SleepEventCard(ev, app) }
                    val talkPhrases = events
                        .filter { it.type == "talk" && !it.transcript.isNullOrBlank() }
                        .mapNotNull { it.transcript }
                    val snoreCount = events.count { it.type == "snore" }
                    if (talkPhrases.isNotEmpty() || snoreCount > 0) {
                        Spacer(Modifier.height(12.dp))
                        SleepTalkSummary(talkPhrases, snoreCount, app)
                    }
                }
            }
        } else {
            ForjaCard(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                fill = SleepCard, stroke = SleepStroke
            ) {
                Text("Evenimentele apar după prima noapte.", style = BodySmall.copy(color = SleepTextDim))
            }
        }

        Spacer(Modifier.height(20.dp))

        // Tendința săptămânii
        SectionLabel("Săptămâna ta", Modifier.padding(horizontal = 20.dp), color = SleepTextDim)
        Spacer(Modifier.height(10.dp))
        ForjaCard(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            fill = SleepCard, stroke = SleepStroke
        ) {
            val byDay = (0..6).map { ago ->
                val dayStart = Fmt.startOfDayMillis((6 - ago).toLong())
                val dayEnd = dayStart + 24 * 3600_000
                week.filter { it.startAt in dayStart until dayEnd }
                    .sumOf { (((it.endAt ?: it.startAt) - it.startAt) / 60000).toInt() }
            }
            val maxMin = (byDay.maxOrNull() ?: 0).coerceAtLeast(480)
            val avg = byDay.filter { it > 0 }.let { if (it.isEmpty()) 0 else it.sum() / it.size }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(90.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                byDay.forEachIndexed { i, min ->
                    val isToday = i == 6
                    val h by animateFloatAsState(
                        (min.toFloat() / maxMin).coerceIn(0.04f, 1f),
                        Springs.natural(), label = "bar$i"
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .width(22.dp)
                                .fillMaxHeight(h)
                                .clip(CircleShape)
                                .background(
                                    if (isToday) Brush.verticalGradient(listOf(Accent, Accent2))
                                    else Brush.verticalGradient(listOf(SleepLight, SleepDeep))
                                )
                        )
                        Spacer(Modifier.height(6.dp))
                        val dayIdx = (java.time.LocalDate.now().dayOfWeek.value - 1 - (6 - i) + 7) % 7
                        Text(
                            if (isToday) "azi" else Fmt.dayLetters[dayIdx],
                            style = monoLabel(8, 0.08f).copy(color = if (isToday) Accent2 else SleepTextDim)
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (avg > 0) "media ${Fmt.durationHm(avg)}" else "încă fără date",
                style = monoLabel(8, 0.10f).copy(color = SleepTextDim)
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Despre somn & confidențialitate", style = BodyTiny.copy(color = SleepTextDim))
            Spacer(Modifier.width(8.dp))
            InfoDot(
                title = "Despre somn",
                text = "FORJA nu pune diagnostice. Sunetul se analizează local, clipurile rămân pe telefon și le ștergi tu. Dacă sforăitul revine des, vorbește cu un medic — ai istoricul aici."
            )
        }
    }
}

/**
 * Player-ul întregii nopți: local dacă fișierul mai există, altfel din stocarea
 * companiei (se șterge automat la 24h). „Sari la moment” pentru fiecare eveniment.
 */
@Composable
private fun NightRecordingCard(session: com.forja.app.core.data.db.SleepSessionEntity, app: ForjaApp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    val events by app.db.sleepDao().eventsForSession(session.id).collectAsState(initial = emptyList())

    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var positionS by remember { mutableStateOf(0) }
    var durationS by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        onDispose {
            try { player?.release() } catch (_: Exception) { }
        }
    }
    LaunchedEffect(playing) {
        while (playing) {
            kotlinx.coroutines.delay(500)
            try {
                player?.let {
                    positionS = it.currentPosition / 1000
                    if (durationS == 0 && it.duration > 0) durationS = it.duration / 1000
                }
            } catch (_: Exception) { }
        }
    }

    fun preparePlayer(onReady: (MediaPlayer) -> Unit) {
        val existing = player
        if (existing != null) { onReady(existing); return }
        if (preparing) return
        preparing = true
        scope.launch {
            try {
                val mp = MediaPlayer()
                val local = java.io.File(java.io.File(context.filesDir, "sleep_full"), "${session.id}.m4a")
                if (local.exists() && local.length() > 4000) {
                    mp.setDataSource(local.absolutePath)
                } else if (app.forjaApi.available) {
                    val auth = app.forjaApi.authHeader()
                    if (auth == null) {
                        toast.show("Intră în cont ca să asculți înregistrarea.")
                        preparing = false
                        return@launch
                    }
                    mp.setDataSource(
                        context,
                        android.net.Uri.parse(app.forjaApi.sleepRecordingUrl(session.id)),
                        mapOf("Authorization" to auth)
                    )
                } else {
                    toast.show("Înregistrarea nu mai e disponibilă.")
                    preparing = false
                    return@launch
                }
                mp.setOnPreparedListener {
                    preparing = false
                    player = mp
                    durationS = (mp.duration / 1000).coerceAtLeast(0)
                    onReady(mp)
                }
                mp.setOnCompletionListener { playing = false; positionS = 0 }
                mp.setOnErrorListener { _, _, _ ->
                    preparing = false
                    playing = false
                    toast.show("Înregistrarea nu s-a putut reda — poate a expirat (24h).")
                    true
                }
                mp.prepareAsync()
            } catch (_: Exception) {
                preparing = false
                toast.show("Înregistrarea nu s-a putut deschide.")
            }
        }
    }

    ForjaCard(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        fill = SleepCard, stroke = SleepStroke
    ) {
        SectionLabel("Înregistrarea nopții", color = SleepTextDim)
        Spacer(Modifier.height(4.dp))
        Text(
            "Toată noaptea, dacă vrei s-o auzi. Se șterge automat după 24 de ore — de peste tot.",
            style = BodyTiny.copy(color = SleepTextDim)
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(
                text = when {
                    preparing -> "se încarcă…"
                    playing -> "Pauză"
                    else -> "▶ Ascultă toată noaptea"
                },
                small = true,
                onClick = {
                    val p = player
                    if (p != null && playing) {
                        p.pause(); playing = false
                    } else {
                        preparePlayer { mp -> mp.start(); playing = true }
                    }
                },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                if (durationS > 0) "${Fmt.durationMs(positionS.toLong())} / ${Fmt.durationMs(durationS.toLong())}"
                else "--:-- / --:--",
                style = monoLabel(10, 0.08f).copy(color = SleepTextDim)
            )
        }
        val audioEvents = events.filter { it.type in setOf("snore", "talk", "sound") }
        if (audioEvents.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("SARI LA MOMENT", style = monoLabel(8, 0.14f).copy(color = SleepTextDim))
            Spacer(Modifier.height(6.dp))
            Row {
                audioEvents.take(4).forEach { ev ->
                    Box(
                        Modifier
                            .padding(end = 8.dp)
                            .background(Color(0x1A7896BE), ChipShape)
                            .pressable({
                                val offsetMs = (ev.at - session.startAt - 5000).coerceAtLeast(0)
                                preparePlayer { mp ->
                                    mp.seekTo(offsetMs.toInt())
                                    mp.start()
                                    playing = true
                                }
                            })
                            .padding(horizontal = 9.dp, vertical = 6.dp)
                    ) {
                        Text(
                            "${if (ev.type == "talk") "vorbit" else "sforăit"} · ${Fmt.clock(ev.at)}",
                            style = BodyTiny.copy(color = SleepRem)
                        )
                    }
                }
            }
        }
    }
}

/** Card de sunet — mare, cu imagine de fundal generată, glow amber când sună. */
@Composable
private fun SoundCard(
    label: String,
    imageKey: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(Radii.card)
    val imgUrl = remember(imageKey) { com.forja.app.core.media.Media.mediaUrl(imageKey) }
    Box(
        modifier
            .height(104.dp)
            .clip(shape)
            .background(SleepCard)
            .border(1.5.dp, if (active) Color(0xB3FFB300) else SleepStroke, shape)
            .pressable(onClick)
    ) {
        if (imgUrl != null) {
            coil.compose.AsyncImage(
                model = imgUrl, contentDescription = label,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color(0x330B111C), 0.5f to Color(0x990B111C), 1f to Color(0xE60B111C)
                )
            )
        )
        Row(
            Modifier.align(Alignment.BottomStart).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(26.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (active) AccentGradient else Brush.linearGradient(listOf(Color(0x66FFFFFF), Color(0x33FFFFFF)))),
                contentAlignment = Alignment.Center
            ) {
                Text(if (active) "⏸" else "▶", style = BodySmall.copy(color = if (active) OnAccent else Color.White, fontSize = 12.sp))
            }
            Spacer(Modifier.width(8.dp))
            Text(label, style = BodyStrong.copy(fontSize = 15.sp, color = Color.White))
        }
        if (active) {
            Text(
                "sună",
                style = monoLabel(8, 0.14f).copy(color = Accent2),
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
            )
        }
    }
}

/** Hipnogramă în trepte, à la Sleep as Android: treaz sus → REM → ușor → profund jos. */
@Composable
private fun Hypnogram(phases: String, modifier: Modifier = Modifier) {
    val segments = remember(phases) {
        phases.split(';').mapNotNull { seg ->
            val p = seg.split(',')
            val s = p.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val e = p.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            val t = p.getOrNull(2) ?: return@mapNotNull null
            Triple(s, e, t)
        }
    }
    if (segments.isEmpty()) return
    val total = segments.maxOf { it.second }.coerceAtLeast(1)
    Canvas(modifier) {
        fun level(t: String) = when (t) {
            "awake" -> 0.08f
            "rem" -> 0.35f
            "light" -> 0.62f
            else -> 0.90f          // deep
        }
        fun colorFor(t: String) = when (t) {
            "awake" -> Color(0xFFFFB300)
            "rem" -> SleepRem
            "light" -> SleepLight
            else -> SleepDeep
        }
        var prevX = 0f
        var prevY = size.height * level(segments.first().third)
        segments.forEach { (s, e, t) ->
            val x1 = size.width * s / total
            val x2 = size.width * e / total
            val y = size.height * level(t)
            // treaptă: linie verticală + orizontală
            drawLine(colorFor(t), Offset(x1, prevY), Offset(x1, y), strokeWidth = 3f, cap = StrokeCap.Round)
            drawLine(colorFor(t), Offset(x1, y), Offset(x2, y), strokeWidth = 5f, cap = StrokeCap.Round)
            prevX = x2
            prevY = y
        }
    }
}

@Composable
private fun SleepTalkSummary(phrases: List<String>, snoreCount: Int, app: ForjaApp) {
    var summary by remember(phrases) { mutableStateOf<String?>(null) }
    LaunchedEffect(phrases) {
        if (phrases.isNotEmpty() && app.forjaApi.available) {
            summary = try { app.forjaApi.sleepTalkSummary(phrases) } catch (_: Exception) { null }
        }
    }
    ForjaCard(
        Modifier.fillMaxWidth(),
        fill = SleepCard, stroke = SleepStroke
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Vorbe din somn", style = BodyStrong.copy(fontSize = 15.sp), modifier = Modifier.weight(1f))
            InfoDot(
                title = "Despre vorbitul în somn",
                text = "Vorbitul în somn e frecvent și, de obicei, inofensiv — sunt frânturi ale subconștientului, nu un diagnostic. Privește-le cu blândețe; dacă apar des și te obosesc, un somn mai odihnitor și mai puțin stres ajută cel mai mult."
            )
        }
        Spacer(Modifier.height(8.dp))
        if (phrases.isEmpty()) {
            Text(
                if (snoreCount > 0) "Azi-noapte n-ai vorbit — doar ai sforăit." else "Azi-noapte n-ai vorbit.",
                style = BodySmall.copy(color = SleepTextDim)
            )
        } else {
            summary?.let {
                Text(it, style = BodySmall.copy(color = TextSecondary, lineHeight = 19.sp))
                Spacer(Modifier.height(8.dp))
            }
            Text("CE S-A AUZIT", style = monoLabel(8, 0.14f).copy(color = SleepTextDim))
            Spacer(Modifier.height(4.dp))
            phrases.take(8).forEach { p ->
                Text("• „$p”", style = BodySmall.copy(color = SleepRem))
            }
        }
    }
}

@Composable
private fun SleepEventCard(ev: SleepEventEntity, app: ForjaApp) {
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    val typeName = when (ev.type) {
        "snore" -> "Sforăit"
        "talk" -> "Vorbire"
        "sound" -> "Sunet"
        else -> "Mișcare"
    }
    val intensityWord = when (ev.intensity) {
        3 -> "Puternic"
        2 -> "Moderat"
        else -> "Redus"
    }
    ForjaCard(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .pressable({ expanded = !expanded }),
        fill = SleepCard, stroke = if (expanded) Color(0x8CFFB300) else SleepStroke,
        padding = 12.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (ev.type == "snore") SleepDeep else if (ev.type == "talk") SleepRem else SleepLight)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(typeName, style = BodyStrong.copy(fontSize = 14.sp))
                Text(
                    "${Fmt.clock(ev.at)} · ${ev.durationS} s",
                    style = monoLabel(8, 0.10f).copy(color = SleepTextDim)
                )
            }
            Text(intensityWord, style = BodySmall.copy(color = if (ev.intensity >= 3) Accent2 else SleepTextDim))
        }
        if (expanded) {
            if (!ev.transcript.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Ai zis: „${ev.transcript}”",
                    style = BodySmall.copy(color = SleepRem)
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ev.clipPath != null && File(ev.clipPath).exists()) {
                    SecondaryButton("▶ ascultă 5 s", padV = 8.dp, onClick = {
                        try {
                            val mp = MediaPlayer()
                            mp.setDataSource(ev.clipPath)
                            mp.setOnCompletionListener { it.release() }
                            mp.prepare()
                            mp.start()
                        } catch (_: Exception) {
                            toast.show("Clipul nu s-a putut reda.")
                        }
                    })
                    Spacer(Modifier.width(10.dp))
                }
                SecondaryButton("Șterge", padV = 8.dp, textColor = LogoutText, onClick = {
                    scope.launch {
                        ev.clipPath?.let { runCatching { File(it).delete() } }
                        app.db.sleepDao().deleteEvent(ev.id)
                        toast.show("Șters. Doar tu decizi ce rămâne.")
                    }
                })
            }
        }
    }
}

@Composable
private fun PhaseLegend(name: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Column {
            Text(name, style = BodyTiny.copy(color = TextSecondary))
            Text(value, style = BodyStrong.copy(fontSize = 13.sp))
        }
    }
}
