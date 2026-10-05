package com.forja.app.feature.permissions

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.forja.app.ForjaApp
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*
import com.forja.app.core.detox.AccessibilityLink
import com.forja.app.core.detox.ForjaGuardService
import com.forja.app.core.media.Media
import com.forja.app.core.music.Music
import com.forja.app.core.social.ContactsReader
import com.forja.app.core.inventory.AllFiles

/** Piesele de echipament — o bifă fiecare: acordurile Android, accesul la muzică și, la urmă, contractul de securitate. */
private enum class Gear(val title: String, val icon: ImageVector) {
    Notifications("Notificări", Icons.Outlined.Notifications),
    Location("Locație", Icons.Outlined.MyLocation),
    Microphone("Microfon", Icons.Outlined.Mic),
    Photos("Poze", Icons.Outlined.PhotoLibrary),
    Files("Fișiere", Icons.Outlined.Folder),
    Battery("Baterie", Icons.Outlined.BatteryChargingFull),
    Contacts("Agendă", Icons.Outlined.Contacts),
    Music("Muzică", Icons.Outlined.MusicNote),
    /** Un singur serviciu de accesibilitate: Paznicul Detox (Focus) și comenzile vocale pe ecran („Hei FORJA”). */
    Accessibility("Accesibilitate", Icons.Outlined.Accessibility),
    Contract("Contract", Icons.Outlined.VerifiedUser)
}

private val GEAR_COUNT = Gear.entries.size

/** Starea unui rând, într-un cuvânt, și culoarea ei. */
private class GearState(val word: String, val color: Color)

// Videoul ghidului (încărcat manual în R2); când nu există server, trezirea de dimineață.
private const val FALLBACK_VIDEO = "https://v.ftcdn.net/05/12/88/79/700_F_512887976_190EN7woFkvAws5F4qzRxGMIOuIjvyPY_ST.mp4"
private const val FALLBACK_POSTER = "https://t3.ftcdn.net/jpg/04/70/98/78/500_F_470987805_jsREzUZZZNUDZ56fG4J9Cpz4UquN6zJg.jpg"

/** Ghidajul primei vizite: explicațiile stau aici, nu pe rânduri (≤ 90 de caractere pe pas, 4 pași). */
private val ECHIPARE_STEPS = listOf(
    CoachStep("echipare.progres", "Zece bife, o singură dată. Apoi FORJA nu te mai întrerupe."),
    CoachStep("echipare.lista", "Atinge un rând ca să-l bifezi. Bateria și alarma se pornesc din Setări Android."),
    CoachStep("echipare.muzica", "Muzică: FORJA vede doar titlul și artistul și le arată prietenilor pe hartă."),
    CoachStep("echipare.contract", "Contractul spune ce pleacă pe site. Mesajele și parolele nu se citesc niciodată.", MascotState.Happy)
)

/** Detaliile, la punctul „i”: tot ce era scris pe ecran înainte, pentru cine vrea să citească. */
private const val ECHIPARE_DETAILS =
    "Tot ce e sensibil rămâne pe telefonul tău. FORJA nu citește mesajele, parolele sau conținutul ecranului.\n\n" +
        "Bateria și alarma pe tot ecranul sunt permisiuni speciale Android: doar tu le poți porni, din Setări. " +
        "Fără ele, alarma de dimineață poate rămâne mută.\n\n" +
        "Muzica: Android numește accesul „Acces la notificări”, dar FORJA nu citește notificările. Vede doar melodia care " +
        "cântă, titlul și artistul, o arată prietenilor pe hartă și pune pauză muzicii la finalul inventarului.\n\n" +
        "Fișiere: „Acces la toate fișierele”, doar ca Inventarul să mute pozele unde alegi tu.\n\n" +
        "Agenda: numărul tău îl scrii în Profil. Focusul își cere accesul la utilizare direct din modulul lui.\n\n" +
        "Accesibilitate: un singur serviciu FORJA, pornit din Setări → Accesibilitate, cu două roluri — Paznicul Detox din Focus și " +
        "comenzile vocale „Hei FORJA” în alte aplicații (citește ecranul, apasă, scrie, caută). Citește ecranul doar când Detoxul e pornit " +
        "sau când dai o comandă vocală; nimic nu pleacă de pe telefon. Pe Android 13+, dacă apare „Setare restricționată”: " +
        "Setări → Aplicații → FORJA → meniul ⋮ → „Permite setările restricționate”, apoi revino în Accesibilitate.\n\n" +
        "Un rând „blocat” înseamnă că Android a închis dialogul: atinge-l și pornește-l din Setări."

/** „Echipare” — zece bife, o singură dată; apoi FORJA nu te mai întrerupe. Rândurile nu explică: arată starea. */
@Composable
fun PermissionsScreen(onBack: () -> Unit, onOpenContract: () -> Unit = {}) {
    val context = LocalContext.current
    val toast = LocalToast.current
    val activity = remember(context) { context.findActivity() }
    val app = remember(context) { ForjaApp.from(context) }

    var refresh by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    fun rationale(p: String) = activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, p)

    // ── Starea fiecărei bife (recalculată la ON_RESUME și după fiecare răspuns) ──
    val notifPermOn = remember(refresh) { Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS) }
    val notifEnabled = remember(refresh) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val notifOn = notifPermOn && notifEnabled
    val fineOn = remember(refresh) { granted(Manifest.permission.ACCESS_FINE_LOCATION) }
    val coarseOn = remember(refresh) { granted(Manifest.permission.ACCESS_COARSE_LOCATION) }
    val bgOn = remember(refresh) { Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
    val locationOn = fineOn && bgOn
    val micOn = remember(refresh) { granted(Manifest.permission.RECORD_AUDIO) }
    val photosFull = remember(refresh) {
        when {
            Build.VERSION.SDK_INT >= 33 -> granted(Manifest.permission.READ_MEDIA_IMAGES)
            else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
    val photosPartial = remember(refresh) {
        Build.VERSION.SDK_INT >= 34 && !photosFull && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    }
    val photosOn = photosFull || photosPartial
    val batteryOn = remember(refresh) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }
    val fsiOn = remember(refresh) {
        if (Build.VERSION.SDK_INT >= 34) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.canUseFullScreenIntent()
        } else true
    }
    val powerOn = batteryOn && fsiOn
    // Agenda: doar permisiunea. Numărul tău se cere în alt pas, nu aici.
    val contactsGranted = remember(refresh) { ContactsReader.granted(context) }
    // Fișiere: „Acces la toate fișierele”, ca Inventarul să mute orice poză, în orice dosar, fără ferestre de acord.
    val filesOn = remember(refresh) { !AllFiles.available || AllFiles.granted() }
    // Muzica: „Acces la notificări” pentru serviciul FORJA — doar ca să vedem sesiunile media.
    val musicOn = remember(refresh) { Music.hasAccess(context) }
    LaunchedEffect(musicOn) { if (musicOn) Music.ensureStarted(context) }
    // Accesibilitate: serviciul FORJA (Detox + comenzi vocale pe ecran) pornit din Setări → Accesibilitate.
    val accessOn = remember(refresh) { ForjaGuardService.isEnabled(context) }
    // Contractul: semnat la versiunea curentă.
    val contractSigned by app.prefs.contractSigned.collectAsState(initial = false)
    val done = listOf(notifOn, locationOn, micOn, photosOn, filesOn, powerOn, contactsGranted, musicOn, accessOn, contractSigned).count { it }

    // Rândurile pe care Android nu mai arată dialogul: starea „blocat”, iar atingerea deschide setările aplicației.
    var blocked by remember { mutableStateOf(emptySet<Gear>()) }
    var pendingGear by remember { mutableStateOf<Gear?>(null) }
    var pendingSingle by remember { mutableStateOf<String?>(null) }

    // ── Lansatoare (unul pentru fiecare fel de cerere) ──
    val single = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        refresh++
        val p = pendingSingle
        val g = pendingGear
        pendingSingle = null
        if (!ok && p != null && g != null && !rationale(p)) blocked = blocked + g
    }
    fun askSingle(p: String, g: Gear) {
        pendingSingle = p
        pendingGear = g
        single.launch(p)
    }
    val multi = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        refresh++
        val g = pendingGear
        // Locația: după „precisă”, Android cere „în fundal” separat (pe 11+ deschide pagina de setări).
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true &&
            Build.VERSION.SDK_INT >= 29 &&
            !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        ) {
            askSingle(Manifest.permission.ACCESS_BACKGROUND_LOCATION, Gear.Location)
        }
        // Pozele pe 14+: accesul parțial e un răspuns bun, nu un refuz.
        val partialOk = Build.VERSION.SDK_INT >= 34 && grants[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] == true
        if (!partialOk && g != null && grants.any { (p, ok) -> !ok && !rationale(p) }) blocked = blocked + g
    }
    fun askMulti(ps: Array<String>, g: Gear) {
        pendingGear = g
        multi.launch(ps)
    }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }

    fun openSafe(intent: Intent, fallback: Intent? = null) {
        try {
            settings.launch(intent)
        } catch (_: Exception) {
            if (fallback != null) {
                try { settings.launch(fallback); return } catch (_: Exception) { }
            }
            toast.show("Deschide manual din Setări → Aplicații → FORJA.")
        }
    }

    fun request(g: Gear) {
        if (g in blocked) {
            openSafe(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            return
        }
        when (g) {
            Gear.Notifications -> {
                if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                    askSingle(Manifest.permission.POST_NOTIFICATIONS, g)
                } else {
                    openSafe(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                        fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    )
                }
            }
            Gear.Location -> {
                if (!fineOn) {
                    askMulti(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), g)
                } else if (!bgOn && Build.VERSION.SDK_INT >= 29) {
                    // Android 11+: sistemul deschide pagina „Se permite tot timpul”.
                    askSingle(Manifest.permission.ACCESS_BACKGROUND_LOCATION, g)
                }
            }
            Gear.Microphone -> askSingle(Manifest.permission.RECORD_AUDIO, g)
            Gear.Photos -> when {
                Build.VERSION.SDK_INT >= 34 -> askMulti(
                    arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED), g
                )
                Build.VERSION.SDK_INT >= 33 -> askSingle(Manifest.permission.READ_MEDIA_IMAGES, g)
                else -> askSingle(Manifest.permission.READ_EXTERNAL_STORAGE, g)
            }
            Gear.Battery -> {
                val pkg = Uri.parse("package:${context.packageName}")
                if (!batteryOn) {
                    // Necesită REQUEST_IGNORE_BATTERY_OPTIMIZATIONS în manifest; arată un dialog de sistem.
                    openSafe(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg),
                        fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    )
                } else if (!fsiOn && Build.VERSION.SDK_INT >= 34) {
                    openSafe(
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg),
                        fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
                    )
                }
            }
            Gear.Contacts -> if (!contactsGranted) askSingle(Manifest.permission.READ_CONTACTS, g)
            Gear.Files -> if (!filesOn) AllFiles.intents(context).let { openSafe(it[0], fallback = it.getOrNull(1)) }
            // „Acces la notificări” e o pagină de setări, nu un dialog: direct la FORJA pe 11+, lista generală altfel.
            Gear.Music -> openSafe(Music.accessIntent(context), fallback = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            // Accesibilitatea e o pagină de setări, nu un dialog: direct la serviciul FORJA pe telefoanele care știu, lista altfel.
            Gear.Accessibility -> AccessibilityLink.intents(context).let { openSafe(it[0], fallback = it[2]) }
            Gear.Contract -> onOpenContract()
        }
    }

    fun isOn(g: Gear): Boolean = when (g) {
        Gear.Notifications -> notifOn
        Gear.Location -> locationOn
        Gear.Microphone -> micOn
        Gear.Photos -> photosOn
        Gear.Files -> filesOn
        Gear.Battery -> powerOn
        Gear.Contacts -> contactsGranted
        Gear.Music -> musicOn
        Gear.Accessibility -> accessOn
        Gear.Contract -> contractSigned
    }

    /** Un cuvânt pe rând: pornit (verde), parțial / oprit / lipsește (jar), blocat (roșu); contractul: semnat / nesemnat. */
    fun stateOf(g: Gear): GearState {
        val on = GearState("pornit", Positive)
        val partialOn = GearState("parțial", Accent2)
        val partial = GearState("parțial", EmberHot)
        val missing = GearState("lipsește", EmberHot)
        val stuck = GearState("blocat", Error)
        return when (g) {
            Gear.Notifications -> when {
                notifOn -> on
                notifPermOn -> GearState("oprit", EmberHot)
                g in blocked -> stuck
                else -> missing
            }
            Gear.Location -> when {
                locationOn -> on
                g in blocked -> stuck
                fineOn || coarseOn -> partial
                else -> missing
            }
            Gear.Photos -> when {
                photosFull -> on
                photosPartial -> partialOn
                g in blocked -> stuck
                else -> missing
            }
            Gear.Battery -> when {
                powerOn -> on
                // Pe 14+ sunt două acorduri (bateria și alarma pe tot ecranul): unul din două = parțial.
                batteryOn || (fsiOn && Build.VERSION.SDK_INT >= 34) -> partial
                else -> missing
            }
            Gear.Contract -> if (contractSigned) GearState("semnat", Positive) else GearState("nesemnat", EmberHot)
            else -> when {
                isOn(g) -> on
                g in blocked -> stuck
                else -> missing
            }
        }
    }

    val guideVideo = remember { Media.mediaUrl("guide.mp4") ?: FALLBACK_VIDEO }
    val guidePoster = remember { Media.mediaUrl("guide.jpg") ?: FALLBACK_POSTER }

    CoachMarks(screen = "echipare", steps = ECHIPARE_STEPS) {
        Box(Modifier.fillMaxSize().topoBackground(decor = false)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 30.dp)
            ) {
                // Ghidul — te întâmpină
                Box(Modifier.fillMaxWidth().height(300.dp)) {
                    VideoSurface(url = guideVideo, posterUrl = guidePoster, modifier = Modifier.fillMaxSize())
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                0f to Color(0x330A0A0B), 0.55f to Color(0xB30A0A0B), 1f to Color(0xFF0A0A0B)
                            )
                        )
                    )
                    SecondaryButton(
                        "Închide", onClick = onBack, padV = 8.dp,
                        modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp)
                    )
                    Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                        Reveal(index = 0) { StampLabel("ECHIPARE") }
                        Spacer(Modifier.height(12.dp))
                        Reveal(index = 1) {
                            Text("Echipare completă.", style = TitleModule.copy(fontSize = 26.sp, lineHeight = 29.sp))
                        }
                        Spacer(Modifier.height(6.dp))
                        Reveal(index = 2) {
                            Text("Zece bife. O singură dată.", style = Body.copy(fontSize = 14.sp, lineHeight = 19.sp))
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                Column(Modifier.padding(horizontal = 20.dp)) {
                    // Progresul echipării + punctul de detalii
                    Column(Modifier.coachTarget("echipare.progres")) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SectionLabel("Echipare")
                            Spacer(Modifier.weight(1f))
                            Text(
                                "$done din $GEAR_COUNT",
                                style = monoLabel(9, 0.12f).copy(color = if (done == GEAR_COUNT) Positive else Accent2)
                            )
                            Spacer(Modifier.width(10.dp))
                            InfoDot(title = "Echipare", text = ECHIPARE_DETAILS, size = 20)
                        }
                        Spacer(Modifier.height(8.dp))
                        ProgressBar(progress = done / GEAR_COUNT.toFloat())
                    }
                    Spacer(Modifier.height(12.dp))

                    ForjaCard(Modifier.fillMaxWidth().coachTarget("echipare.lista"), stroke = StrokeCard, padding = 6.dp) {
                        Gear.entries.forEachIndexed { i, g ->
                            if (i > 0) Divider()
                            val st = stateOf(g)
                            GearRow(
                                g = g,
                                on = isOn(g),
                                state = st.word,
                                stateColor = st.color,
                                onActivate = { request(g) },
                                // Contractul se poate reciti oricând — și semnat.
                                openWhenOn = g == Gear.Contract,
                                modifier = when (g) {
                                    Gear.Music -> Modifier.coachTarget("echipare.muzica")
                                    Gear.Contract -> Modifier.coachTarget("echipare.contract")
                                    else -> Modifier
                                }
                            )
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                    PrimaryButton(
                        if (done == GEAR_COUNT) "La datorie" else "Continuă",
                        onClick = onBack, modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x0DFFFFFF)))
}

/** Un rând de echipament: iconița, titlul (≤ 2 cuvinte), starea într-un cuvânt și căsuța pătrată de bifat. */
@Composable
private fun GearRow(
    g: Gear,
    on: Boolean,
    state: String,
    stateColor: Color,
    onActivate: () -> Unit,
    openWhenOn: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (on && !openWhenOn) Modifier else Modifier.pressable(onActivate, scaleDown = 0.99f, haptic = false))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(if (on) Color(0x1F2FBE71) else Surface2),
            contentAlignment = Alignment.Center
        ) {
            Icon(g.icon, contentDescription = null, tint = if (on) Positive else Accent2, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(g.title, style = BodyStrong.copy(fontSize = 15.sp), modifier = Modifier.weight(1f))
        Text(state.uppercase(), style = monoLabel(9, 0.14f).copy(color = stateColor))
        Spacer(Modifier.width(12.dp))
        GearCheckbox(on = on)
    }
}

/** Căsuța de bifat: pătrat 22dp, colțuri 4dp; plină cu bifă când e gata, conturată olive când mai e de bifat. */
@Composable
private fun GearCheckbox(on: Boolean) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .size(22.dp)
            .clip(shape)
            .background(if (on) Positive else Surface2)
            .border(1.dp, if (on) Positive else Accent2.copy(alpha = 0.55f), shape),
        contentAlignment = Alignment.Center
    ) {
        PopIn(visible = on, fromScale = 0.4f) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
        }
    }
}

/** Activitatea din spatele contextului Compose (pentru shouldShowRequestPermissionRationale). */
private fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
