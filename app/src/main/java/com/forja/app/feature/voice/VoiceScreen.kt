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
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.text.style.TextOverflow
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
    "începe antrenamentul, pornește un playlist, trimite mesaj lui Ion, sună-l pe Andrei sau citește ecranul. Spune „ajutor” oricând."

/** Un modul și comenzile lui — o plăcuță cu un singur exemplu; lista întreagă într-o foaie, la atingere. */
private data class VoiceModule(
    val title: String,
    val icon: ImageVector,
    val example: String,
    val blurb: String,
    val phrases: List<String>
)

private val MODULES = listOf(
    VoiceModule(
        "Antrenament", Icons.Outlined.FitnessCenter, "Începe antrenamentul",
        "Sesiunea live, fără să atingi ecranul: seriile, pauzele, exercițiile.",
        listOf(
            "Începe antrenamentul", "Începe antrenamentul de picioare", "Am terminat seria", "Pauză", "Continuă",
            "Sari pauza", "Mai dă-mi 15 secunde", "Următorul exercițiu", "Rezumat antrenament", "Termină antrenamentul"
        )
    ),
    VoiceModule(
        "Muzică", Icons.Outlined.MusicNote, "Pornește un playlist",
        "Playerul tău (Spotify, YouTube Music), prin sesiunile media; lista FORJA e clădită din ce asculți.",
        listOf(
            "Pornește muzica", "Pornește un playlist", "Pune muzică de antrenament", "Pune lista cu melodii noi",
            "Pornește playlistul Rock pe Spotify", "Următoarea melodie", "Melodia anterioară", "Ce cântă acum?", "Pauză la muzică"
        )
    ),
    VoiceModule(
        "Hartă", Icons.Outlined.Map, "Unde e Ion?",
        "Prietenii pe harta FORJA, turele tale, locuri și navigație.",
        listOf(
            "Deschide harta", "Unde e Ion?", "Arată-l pe Andrei pe hartă", "Cine e online?", "Pornește o alergare",
            "Pornește o plimbare", "Oprește tura", "Du-mă la gară", "Deschide Waze și du-mă la unitate"
        )
    ),
    VoiceModule(
        "Focus", Icons.Outlined.SelfImprovement, "Pornește focusul",
        "Aplicațiile alese rămân blocate; detoxul digital pune totul în pauză.",
        listOf("Pornește focusul", "Oprește focusul", "Pornește detoxul digital 30 de minute", "Oprește detoxul", "Cât mai am din focus?", "Deschide focusul")
    ),
    VoiceModule(
        "Somn", Icons.Outlined.Bedtime, "Pornește somnul",
        "Monitorizarea nopții, alarma și rezumatul de dimineață.",
        listOf("Pornește somnul", "M-am trezit", "Cât am dormit?", "Pune alarma la 7", "Deschide somnul")
    ),
    VoiceModule(
        "Respiră", Icons.Outlined.Air, "Respiră cu mine",
        "Exercițiul de respirație pornește singur: patru secunde fiecare.",
        listOf("Respiră cu mine", "Pornește exercițiul de respirație", "Deschide respirația")
    ),
    VoiceModule(
        "Nutriție", Icons.Outlined.Restaurant, "Câte calorii am azi?",
        "Jurnalul de mese, citit cu voce.",
        listOf("Câte calorii am azi?", "Ce am mâncat azi?", "Deschide nutriția", "Scanează codul de bare", "Poză la masă")
    ),
    VoiceModule(
        "Ziua ta", Icons.Outlined.Today, "Cum stau azi?",
        "Rezumatul zilei: ture, mese, antrenamente, somn, focus.",
        listOf("Cum stau azi?", "Rezumatul zilei", "Cât e ceasul?", "Ce zi e azi?", "Acasă", "Deschide profilul")
    ),
    VoiceModule(
        "Mesaje & apeluri", Icons.Outlined.Sms, "Sună-l pe Andrei",
        "Din agenda telefonului; mesajul se citește înainte să plece.",
        listOf("Trimite mesaj lui Ion: ajung în zece minute", "Scrie-i pe WhatsApp lui Maria: ajung la 8", "Sună-l pe Andrei", "Sună la 112")
    ),
    VoiceModule(
        "Aplicații & web", Icons.Outlined.Apps, "Caută pe Google…",
        "Deschide aplicații, caută pe internet, pune pe YouTube.",
        listOf("Deschide WhatsApp", "Deschide YouTube și pune Phoenix", "Caută pe Google despre căpșuni", "Deschide Waze și du-mă la gară", "Open Google and search about strawberries")
    ),
    VoiceModule(
        "Pe ecran", Icons.Outlined.TouchApp, "Citește ecranul",
        "În orice aplicație, cu serviciul FORJA din Accesibilitate.",
        listOf("Citește ecranul", "Apasă pe primul rezultat", "Scrie salut, ce faci", "Caută aici meniato", "Derulează în jos", "Enter", "Înapoi")
    )
)

private const val VOICE_DETAILS =
    "„Hei FORJA” înțelege comenzi în română și engleză, pe loc, fără internet: niciun cuvânt rostit nu pleacă de pe telefon " +
        "(recunoașterea vorbirii e a telefonului, ca la orice dictare).\n\n" +
        "În FORJA: antrenamentul merge de la cap la coadă cu vocea — „începe antrenamentul (de picioare)”, „am terminat seria”, " +
        "„pauză” / „continuă” (cronometrul stă, muzica FORJA tace), „sari pauza”, „următorul exercițiu”, „rezumat antrenament”, " +
        "„termină antrenamentul”. Muzica se controlează prin sesiunile media ale playerului tău (are nevoie de „Acces la notificări”, " +
        "din Echipare, la Muzică): „pornește un playlist” pornește lista FORJA, clădită din ce asculți de obicei; „pornește playlistul X pe " +
        "Spotify” caută playlistul după nume. Harta: „unde e Ion” îl arată pe prieten; dacă nu e un prieten, caută locul în aplicația de " +
        "hărți. Focusul și detoxul digital, somnul, respirația, nutriția și rezumatul zilei răspund și ele.\n\n" +
        "„Pauză”, „continuă” și „următorul” spuse singure se potrivesc singure: antrenamentul dacă e pornit, altfel muzica, altfel ecranul.\n\n" +
        "Pe telefon: mesaje și apeluri din agendă (cu confirmare), aplicații, căutări pe Google, YouTube, Waze / Google Maps, alarma, " +
        "și — cu serviciul FORJA din Accesibilitate — lucrul în interiorul oricărei aplicații: citește, apasă, scrie, caută, derulează, înapoi.\n\n" +
        "Cu „Hei FORJA mereu la ascultare” pornit, comenzile se dau de oriunde, și cu ecranul stins. Butonul plutitor cu microfon e pe " +
        "ecranele principale și în sesiunea de antrenament."

/**
 * „Hei FORJA" — ecranul asistentului vocal. Totul mare, contrastant și citit cu voce,
 * ca să poată fi folosit fără să te uiți la ecran (merge și cu TalkBack).
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    // Foaia cu toate comenzile unui modul (atingerea unei plăcuțe).
    var openModule by remember { mutableStateOf<VoiceModule?>(null) }
    var showDetails by rememberSaveable { mutableStateOf(false) }

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
                Column(Modifier.weight(1f)) {
                    Text("HEI FORJA", style = monoLabel(10, 0.16f).copy(color = Accent2))
                    Spacer(Modifier.height(4.dp))
                    Text("Vorbește cu FORJA", style = TitleModule.copy(fontSize = 26.sp))
                }
                IconButton(
                    onClick = { showDetails = true },
                    modifier = Modifier.semantics { contentDescription = "Comenzi, setări și permisiuni" }
                ) {
                    Box(
                        Modifier.size(28.dp).border(1.dp, Accent2, CircleShape),
                        contentAlignment = Alignment.Center
                    ) { Text("!", style = BodyStrong.copy(color = Accent2, fontSize = 20.sp)) }
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

            if (st.transcript.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                ForjaCard(Modifier.fillMaxWidth()) {
                    SectionLabel("Ai spus")
                    Spacer(Modifier.height(8.dp))
                    Text(st.transcript.ifBlank { "—" }, style = TitleModule.copy(fontSize = 22.sp, lineHeight = 27.sp))
                }
            }
            if (st.question != null || st.response.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                ForjaCard(
                    Modifier.fillMaxWidth().semantics { if (!speakOn) liveRegion = LiveRegionMode.Polite },
                    stroke = if (st.question != null) Color(0x806F855A) else StrokeCardStrong
                ) {
                    SectionLabel(if (st.question != null) "FORJA întreabă" else "FORJA", color = if (st.question != null) Accent2 else TextDim)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        st.question ?: st.response,
                        style = Body.copy(fontSize = 17.sp, lineHeight = 23.sp, color = TextPrimary)
                    )
                }

            }
        }
    }

    if (showDetails && openModule == null) {
        ModalBottomSheet(
            onDismissRequest = { showDetails = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Surface1,
            shape = SheetShape
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp).padding(bottom = 28.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Comenzi și setări", style = TitleModule, modifier = Modifier.weight(1f))
                    SecondaryButton("Închide", onClick = { showDetails = false }, padV = 8.dp)
                }
                Spacer(Modifier.height(14.dp))
                // Pentru cine preferă să scrie (sau când microfonul nu e disponibil)
                var typed by remember { mutableStateOf("") }
                fun send() { if (typed.isNotBlank()) { voice.submitText(typed.trim()); typed = ""; showDetails = false } }
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

                // ── Catalogul: un modul = o plăcuță cu un exemplu; atingerea deschide toate comenzile lui ──
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Ce poți spune")
                    Spacer(Modifier.weight(1f))
                    Text("ATINGE O PLĂCUȚĂ", style = monoLabel(9, 0.12f).copy(color = TextDim))
                    Spacer(Modifier.width(8.dp))
                    InfoDot(text = VOICE_DETAILS, title = "Hei FORJA", size = 20)
                }
                Spacer(Modifier.height(10.dp))
                MODULES.chunked(2).forEachIndexed { rowIdx, pair ->
                    Reveal(index = rowIdx) {
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max)) {
                            pair.forEachIndexed { i, m ->
                                CommandTile(
                                    m,
                                    modifier = Modifier.weight(1f).fillMaxHeight().padding(end = if (i == 0) 10.dp else 0.dp),
                                    onClick = { openModule = m }
                                )
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                Spacer(Modifier.height(16.dp))
                SectionLabel("Setări")
                Spacer(Modifier.height(10.dp))
                VoiceToggle(
                    "„Hei FORJA” mereu la ascultare",
                    if (wakeOn) "Microfonul ascultă în fundal, și cu ecranul stins (vezi notificarea)."
                    else "Cheamă FORJA cu vocea, fără să atingi telefonul. Consumă ceva baterie.",
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
                VoiceToggle("Răspunsuri cu voce", "FORJA citește tot ce face. Oprește dacă folosești TalkBack.", checked = speakOn) { v ->
                    scope.launch { app.prefs.setVoiceSpeakOn(v) }
                }
                VoiceToggle("Confirmă înainte de a trimite", "Mesajul se citește cu voce și pleacă doar după „da”.", checked = confirmSend) { v ->
                    scope.launch { app.prefs.setVoiceConfirmSend(v) }
                }
                VoiceSetting("Limba în care asculți", "Comenzile merg în română și engleză.") {
                    Row {
                        LangChip("Română", lang == "ro-RO") { scope.launch { app.prefs.setVoiceLang("ro-RO") } }
                        Spacer(Modifier.width(6.dp))
                        LangChip("English", lang == "en-US") { scope.launch { app.prefs.setVoiceLang("en-US") } }
                    }
                }

                // Lucrul în alte aplicații: serviciul de accesibilitate FORJA (același ca pentru Detox; doar utilizatorul îl poate porni)
                val screenOn = remember(refresh, st.phase) { ScreenAgent.isEnabled(context) }
                val screenConnected = remember(refresh, st.phase) { ScreenAgent.isConnected() }
                VoiceSetting(
                    "Comenzi pe ecran, în alte aplicații",
                    when {
                        screenConnected -> "Pornit: FORJA citește, apasă, scrie și caută în aplicația din față."
                        screenOn -> "Pornit, dar neconectat: oprește și repornește „FORJA” din Setări → Accesibilitate."
                        else -> "Oprit. Pornește serviciul „FORJA” din Echipare → Accesibilitate (sau de aici)."
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
                    "Contactele și mesajele rămân pe telefon: FORJA le folosește doar pe loc, pentru comanda ta.",
                    style = BodyTiny.copy(color = TextDim)
                )
            }
        }
    }

    openModule?.let { m ->
        CommandSheet(
            m,
            onSay = { phrase -> showDetails = false; voice.submitText(phrase) },
            onClose = { openModule = null }
        )
    }
}

/** O plăcuță de modul: iconița în bulă olive, titlul și un singur exemplu. Atingerea deschide lista întreagă. */
@Composable
private fun CommandTile(m: VoiceModule, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(CardShape)
            .background(Surface1)
            .border(1.dp, StrokeCard, CardShape)
            .semantics { role = Role.Button; contentDescription = "${m.title}: comenzi vocale. De exemplu: ${m.example}. Deschide lista." }
            .pressable(onClick)
            .padding(14.dp)
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0x1F6F855A)), contentAlignment = Alignment.Center) {
            Icon(m.icon, contentDescription = null, tint = Accent2, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(m.title, style = BodyStrong.copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text("„${m.example}”", style = BodyTiny.copy(color = TextSecondary, lineHeight = 15.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Foaia unui modul: toate comenzile lui, mari, fiecare o atingere („Încearcă”). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommandSheet(m: VoiceModule, onSay: (String) -> Unit, onClose: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Surface1, shape = SheetShape
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0x1F6F855A)), contentAlignment = Alignment.Center) {
                    Icon(m.icon, contentDescription = null, tint = Accent2, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("SPUNE „HEI FORJA” ȘI…", style = monoLabel(9, 0.14f).copy(color = Accent2))
                    Spacer(Modifier.height(2.dp))
                    Text(m.title, style = TitleModule.copy(fontSize = 22.sp, lineHeight = 25.sp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(m.blurb, style = BodySmall.copy(color = TextSecondary, lineHeight = 17.sp))
            Spacer(Modifier.height(14.dp))
            m.phrases.forEachIndexed { i, p ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clip(SecondaryShape)
                        .background(Surface2)
                        .border(1.dp, StrokeCardStrong, SecondaryShape)
                        .semantics { role = Role.Button; contentDescription = "Încearcă: $p" }
                        .pressable({ onSay(p); onClose() })
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Mic, contentDescription = null, tint = Accent2, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("„$p”", style = BodyStrong.copy(fontSize = 15.sp, lineHeight = 20.sp), modifier = Modifier.weight(1f))
                    if (i == 0) Text("ÎNCEARCĂ", style = monoLabel(8, 0.14f).copy(color = Accent2))
                }
            }
            Spacer(Modifier.height(4.dp))
            SecondaryButton("Închide", onClick = onClose, modifier = Modifier.fillMaxWidth())
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
