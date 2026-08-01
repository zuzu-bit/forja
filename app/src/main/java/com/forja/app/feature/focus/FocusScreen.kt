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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
fun FocusScreen() {
    val context = LocalContext.current
    val app = remember { ForjaApp.from(context) }
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current

    val rules by app.db.focusDao().rules().collectAsState(initial = emptyList())
    var hasUsage by remember { mutableStateOf(FocusMonitorService.hasUsageAccess(context)) }
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

    // Reîmprospătează la revenirea din setări
    LaunchedEffect(permOpen) {
        if (!permOpen) hasUsage = FocusMonitorService.hasUsageAccess(context)
    }

    Box(Modifier.fillMaxSize().background(Surface0)) {
        VideoSurface(
            url = "https://v.ftcdn.net/04/99/13/67/700_F_499136769_X4Pfv9UFpmLtXcXu0JLdSo80FTPH2BGx_ST.mp4",
            posterUrl = "https://t3.ftcdn.net/jpg/10/16/02/48/500_F_1016024842_sVPfKb4a4gZkZ7XjEjnGtdkeYz1eF2Gz.jpg",
            modifier = Modifier.fillMaxSize()
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xB80A0A0B))
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

            // Cerc de respirație — gentle, 4,6s, scale .82→1.12
            val reduced = LocalReducedMotion.current
            val infinite = rememberInfiniteTransition(label = "breath")
            val breath by infinite.animateFloat(
                initialValue = 0.82f, targetValue = 1.12f,
                animationSpec = infiniteRepeatable(tween(4600), RepeatMode.Reverse),
                label = "breathScale"
            )
            Box(
                Modifier
                    .size(190.dp)
                    .scale(if (reduced) 1f else breath)
                    .clip(CircleShape)
                    .background(Color(0x14FF9E2D))
                    .border(1.5.dp, Color(0x66FFB300), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("Respiră.", style = TitleModule.copy(fontSize = 24.sp))
            }

            Spacer(Modifier.height(22.dp))
            val activeRule = rules.firstOrNull { it.enabled }
            Text(
                when {
                    !focusActive -> "Alege ce aplicații intră în pauză.\nRestul rămâne neatins."
                    activeRule != null -> "${activeRule.label} e blocat până la ${"%02d:%02d".format(activeRule.untilHour, activeRule.untilMinute)}."
                    else -> "Focus pornit — adaugă aplicații mai jos."
                },
                style = Body.copy(fontSize = 15.sp, color = TextPrimary),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
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
                            toast.show("Detox oprit. Ai rezistat — contează.")
                        }
                    }, modifier = Modifier.fillMaxWidth())
                } else {
                    Row {
                        listOf(30, 60, 120).forEach { min ->
                            SecondaryButton(
                                if (min < 120) "$min min" else "2 ore",
                                onClick = {
                                    if (!hasUsage) { permOpen = true; return@SecondaryButton }
                                    scope.launch {
                                        app.prefs.setDetoxUntil(System.currentTimeMillis() + min * 60_000L)
                                        FocusMonitorService.start(context)
                                        toast.show("Detox pornit. Ne vedem peste ${Fmt.durationHm(min)}.")
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
            } else if (!focusActive) {
                PrimaryButton(
                    "Pornește Focus",
                    onClick = {
                        if (rules.none { it.enabled }) {
                            pickerOpen = true
                        } else {
                            FocusMonitorService.start(context)
                            focusActive = true
                            scope.launch { app.prefs.setFocusActive(true) }
                            toast.show("Focus pornit. Respiră.")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Row(Modifier.fillMaxWidth()) {
                    SecondaryButton(
                        "Deblochează 5 min",
                        onClick = {
                            scope.launch {
                                app.prefs.setFocusUnlockUntil(System.currentTimeMillis() + 5 * 60_000)
                                toast.show("5 minute. Se reblochează la ${Fmt.clock(System.currentTimeMillis() + 5 * 60_000)}.")
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(10.dp))
                    SecondaryButton(
                        "Oprește Focus",
                        onClick = {
                            FocusMonitorService.stop(context)
                            focusActive = false
                            scope.launch { app.prefs.setFocusActive(false) }
                            toast.show("Focus oprit.")
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
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

            Spacer(Modifier.height(16.dp))
            Text(
                "FORJA nu citește mesajele, parolele sau conținutul ecranului.",
                style = BodyTiny.copy(color = TextDim),
                textAlign = TextAlign.Center
            )
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
