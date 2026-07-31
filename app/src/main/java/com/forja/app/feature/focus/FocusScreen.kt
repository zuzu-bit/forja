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

    LaunchedEffect(Unit) {
        focusActive = app.prefs.focusActive.first()
    }
    // Timp de ecran real azi (dacă avem permisiunea)
    LaunchedEffect(hasUsage) {
        if (hasUsage) {
            try {
                val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val start = Fmt.startOfDayMillis()
                val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, System.currentTimeMillis())
                val total = stats.filter { it.lastTimeUsed >= start }.sumOf { it.totalTimeInForeground }
                screenTimeMin = (total / 60000).toInt()
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
                if (screenTimeMin >= 0) "Timp de ecran azi: ${Fmt.durationHm(screenTimeMin)}"
                else "Timpul de ecran apare după ce dai permisiunea.",
                style = monoLabel(9, 0.12f).copy(color = Accent2)
            )

            Spacer(Modifier.height(24.dp))

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
                    "Ca să blocheze aplicațiile alese, FORJA are nevoie de permisiunea „Usage Access" din Android.",
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
