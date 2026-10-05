package com.forja.app.feature.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.forja.app.ForjaApp
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*
import com.forja.app.core.voice.ScreenAgent
import com.forja.app.core.voice.VoiceAssistant
import com.forja.app.core.voice.VoiceWakeService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val INTRO = "Salut, sunt FORJA. Apasă butonul mare din mijloc și spune ce vrei: " +
    "trimite mesaj lui Ion, sună-l pe Andrei, pune muzică pe YouTube sau deschide antrenamentul. Spune „ajutor” oricând."

private val EXAMPLES = listOf(
    "Trimite mesaj lui Ion: ajung în zece minute",
    "Sună-l pe Andrei",
    "Deschide YouTube și pune Phoenix",
    "Caută pe Google despre căpșuni",
    "Deschide Waze și du-mă la gară",
    "Citește ecranul",
    "Apasă pe primul rezultat",
    "Scrie salut, ce faci",
    "Derulează în jos",
    "Înapoi",
    "Deschide antrenamentul",
    "Pornește somnul",
    "Cum stau azi?",
    "Pune alarma la 7"
)

/**
 * „Hei FORJA" — ecranul asistentului vocal. Totul mare, contrastant și citit cu voce,
 * ca să poată fi folosit fără să te uiți la ecran (merge și cu TalkBack).
 */
@Composable
fun VoiceScreen(listenKey: Int = 0, onListenConsumed: () -> Unit = {}, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = remember { ForjaApp.from(context) }
    val voice = app.voice
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current

    val st by voice.state.collectAsState()
    val wakeOn by app.prefs.voiceWakeOn.collectAsState(initial = false)
    val speakOn by app.prefs.voiceSpeakOn.collectAsState(initial = true)
    val confirmSend by app.prefs.voiceConfirmSend.collectAsState(initial = true)
    val lang by app.prefs.voiceLang.collectAsState(initial = "ro-RO")

    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    var refresh by remember { mutableIntStateOf(0) }
    var micGranted by remember { mutableStateOf(granted(Manifest.permission.RECORD_AUDIO)) }
    // Tot ce se întâmplă aici se și spune: toast-urile nu sunt citite de TalkBack, iar cine nu vede nu le vede.
    fun tell(msg: String) { toast.show(msg); voice.speak(msg) }
    // La revenirea din Setări (accesibilitate, permisiuni) recitim starea.
    val lifecycleOwner = LocalLifecycleOwner.current
    var screenWasOn by remember { mutableStateOf(ScreenAgent.isEnabled(context)) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                refresh++
                micGranted = granted(Manifest.permission.RECORD_AUDIO)
                val nowOn = ScreenAgent.isEnabled(context)
                // Întors din Setări cu serviciul pornit: spunem cu voce, nu doar cu o bifă.
                if (nowOn && !screenWasOn) tell("Accesibilitatea FORJA e pornită: comenzile pe ecran merg. Încearcă: „deschide YouTube și pune Phoenix” sau „citește ecranul”.")
                screenWasOn = nowOn
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        micGranted = ok; refresh++
        if (!ok) tell("Fără microfon nu te pot auzi. Permite din Setări → Aplicații → FORJA.")
    }
    val permsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }

    // La prima deschidere ne prezentăm, cu voce, și abia apoi cerem microfonul (altfel dialogul vorbește peste prezentare).
    LaunchedEffect(Unit) {
        val firstTime = !app.prefs.voiceIntroSeen.first()
        if (firstTime) app.prefs.setVoiceIntroSeen()
        if (firstTime && listenKey == 0) {
            voice.speak(INTRO) { if (!micGranted) micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
        } else if (!micGranted) micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    // Deschis din tile / scurtătură / butonul plutitor (listenKey > 0): ascultă imediat ce avem microfon, o singură dată.
    LaunchedEffect(listenKey, micGranted) {
        if (listenKey > 0 && micGranted) { onListenConsumed(); voice.listen() }
    }

    val listening = st.phase == VoiceAssistant.Phase.LISTENING
    val active = st.phase == VoiceAssistant.Phase.LISTENING || st.phase == VoiceAssistant.Phase.THINKING || st.phase == VoiceAssistant.Phase.SPEAKING
    val status = when (st.phase) {
        VoiceAssistant.Phase.IDLE -> when {
            !st.available -> "Telefonul nu are recunoaștere vocală — instalează aplicația Google."
            !micGranted -> "Permite microfonul ca să te pot auzi."
            st.wakeLoop -> "Spune „Hei FORJA” sau apasă microfonul."
            else -> "Apasă microfonul și spune o comandă."
        }
        VoiceAssistant.Phase.WAITING_WAKE -> "Aștept „Hei FORJA”…"
        VoiceAssistant.Phase.LISTENING -> "Te ascult…"
        VoiceAssistant.Phase.THINKING -> "Mă gândesc…"
        VoiceAssistant.Phase.SPEAKING -> "FORJA vorbește…"
    }

    val reduced = LocalReducedMotion.current
    val infinite = rememberInfiniteTransition(label = "mic")
    val pulse by infinite.animateFloat(1f, 1.10f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    val ringScale = if (listening && !reduced) pulse + st.level * 0.35f else 1f

    Box(Modifier.fillMaxSize().topoBackground(decor = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 40.dp)
        ) {
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("HEI FORJA", style = monoLabel(10, 0.16f).copy(color = Accent2))
                    Spacer(Modifier.height(4.dp))
                    Text("Vorbește cu FORJA", style = TitleModule.copy(fontSize = 26.sp))
                }
                SecondaryButton("Închide", onClick = onBack, modifier = Modifier.semantics { role = Role.Button }, padV = 8.dp)
            }

            Spacer(Modifier.height(18.dp))
            // Starea e anunțată de TalkBack doar când FORJA nu vorbește ea însăși (altfel se aud două voci deodată).
            val announceStatus = !speakOn || st.phase == VoiceAssistant.Phase.IDLE ||
                st.phase == VoiceAssistant.Phase.LISTENING || st.phase == VoiceAssistant.Phase.WAITING_WAKE
            Text(
                status,
                style = BodyStrong.copy(fontSize = 18.sp, lineHeight = 24.sp, color = if (active) Accent2 else TextPrimary),
                modifier = Modifier.fillMaxWidth().semantics { if (announceStatus) liveRegion = LiveRegionMode.Polite }
            )

            Spacer(Modifier.height(10.dp))
            // Microfonul — mare, în mijloc, cu inel care respiră cât ascultă
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                if (listening) {
                    Box(Modifier.size(172.dp).scale(ringScale).clip(CircleShape).background(Accent2.copy(alpha = 0.16f)))
                }
                Box(
                    Modifier
                        .size(140.dp)
                        .shadow(if (active) 18.dp else 10.dp, CircleShape, spotColor = if (active) Accent else Color.Black)
                        .clip(CircleShape)
                        .then(if (active) Modifier.background(AccentGradient) else Modifier.background(Surface2))
                        .border(2.dp, if (active) Color(0x996F855A) else StrokeCardStrong, CircleShape)
                        .semantics {
                            role = Role.Button
                            contentDescription = if (active) "Oprește ascultarea" else "Microfon. Apasă și spune comanda."
                        }
                        .pressable({
                            when {
                                active -> voice.stop()
                                micGranted -> voice.listen()
                                else -> micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }, scaleDown = 0.94f),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (active) Icons.Filled.Stop else Icons.Filled.Mic, contentDescription = null,
                        tint = if (active) OnAccent else TextPrimary, modifier = Modifier.size(58.dp)
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            ForjaCard(Modifier.fillMaxWidth()) {
                SectionLabel("Ai spus")
                Spacer(Modifier.height(8.dp))
                Text(st.transcript.ifBlank { "—" }, style = TitleModule.copy(fontSize = 22.sp, lineHeight = 27.sp))
            }
            Spacer(Modifier.height(10.dp))
            ForjaCard(
                Modifier.fillMaxWidth().semantics { if (!speakOn) liveRegion = LiveRegionMode.Polite },
                stroke = if (st.question != null) Color(0x806F855A) else StrokeCardStrong
            ) {
                SectionLabel(if (st.question != null) "FORJA întreabă" else "FORJA", color = if (st.question != null) Accent2 else TextDim)
                Spacer(Modifier.height(8.dp))
                Text(
                    st.question ?: st.response.ifBlank { "Salut! Spune, de exemplu: „trimite mesaj lui Ion, ajung în zece minute”." },
                    style = Body.copy(fontSize = 17.sp, lineHeight = 23.sp, color = TextPrimary)
                )
            }

            Spacer(Modifier.height(14.dp))
            // Pentru cine preferă să scrie (sau când microfonul nu e disponibil)
            var typed by remember { mutableStateOf("") }
            fun send() { if (typed.isNotBlank()) { voice.submitText(typed.trim()); typed = "" } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = typed, onValueChange = { typed = it }, singleLine = true,
                    placeholder = { Text("Sau scrie comanda aici", style = Body) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    textStyle = BodyStrong.copy(fontSize = 15.sp),
                    modifier = Modifier.weight(1f).clip(SecondaryShape).border(1.dp, StrokeCardStrong, SecondaryShape),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Surface2, unfocusedContainerColor = Surface1,
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Accent2,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                    )
                )
                Spacer(Modifier.width(8.dp))
                MonoButton("Trimite", onClick = { send() }, modifier = Modifier.semantics { role = Role.Button }, color = Accent2)
            }

            Spacer(Modifier.height(22.dp))
            SectionLabel("Setări")
            Spacer(Modifier.height(10.dp))
            VoiceToggle(
                "„Hei FORJA” mereu la ascultare",
                if (wakeOn) "Microfonul e pornit în fundal (vezi notificarea). Spune „Hei FORJA” oricând, și cu ecranul stins."
                else "Pornește ca să chemi FORJA cu vocea, fără să atingi telefonul. Consumă ceva baterie.",
                checked = wakeOn
            ) { on ->
                scope.launch {
                    if (on) {
                        if (!micGranted) { micLauncher.launch(Manifest.permission.RECORD_AUDIO); return@launch }
                        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                            permsLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                        }
                        app.prefs.setVoiceWakeOn(true)
                        if (VoiceWakeService.start(context)) tell("„Hei FORJA” ascultă — și cu ecranul stins.")
                        else { app.prefs.setVoiceWakeOn(false); tell("Nu am putut porni ascultarea. Verifică microfonul.") }
                    } else {
                        app.prefs.setVoiceWakeOn(false)
                        VoiceWakeService.stop(context)
                        tell("Ascultarea continuă e oprită.")
                    }
                }
            }
            VoiceToggle("Răspunsuri cu voce", "FORJA citește tot ce face. Oprește dacă folosești TalkBack și nu vrei dublură.", checked = speakOn) { v ->
                scope.launch { app.prefs.setVoiceSpeakOn(v) }
            }
            VoiceToggle("Confirmă înainte de a trimite", "Mesajul se citește cu voce și pleacă doar după „da”. Când numele din agendă nu e exact, se citește oricum.", checked = confirmSend) { v ->
                scope.launch { app.prefs.setVoiceConfirmSend(v) }
            }
            VoiceSetting("Limba în care asculți", "Comenzile se înțeleg în română și engleză; alege limba în care vorbești de obicei.") {
                Row {
                    LangChip("Română", lang == "ro-RO") { scope.launch { app.prefs.setVoiceLang("ro-RO") } }
                    Spacer(Modifier.width(6.dp))
                    LangChip("English", lang == "en-US") { scope.launch { app.prefs.setVoiceLang("en-US") } }
                }
            }

            // Lucrul în alte aplicații: serviciul de accesibilitate „Comenzi pe ecran” (doar utilizatorul îl poate porni)
            val screenOn = remember(refresh, st.phase) { ScreenAgent.isEnabled(context) }
            val screenConnected = remember(refresh, st.phase) { ScreenAgent.isConnected() }
            VoiceSetting(
                "Comenzi pe ecran, în alte aplicații",
                when {
                    screenConnected -> "Pornit (serviciul FORJA din Accesibilitate, același ca pentru Focus): FORJA poate citi ecranul, apăsa, scrie și căuta în aplicația din față („citește ecranul”, „apasă pe…”, „scrie…”, „deschide YouTube și pune…”)."
                    screenOn -> "Pornit în setări, dar neconectat încă. Oprește și pornește din nou „FORJA” din Setări → Accesibilitate."
                    else -> "Oprit. Pornește serviciul „FORJA” din Setări → Accesibilitate (e în Echipare, la „Accesibilitate”) ca FORJA să poată lucra în YouTube, Google, WhatsApp și orice altă aplicație."
                }
            ) {
                if (screenConnected) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(Positive), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Check, contentDescription = "Comenzi pe ecran: pornit", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                } else {
                    Box(
                        Modifier.clip(ChipShape).background(AccentGradient)
                            .semantics { role = Role.Button; contentDescription = "Pornește comenzile pe ecran din setările de accesibilitate" }
                            .pressable({
                                if (!ScreenAgent.openSettings(context)) tell("Deschide manual Setări → Accesibilitate → FORJA.")
                                else voice.speak("Pornește „FORJA” și confirmă. Dacă Android spune „setare restricționată”, intră în Setări, Aplicații, FORJA, meniul cu trei puncte, „Permite setările restricționate”. Apoi revino în FORJA.")
                            }).padding(horizontal = 14.dp, vertical = 8.dp)
                    ) { Text("Pornește", style = ButtonTextSmall) }
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionLabel("Permisiuni pentru comenzi")
            Spacer(Modifier.height(10.dp))
            val contactsOn = remember(refresh) { granted(Manifest.permission.READ_CONTACTS) }
            val smsOn = remember(refresh) { granted(Manifest.permission.SEND_SMS) }
            val callOn = remember(refresh) { granted(Manifest.permission.CALL_PHONE) }
            ForjaCard(Modifier.fillMaxWidth(), padding = 6.dp) {
                PermLine("Microfon", "ca să te aud", micGranted) { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                PermLine("Contacte", "ca să găsesc numărul după nume", contactsOn) { permsLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS)) }
                PermLine("SMS", "ca mesajul să plece fără să atingi ecranul", smsOn) { permsLauncher.launch(arrayOf(Manifest.permission.SEND_SMS)) }
                PermLine("Apeluri", "ca să sun direct, nu doar să formez", callOn) { permsLauncher.launch(arrayOf(Manifest.permission.CALL_PHONE)) }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Contactele și mesajele rămân pe telefon: FORJA le folosește doar pe loc, pentru comanda ta, și nu le trimite nicăieri.",
                style = BodyTiny.copy(color = TextDim)
            )

            Spacer(Modifier.height(22.dp))
            SectionLabel("Exemple — apasă ca să încerci")
            Spacer(Modifier.height(10.dp))
            ForjaCard(Modifier.fillMaxWidth(), padding = 4.dp) {
                for (ex in EXAMPLES) {
                    Text(
                        "„$ex”",
                        style = Body.copy(fontSize = 15.sp, lineHeight = 20.sp, color = TextPrimary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { role = Role.Button; contentDescription = "Încearcă: $ex" }
                            .pressable({ voice.submitText(ex) })
                            .padding(horizontal = 10.dp, vertical = 10.dp)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Mai poți spune: „deschide harta”, „respiră”, „ce zi e azi”, „cine e online”, „m-am trezit”, „repetă”, „oprește ascultarea”, " +
                    "„deschide WhatsApp și trimite mesaj lui Ion că ajung”, „open Google and search about strawberries”, „caută aici …”, „apasă pe căutare”, „enter”.",
                style = BodySmall.copy(color = TextSecondary)
            )
        }
    }
}

/** Comutator accesibil: tot rândul e controlul, TalkBack anunță „titlu, descriere, comutator, pornit/oprit”. */
@Composable
private fun VoiceToggle(title: String, subtitle: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    ForjaCard(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onToggle)
            .semantics { stateDescription = if (checked) "pornit" else "oprit" },
        padding = 14.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = BodyStrong.copy(fontSize = 14.sp))
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = BodyTiny.copy(color = TextSecondary))
            }
            Spacer(Modifier.width(12.dp))
            // Comutatorul desenat rămâne doar vizual: rândul întreg e cel anunțat și apăsat.
            Box(Modifier.clearAndSetSemantics { }) { ForjaSwitch(checked, onToggle) }
        }
    }
}

@Composable
private fun VoiceSetting(title: String, subtitle: String, trailing: @Composable () -> Unit) {
    ForjaCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), padding = 14.dp) {
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

@Composable
private fun LangChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(ChipShape)
            .background(if (selected) TabPillActive else Surface2)
            .border(1.dp, if (selected) Color(0x996F855A) else StrokeCardStrong, ChipShape)
            .semantics { role = Role.Button; contentDescription = label + if (selected) ", selectat" else "" }
            .pressable(onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Text(label, style = monoLabel(10, 0.10f).copy(color = if (selected) Accent2 else TextSecondary))
    }
}

@Composable
private fun PermLine(title: String, sub: String, on: Boolean, onActivate: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = BodyStrong.copy(fontSize = 14.sp))
            Text(sub, style = BodyTiny.copy(color = TextSecondary))
        }
        Spacer(Modifier.width(10.dp))
        if (on) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(Positive), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, contentDescription = "$title: permis", tint = Color.White, modifier = Modifier.size(16.dp))
            }
        } else {
            Box(
                Modifier.clip(ChipShape).background(AccentGradient)
                    .semantics { role = Role.Button; contentDescription = "Permite $title" }
                    .pressable(onActivate).padding(horizontal = 14.dp, vertical = 8.dp)
            ) { Text("Permite", style = ButtonTextSmall) }
        }
    }
}

/** Butonul plutitor cu microfon — pe toate ecranele principale, mereu la același loc. */
@Composable
fun VoiceFab(wakeOn: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(58.dp)
            .shadow(12.dp, CircleShape, spotColor = Accent)
            .clip(CircleShape)
            .background(AccentGradient)
            .border(1.dp, Color(0x996F855A), CircleShape)
            .semantics {
                role = Role.Button
                contentDescription = if (wakeOn) "Hei FORJA, asistent vocal. Ascultarea continuă e pornită. Apasă și spune o comandă."
                else "Hei FORJA, asistent vocal. Apasă și spune o comandă."
            }
            .pressable(onClick, scaleDown = 0.92f),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Filled.Mic, contentDescription = null, tint = OnAccent, modifier = Modifier.size(26.dp))
        if (wakeOn) LiveDotBadge(Modifier.align(Alignment.TopEnd).padding(10.dp))
    }
}
