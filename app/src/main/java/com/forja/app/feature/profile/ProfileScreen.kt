package com.forja.app.feature.profile

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Tour
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Ghidajul primei vizite: ce spuneau subtitlurile rândurilor, spus o singură dată (≤ 90 de caractere pe pas). */
private val PROFIL_STEPS = listOf(
    CoachStep("profil.cifre", "Săptămâna ta în cifre. Bara se umple cu fiecare kilometru."),
    CoachStep("profil.cod", "Dă codul prietenilor. Așa apăreți unul altuia pe hartă.", MascotState.Happy),
    CoachStep("profil.explorare", "Explorare: zonele prin care treci se deblochează pe hartă."),
    CoachStep("profil.info", "Aici afli ce face fiecare rând. Ghidajul îl reiei jos, din „Reia ghidajul”.", MascotState.Wink)
)

/** „Date & confidențialitate”, la punctul „i”: trimite la contract, nu repetă contractul. */
private const val PRIVACY_DETAILS =
    "Ce pleacă de pe telefon, unde stă și cât timp scrie în Contract. Fără contract semnat, nimic nu pleacă pe site.\n\n" +
        "Mesajele, parolele și conținutul ecranului nu se citesc niciodată.\n\n" +
        "Locația: prietenii te văd doar când nu ești fantomă. Familia te vede mereu."

/** Profil: identitate + controale oneste, nimic îngropat. Rândurile arată starea, nu explică; explicațiile stau în ghidaj și la „i”. */
@Composable
fun ProfileScreen(
    onOpenProbe: () -> Unit = {},
    onLogout: () -> Unit,
    onOpenMapGhost: () -> Unit,
    onOpenPermissions: () -> Unit = {},
    onOpenContract: () -> Unit = {},
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

    val exploreOn by app.prefs.exploreOn.collectAsState(initial = true)
    val familyUids by app.prefs.familyUids.collectAsState(initial = emptySet())
    // Stările citite din DataStore pornesc de la null: până la prima citire rândul nu arată nicio stare (fără „clipit”).
    val ghostUntil by app.prefs.ghostUntilLocal.collectAsState(initial = null)
    val ghostOn = ghostUntil?.let { it == -1L || it > System.currentTimeMillis() }
    val contractSigned by app.prefs.contractSigned.collectAsState(initial = null)
    val bgShareOn by app.prefs.bgShareOn.collectAsState(initial = false)
    val geminiKey by app.prefs.geminiKey.collectAsState(initial = null)
    var aiKeyOpen by remember { mutableStateOf(false) }
    val serverOn = app.forjaApi.available
    val lostPhoneOn = remember { com.forja.app.core.recovery.LostPhoneRecovery.enabled(context) }
    val hasBackground = com.forja.app.core.location.BgLocation.hasBackground(context)

    // Ce spuneau înainte subtitlurile — la „i”, pentru cine vrea să citească.
    val details = remember(familyUids.size) {
        "Cod invitație: îl dai prietenilor și apăreți unul altuia pe hartă.\n\n" +
            "Prieteni din agendă: cei care te au în agendă te găsesc după număr. Serverul ține doar o amprentă a numărului; " +
            "numele din agendă rămân pe telefon.\n\n" +
            "Explorare: zonele prin care treci se deblochează pe hartă. Totul rămâne în contul tău.\n\n" +
            "Fantoma te ascunde de prieteni; " +
            (if (familyUids.isNotEmpty()) "familia (${familyUids.size}) te vede și atunci. " else "familia te vede și atunci. ") +
            "Locația în fundal: prietenii te văd și cu aplicația închisă.\n\n" +
            "Panoul online: harta, locurile și rapoartele tale, pe laptop, cu același cont. " +
            "Telefonul meu: dacă îl pierzi, îl cauți de acolo."
    }

    CoachMarks(screen = "profil", steps = PROFIL_STEPS) {
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

            Column(Modifier.coachTarget("profil.cifre")) {
                // Statistici reale
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatBlock(Fmt.km(weekM), "KM SĂPT.")
                    StatBlock("$streak", "ZILE SERIE")
                    StatBlock("$workouts", "ANTRENAM.")
                }

                Spacer(Modifier.height(22.dp))

                // Obiectivul săptămânii: bara, ce mai ai, camaradul cel mai apropiat — cifre, nu propoziții.
                ForjaCard(Modifier.fillMaxWidth()) {
                    SectionLabel("Obiectiv · $weekTarget km")
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
                    val rival = friends.filter { !it.ghost && it.weekKm > 0 }.maxByOrNull { it.weekKm }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (remaining > 0) "mai ai ${Fmt.km(remaining * 1000)} km" else "obiectiv atins",
                            style = BodySmall.copy(color = if (remaining > 0) TextSecondary else Positive),
                            modifier = Modifier.weight(1f)
                        )
                        rival?.let {
                            // Jar = e în fața ta; verde = tu conduci.
                            Text(
                                "${it.name.split(' ').first()} · ${Fmt.km(it.weekKm * 1000)} km".uppercase(),
                                style = monoLabel(9, 0.12f).copy(color = if (it.weekKm > weekM / 1000.0) EmberHot else Positive),
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // Singurul citat cald al ecranului — din fondul comun, cu alt „salt” decât panoul, ca să nu se repete în aceeași zi.
            Spacer(Modifier.height(18.dp))
            WarmQuote(Tone.ofDay(Tone.general, salt = 1), Modifier.fillMaxWidth())

            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Setări", Modifier.weight(1f))
                InfoDot(title = "Setări", text = details, size = 20, modifier = Modifier.coachTarget("profil.info"))
            }
            Spacer(Modifier.height(10.dp))

            // ── Camarazi & hartă ──
            SettingsGroup {
                SettingRow(
                    icon = Icons.Outlined.GroupAdd,
                    title = "Cod invitație",
                    modifier = Modifier.coachTarget("profil.cod"),
                    state = if (inviteCode.isEmpty()) "…" else "FORJA-$inviteCode",
                    stateColor = Accent2,
                    onClick = {
                        if (inviteCode.isNotEmpty()) {
                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("FORJA", "FORJA-$inviteCode"))
                            toast.show("Cod copiat.")
                        }
                    },
                    trailing = { TrailingIcon(Icons.Outlined.ContentCopy) }
                )
                RowDivider()
                // Prieteni din agendă (ca la Telegram): numărul tău, comutatorul, verificarea prin SMS (opțional), sincronizarea.
                ContactsRow(onOpenPermissions = onOpenPermissions)
                RowDivider()
                SettingRow(
                    icon = Icons.Outlined.Explore,
                    title = "Explorare",
                    modifier = Modifier.coachTarget("profil.explorare"),
                    trailing = {
                        ForjaSwitch(exploreOn) { v ->
                            scope.launch {
                                app.prefs.setExploreOn(v)
                                toast.show(if (v) "Explorarea e pornită. Fiecare plimbare deblochează zone." else "Explorarea e oprită. Zonele deja deblocate rămân.")
                            }
                        }
                    }
                )
                RowDivider()
                SettingRow(
                    icon = Icons.Outlined.VisibilityOff,
                    title = "Mod fantomă",
                    state = ghostOn?.let { if (it) "pornit" else "oprit" },
                    stateColor = if (ghostOn == true) EmberHot else TextDim,
                    onClick = onOpenMapGhost
                )
                RowDivider()
                // Locația în fundal — harta VIU trăiește și cu aplicația închisă.
                SettingRow(
                    icon = Icons.Outlined.MyLocation,
                    title = "Locație în fundal",
                    state = if (hasBackground) null else "lipsește",
                    stateColor = EmberHot,
                    trailing = {
                        ForjaSwitch(checked = bgShareOn && hasBackground, onCheckedChange = { on ->
                            scope.launch {
                                if (on && !com.forja.app.core.location.BgLocation.hasBackground(context)) {
                                    toast.show("Deschide harta și apasă „Activează” pe cardul galben.")
                                } else {
                                    app.prefs.setBgShareOn(on)
                                    // registerIfReady reconciliază: pornește, ține cadența familiei dacă familia nu e goală, sau oprește
                                    // cererea de poziții când nimic nu o mai cere (oprirea directă ar tăia și familia până la următorul ON_START).
                                    com.forja.app.core.location.BgLocation.registerIfReady(context)
                                    toast.show(
                                        when {
                                            on -> "Locația în fundal e pornită."
                                            familyUids.isNotEmpty() -> "Locația în fundal e oprită. Familia (${familyUids.size}) te vede în continuare."
                                            else -> "Locația în fundal e oprită."
                                        }
                                    )
                                }
                            }
                        })
                    }
                )
            }

            Spacer(Modifier.height(10.dp))

            // ── Cont & date ──
            SettingsGroup {
                // v4.0 — panoul online (site-ul FORJA), cu același cont.
                SettingRow(
                    icon = Icons.Outlined.Language,
                    title = "Panoul online",
                    onClick = {
                        try {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(com.forja.app.BuildConfig.INSIGHTS_URL.trimEnd('/') + "/insights")
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (_: Exception) { toast.show("Nu am găsit un browser. Deschide manual: ${com.forja.app.BuildConfig.INSIGHTS_URL}") }
                    },
                    trailing = { TrailingIcon(Icons.AutoMirrored.Outlined.OpenInNew) }
                )
                RowDivider()
                SettingRow(icon = Icons.Outlined.Checklist, title = "Echipare", onClick = onOpenPermissions)
                RowDivider()
                // v4.2 — Contractul de securitate: aici îl recitești sau îl revoci.
                SettingRow(
                    icon = Icons.Outlined.VerifiedUser,
                    title = "Contract",
                    state = contractSigned?.let { if (it) "semnat" else "nesemnat" },
                    stateColor = if (contractSigned == true) Positive else EmberHot,
                    onClick = onOpenContract
                )
                RowDivider()
                // v4.0 — Telefonul meu: găsirea telefonului pierdut din panoul online (opt-in, implicit oprit).
                SettingRow(
                    icon = Icons.Outlined.PhoneAndroid,
                    title = "Telefonul meu",
                    state = if (lostPhoneOn) "pornit" else "oprit",
                    stateColor = if (lostPhoneOn) Positive else TextDim,
                    onClick = onOpenLostPhone
                )
                RowDivider()
                // Analiza pozelor cu mâncare: prin server, cu cheia ta Gemini sau încă neactivată.
                SettingRow(
                    icon = Icons.Outlined.AutoAwesome,
                    title = "Analiza meselor",
                    state = when {
                        serverOn -> "server"
                        geminiKey == null -> null
                        geminiKey.isNullOrBlank() -> "lipsește"
                        else -> "cheia ta"
                    },
                    stateColor = when {
                        serverOn -> Positive
                        geminiKey.isNullOrBlank() -> EmberHot
                        else -> Accent2
                    },
                    onClick = if (serverOn) null else ({ aiKeyOpen = true }),
                    trailing = { if (!serverOn) TrailingIcon(Icons.AutoMirrored.Outlined.KeyboardArrowRight) }
                )
                RowDivider()
                SettingRow(
                    icon = Icons.Outlined.PrivacyTip,
                    title = "Date & confidențialitate",
                    trailing = { InfoDot(title = "Date & confidențialitate", text = PRIVACY_DETAILS, size = 20) }
                )
            }

            Spacer(Modifier.height(10.dp))

            // ── Alerte & ghidaj ──
            SettingsGroup {
                SettingRow(
                    icon = Icons.Outlined.Notifications,
                    title = "Notificări",
                    trailing = { ForjaSwitch(notifOn) { v -> scope.launch { app.prefs.setNotifOn(v) } } }
                )
                RowDivider()
                SettingRow(
                    icon = Icons.Outlined.Bedtime,
                    title = "Amintește-mi de somn",
                    trailing = { ForjaSwitch(sleepReminder) { v -> scope.launch { app.prefs.setSleepReminder(v) } } }
                )
                RowDivider()
                // 4.3 — ghidajul de la prima folosire, din nou, pe fiecare ecran.
                SettingRow(
                    icon = Icons.Outlined.Tour,
                    title = "Reia ghidajul",
                    onClick = {
                        scope.launch {
                            Tutorial.reset(context)
                            toast.show("Ghidajul pornește din nou.")
                        }
                    },
                    trailing = { TrailingIcon(Icons.Outlined.Replay) }
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

            Spacer(Modifier.height(18.dp))
            // Ieșirea așteaptă cel mult ~1,5 s după site (ștergerea listării după număr): rândul spune că lucrează și nu
            // primește a doua atingere. Dacă ecranul e tot aici după 8 s (ceva a dat greș), rândul redevine activ.
            var loggingOut by remember { mutableStateOf(false) }
            LaunchedEffect(loggingOut) { if (loggingOut) { delay(8_000L); loggingOut = false } }
            Text(
                if (loggingOut) "Se deconectează…" else "Ieși din cont",
                style = BodyStrong.copy(color = if (loggingOut) TextDim else LogoutText, fontSize = 15.sp),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .pressable(onClick = { if (!loggingOut) { loggingOut = true; onLogout() } })
                    .padding(10.dp)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "FORJA v${com.forja.app.BuildConfig.VERSION_NAME} · build ${com.forja.app.BuildConfig.VERSION_CODE} · REAL & VIU",
                style = BodyTiny.copy(color = TextDim2),
                modifier = Modifier.align(Alignment.CenterHorizontally).then(com.forja.app.feature.probe.ProbeEntry.taps(onOpenProbe))
            )
        }
    }
}

/**
 * „Prieteni din agendă”: pe rând, titlul și comutatorul „Pot fi găsit după număr”; dedesubt, numărul cu starea lui
 * (verificat / neverificat) sau câmpul în care îl scrii, comparația, „Verifică numărul” prin SMS (opțional), codul din SMS.
 * Numărul se scrie aici: Echipare trimite în Profil pentru el (câmpul lipsea de când Echiparea a trecut pe contract).
 */
@Composable
private fun ContactsRow(onOpenPermissions: () -> Unit) {
    val context = LocalContext.current
    val app = remember { ForjaApp.from(context) }
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current

    // null = DataStore încă necitit: nici câmpul de număr, nici starea nu apar o clipă degeaba.
    val phoneDeclaredOrNull by app.prefs.phoneDeclared.collectAsState(initial = null)
    val phoneDeclared = phoneDeclaredOrNull.orEmpty()
    val numberLoaded = phoneDeclaredOrNull != null
    val contactsOn by app.prefs.contactsOn.collectAsState(initial = false)
    val status by app.prefs.contactsStatus.collectAsState(initial = "")
    val syncedAt by app.prefs.contactsSyncedAt.collectAsState(initial = 0L)
    var tick by remember { mutableIntStateOf(0) }
    val verified = remember(tick) { Discovery.verifiedPhone() }
    val number = verified ?: phoneDeclared
    val hasNumber = PhoneNumbers.isValid(number)
    var busy by remember { mutableStateOf(false) }

    // Numărul declarat: se scrie aici (Echipare trimite în Profil), se poate schimba cât nu e verificat.
    var editing by remember { mutableStateOf(false) }
    var numberInput by remember { mutableStateOf("") }
    var savingNumber by remember { mutableStateOf(false) }
    fun saveNumber() {
        val normalized = PhoneNumbers.normalize(numberInput, PhoneNumbers.defaultCountryCode(context))
        if (normalized == null) { toast.show("Număr invalid. Scrie-l ca +40 7xx xxx xxx."); return }
        if (normalized == phoneDeclared) { editing = false; return }      // neschimbat: doar închide câmpul
        savingNumber = true
        scope.launch {
            try {
                app.prefs.setPhoneDeclared(normalized)
                editing = false
                // Listarea după număr trece pe numărul nou doar dacă e deja pornită.
                if (contactsOn) {
                    val r = Discovery.register(app)
                    if (r.isSuccess) {
                        ContactsSync.runNow(app)
                        toast.show("Număr salvat. Agenda se compară acum.")
                    } else {
                        toast.show(Discovery.humanError(r.exceptionOrNull() ?: Exception()))
                    }
                } else {
                    toast.show("Număr salvat.")
                }
            } catch (_: Exception) {
                toast.show("Nu a mers. Încearcă din nou.")
            }
            savingNumber = false
        }
    }

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

    val needsNumber = numberLoaded && (!hasNumber || editing)
    Column(Modifier.fillMaxWidth()) {
        // Titlul și comutatorul pe rând; starea numărului (verificat / neverificat) stă dedesubt, lângă număr.
        SettingRow(
            icon = Icons.Outlined.Contacts,
            title = "Prieteni din agendă",
            trailing = {
                if (busy) {
                    CircularProgressIndicator(color = Accent2, modifier = Modifier.size(20.dp))
                } else {
                    ForjaSwitch(contactsOn) { on ->
                        if (!on) {
                            busy = true
                            scope.launch {
                                val ok = try { ContactsSync.disable(app) } catch (_: Exception) { false }
                                busy = false
                                // Onest: fără confirmarea site-ului nu spunem că listarea a dispărut — DELETE-ul se reia cu net.
                                toast.show(
                                    if (ok) "Nu mai poți fi găsit după număr. Potrivirile s-au șters de pe telefon."
                                    else "Site-ul nu a răspuns. Listarea de pe site expiră singură în cel mult 30 de zile; reîncerc când e net."
                                )
                            }
                            return@ForjaSwitch
                        }
                        if (!hasNumber) { toast.show("Scrie întâi numărul tău."); return@ForjaSwitch }
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
        )

        if (numberLoaded) {
            Column(Modifier.fillMaxWidth().padding(start = 60.dp, end = 12.dp, bottom = 12.dp)) {
                if (needsNumber) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            value = numberInput,
                            onValueChange = { numberInput = it.filter { ch -> ch.isDigit() || ch == '+' || ch == ' ' }.take(20) },
                            singleLine = true,
                            enabled = !savingNumber,
                            placeholder = { Text("+40 7xx xxx xxx", style = BodySmall) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            textStyle = BodyStrong.copy(fontSize = 14.sp),
                            modifier = Modifier.weight(1f).clip(SecondaryShape).border(1.dp, StrokeCardStrong, SecondaryShape),
                            colors = fieldColors()
                        )
                        Spacer(Modifier.width(10.dp))
                        if (savingNumber) {
                            CircularProgressIndicator(color = Accent2, modifier = Modifier.size(20.dp))
                        } else {
                            PrimaryButton("Salvează", small = true, enabled = numberInput.count { it.isDigit() } >= 6, onClick = ::saveNumber)
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(number, style = monoLabel(10, 0.08f).copy(color = TextSecondary))
                        Text(
                            if (verified != null) " · VERIFICAT" else " · NEVERIFICAT",
                            style = monoLabel(9, 0.12f).copy(color = if (verified != null) Positive else Accent2)
                        )
                        if (verified == null) {
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                Icons.Outlined.Edit, contentDescription = "Schimbă numărul", tint = TextDim,
                                modifier = Modifier
                                    .size(22.dp)
                                    .pressable({ numberInput = phoneDeclared; editing = true })
                                    .padding(3.dp)
                            )
                        }
                    }
                    if (contactsOn) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            buildString {
                                append(status.ifBlank { "se compară zilnic" })
                                if (syncedAt > 0) append(" · ${Fmt.freshness(syncedAt)}")
                            },
                            style = BodyTiny.copy(color = TextDim)
                        )
                    }
                    if (contactsOn || (verified == null && verificationId == null)) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (contactsOn) {
                                MonoButton("Sincronizează", color = Accent2, onClick = {
                                    ContactsSync.runNow(context)
                                    toast.show("Compar agenda. Rezultatul apare aici.")
                                })
                                Spacer(Modifier.width(8.dp))
                            }
                            if (verified == null && verificationId == null) {
                                if (smsBusy) {
                                    CircularProgressIndicator(color = Accent2, modifier = Modifier.size(18.dp))
                                } else {
                                    MonoButton("Verifică numărul", onClick = {
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
                                colors = fieldColors()
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
                }
            }
        }
    }
}

@Composable
private fun fieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Surface2, unfocusedContainerColor = Surface2,
    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
    cursorColor = Accent2,
    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
    disabledContainerColor = Surface2, disabledIndicatorColor = Color.Transparent
)

@Composable
private fun StatBlock(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = heroNumeral(34))
        Spacer(Modifier.height(4.dp))
        Text(label, style = monoLabel(8, 0.14f))
    }
}

/** Un grup de rânduri într-un singur card, cu linii subțiri între ele (ca la Echipare). */
@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    ForjaCard(Modifier.fillMaxWidth(), stroke = StrokeCard, padding = 4.dp, content = content)
}

@Composable
private fun RowDivider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(1.dp).background(Color(0x0DFFFFFF)))
}

@Composable
private fun TrailingIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, tint = TextDim, modifier = Modifier.size(20.dp))
}

/**
 * Un rând de setări: iconița, titlul (≤ 3 cuvinte), starea într-un cuvânt (mono, colorată) și controlul din dreapta.
 * Cu `onClick` și fără `trailing`, rândul primește o săgeată.
 */
@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    state: String? = null,
    stateColor: Color = TextDim,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.pressable(onClick, scaleDown = 0.99f, haptic = false) else Modifier)
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(Surface2),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Accent2, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            style = BodyStrong.copy(fontSize = 15.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (state != null) {
            Spacer(Modifier.width(8.dp))
            Text(state.uppercase(), style = monoLabel(9, 0.12f).copy(color = stateColor), maxLines = 1)
        }
        Spacer(Modifier.width(10.dp))
        when {
            trailing != null -> trailing()
            onClick != null -> TrailingIcon(Icons.AutoMirrored.Outlined.KeyboardArrowRight)
        }
    }
}
