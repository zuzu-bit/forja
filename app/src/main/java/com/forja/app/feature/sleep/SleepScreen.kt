package com.forja.app.feature.sleep

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.forja.app.ForjaApp
import com.forja.app.core.data.db.SleepEventEntity
import com.forja.app.core.data.db.SleepSessionEntity
import com.forja.app.core.network.SleepApi
import com.forja.app.core.sleep.AacRecorder
import com.forja.app.core.sleep.SleepStaging
import com.forja.app.core.sleep.SleepTimeline
import com.forja.app.core.sleep.SleepUpload
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*
import com.forja.app.core.sleep.SleepTrackService
import com.forja.app.core.util.Fmt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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

    val nights by app.db.sleepDao().recent(14).collectAsState(initial = emptyList())

    val alarmEnabled by app.prefs.alarmEnabled.collectAsState(initial = false)
    val alarmHour by app.prefs.alarmHour.collectAsState(initial = 7)
    val alarmMinute by app.prefs.alarmMinute.collectAsState(initial = 0)
    val alarmWindow by app.prefs.alarmWindowMin.collectAsState(initial = 40)

    // Veghea de noapte: FORJA trebuie scoasă de la optimizarea bateriei și, pe Android 14+,
    // lăsată să pornească alarma pe tot ecranul. Re-verificăm la fiecare revenire în ecran.
    var refresh by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val batteryExempt = remember(refresh) {
        try {
            context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        } catch (_: Exception) { true }
    }
    val fullScreenOk = remember(refresh) {
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() ?: true
            } catch (_: Exception) { true }
        } else true
    }
    fun requestVigil() {
        if (!batteryExempt) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: ActivityNotFoundException) {
                try {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) { toast.show("Nu am găsit setarea bateriei pe acest telefon.") }
            } catch (_: Exception) { toast.show("Nu am găsit setarea bateriei pe acest telefon.") }
            return
        }
        if (Build.VERSION.SDK_INT >= 34 && !fullScreenOk) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Exception) { toast.show("Deschide setările aplicației și permite alarma pe tot ecranul.") }
            }
        }
    }

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

    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    var hasMic by remember { mutableStateOf(granted(Manifest.permission.RECORD_AUDIO)) }
    /** Microfon + notificări (33+), cerute împreună; serviciul pornește oricum — își alege tipul după ce a primit. */
    fun missingSleepPermissions(): List<String> {
        val need = mutableListOf<String>()
        if (!granted(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) need.add(Manifest.permission.POST_NOTIFICATIONS)
        return need
    }
    fun beginSleep(micOn: Boolean, notificationsDenied: Boolean) {
        SleepTrackService.start(context)
        startSleepExtras()
        toast.show(
            when {
                notificationsDenied -> "Fără notificări, alarma nu poate porni ecranul."
                alarmEnabled && !fullScreenOk -> "Permite alarma pe tot ecranul din cardul „Veghea de noapte”."
                micOn -> "Noapte bună. Microfonul ascultă. Dimineața, înregistrarea urcă pe server, pe Wi-Fi."
                else -> "Noapte bună. Fără microfon: doar mișcarea se analizează."
            }
        )
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val mic = result[Manifest.permission.RECORD_AUDIO] ?: granted(Manifest.permission.RECORD_AUDIO)
        hasMic = mic
        val notifDenied = Build.VERSION.SDK_INT >= 33 &&
            !(result[Manifest.permission.POST_NOTIFICATIONS] ?: granted(Manifest.permission.POST_NOTIFICATIONS))
        beginSleep(mic, notifDenied)
    }

    // Raportul ultimei nopți de pe disc: manifestul bucăților, cronologia serverului, stadiile, starea urcării.
    val report = rememberNightReport(app, last, refresh)
    val nightChunks = report.manifest?.chunks ?: emptyList()
    val nightPlayer = remember(last?.id, nightChunks) { ChunkPlayer(context, app, last?.id ?: 0L, nightChunks, scope, toast) }
    DisposableEffect(nightPlayer) { onDispose { nightPlayer.release() } }
    LaunchedEffect(nightPlayer, nightPlayer.playing) {
        while (nightPlayer.playing) { delay(500); nightPlayer.tick() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(SleepBg)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 120.dp)
    ) {
        // Header conștient de oră: dimineața (6–11) video luminos + raport;
        // seara (19–6) video închis + „Pregătește-te de somn"; în rest, neutru.
        val hour = remember { java.time.LocalTime.now().hour }
        val morning = hour in 6..10
        val eveningSleep = hour >= 19 || hour < 6
        Box(Modifier.fillMaxWidth().height(252.dp)) {
            if (morning) {
                VideoSurface(
                    url = "https://v.ftcdn.net/11/26/44/56/700_F_1126445619_bJBEc25rOq3b1ofF41h2oJgHrEOy7kVy_ST.mp4",
                    posterUrl = "https://t3.ftcdn.net/jpg/04/70/98/78/500_F_470987805_jsREzUZZZNUDZ56fG4J9Cpz4UquN6zJg.jpg",
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                VideoSurface(
                    url = "",
                    posterUrl = "https://t3.ftcdn.net/jpg/05/62/79/66/500_F_562796663_NJKtdLr9EatSHwup53J47QNnYOCr0ZZ8.jpg",
                    modifier = Modifier.fillMaxSize()
                )
            }
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
                // Ștampila postului, deasupra numelui filei — în culoarea nopții, ca să rămână în paleta somnului.
                StampLabel("STINGEREA", color = SleepTextDim, rotationDeg = -4f)
                Spacer(Modifier.height(6.dp))
                Text("Somn", style = TitleModule)
                Text(
                    when {
                        morning -> "RAPORT DE DIMINEAȚĂ"
                        eveningSleep -> "PREGĂTEȘTE-TE DE SOMN"
                        else -> "ÎNTRE DOUĂ NOPȚI"
                    },
                    style = monoLabel(9, 0.16f).copy(color = SleepRem)
                )
            }
            if (!morning) {
                Text(
                    if (eveningSleep) "Lasă ziua jos. Pornește somnul când te bagi în pat."
                    else "Raportul nopții te așteaptă mâine dimineață.",
                    style = Body.copy(color = SleepRem),
                    modifier = Modifier.align(Alignment.BottomStart).padding(20.dp)
                )
            }
            if (morning) Row(
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
                    } ?: Text("Prima noapte, diseară.\nRaportul, mâine.", style = Body.copy(color = SleepTextDim))
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
                    "De la ${Fmt.clock(active!!.startAt)} · ${if (hasMic) "microfon + mișcare; noaptea urcă dimineața pe server" else "doar mișcare (fără microfon)"}.",
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
                    val need = missingSleepPermissions()
                    if (need.isEmpty()) {
                        hasMic = true
                        SleepTrackService.start(context)
                        startSleepExtras()
                        toast.show(
                            if (alarmEnabled && !fullScreenOk) "Permite alarma pe tot ecranul din cardul „Veghea de noapte”."
                            else "Noapte bună. Lasă telefonul lângă tine, cu fața în jos."
                        )
                    } else {
                        permLauncher.launch(need.toTypedArray())
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            )
        }

        // Veghea de noapte — fără scutirea de baterie, OEM-urile omoară serviciul; fără alarma pe
        // tot ecranul (Android 14+), dimineața rămâne doar o notificare.
        if (!batteryExempt || !fullScreenOk) {
            Spacer(Modifier.height(12.dp))
            ForjaCard(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                fill = SleepCard, stroke = SleepStroke
            ) {
                Text("Veghea de noapte", style = BodyStrong)
                Spacer(Modifier.height(4.dp))
                Text(
                    "FORJA trebuie să rămână trează cât dormi tu. Scoate-o de la optimizarea bateriei și permite alarma pe tot ecranul.",
                    style = BodySmall.copy(color = SleepTextDim)
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    Text(
                        if (batteryExempt) "BATERIE · OK" else "BATERIE · LIPSĂ",
                        style = monoLabel(8, 0.12f).copy(color = if (batteryExempt) Accent2 else SleepRem)
                    )
                    if (Build.VERSION.SDK_INT >= 34) {
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (fullScreenOk) "ALARMĂ PE ECRAN · OK" else "ALARMĂ PE ECRAN · LIPSĂ",
                            style = monoLabel(8, 0.12f).copy(color = if (fullScreenOk) Accent2 else SleepRem)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                PrimaryButton(
                    text = "Permite veghea",
                    onClick = { requestVigil() },
                    modifier = Modifier.fillMaxWidth()
                )
            }
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
                        .then(
                            if (sel) Modifier.background(AccentGradient)
                            else Modifier.background(Color(0xFF152233)).border(1.dp, SleepStroke, ChipShape)
                        )
                        .pressable({ com.forja.app.core.sleep.SleepSounds.setTimer(m) })
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) { Text(lbl, style = BodyStrong.copy(fontSize = 13.sp, color = if (sel) OnAccent else SleepRem)) }
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
                        if (on) toast.show(
                            if (!fullScreenOk) "Permite alarma pe tot ecranul din cardul „Veghea de noapte”, altfel dimineața rămâne doar o notificare."
                            else "La culcare setez și alarma din Ceas la %02d:%02d — plasă de siguranță.".format(alarmHour, alarmMinute)
                        )
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

        // Noaptea, ascultată — cronologia serverului, cu dovezi (8 s în jurul fiecărui moment).
        last?.let { s ->
            NightListenedSection(session = s, app = app, report = report, player = nightPlayer, onChanged = { refresh++ })
            Spacer(Modifier.height(20.dp))
        }

        // Înregistrarea completă a nopții — pe telefon 24 h, pe server 7 zile, apoi dispare.
        last?.let { s ->
            if (s.recordedUntil > System.currentTimeMillis() && nightChunks.isNotEmpty()) {
                NightRecordingCard(session = s, app = app, report = report, player = nightPlayer)
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
                report.staging?.let { st ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Adormit în ${st.latencyMin} min · ${when (st.awakenings) { 0 -> "fără treziri"; 1 -> "o trezire"; else -> "${st.awakenings} treziri" }}" +
                            if (st.awakeMin > 0) " · ${st.awakeMin} min treaz" else "",
                        style = BodyTiny.copy(color = TextSecondary)
                    )
                    if (st.lines.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Scor ${st.score} = " + st.lines.joinToString(" · ") { l ->
                                (if (l.delta < 0) "−${-l.delta}" else "${l.delta}") + ": ${l.reason}"
                            },
                            style = BodyTiny.copy(color = SleepTextDim)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Estimare din mișcare (ferestre de 1 min) și cicluri de ~90 min · ${s.movements} mișcări",
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

        Spacer(Modifier.height(20.dp))

        // Istoricul compact — ultimele 14 nopți: dată, durată, scor, sforăituri.
        SectionLabel("Nopțile tale", Modifier.padding(horizontal = 20.dp), color = SleepTextDim)
        Spacer(Modifier.height(10.dp))
        ForjaCard(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            fill = SleepCard, stroke = SleepStroke
        ) {
            if (nights.isEmpty()) {
                Text("Prima noapte apare aici, mâine dimineață.", style = BodySmall.copy(color = SleepTextDim))
            } else {
                nights.forEachIndexed { idx, s ->
                    key(s.id) { NightRow(s, app) }
                    if (idx < nights.lastIndex) {
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.fillMaxWidth().height(1.dp).background(SleepStroke))
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
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
                text = "FORJA nu pune diagnostice. Clipurile de 5 s rămân pe telefon și le ștergi tu. Înregistrarea întreagă urcă pe serverul FORJA doar ca să fie ascultată de model; pe telefon stă 24 h, pe server 7 zile, apoi dispare. Stadiile somnului sunt estimate din mișcare. Dacă sforăitul revine des, vorbește cu un medic — ai istoricul aici."
            )
        }
    }
}

/** Un rând din „Nopțile tale”: data, durata, scorul și numărul de sforăituri. */
@Composable
private fun NightRow(s: SleepSessionEntity, app: ForjaApp) {
    val events by app.db.sleepDao().eventsForSession(s.id).collectAsState(initial = emptyList())
    val snores = events.count { it.type == "snore" }
    val minutes = (((s.endAt ?: s.startAt) - s.startAt) / 60000).toInt()
    val dateLabel = remember(s.startAt) {
        val fmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale("ro"))
        Instant.ofEpochMilli(s.startAt).atZone(ZoneId.systemDefault()).format(fmt).replace(".", "").uppercase()
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(dateLabel, style = monoLabel(8, 0.12f).copy(color = SleepTextDim))
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Fmt.durationHm(minutes), style = BodyStrong)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${Fmt.clock(s.startAt)} → ${Fmt.clock(s.endAt ?: s.startAt)}",
                    style = BodyTiny.copy(color = SleepTextDim)
                )
            }
            Text(
                when (snores) {
                    0 -> "fără sforăit"
                    1 -> "1 sforăit"
                    else -> "$snores sforăituri"
                },
                style = BodyTiny.copy(color = SleepTextDim)
            )
        }
        Box(
            Modifier
                .clip(ChipShape)
                .background(Color(0x1A7896BE))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                if (s.score > 0) "${s.score}" else "—",
                style = BodyStrong.copy(
                    fontSize = 13.sp,
                    color = when {
                        s.score >= 80 -> Accent2
                        s.score > 0 -> SleepRem
                        else -> SleepTextDim
                    }
                )
            )
        }
    }
}

/**
 * Player-ul întregii nopți, pe bucăți: fiecare bucată se redă local dacă mai există, altfel din
 * server (7 zile). „Sari la moment” caută bucata potrivită și pornește 5 s înainte de eveniment.
 */
@Composable
private fun NightRecordingCard(session: SleepSessionEntity, app: ForjaApp, report: NightReport, player: ChunkPlayer) {
    val context = LocalContext.current
    val events by app.db.sleepDao().eventsForSession(session.id).collectAsState(initial = emptyList())
    val audioStart = report.manifest?.startedAt?.takeIf { it > 0L } ?: session.startAt
    val localLeft = remember(report.manifest) {
        report.manifest?.chunks?.count { c -> AacRecorder.chunkFile(context.filesDir, session.id, c).exists() } ?: 0
    }

    ForjaCard(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        fill = SleepCard, stroke = SleepStroke
    ) {
        SectionLabel("Înregistrarea nopții", color = SleepTextDim)
        Spacer(Modifier.height(4.dp))
        Text(
            "Toată noaptea, în ${player.chunks.size} ${if (player.chunks.size == 1) "bucată" else "bucăți"}. " +
                (if (localLeft > 0) "Pe telefon 24 de ore, " else "Pe telefon nu mai e; ") +
                "pe server 7 zile, apoi dispare.",
            style = BodyTiny.copy(color = SleepTextDim)
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(
                text = when {
                    player.preparing -> "se încarcă…"
                    player.playing -> "Pauză"
                    else -> "▶ Ascultă toată noaptea"
                },
                small = true,
                onClick = { player.toggle() },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                if (player.totalMs > 0) "${Fmt.durationMs(player.positionMs / 1000)} / ${Fmt.durationMs(player.totalMs / 1000)}"
                else "${Fmt.durationMs(player.positionMs / 1000)} / --:--",
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
                            .pressable({ player.playFrom(ev.at - audioStart - 5000) })
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

/** Ce știm despre ultima noapte, de pe disc: bucățile, cronologia serverului, stadiile, starea urcării. */
private data class NightReport(
    val manifest: AacRecorder.Manifest? = null,
    val timeline: SleepTimeline? = null,
    val progress: SleepUpload.Progress? = null,
    val staging: SleepStaging.Result? = null,
    val uploadState: String = ""
)

/** Se recitește la revenirea în ecran și la fiecare 30 s cât urcarea/analiza e în lucru. */
@Composable
private fun rememberNightReport(app: ForjaApp, session: SleepSessionEntity?, refresh: Int): NightReport {
    val context = LocalContext.current
    var report by remember(session?.id) { mutableStateOf(NightReport()) }
    LaunchedEffect(session?.id, refresh) {
        val s = session ?: return@LaunchedEffect
        while (true) {
            val r = withContext(Dispatchers.IO) {
                val dir = AacRecorder.sessionDir(context.filesDir, s.id)
                NightReport(
                    manifest = AacRecorder.manifestFor(context.filesDir, s.id, s.startAt),
                    timeline = SleepTimeline.load(dir),
                    progress = SleepUpload.loadProgress(dir),
                    staging = File(dir, SleepTrackService.STAGING_FILE).takeIf { it.exists() }?.let { SleepStaging.fromJson(it.readText()) },
                    uploadState = SleepUpload.describe(context, s.id)
                )
            }
            report = r
            val pending = r.manifest != null && r.progress?.done != true && app.forjaApi.available
            if (!pending) break
            delay(30_000)
        }
    }
    return report
}

/**
 * Redare pe bucăți: un MediaPlayer per bucată (offset local = moment − `from`), trecere automată la
 * bucata următoare, fragmente de N secunde („Ascultă” din cronologie). Sesiunile vechi = o singură bucată.
 */
private class ChunkPlayer(
    private val context: android.content.Context,
    private val app: ForjaApp,
    private val sessionId: Long,
    val chunks: List<AacRecorder.Chunk>,
    private val scope: CoroutineScope,
    private val toast: ToastState
) {
    private var player: MediaPlayer? = null
    private var chunkIndex = -1
    private var pendingSeekMs = 0L
    private var stopAtMs = 0L
    var playing by mutableStateOf(false)
    var preparing by mutableStateOf(false)
    var positionMs by mutableStateOf(0L)
    var totalMs by mutableStateOf(chunks.sumOf { it.dur })

    fun release() {
        try { player?.release() } catch (_: Exception) { }
        player = null
        chunkIndex = -1
        playing = false
    }

    fun toggle() {
        val p = player
        if (p != null && playing) { try { p.pause() } catch (_: Exception) { }; playing = false; return }
        if (p != null && chunkIndex >= 0) { stopAtMs = 0L; try { p.start(); playing = true } catch (_: Exception) { }; return }
        playFrom(0L)
    }

    /** Redă de la un moment global (ms de la începutul audio-ului); `snippetMs` > 0 → se oprește după atât. */
    fun playFrom(globalMs: Long, snippetMs: Long = 0L) {
        val target = globalMs.coerceAtLeast(0L)
        val chunk = SleepTimeline.chunkFor(target, chunks)
        if (chunk == null) { toast.show("Înregistrarea nu mai e disponibilă."); return }
        stopAtMs = if (snippetMs > 0) target + snippetMs else 0L
        val local = (target - chunk.from).coerceAtLeast(0L)
        val p = player
        if (p != null && chunkIndex == chunk.index) {
            try { p.seekTo(local.toInt()); p.start(); playing = true; positionMs = target } catch (_: Exception) { }
            return
        }
        pendingSeekMs = local
        open(chunk)
    }

    private fun open(chunk: AacRecorder.Chunk) {
        if (preparing) return
        preparing = true
        release()
        scope.launch {
            try {
                val mp = MediaPlayer()
                val f = AacRecorder.chunkFile(context.filesDir, sessionId, chunk)
                if (f.exists() && f.length() > 4000) {
                    mp.setDataSource(f.absolutePath)
                } else if (app.forjaApi.available) {
                    val auth = app.forjaApi.authHeader()
                    if (auth == null) {
                        toast.show("Intră în cont ca să asculți înregistrarea.")
                        preparing = false
                        return@launch
                    }
                    mp.setDataSource(
                        context,
                        Uri.parse(SleepApi.get(app.forjaApi).chunkUrl(sessionId, chunk.index)),
                        mapOf("Authorization" to auth)
                    )
                } else {
                    toast.show("Înregistrarea nu mai e pe telefon.")
                    preparing = false
                    return@launch
                }
                mp.setOnPreparedListener {
                    preparing = false
                    player = mp
                    chunkIndex = chunk.index
                    if (chunk.dur == 0L && chunks.size == 1) totalMs = mp.duration.toLong().coerceAtLeast(0L)
                    try {
                        if (pendingSeekMs > 0) mp.seekTo(pendingSeekMs.toInt())
                        mp.start()
                        playing = true
                    } catch (_: Exception) { }
                    positionMs = chunk.from + pendingSeekMs
                    pendingSeekMs = 0L
                }
                mp.setOnCompletionListener {
                    val next = chunks.firstOrNull { it.index > chunk.index }
                    if (next != null && stopAtMs == 0L) {
                        pendingSeekMs = 0L
                        open(next)
                    } else {
                        playing = false
                        stopAtMs = 0L
                    }
                }
                mp.setOnErrorListener { _, _, _ ->
                    preparing = false
                    playing = false
                    toast.show("Bucata nu s-a putut reda. Poate a expirat pe server (7 zile).")
                    true
                }
                mp.prepareAsync()
            } catch (_: Exception) {
                preparing = false
                toast.show("Înregistrarea nu s-a putut deschide.")
            }
        }
    }

    /** La 500 ms cât se redă: poziția globală și oprirea fragmentelor. */
    fun tick() {
        val p = player ?: return
        try {
            val chunk = chunks.firstOrNull { it.index == chunkIndex } ?: return
            positionMs = chunk.from + p.currentPosition
            if (stopAtMs > 0 && positionMs >= stopAtMs) {
                p.pause()
                playing = false
                stopAtMs = 0L
            }
        } catch (_: Exception) { }
    }
}

/**
 * „Noaptea, ascultată”: acoperirea onestă, cronologia serverului (oră, tip, intensitate, transcriere
 * EXACTĂ, încredere) cu „Ascultă” = 8 s în jurul momentului, starea urcării și opțiunea de date mobile.
 * Fără analiză, spune exact de ce — nu pretinde mai mult.
 */
@Composable
private fun NightListenedSection(session: SleepSessionEntity, app: ForjaApp, report: NightReport, player: ChunkPlayer, onChanged: () -> Unit) {
    val context = LocalContext.current
    val toast = LocalToast.current
    var cellular by remember { mutableStateOf(SleepUpload.cellularAllowed(context)) }
    var showAll by remember(session.id) { mutableStateOf(false) }
    val t = report.timeline
    val audioStart = t?.startedAt?.takeIf { it > 0L } ?: report.manifest?.startedAt?.takeIf { it > 0L } ?: session.startAt

    SectionLabel("Noaptea, ascultată", Modifier.padding(horizontal = 20.dp), color = SleepTextDim)
    Spacer(Modifier.height(10.dp))
    ForjaCard(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        fill = SleepCard, stroke = SleepStroke
    ) {
        when {
            report.manifest == null -> Text(
                "Fără înregistrare azi-noapte. Microfonul n-a fost pornit, așa că ai doar mișcarea.",
                style = BodySmall.copy(color = SleepTextDim)
            )
            !app.forjaApi.available -> Text(
                "Serverul FORJA nu e configurat în această versiune. Ai doar clipurile prinse pe telefon.",
                style = BodySmall.copy(color = SleepTextDim)
            )
            t == null || t.status == "processing" -> {
                Text(
                    if ((report.progress?.attempts ?: 0) >= SleepUpload.MAX_ATTEMPTS) "Urcarea a renunțat. Ai doar clipurile prinse pe telefon."
                    else "Raportul nopții se pregătește pe server.",
                    style = BodyStrong.copy(fontSize = 14.sp)
                )
                Spacer(Modifier.height(4.dp))
                Text(report.uploadState, style = BodySmall.copy(color = SleepTextDim))
                report.progress?.lastError?.takeIf { it.isNotBlank() }?.let {
                    Text("Ultima problemă: $it", style = BodyTiny.copy(color = SleepTextDim))
                }
            }
            t.status == "clips_only" -> Text(
                if (t.reason.contains("gemini", ignoreCase = true) || t.reason.isBlank())
                    "Serverul nu a putut asculta noaptea (lipsește cheia Gemini). Ai doar clipurile prinse pe telefon."
                else "Serverul nu a putut asculta noaptea (${t.reason}). Ai doar clipurile prinse pe telefon.",
                style = BodySmall.copy(color = SleepTextDim)
            )
            !t.listened -> {
                Text(
                    "Serverul n-a terminat de ascultat noaptea" + (if (t.reason.isNotBlank()) " (${t.reason})." else ".") +
                        " Ai doar clipurile prinse pe telefon.",
                    style = BodySmall.copy(color = SleepTextDim)
                )
                Spacer(Modifier.height(10.dp))
                SecondaryButton("Încearcă din nou", padV = 8.dp, onClick = {
                    val dir = AacRecorder.sessionDir(context.filesDir, session.id)
                    val p = SleepUpload.loadProgress(dir) ?: SleepUpload.Progress()
                    SleepUpload.saveProgress(dir, p.copy(analyzeRequestedAt = 0L, pollStartedAt = 0L, pollSpentMs = 0L, done = false, attempts = 0, lastError = ""))
                    try { File(dir, SleepTimeline.FILE).delete() } catch (_: Exception) { }
                    SleepUpload.schedule(context, session.id, replace = true)
                    toast.show("Am cerut analiza din nou. Poate dura până la 20 min.")
                    onChanged()
                })
            }
            else -> {
                // acoperirea, onest
                val cov = t.stats.coverageMin
                val tot = t.stats.totalMin
                Text(
                    when {
                        cov > 0 && tot > 0 -> "Am ascultat ${Fmt.durationHm(cov)} din ${Fmt.durationHm(tot)}."
                        tot > 0 -> "Am trimis ${Fmt.durationHm(tot)}. Serverul n-a raportat cât a ascultat."
                        else -> "Serverul a ascultat înregistrarea, fără să raporteze acoperirea."
                    },
                    style = BodyStrong.copy(fontSize = 14.sp)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    listOf(
                        if (t.stats.snoreMin > 0 || t.stats.snoreEpisodes > 0)
                            "sforăit ${t.stats.snoreMin} min în ${t.stats.snoreEpisodes} ${if (t.stats.snoreEpisodes == 1) "episod" else "episoade"}"
                        else "fără sforăit",
                        when (t.stats.talkCount) { 0 -> "fără vorbit"; 1 -> "o frază"; else -> "${t.stats.talkCount} fraze" },
                        if (t.stats.coughCount > 0) "tuse ×${t.stats.coughCount}" else null
                    ).filterNotNull().joinToString(" · "),
                    style = BodySmall.copy(color = SleepTextDim)
                )
                if (t.events.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Nimic de semnalat în ce s-a ascultat. Noapte liniștită.", style = BodySmall.copy(color = TextSecondary))
                } else {
                    Spacer(Modifier.height(10.dp))
                    val shown = if (showAll) t.events else t.events.take(10)
                    shown.forEachIndexed { i, ev ->
                        TimelineEventRow(ev, audioStart, player)
                        if (i < shown.lastIndex) {
                            Spacer(Modifier.height(6.dp))
                            Box(Modifier.fillMaxWidth().height(1.dp).background(SleepStroke))
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                    if (t.events.size > 10 && !showAll) {
                        Spacer(Modifier.height(8.dp))
                        MonoButton("Arată toate (${t.events.size})", onClick = { showAll = true }, color = SleepRem)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Analiză cu model, pe server. Transcrierile sunt exact ce s-a auzit, fără completări. Unde încrederea e mică, ascultă tu.",
                    style = BodyTiny.copy(color = SleepTextDim)
                )
            }
        }

        // Opțiunea de rețea — implicit doar Wi-Fi.
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Și pe date mobile", style = BodyStrong.copy(fontSize = 13.sp))
                Text(
                    "O noapte are ~10 MB la fiecare 30 min. Implicit urcă doar pe Wi-Fi, cu bateria peste 15 %.",
                    style = BodyTiny.copy(color = SleepTextDim)
                )
            }
            ForjaSwitch(checked = cellular, onCheckedChange = { on ->
                cellular = on
                SleepUpload.setCellularAllowed(context, on)
                if (report.manifest != null && report.progress?.done != true && app.forjaApi.available) {
                    SleepUpload.reschedule(context, session.id)
                }
                onChanged()
            })
        }
    }
}

/** Un rând din cronologie: oră · tip · intensitate, transcrierea exactă, încrederea și „Ascultă” (8 s). */
@Composable
private fun TimelineEventRow(ev: SleepTimeline.Event, audioStart: Long, player: ChunkPlayer) {
    val typeName = when (ev.type) {
        "talk" -> "Vorbit"
        "snore" -> "Sforăit"
        "cough" -> "Tuse"
        "breath" -> "Respirație"
        else -> "Zgomot"
    }
    val intensityWord = when {
        ev.intensity >= 0.7 -> "puternic"
        ev.intensity >= 0.4 -> "moderat"
        ev.intensity > 0.0 -> "redus"
        else -> ""
    }
    val dot = when (ev.type) { "snore" -> SleepDeep; "talk" -> SleepRem; "cough" -> Accent2; else -> SleepLight }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                listOf(Fmt.clock(audioStart + ev.at), typeName, intensityWord, if (ev.durMs >= 1000) "${ev.durMs / 1000} s" else "")
                    .filter { it.isNotBlank() }.joinToString(" · "),
                style = BodyStrong.copy(fontSize = 13.sp)
            )
            if (ev.transcript.isNotBlank()) {
                Text("„${ev.transcript}”", style = BodySmall.copy(color = SleepRem))
            }
            if (ev.confidence > 0.0) {
                Text("încredere ${(ev.confidence * 100).toInt()} %", style = monoLabel(8, 0.10f).copy(color = SleepTextDim))
            }
        }
        Spacer(Modifier.width(8.dp))
        SecondaryButton("Ascultă", padV = 6.dp, onClick = { player.playFrom(ev.at - 3000, 8000) })
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
            .border(1.5.dp, if (active) Color(0xB36F855A) else SleepStroke, shape)
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
            "awake" -> Color(0xFF6F855A)
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
        fill = SleepCard, stroke = if (expanded) Color(0x8C6F855A) else SleepStroke,
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
