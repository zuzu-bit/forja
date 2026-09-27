package com.forja.app.feature.profile

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forja.app.ForjaApp
import com.forja.app.core.data.Friend
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*
import com.forja.app.core.social.ContactsReader
import com.forja.app.core.social.ContactsSync
import com.forja.app.core.social.Discovery
import com.forja.app.core.social.PhoneNumbers
import com.forja.app.core.social.PhoneVerify
import com.forja.app.core.util.Fmt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Profil: identitate + controale oneste, nimic îngropat. Statistici reale din Room. */
@Composable
fun ProfileScreen(
    onLogout: () -> Unit,
    onOpenMapGhost: () -> Unit,
    onOpenPermissions: () -> Unit = {},
    onOpenLostPhone: () -> Unit = {}
) {
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
                // Livretul: ștampila deasupra numelui — fără animație, profilul se redeschide des.
                ModuleHeader(
                    stamp = "LIVRET",
                    title = name.ifBlank { "Sportiv FORJA" },
                    titleStyle = TitleModule.copy(fontSize = 24.sp),
                    reveal = false
                )
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

        // Singurul citat cald al ecranului — din fondul comun, cu alt „salt” decât panoul, ca să nu se repete în aceeași zi.
        Spacer(Modifier.height(18.dp))
        WarmQuote(Tone.ofDay(Tone.general, salt = 1), Modifier.fillMaxWidth())

        Spacer(Modifier.height(22.dp))
        SectionLabel("Setări")
        Spacer(Modifier.height(10.dp))

        // v4.0 — panoul online (site-ul FORJA), cu același cont.
        SettingRow(
            "Panoul meu online",
            "Site-ul FORJA: harta cu zonele și locurile tale, organizarea din laptop, rapoartele — cu același cont.",
            onClick = {
                try {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(com.forja.app.BuildConfig.INSIGHTS_URL.trimEnd('/') + "/insights")
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Exception) { toast.show("Nu am găsit un browser. Deschide manual: ${com.forja.app.BuildConfig.INSIGHTS_URL}") }
            }
        ) { Text("deschide ↗", style = BodySmall.copy(color = Accent2)) }

        SettingRow(
            "Echipare",
            "Notificări, locație, microfon, poze, baterie, agendă — șase bife, o singură dată.",
            onClick = onOpenPermissions
        ) { Text("deschide →", style = BodySmall.copy(color = Accent2)) }

        // v4.0 — Explorarea: zone deblocate + locuri unde ai stat; pragul se alege din hartă.
        val exploreOn by app.prefs.exploreOn.collectAsState(initial = true)
        val placeThreshold by app.prefs.placeThresholdMin.collectAsState(initial = 300)
        val familyUids by app.prefs.familyUids.collectAsState(initial = emptySet())
        SettingRow(
            "Explorare",
            buildString {
                append("Zonele prin care treci se deblochează pe hartă; un loc = ai stat ≥ ")
                append(if (placeThreshold >= 60) "${placeThreshold / 60} h" else "$placeThreshold min")
                append(". Totul rămâne în contul tău.")
                if (familyUids.isNotEmpty()) append(" Familia (${familyUids.size}) te vede și în fantomă.")
            },
        ) {
            ForjaSwitch(exploreOn) { v ->
                scope.launch {
                    app.prefs.setExploreOn(v)
                    toast.show(if (v) "Explorarea e pornită. Fiecare plimbare deblochează zone." else "Explorarea e oprită. Zonele deja deblocate rămân.")
                }
            }
        }

        // v4.0 pasul 2 — Sincronizarea în cont (site): se pornește și se oprește din Echipare; aici doar starea.
        val syncStatus by app.prefs.syncStatus.collectAsState(initial = "")
        SettingRow(
            "Sincronizare în cont",
            syncStatus.ifBlank { "oprită" },
            onClick = onOpenPermissions
        ) { Text("deschide →", style = BodySmall.copy(color = Accent2)) }

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

        // Prieteni din agendă (ca la Telegram): numărul tău, comutatorul, verificarea prin SMS (opțional), sincronizarea.
        ContactsRow(onOpenPermissions = onOpenPermissions)

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
                else -> "cu cheia ta · analiză cu model Gemini"
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

        // v4.0 — Telefonul meu: găsirea telefonului pierdut din panoul online (opt-in, implicit oprit).
        val lostPhoneOn = remember { com.forja.app.core.recovery.LostPhoneRecovery.enabled(context) }
        SettingRow(
            "Telefonul meu",
            if (lostPhoneOn) "găsirea e activată — telefonul răspunde panoului online 5, 15 sau 30 de minute, la cererea ta"
            else "oprit — dacă îl pierzi, îl cauți din panoul online; se activează cu o bifă",
            onClick = onOpenLostPhone
        ) { Text(if (lostPhoneOn) "activată →" else "deschide →", style = BodySmall.copy(color = if (lostPhoneOn) Positive else Accent2)) }

        SettingRow(
            "Date & confidențialitate",
            "Jurnalele (mese, somn, activități), zonele explorate și locurile tale se sincronizează în contul tău FORJA. " +
                "Pozele meselor și clipurile audio se analizează și dispar; înregistrarea nopții se păstrează 24 h. " +
                "Locația: prietenii doar când nu ești fantomă — familia mereu. Sugestiile AI la curățenie sunt opt-in.",
            onClick = { toast.show("Nimic nu pleacă de pe telefon fără o bifă pusă de tine.") }
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
            "FORJA v${com.forja.app.BuildConfig.VERSION_NAME} · build ${com.forja.app.BuildConfig.VERSION_CODE} · REAL & VIU",
            style = BodyTiny.copy(color = TextDim2),
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}

/**
 * „Numărul tău · Prieteni din agendă”: numărul (declarat sau verificat), comutatorul „Pot fi găsit după număr”,
 * „Verifică prin SMS” (opțional; dacă Phone Auth nu e activat în consolă, spunem sec), „Sincronizează acum”.
 */
@Composable
private fun ContactsRow(onOpenPermissions: () -> Unit) {
    val context = LocalContext.current
    val app = remember { ForjaApp.from(context) }
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current

    val phoneDeclared by app.prefs.phoneDeclared.collectAsState(initial = "")
    val contactsOn by app.prefs.contactsOn.collectAsState(initial = false)
    val status by app.prefs.contactsStatus.collectAsState(initial = "")
    val syncedAt by app.prefs.contactsSyncedAt.collectAsState(initial = 0L)
    var tick by remember { mutableIntStateOf(0) }
    val verified = remember(tick) { Discovery.verifiedPhone() }
    val number = verified ?: phoneDeclared
    val hasNumber = PhoneNumbers.isValid(number)
    var busy by remember { mutableStateOf(false) }

    // Verificarea prin SMS: codul trimis → câmp de cod → legare de cont.
    var verificationId by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    var smsBusy by remember { mutableStateOf(false) }
    fun activity(): android.app.Activity? {
        var c: android.content.Context = context
        while (c is android.content.ContextWrapper) { if (c is android.app.Activity) return c; c = c.baseContext }
        return null
    }
    fun afterLink(res: Result<String>) {
        smsBusy = false
        res.fold(
            onSuccess = {
                verificationId = null; code = ""
                tick++
                toast.show("Număr verificat prin SMS.")
                // Listarea trece pe „verificat” (poate revendica un număr declarat de alt cont).
                scope.launch { if (contactsOn) Discovery.register(app) }
            },
            onFailure = { toast.show(it.message ?: "Nu a mers. Numărul declarat merge.") }
        )
    }

    ForjaCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).pressable(onOpenPermissions)) {
                Text("Numărul tău · Prieteni din agendă", style = BodyStrong.copy(fontSize = 14.sp))
                Spacer(Modifier.height(2.dp))
                Text(
                    when {
                        verified != null -> "$verified · verificat prin SMS"
                        hasNumber -> "$number · declarat, neverificat"
                        else -> "fără număr — scrie-l în Echipare"
                    },
                    style = BodyTiny.copy(color = TextSecondary)
                )
                Spacer(Modifier.height(2.dp))
                Text("Pot fi găsit după număr", style = BodyTiny.copy(color = TextDim))
            }
            Spacer(Modifier.width(12.dp))
            if (busy) {
                CircularProgressIndicator(color = Accent2, modifier = Modifier.size(20.dp))
            } else {
                ForjaSwitch(contactsOn) { on ->
                    if (!on) {
                        busy = true
                        scope.launch {
                            try { ContactsSync.disable(app) } catch (_: Exception) { }
                            busy = false
                            toast.show("Nu mai poți fi găsit după număr. Potrivirile s-au șters de pe telefon.")
                        }
                        return@ForjaSwitch
                    }
                    if (!hasNumber) { toast.show("Scrie întâi numărul tău, în Echipare."); onOpenPermissions(); return@ForjaSwitch }
                    if (!ContactsReader.granted(context)) { toast.show("Bifează „Agendă” în Echipare."); onOpenPermissions(); return@ForjaSwitch }
                    busy = true
                    scope.launch {
                        try {
                            app.prefs.setContactsOn(true)
                            val r = Discovery.register(app)
                            if (r.isSuccess) {
                                app.prefs.setContactsStatus("")
                                ContactsSync.scheduleIfOn(app)
                                ContactsSync.runNow(app)
                                toast.show("Poți fi găsit după număr. Agenda se compară acum.")
                            } else {
                                app.prefs.setContactsOn(false)
                                toast.show(Discovery.humanError(r.exceptionOrNull() ?: Exception()))
                            }
                        } catch (_: Exception) {
                            app.prefs.setContactsOn(false)
                            toast.show("Nu a mers. Verifică internetul.")
                        }
                        busy = false
                    }
                }
            }
        }
        if (contactsOn || (hasNumber && verified == null)) {
            Spacer(Modifier.height(10.dp))
            if (contactsOn) {
                Text(
                    buildString {
                        append(status.ifBlank { "se compară zilnic, cu net" })
                        if (syncedAt > 0) append(" · ${Fmt.freshness(syncedAt)}")
                    },
                    style = BodyTiny.copy(color = TextSecondary)
                )
                Spacer(Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (contactsOn) {
                    MonoButton("Sincronizează acum", color = Accent2, onClick = {
                        ContactsSync.runNow(context)
                        toast.show("Compar agenda. Durează sub un minut cu net.")
                    })
                    Spacer(Modifier.width(8.dp))
                }
                if (hasNumber && verified == null && verificationId == null) {
                    if (smsBusy) {
                        CircularProgressIndicator(color = Accent2, modifier = Modifier.size(18.dp))
                    } else {
                        MonoButton("Verifică prin SMS", onClick = {
                            val act = activity() ?: return@MonoButton
                            smsBusy = true
                            PhoneVerify.start(act, number) { step ->
                                when (step) {
                                    is PhoneVerify.Step.CodeSent -> { smsBusy = false; verificationId = step.verificationId; toast.show("Cod trimis prin SMS.") }
                                    is PhoneVerify.Step.Completed -> scope.launch { afterLink(PhoneVerify.link(step.credential)) }
                                    is PhoneVerify.Step.Failed -> { smsBusy = false; toast.show(step.message) }
                                }
                            }
                        })
                    }
                }
            }
            if (verificationId != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = code,
                        onValueChange = { code = it.filter { ch -> ch.isDigit() }.take(6) },
                        singleLine = true,
                        placeholder = { Text("Codul din SMS", style = BodySmall) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = BodyStrong.copy(fontSize = 14.sp),
                        modifier = Modifier.weight(1f).clip(SecondaryShape).border(1.dp, StrokeCardStrong, SecondaryShape),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Surface2, unfocusedContainerColor = Surface2,
                            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                            cursorColor = Accent2,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                        )
                    )
                    Spacer(Modifier.width(10.dp))
                    if (smsBusy) {
                        CircularProgressIndicator(color = Accent2, modifier = Modifier.size(20.dp))
                    } else {
                        PrimaryButton("Confirmă", small = true, enabled = code.length >= 4, onClick = {
                            val id = verificationId ?: return@PrimaryButton
                            smsBusy = true
                            scope.launch { afterLink(PhoneVerify.confirm(id, code)) }
                        })
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Serverul păstrează o amprentă a numărului, nu numărul. Numele din agendă nu pleacă de pe telefon.",
                style = BodyTiny.copy(color = TextDim)
            )
        }
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
