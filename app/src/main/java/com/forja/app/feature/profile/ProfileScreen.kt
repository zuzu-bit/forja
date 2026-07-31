package com.forja.app.feature.profile

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forja.app.ForjaApp
import com.forja.app.core.data.Friend
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*
import com.forja.app.core.util.Fmt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Profil: identitate + controale oneste, nimic îngropat. Statistici reale din Room. */
@Composable
fun ProfileScreen(onLogout: () -> Unit, onOpenMapGhost: () -> Unit) {
    val context = LocalContext.current
    val app = remember { ForjaApp.from(context) }
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current

    var name by remember { mutableStateOf("") }
    var inviteCode by remember { mutableStateOf("") }
    var since by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        name = app.prefs.cachedName.first()
        try {
            app.auth.loadProfile()?.let {
                name = it.name
                inviteCode = it.inviteCode
                app.prefs.setCachedName(it.name)
            }
        } catch (_: Exception) { }
        since = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy"))
    }

    val weekM by app.db.activityDao().distanceSince(Fmt.startOfWeekMillis()).collectAsState(initial = 0.0)
    val workouts by app.db.workoutDao().sessionCountSince(Fmt.startOfWeekMillis()).collectAsState(initial = 0)
    val weekTarget by app.prefs.weekKmTarget.collectAsState(initial = 29)
    val notifOn by app.prefs.notifOn.collectAsState(initial = true)
    val sleepReminder by app.prefs.sleepReminder.collectAsState(initial = false)

    var friends by remember { mutableStateOf<List<Friend>>(emptyList()) }
    LaunchedEffect(Unit) {
        val uid = app.auth.currentUid ?: return@LaunchedEffect
        app.friends.friendsFlow(uid).collect { friends = it }
    }

    // Serie: zile consecutive cu activitate/antrenament — simplu și onest.
    var streak by remember { mutableStateOf(0) }
    LaunchedEffect(weekM, workouts) {
        var s = 0
        for (ago in 0..30) {
            val dayStart = Fmt.startOfDayMillis(ago.toLong())
            val dayEnd = dayStart + 24 * 3600_000
            val hasActivity = app.db.activityDao().since(dayStart).first().any { it.startAt < dayEnd }
            if (hasActivity) s++ else if (ago > 0) break
        }
        streak = s
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Surface0)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 120.dp)
    ) {
        Spacer(Modifier.height(16.dp))

        // Identitate
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(name = name.ifBlank { "F" }, size = 76.dp, ring = true)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(name.ifBlank { "Sportiv FORJA" }, style = TitleModule.copy(fontSize = 24.sp))
                Text("CU FORJA DIN $since", style = monoLabel(9, 0.14f))
            }
        }

        Spacer(Modifier.height(22.dp))

        // Statistici reale
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatBlock(Fmt.km(weekM), "KM SĂPT.")
            StatBlock("$streak", "ZILE SERIE")
            StatBlock("$workouts", "ANTRENAM.")
        }

        Spacer(Modifier.height(22.dp))

        // Obiectivul săptămânii
        ForjaCard(Modifier.fillMaxWidth()) {
            SectionLabel("Obiectivul săptămânii · $weekTarget km")
            Spacer(Modifier.height(10.dp))
            val progress = ((weekM / 1000.0) / weekTarget).coerceIn(0.0, 1.0).toFloat()
            val p by animateFloatAsState(progress, Springs.natural(), label = "goal")
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(SwitchOff)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(p.coerceAtLeast(0.02f))
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(AccentGradient)
                )
            }
            Spacer(Modifier.height(8.dp))
            val remaining = (weekTarget - weekM / 1000.0).coerceAtLeast(0.0)
            val rival = friends.filter { !it.ghost }.maxByOrNull { it.weekKm }
            Text(
                buildString {
                    if (remaining > 0) append("Mai ai ${Fmt.km(remaining * 1000)} km până duminică.")
                    else append("Obiectiv atins. Respect.")
                    rival?.let {
                        if (it.weekKm > 0) append(" ${it.name.split(' ').first()} e la ${Fmt.km(it.weekKm * 1000)} — ${if (it.weekKm > weekM / 1000.0) "o prinzi" else "ești în față"}.")
                    }
                },
                style = BodySmall.copy(color = TextSecondary)
            )
        }

        Spacer(Modifier.height(22.dp))
        SectionLabel("Setări")
        Spacer(Modifier.height(10.dp))

        SettingRow(
            "Notificări",
            "Doar ce contează: serii, prieteni, somn.",
        ) { ForjaSwitch(notifOn) { v -> scope.launch { app.prefs.setNotifOn(v) } } }

        SettingRow(
            "Amintește-mi de somn",
            "Seara, o notificare blândă la fereastra ta de somn.",
        ) { ForjaSwitch(sleepReminder) { v -> scope.launch { app.prefs.setSleepReminder(v) } } }

        SettingRow(
            "Mod fantomă",
            "Dispari de pe hartă. Se setează din hartă.",
            onClick = onOpenMapGhost
        ) { Text("deschide →", style = BodySmall.copy(color = Accent2)) }

        SettingRow(
            "Codul tău de invitație",
            if (inviteCode.isEmpty()) "se încarcă…" else "FORJA-$inviteCode — dă-l prietenilor",
            onClick = {
                if (inviteCode.isNotEmpty()) {
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("FORJA", "FORJA-$inviteCode"))
                    toast.show("Cod copiat.")
                }
            }
        ) { Text("copiază", style = BodySmall.copy(color = Accent2)) }

        // Locația în fundal — harta VIU trăiește și cu aplicația închisă.
        val bgShareOn by app.prefs.bgShareOn.collectAsState(initial = false)
        SettingRow(
            "Locație în fundal",
            if (com.forja.app.core.location.BgLocation.hasBackground(context))
                "prietenii te văd mereu — fantoma e singura excepție"
            else "necesită „Se permite tot timpul” — pornește de pe hartă",
        ) {
            ForjaSwitch(checked = bgShareOn && com.forja.app.core.location.BgLocation.hasBackground(context), onCheckedChange = { on ->
                scope.launch {
                    if (on && !com.forja.app.core.location.BgLocation.hasBackground(context)) {
                        toast.show("Deschide harta și apasă „Activează” pe cardul galben.")
                    } else {
                        app.prefs.setBgShareOn(on)
                        if (on) com.forja.app.core.location.BgLocation.registerIfReady(context)
                        else com.forja.app.core.location.BgLocation.unregister(context)
                        toast.show(if (on) "Locația în fundal e pornită." else "Locația în fundal e oprită.")
                    }
                }
            })
        }

        val geminiKey by app.prefs.geminiKey.collectAsState(initial = "")
        var aiKeyOpen by remember { mutableStateOf(false) }
        val serverOn = app.forjaApi.available
        SettingRow(
            "Analiza AI a pozelor cu mâncare",
            when {
                serverOn -> "prin serverul FORJA ✓ — fără chei la tine"
                geminiKey.isBlank() -> "neactivată — cheie gratuită Gemini, 2 minute"
                else -> "cu cheia ta · serverul FORJA vine în curând"
            },
            onClick = { if (!serverOn) aiKeyOpen = true }
        ) {
            Text(
                if (serverOn) "server ✓" else if (geminiKey.isBlank()) "activează →" else "schimbă",
                style = BodySmall.copy(color = if (serverOn) Positive else Accent2)
            )
        }
        if (aiKeyOpen) {
            com.forja.app.feature.nutrition.AiKeySheet(
                onSaved = { key ->
                    scope.launch {
                        app.prefs.setGeminiKey(key)
                        aiKeyOpen = false
                        toast.show("Cheie AI salvată.")
                    }
                },
                onClose = { aiKeyOpen = false }
            )
        }

        SettingRow(
            "Date & confidențialitate",
            "Jurnalele (mese, somn, activități) se sincronizează în contul tău FORJA. Pozele și clipurile audio NU se stochează — se analizează și dispar. Locația: doar prietenii, doar când nu ești fantomă.",
            onClick = { toast.show("Pozele și sunetele nu se stochează nicăieri — se analizează și dispar.") }
        ) { }

        Spacer(Modifier.height(18.dp))
        Text(
            "Ieși din cont",
            style = BodyStrong.copy(color = LogoutText, fontSize = 15.sp),
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .pressable(onLogout)
                .padding(10.dp)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "FORJA v1.0 · construită după prototipul REAL & VIU",
            style = BodyTiny.copy(color = TextDim2),
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}

@Composable
private fun StatBlock(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = heroNumeral(34))
        Spacer(Modifier.height(4.dp))
        Text(label, style = monoLabel(8, 0.14f))
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit
) {
    ForjaCard(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .then(if (onClick != null) Modifier.pressable(onClick) else Modifier),
        padding = 14.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = BodyStrong.copy(fontSize = 14.sp))
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = BodyTiny.copy(color = TextSecondary))
            }
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}
