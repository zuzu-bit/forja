package com.forja.app.feature.focus

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forja.app.ForjaApp
import com.forja.app.core.data.db.FocusRuleEntity
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*
import com.forja.app.core.focus.FocusMonitorService
import com.forja.app.core.util.Fmt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Focus: respirație 4,6s, blocare onestă cu UsageStats, timp de ecran real. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusScreen(onOpenCleanup: () -> Unit = {}) {
    val context = LocalContext.current
    val app = remember { ForjaApp.from(context) }
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current

    val rules by app.db.focusDao().rules().collectAsState(initial = emptyList())
    var hasUsage by remember { mutableStateOf(FocusMonitorService.hasUsageAccess(context)) }
    var hasOverlay by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(context)) }
    var overlayOpen by remember { mutableStateOf(false) }
    var focusActive by remember { mutableStateOf(false) }
    var permOpen by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    var screenTimeMin by remember { mutableStateOf(-1) }
    var opensToday by remember { mutableStateOf(-1) }
    var topApps by remember { mutableStateOf<List<Triple<String, Int, Float>>>(emptyList()) }
    var weekMinutes by remember { mutableStateOf<List<Int>>(emptyList()) }
    val detoxUntil by app.prefs.detoxUntil.collectAsState(initial = 0L)

    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        focusActive = app.prefs.focusActive.first()
        while (true) { kotlinx.coroutines.delay(30_000); tick++ }
    }
    // Raportul de utilizare — azi + săptămâna, per aplicație (à la Digital Detox).
    LaunchedEffect(hasUsage, tick) {
        if (hasUsage) {
            try {
                val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val pm = context.packageManager
                val start = Fmt.startOfDayMillis()
                val now = System.currentTimeMillis()
                val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now)
                val perApp = HashMap<String, Long>()
                stats.filter { it.lastTimeUsed >= start }.forEach {
                    perApp[it.packageName] = (perApp[it.packageName] ?: 0L) + it.totalTimeInForeground
                }
                perApp.remove(context.packageName)
                val total = perApp.values.sum()
                screenTimeMin = (total / 60000).toInt()
                val top = perApp.entries
                    .filter { it.value >= 60_000 }
                    .sortedByDescending { it.value }
                    .take(6)
                val maxV = top.firstOrNull()?.value?.coerceAtLeast(1) ?: 1
                topApps = top.mapNotNull { e ->
                    val label = try {
                        pm.getApplicationLabel(pm.getApplicationInfo(e.key, 0)).toString()
                    } catch (_: Exception) { return@mapNotNull null }
                    Triple(label, (e.value / 60000).toInt(), e.value.toFloat() / maxV)
                }
                // Deschideri azi
                var opens = 0
                val events = usm.queryEvents(start, now)
                val ev = android.app.usage.UsageEvents.Event()
                var lastPkg = ""
                while (events.hasNextEvent()) {
                    events.getNextEvent(ev)
                    if (ev.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED && ev.packageName != lastPkg) {
                        opens++; lastPkg = ev.packageName
                    }
                }
                opensToday = opens
                // Săptămâna: 7 zile de total
                weekMinutes = (6 downTo 0).map { ago ->
                    val ds = Fmt.startOfDayMillis(ago.toLong())
                    val de = ds + 24 * 3600_000
                    val dayStats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, ds, minOf(de, now))
                    val m = HashMap<String, Long>()
                    dayStats.filter { it.lastTimeUsed in ds until de }.forEach {
                        m[it.packageName] = maxOf(m[it.packageName] ?: 0L, it.totalTimeInForeground)
                    }
                    m.remove(context.packageName)
                    (m.values.sum() / 60000).toInt()
                }
            } catch (_: Exception) { screenTimeMin = -1 }
        }
    }

    // Reîmprospătează permisiunile la fiecare revenire pe ecran.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasUsage = FocusMonitorService.hasUsageAccess(context)
                hasOverlay = android.provider.Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    fun ensureBlockingPermissions(onReady: () -> Unit) {
        when {
            !hasUsage -> permOpen = true
            !hasOverlay -> overlayOpen = true
            else -> onReady()
        }
    }

    Box(Modifier.fillMaxSize().background(Surface0)) {
        VideoSurface(
            url = "",
            posterUrl = com.forja.app.core.media.Media.mediaUrl("snd_stream.jpg")
                ?: "https://t3.ftcdn.net/jpg/10/16/02/48/500_F_1016024842_sVPfKb4a4gZkZ7XjEjnGtdkeYz1eF2Gz.jpg",
            modifier = Modifier.fillMaxSize()
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x8C0A0A0B))
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 130.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(8.dp))
            Text("Focus", style = TitleModule, modifier = Modifier.align(Alignment.Start))
            Spacer(Modifier.height(24.dp))

            // Pădurea de azi — copaci care cresc cât te ții de focus, se ofilesc la renunțare
            val forest by app.prefs.focusForest.collectAsState(initial = Triple(0, 0, 0))
            FocusForest(grown = forest.first, withered = forest.second, partialSecs = forest.third)

            Spacer(Modifier.height(22.dp))
            val activeRule = rules.firstOrNull { it.enabled }
            if (activeRule != null) {
                Text(
                    "${activeRule.label} e blocat până la ${"%02d:%02d".format(activeRule.untilHour, activeRule.untilMinute)}.",
                    style = Body.copy(fontSize = 15.sp, color = TextPrimary),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                if (screenTimeMin >= 0)
                    "Timp de ecran azi: ${Fmt.durationHm(screenTimeMin)}" +
                        (if (opensToday >= 0) " · $opensToday deschideri" else "")
                else "Timpul de ecran apare după ce dai permisiunea.",
                style = monoLabel(9, 0.12f).copy(color = Accent2)
            )

            Spacer(Modifier.height(20.dp))

            // Detox digital: totul în pauză, în afară de esențiale (telefon, mesaje, FORJA).
            val detoxOn = detoxUntil > System.currentTimeMillis()
            ForjaCard(Modifier.fillMaxWidth(), fill = Color(0xE6121214)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Detox digital", style = BodyStrong.copy(fontSize = 15.sp))
                        Text(
                            if (detoxOn) {
                                val left = ((detoxUntil - System.currentTimeMillis()) / 60000).toInt() + tick.toInt() * 0
                                "activ · mai sunt ${Fmt.durationHm(left.coerceAtLeast(1))} · doar telefon, mesaje și FORJA"
                            } else "blochează tot, în afară de telefon, mesaje și FORJA",
                            style = BodyTiny.copy(color = if (detoxOn) Accent2 else TextSecondary)
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (detoxOn) {
                    SecondaryButton("Oprește detoxul", onClick = {
                        scope.launch {
                            app.prefs.setDetoxUntil(0L)
                            app.prefs.witherFocusTree()
                            toast.show("Detox oprit. Ai rezistat — contează.")
                        }
                    }, modifier = Modifier.fillMaxWidth())
                } else {
                    Row {
                        listOf(30, 60, 120).forEach { min ->
                            SecondaryButton(
                                if (min < 120) "$min min" else "2 ore",
                                onClick = {
                                    ensureBlockingPermissions {
                                        scope.launch {
                                            app.prefs.setDetoxUntil(System.currentTimeMillis() + min * 60_000L)
                                            FocusMonitorService.start(context)
                                            toast.show("Detox pornit. Ne vedem peste ${Fmt.durationHm(min)}.")
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f).padding(end = if (min < 120) 8.dp else 0.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Raportul de azi — pe aplicații
            if (hasUsage && topApps.isNotEmpty()) {
                SectionLabel("Raportul de azi", Modifier.align(Alignment.Start))
                Spacer(Modifier.height(8.dp))
                ForjaCard(Modifier.fillMaxWidth(), fill = Color(0xE6121214)) {
                    topApps.forEachIndexed { i, (label, min, frac) ->
                        Column(Modifier.padding(bottom = if (i < topApps.size - 1) 10.dp else 0.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(label, style = BodyStrong.copy(fontSize = 13.sp), maxLines = 1, modifier = Modifier.weight(1f))
                                Text(Fmt.durationHm(min), style = BodySmall.copy(color = TextSecondary))
                            }
                            Spacer(Modifier.height(4.dp))
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .clip(CircleShape)
                                    .background(SwitchOff)
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(frac.coerceIn(0.03f, 1f))
                                        .fillMaxHeight()
                                        .clip(CircleShape)
                                        .background(AccentGradient)
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                // Săptămâna ta de ecran
                if (weekMinutes.size == 7) {
                    SectionLabel("Săptămâna ta de ecran", Modifier.align(Alignment.Start))
                    Spacer(Modifier.height(8.dp))
                    ForjaCard(Modifier.fillMaxWidth(), fill = Color(0xE6121214)) {
                        val maxW = (weekMinutes.maxOrNull() ?: 0).coerceAtLeast(60)
                        Row(
                            Modifier.fillMaxWidth().height(70.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            weekMinutes.forEachIndexed { i, min ->
                                val isToday = i == 6
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(
                                        Modifier
                                            .width(20.dp)
                                            .fillMaxHeight((min.toFloat() / maxW).coerceIn(0.05f, 1f))
                                            .clip(CircleShape)
                                            .background(if (isToday) AccentGradient else androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF34343C), Color(0xFF23232A))))
                                    )
                                    Spacer(Modifier.height(5.dp))
                                    val dayIdx = (java.time.LocalDate.now().dayOfWeek.value - 1 - (6 - i) + 7) % 7
                                    Text(
                                        if (isToday) "azi" else Fmt.dayLetters[dayIdx],
                                        style = monoLabel(8, 0.08f).copy(color = if (isToday) Accent2 else TextDim)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        val avg = weekMinutes.filter { it > 0 }.let { if (it.isEmpty()) 0 else it.sum() / it.size }
                        Text(
                            if (avg > 0) "media ${Fmt.durationHm(avg)} pe zi" else "datele se adună zi de zi",
                            style = monoLabel(8, 0.10f).copy(color = TextDim)
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            if (!hasUsage) {
                PrimaryButton("Vezi permisiunea", onClick = { permOpen = true }, modifier = Modifier.fillMaxWidth())
            } else if (!hasOverlay) {
                PrimaryButton("Permite blocarea (peste alte aplicații)", onClick = { overlayOpen = true }, modifier = Modifier.fillMaxWidth())
            } else if (!focusActive) {
                PrimaryButton(
                    "Pornește Focus",
                    onClick = {
                        if (rules.none { it.enabled }) {
                            pickerOpen = true
                        } else {
                            ensureBlockingPermissions {
                                FocusMonitorService.start(context)
                                focusActive = true
                                scope.launch { app.prefs.setFocusActive(true) }
                                toast.show("Focus pornit. Respiră.")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                SecondaryButton(
                    "Oprește Focus",
                    onClick = {
                        FocusMonitorService.stop(context)
                        focusActive = false
                        scope.launch {
                            app.prefs.setFocusActive(false)
                            app.prefs.witherFocusTree()
                        }
                        toast.show("Focus oprit. Copacul început s-a ofilit — cronometrul pleacă de la zero.")
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(20.dp))

            // Lista de aplicații blocate
            SectionLabel("Aplicații în pauză", Modifier.align(Alignment.Start))
            Spacer(Modifier.height(8.dp))
            if (rules.isEmpty()) {
                Text(
                    "Încă nimic. Adaugă aplicațiile care îți mănâncă serile.",
                    style = BodySmall.copy(color = TextSecondary),
                    modifier = Modifier.align(Alignment.Start)
                )
            }
            rules.forEach { r ->
                ForjaCard(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    fill = Color(0xE6121214), padding = 12.dp
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.label, style = BodyStrong.copy(fontSize = 14.sp))
                            Text(
                                "blocat până la ${"%02d:%02d".format(r.untilHour, r.untilMinute)}",
                                style = BodyTiny.copy(color = TextSecondary)
                            )
                        }
                        ForjaSwitch(checked = r.enabled, onCheckedChange = { on ->
                            scope.launch { app.db.focusDao().upsert(r.copy(enabled = on)) }
                        })
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "șterge",
                            style = BodyTiny.copy(color = TextDim),
                            modifier = Modifier.pressable({
                                scope.launch { app.db.focusDao().delete(r.packageName) }
                            })
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            SecondaryButton("+ Adaugă o aplicație", onClick = { pickerOpen = true }, modifier = Modifier.fillMaxWidth())

            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Confidențialitate Focus", style = BodyTiny.copy(color = TextDim))
                Spacer(Modifier.width(8.dp))
                InfoDot(
                    title = "Confidențialitate",
                    text = "FORJA vede doar ce aplicație e deschisă — nu citește mesajele, parolele sau conținutul ecranului."
                )
            }

            Spacer(Modifier.height(24.dp))

            // Curățenie de azi — detox digital
            SectionLabel("Curățenie de azi", Modifier.align(Alignment.Start))
            Spacer(Modifier.height(8.dp))
            ForjaCard(
                Modifier.fillMaxWidth().pressable(onOpenCleanup),
                fill = Color(0xE6121214)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Telefon mai ușor, minte mai limpede",
                        style = BodyStrong.copy(fontSize = 15.sp),
                        modifier = Modifier.weight(1f)
                    )
                    InfoDot(
                        title = "Curățenie de azi",
                        text = "Treci prin pozele și fișierele adunate — app-ul îți sugerează ce pare gunoi, tu decizi. Nimic nu se șterge singur, nimic nu pleacă de pe telefon."
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text("Sugestii pentru azi — tu hotărăști ce pleacă.", style = BodyTiny.copy(color = TextSecondary))
                Spacer(Modifier.height(10.dp))
                Text("Fă curat →", style = BodySmall.copy(color = Accent2))
            }

            Spacer(Modifier.height(24.dp))
            DetoxAddictionSection()
            Spacer(Modifier.height(8.dp))
        }
    }

    // Sheet-ul „peste alte aplicații" — fără el, Android nu ne lasă să afișăm ecranul de blocare (ca la Forest).
    if (overlayOpen) {
        ModalBottomSheet(
            onDismissRequest = { overlayOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Surface1, shape = SheetShape
        ) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text("Afișare peste alte aplicații", style = TitleModule.copy(fontSize = 20.sp))
                Spacer(Modifier.height(8.dp))
                Text(
                    "Ca să apară ecranul de respirație PESTE aplicația blocată (exact ca la Forest), Android cere permisiunea „Afișare peste alte aplicații”.",
                    style = Body
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "FORJA o folosește doar pentru ecranul de blocare — nimic altceva nu se desenează peste telefonul tău.",
                    style = BodySmall.copy(color = TextSecondary)
                )
                Spacer(Modifier.height(18.dp))
                Row {
                    SecondaryButton("Nu acum", onClick = { overlayOpen = false }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    PrimaryButton(
                        "Permite",
                        onClick = {
                            overlayOpen = false
                            try {
                                context.startActivity(
                                    Intent(
                                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        android.net.Uri.parse("package:${context.packageName}")
                                    )
                                )
                            } catch (_: Exception) {
                                toast.show("Deschide Setări → Aplicații → FORJA → Afișare peste alte aplicații.")
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    // Permission sheet — refuz vizibil, copy onest.
    if (permOpen) {
        ModalBottomSheet(
            onDismissRequest = { permOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Surface1, shape = SheetShape
        ) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text("Acces la utilizare", style = TitleModule.copy(fontSize = 20.sp))
                Spacer(Modifier.height(8.dp))
                Text(
                    "Ca să blocheze aplicațiile alese, FORJA are nevoie de permisiunea „Usage Access” din Android.",
                    style = Body
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "FORJA vede doar ce aplicație e deschisă — nu citește mesajele, parolele sau conținutul ecranului.",
                    style = BodySmall.copy(color = TextSecondary)
                )
                Spacer(Modifier.height(18.dp))
                Row {
                    SecondaryButton("Nu acum", onClick = { permOpen = false }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    PrimaryButton(
                        "Permite",
                        onClick = {
                            permOpen = false
                            try {
                                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                            } catch (_: Exception) {
                                toast.show("Deschide Setări → Acces special → Acces la date de utilizare.")
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    if (pickerOpen) {
        AppPickerSheet(
            onClose = { pickerOpen = false },
            onPick = { pkg, label, hour ->
                scope.launch {
                    app.db.focusDao().upsert(FocusRuleEntity(pkg, label, hour, 0, true))
                    toast.show("$label intră în pauză până la %02d:00.".format(hour))
                }
                pickerOpen = false
            }
        )
    }
}

/** Detox de adicție — accountability tool, totul pe telefon, întâmpinare blândă. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetoxAddictionSection() {
    val context = LocalContext.current
    val app = remember { ForjaApp.from(context) }
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current

    val detoxOn by app.prefs.detoxOn.collectAsState(initial = false)
    val letter by app.prefs.detoxLetter.collectAsState(initial = "")
    val words by app.prefs.detoxWords.collectAsState(initial = "")

    var guardOn by remember { mutableStateOf(com.forja.app.core.detox.ForjaGuardService.isEnabled(context)) }
    var letterOpen by remember { mutableStateOf(false) }
    var wordsOpen by remember { mutableStateOf(false) }
    var guardStep by remember { mutableStateOf(0) }        // 0 închis · 1 pasul 1 · 2 pasul 2
    var mascotLine by remember { mutableStateOf<String?>(null) }
    val guardianLines = listOf(
        "Sunt aici cu tine. Respiră — momentul trece.",
        "Ți-ai scris de ce vrei asta. Vrei să recitim împreună?",
        "Ești mai puternic decât impulsul de acum.",
        "Un pas mic acum, mândrie mare mâine.",
        "Nu ești singur în asta — eu țin de baston, tu ții de tine."
    )
    val mascotUrl = remember { com.forja.app.core.media.Media.mediaUrl("paznic.jpg") }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                guardOn = com.forja.app.core.detox.ForjaGuardService.isEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Detox de adicție", Modifier.weight(1f))
            InfoDot(
                title = "Paznicul tău",
                text = "Un paznic blând care te ajută să reziști. Totul rămâne pe telefonul tău — nimic, dar absolut nimic, nu pleacă la vreun server. E instrumentul tău, nu al nostru."
            )
        }
        Spacer(Modifier.height(12.dp))

        if (detoxOn) {
            // Paznicul — mascota, status și o vorbă bună la atingere
            ForjaCard(Modifier.fillMaxWidth().padding(bottom = 10.dp), fill = Color(0xE6121214)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(64.dp).clip(CircleShape)
                            .background(Color(0x1F6F855A)).border(1.dp, Color(0x4D6F855A), CircleShape)
                            .pressable({ mascotLine = guardianLines.random() }),
                        contentAlignment = Alignment.Center
                    ) {
                        if (mascotUrl != null) {
                            coil.compose.AsyncImage(
                                model = mascotUrl, contentDescription = "Paznicul",
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clip(CircleShape)
                            )
                        } else Text("👮", fontSize = 30.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Paznicul e cu tine", style = BodyStrong.copy(fontSize = 15.sp))
                        Text(
                            if (guardOn) "activ — te oprește la tentația aleasă de tine"
                            else "mai e un pas ca să te poată opri",
                            style = BodyTiny.copy(color = if (guardOn) Positive else Accent2)
                        )
                        Text("atinge-l pentru o vorbă bună", style = BodyTiny.copy(color = TextDim))
                    }
                    ForjaSwitch(checked = detoxOn, onCheckedChange = { on ->
                        scope.launch {
                            app.prefs.setDetoxOn(on)
                            if (!on) toast.show("Detox oprit. Poți reveni oricând, fără rușine.")
                        }
                    })
                }
                if (mascotLine != null) {
                    Spacer(Modifier.height(12.dp))
                    Column(Modifier.fillMaxWidth().clip(CardShape).background(Surface2).padding(12.dp)) {
                        Text("„$mascotLine”", style = Body.copy(fontSize = 14.sp, lineHeight = 20.sp))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (letter.isBlank()) "→ scrie-ți scrisoarea" else "→ recitește / schimbă scrisoarea",
                            style = BodySmall.copy(color = Accent2),
                            modifier = Modifier.pressable({ letterOpen = true })
                        )
                    }
                }
            }

            if (!guardOn) {
                ForjaCard(Modifier.fillMaxWidth().padding(bottom = 10.dp), fill = Color(0xE6121214), stroke = Color(0x666F855A)) {
                    Text("Mai e un pas", style = BodyStrong.copy(fontSize = 14.sp))
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Pornește paznicul în 3 pași simpli — te ghidez eu, ecran cu ecran.",
                        style = BodyTiny.copy(color = TextSecondary)
                    )
                    Spacer(Modifier.height(10.dp))
                    PrimaryButton("Pornește paznicul", small = true, onClick = { guardStep = 1 }, modifier = Modifier.fillMaxWidth())
                }
            }

            SecondaryButton(
                if (letter.isBlank()) "Scrie-ți scrisoarea (de ce vreau să scap)" else "Scrisoarea mea ✓ · atinge ca s-o schimbi",
                onClick = { letterOpen = true },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            SecondaryButton(
                if (words.isBlank()) "Cuvinte de blocat (le alegi tu)" else "Cuvinte de blocat ✓ · atinge ca să le schimbi",
                onClick = { wordsOpen = true },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            PrimaryButton(
                "Pornește Detox de adicție",
                onClick = {
                    scope.launch {
                        app.prefs.setDetoxOn(true)
                        toast.show("Ești pe drum. Îți scrii scrisoarea și alegi cuvintele mai jos.")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (guardStep > 0) {
        GuardWizardSheet(
            step = guardStep,
            onOpenApps = {
                // Ne oprim în lista de Aplicații din Setări (nu direct în FORJA) — cauți tu FORJA acolo.
                val tries = listOf(
                    Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS),
                    Intent(Settings.ACTION_APPLICATION_SETTINGS),
                    Intent(Settings.ACTION_SETTINGS)
                )
                var ok = false
                for (i in tries) {
                    try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(i); ok = true; break } catch (_: Exception) { }
                }
                if (!ok) toast.show("Deschide Setări → Aplicații și caută FORJA.")
            },
            onNext = { guardStep = (guardStep + 1).coerceAtMost(3) },
            onOpenAccess = {
                // Încearcă să deschidă DIRECT pagina serviciului FORJA din Accesibilitate; altfel, lista.
                val cn = android.content.ComponentName(context, com.forja.app.core.detox.ForjaGuardService::class.java)
                val flat = cn.flattenToString()
                fun args() = android.os.Bundle().apply { putString(":settings:fragment_args_key", flat) }
                val tries = listOf(
                    Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
                        putExtra(":settings:fragment_args_key", flat)
                        putExtra(":settings:show_fragment_args", args())
                        putExtra(Intent.EXTRA_COMPONENT_NAME, cn)
                    },
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        putExtra(":settings:fragment_args_key", flat)
                        putExtra(":settings:show_fragment_args", args())
                    },
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                )
                var ok = false
                for (i in tries) {
                    try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(i); ok = true; break } catch (_: Exception) { }
                }
                if (!ok) toast.show("Deschide Setări → Accesibilitate → Aplicații instalate → FORJA.")
            },
            onClose = { guardStep = 0 }
        )
    }

    if (letterOpen) {
        DetoxTextSheet(
            title = "Scrisoarea către tine",
            hint = "De ce vreau să scap. Cui vreau să devin. Ce pierd dacă alunec. Scrie din inimă — o vei citi fix în momentul greu.",
            placeholder = "Vreau să scap pentru că…",
            initial = letter,
            singleLine = false,
            onSave = { scope.launch { app.prefs.setDetoxLetter(it); letterOpen = false; toast.show("Salvat. Cuvintele tale te vor aștepta acolo.") } },
            onClose = { letterOpen = false }
        )
    }
    if (wordsOpen) {
        DetoxWordsSheet(
            initial = words,
            onSave = { scope.launch { app.prefs.setDetoxWords(it); wordsOpen = false; toast.show("Salvate — pe telefonul tău, nicăieri altundeva.") } },
            onClose = { wordsOpen = false }
        )
    }
}

/** Pădurea de Focus: un copac crește în etape cât te ții de focus; se ofilește când renunți. */
@Composable
private fun FocusForest(grown: Int, withered: Int, partialSecs: Int) {
    // Progresul spre următorul copac (15 min) — inelul ESTE timpul: jumătate de timp = jumătate de cerc.
    val progress = (partialSecs / 900f).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(190.dp), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val sw = 7.dp.toPx()
                val inset = sw / 2 + 2.dp.toPx()
                val d = size.minDimension - inset * 2
                drawArc(
                    color = Color(0x1FFFFFFF), startAngle = -90f, sweepAngle = 360f, useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(d, d),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                )
                if (progress > 0.008f) drawArc(
                    brush = AccentGradient, startAngle = -90f, sweepAngle = 360f * progress, useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(d, d),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                )
            }
            // Bradul — crește CONTINUU cu timpul, desenat de mână (două tonuri, umbră, asimetrii mici)
            androidx.compose.foundation.Canvas(Modifier.size(126.dp)) { drawPine(progress, alive = true) }
        }
        if (grown > 0 || withered > 0) {
            Spacer(Modifier.height(10.dp))
            // Pădurea de azi — brazi mici desenați, cei ofiliți în gri, aplecați
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.Bottom
            ) {
                repeat(grown.coerceAtMost(18)) {
                    androidx.compose.foundation.Canvas(Modifier.size(26.dp).padding(horizontal = 2.dp)) {
                        drawPine(1f, alive = true)
                    }
                }
                repeat(withered.coerceAtMost(18)) {
                    androidx.compose.foundation.Canvas(Modifier.size(26.dp).padding(horizontal = 2.dp)) {
                        rotate(12f) { drawPine(0.8f, alive = false) }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "$grown ${if (grown == 1) "copac crescut" else "copaci crescuți"} azi" +
                (if (withered > 0) " · $withered ofiliți" else ""),
            style = BodyTiny.copy(color = TextSecondary), textAlign = TextAlign.Center
        )
    }
}

/**
 * Brad „militar", desenat de mână: trunchi, 4 rânduri de coroană cu două tonuri
 * (umbră + lumină), contururi ușor asimetrice. Crește continuu cu [p] (0..1):
 * întâi vlăstar cu două frunze, apoi rândurile coroanei apar unul câte unul.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPine(p: Float, alive: Boolean) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val baseY = h * 0.90f
    val dark = if (alive) Color(0xFF3E4F31) else Color(0xFF4A4A44)
    val mid = if (alive) Color(0xFF55693F) else Color(0xFF5C5C54)
    val light = if (alive) Color(0xFF83a066) else Color(0xFF73736A)
    val trunk = if (alive) Color(0xFF6A5940) else Color(0xFF5C5248)

    // umbra de la sol — crește cu copacul
    drawOval(
        Color(0x33000000),
        topLeft = androidx.compose.ui.geometry.Offset(cx - w * 0.20f * (0.4f + 0.6f * p), baseY - h * 0.018f),
        size = androidx.compose.ui.geometry.Size(w * 0.40f * (0.4f + 0.6f * p), h * 0.05f)
    )

    if (p < 0.16f) {
        // vlăstar: tulpină + două frunze
        val g = (p / 0.16f).coerceIn(0.25f, 1f)
        val stemH = h * 0.22f * g
        drawLine(mid, androidx.compose.ui.geometry.Offset(cx, baseY), androidx.compose.ui.geometry.Offset(cx, baseY - stemH), strokeWidth = w * 0.028f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        val leaf = androidx.compose.ui.graphics.Path()
        leaf.moveTo(cx, baseY - stemH)
        leaf.cubicTo(cx - w * 0.16f * g, baseY - stemH - h * 0.10f * g, cx - w * 0.05f, baseY - stemH - h * 0.16f * g, cx, baseY - stemH - h * 0.02f)
        drawPath(leaf, light)
        val leaf2 = androidx.compose.ui.graphics.Path()
        leaf2.moveTo(cx, baseY - stemH * 0.7f)
        leaf2.cubicTo(cx + w * 0.15f * g, baseY - stemH * 0.7f - h * 0.09f * g, cx + w * 0.05f, baseY - stemH * 0.7f - h * 0.14f * g, cx, baseY - stemH * 0.7f - h * 0.015f)
        drawPath(leaf2, mid)
        return
    }

    // creșterea de la vlăstar la brad matur
    val g = ((p - 0.16f) / 0.84f).coerceIn(0f, 1f)
    val treeH = h * (0.36f + 0.50f * g)
    val topY = baseY - treeH

    // trunchiul
    drawRoundRect(
        trunk,
        topLeft = androidx.compose.ui.geometry.Offset(cx - w * 0.035f, baseY - treeH * 0.22f),
        size = androidx.compose.ui.geometry.Size(w * 0.07f, treeH * 0.24f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.02f)
    )

    // rândurile coroanei, de jos în sus; fiecare apare pe măsură ce g crește
    val tiers = 4
    for (i in 0 until tiers) {
        val show = ((g - i * 0.22f) / 0.22f).coerceIn(0f, 1f)
        if (show <= 0f) continue
        val f0 = i / tiers.toFloat()
        val f1 = (i + 1.15f) / tiers
        val yBot = baseY - treeH * (0.16f + 0.70f * f0)
        val yTop = baseY - treeH * (0.16f + 0.74f * f1.coerceAtMost(1f))
        val half = w * (0.34f - 0.066f * i) * show
        val wob = w * 0.014f * (if (i % 2 == 0) 1 else -1)   // asimetrie „de mână"
        // tonul închis — toată pana
        val body = androidx.compose.ui.graphics.Path()
        body.moveTo(cx + wob, yTop)
        body.lineTo(cx - half, yBot)
        body.quadraticBezierTo(cx - half * 0.35f, yBot + h * 0.012f, cx, yBot - h * 0.004f)
        body.quadraticBezierTo(cx + half * 0.4f, yBot + h * 0.010f, cx + half * 0.96f, yBot - h * 0.002f)
        body.close()
        drawPath(body, if (i == 0) dark else mid)
        // lumina — jumătatea stângă, ușor mai mică (efect 3D fără plastic)
        val lit = androidx.compose.ui.graphics.Path()
        lit.moveTo(cx + wob * 0.4f, yTop + (yBot - yTop) * 0.10f)
        lit.lineTo(cx - half * 0.82f, yBot - (yBot - yTop) * 0.03f)
        lit.quadraticBezierTo(cx - half * 0.3f, yBot + h * 0.006f, cx - w * 0.006f, yBot - h * 0.006f)
        lit.close()
        drawPath(lit, light.copy(alpha = 0.85f))
    }
    // vârful — o mică flamă verde
    if (g > 0.9f) {
        drawCircle(light, radius = w * 0.022f, center = androidx.compose.ui.geometry.Offset(cx, topY - h * 0.012f))
    }
}

/** Cuvinte de blocat: pachete după tipul de adicție + cuvintele tale. Totul rămâne pe telefon. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetoxWordsSheet(initial: String, onSave: (String) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val packs = listOf(
        Triple("🍸", "Club & băutură", listOf("shots", "hai la shots", "hai să ne îmbătăm", "ies la băut", "beau ceva", "tequila", "vodka", "whisky", "bere", "club", "chef", "mahmureală", "alcool")),
        Triple("🎰", "Pariuri & jocuri", listOf("pariuri", "betting", "casino", "ruletă", "poker", "superbet", "betano", "unibet", "fortuna", "mostbet", "1xbet", "mize", "cotă", "bilet", "jackpot")),
        Triple("🔞", "Conținut +18", listOf("porn", "porno", "xxx", "sex", "xvideos", "pornhub", "onlyfans", "nsfw", "hentai")),
        Triple("🌀", "Anime & manga", listOf("anime", "manga", "crunchyroll", "mangadex", "9anime", "otaku", "webtoon", "naruto", "one piece"))
    )
    fun addPack(pack: List<String>) {
        val cur = text.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        text = (cur + pack).distinctBy { it.lowercase() }.joinToString("\n")
    }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Surface1, shape = SheetShape
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)
                .fillMaxHeight(0.9f).verticalScroll(rememberScrollState()).imePadding()
        ) {
            Text("Cuvinte de blocat", style = TitleModule.copy(fontSize = 20.sp))
            Spacer(Modifier.height(6.dp))
            Text(
                "Alege un pachet gata făcut sau scrie-ți cuvintele tale. Când le tastezi oriunde pe telefon, paznicul te oprește. Rămân DOAR pe telefonul tău.",
                style = BodySmall.copy(color = TextSecondary)
            )
            Spacer(Modifier.height(14.dp))
            SectionLabel("Pachete după tipul de adicție")
            Spacer(Modifier.height(8.dp))
            packs.forEach { (emoji, name, pack) ->
                ForjaCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), fill = Surface2, padding = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(emoji, fontSize = 22.sp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, style = BodyStrong.copy(fontSize = 14.sp))
                            Text("${pack.size} cuvinte · le poți edita după", style = BodyTiny.copy(color = TextDim))
                        }
                        SecondaryButton("Adaugă", padV = 8.dp, onClick = { addPack(pack) })
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            SectionLabel("Personalizat — cuvintele tale")
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.TextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("un cuvânt pe fiecare rând", style = BodySmall) },
                textStyle = Body.copy(color = TextPrimary, fontSize = 15.sp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).clip(SecondaryShape),
                colors = androidx.compose.material3.TextFieldDefaults.colors(
                    focusedContainerColor = Surface2, unfocusedContainerColor = Surface2,
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    cursorColor = Accent2,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                )
            )
            Spacer(Modifier.height(14.dp))
            PrimaryButton("Salvează", onClick = { onSave(text.trim()) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Pornirea paznicului în 3 pași (ordinea reală Android): 1) încerci în Accesibilitate și Android blochează,
 *  2) deblochezi din Aplicații, 3) pornești din Accesibilitate. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GuardWizardSheet(
    step: Int,
    onOpenApps: () -> Unit,
    onNext: () -> Unit,
    onOpenAccess: () -> Unit,
    onClose: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Surface1, shape = SheetShape
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("PASUL $step DIN 3", style = monoLabel(9, 0.14f).copy(color = Accent2))
            Spacer(Modifier.height(6.dp))
            when (step) {
                1 -> {
                    Text("Încearcă să pornești paznicul", style = TitleModule.copy(fontSize = 20.sp))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Intră în Accesibilitate, apasă pe „FORJA · Detox de adicție” și încearcă să-l pornești. Android îl va bloca cu un mesaj „Setare restricționată / Restricted setting”.",
                        style = Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = TextSecondary)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "E normal — chiar acest blocaj deblochează opțiunea de care avem nevoie la pasul 2.",
                        style = BodySmall.copy(color = Accent2, lineHeight = 19.sp)
                    )
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton("Deschide Accesibilitatea", onClick = onOpenAccess, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton("Am încercat → pasul 2", onClick = onNext, modifier = Modifier.fillMaxWidth())
                }
                2 -> {
                    Text("Deblochează setările", style = TitleModule.copy(fontSize = 20.sp))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Acum, în Setări → Aplicații, caută FORJA și deschide-l.",
                        style = Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = TextSecondary)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("1.  Sus în dreapta, apasă cele trei puncte  ⋮", style = BodySmall.copy(color = TextSecondary, lineHeight = 19.sp))
                    Spacer(Modifier.height(6.dp))
                    Text("2.  Alege „Allow restricted settings” (Permite setările restricționate). Dacă cere codul telefonului, introdu-l.", style = BodySmall.copy(color = TextSecondary, lineHeight = 19.sp))
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton("Deschide Aplicații", onClick = onOpenApps, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton("Am făcut → pasul 3", onClick = onNext, modifier = Modifier.fillMaxWidth())
                }
                else -> {
                    Text("Pornește paznicul", style = TitleModule.copy(fontSize = 20.sp))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Revii în Accesibilitate și pornește „FORJA · Detox de adicție”. Acum comutatorul merge — paznicul e activ și te oprește la tentația aleasă de tine.",
                        style = Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = TextSecondary)
                    )
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton("Deschide Accesibilitatea", onClick = onOpenAccess, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton("Am terminat", onClick = onClose, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetoxTextSheet(
    title: String,
    hint: String,
    placeholder: String,
    initial: String,
    singleLine: Boolean,
    onSave: (String) -> Unit,
    onClose: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Surface1, shape = SheetShape
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).imePadding()) {
            Text(title, style = TitleModule.copy(fontSize = 20.sp))
            Spacer(Modifier.height(6.dp))
            Text(hint, style = BodySmall.copy(color = TextSecondary))
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.TextField(
                value = text,
                onValueChange = { text = it },
                singleLine = singleLine,
                placeholder = { Text(placeholder, style = BodySmall) },
                textStyle = Body.copy(color = TextPrimary, fontSize = 15.sp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp).clip(SecondaryShape),
                colors = androidx.compose.material3.TextFieldDefaults.colors(
                    focusedContainerColor = Surface2, unfocusedContainerColor = Surface2,
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    cursorColor = Accent2,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                )
            )
            Spacer(Modifier.height(14.dp))
            PrimaryButton("Salvează", onClick = { onSave(text.trim()) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Alege aplicația + ora până la care e blocată. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerSheet(onClose: () -> Unit, onPick: (pkg: String, label: String, untilHour: Int) -> Unit) {
    val context = LocalContext.current
    var hour by remember { mutableStateOf(18) }
    val apps = remember {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null
                pkg to (ri.loadLabel(pm)?.toString() ?: pkg)
            }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Surface1, shape = SheetShape
    ) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .fillMaxHeight(0.85f)
        ) {
            Text("Ce aplicație intră în pauză?", style = TitleModule.copy(fontSize = 20.sp))
            Spacer(Modifier.height(12.dp))
            SectionLabel("Blocată până la ora")
            Spacer(Modifier.height(8.dp))
            Row {
                listOf(12, 15, 18, 20, 22).forEach { h ->
                    Box(
                        Modifier
                            .padding(end = 8.dp)
                            .clip(ChipShape)
                            .background(if (h == hour) TabPillActive else Surface2)
                            .pressable({ hour = h })
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text("%02d:00".format(h), style = BodyStrong.copy(color = if (h == hour) Accent2 else TextSecondary))
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Column(Modifier.verticalScroll(rememberScrollState())) {
                apps.forEach { (pkg, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .pressable({ onPick(pkg, label, hour) })
                            .padding(vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(label, style = BodyStrong.copy(fontSize = 14.sp), modifier = Modifier.weight(1f))
                        Text("alege", style = BodyTiny.copy(color = Accent2))
                    }
                }
            }
        }
    }
}
