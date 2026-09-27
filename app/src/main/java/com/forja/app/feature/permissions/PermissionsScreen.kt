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
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
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
import com.forja.app.core.media.Media
import com.forja.app.core.social.ContactsReader
import com.forja.app.core.social.ContactsSync
import com.forja.app.core.social.Discovery
import com.forja.app.core.social.PhoneNumbers
import com.forja.app.core.util.Fmt
import kotlinx.coroutines.launch

/** Cele șase piese de echipament — o bifă fiecare. */
private enum class Gear(val title: String, val sub: String, val icon: ImageVector) {
    Notifications("Notificări", "raportul de dimineață, alarma, prietenii", Icons.Outlined.Notifications),
    Location("Locație (precisă + în fundal)", "harta, alergarea, prietenii te văd", Icons.Outlined.MyLocation),
    Microphone("Microfon", "somnul măsurat local — sforăit, vorbit", Icons.Outlined.Mic),
    Photos("Poze & galerie", "curățenia galeriei, analiza meselor", Icons.Outlined.PhotoLibrary),
    Battery("Baterie & alarmă pe ecran", "FORJA rămâne trează noaptea și te trezește", Icons.Outlined.BatteryChargingFull),
    Contacts("Agendă", "prietenii cu FORJA din agenda ta apar singuri", Icons.Outlined.Contacts)
}

private const val GEAR_COUNT = 6

// Videoul ghidului (încărcat manual în R2); când nu există server, trezirea de dimineață.
private const val FALLBACK_VIDEO = "https://v.ftcdn.net/05/12/88/79/700_F_512887976_190EN7woFkvAws5F4qzRxGMIOuIjvyPY_ST.mp4"
private const val FALLBACK_POSTER = "https://t3.ftcdn.net/jpg/04/70/98/78/500_F_470987805_jsREzUZZZNUDZ56fG4J9Cpz4UquN6zJg.jpg"

/** „Echipare” — șase bife, o singură dată; apoi FORJA nu te mai întrerupe. */
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val toast = LocalToast.current
    val activity = remember(context) { context.findActivity() }
    val app = remember(context) { ForjaApp.from(context) }
    val scope = rememberCoroutineScope()

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
    // Agenda: permisiune + comutator pornit + număr (declarat sau verificat prin SMS).
    val contactsGranted = remember(refresh) { ContactsReader.granted(context) }
    val contactsOn by app.prefs.contactsOn.collectAsState(initial = false)
    val phoneDeclared by app.prefs.phoneDeclared.collectAsState(initial = "")
    val contactsStatus by app.prefs.contactsStatus.collectAsState(initial = "")
    val contactsSyncedAt by app.prefs.contactsSyncedAt.collectAsState(initial = 0L)
    val verifiedPhone = remember(refresh) { Discovery.verifiedPhone() }
    val hasNumber = PhoneNumbers.isValid(phoneDeclared) || verifiedPhone != null
    val contactsReady = contactsGranted && contactsOn && hasNumber
    var numberOpen by remember { mutableStateOf(false) }
    var numberBusy by remember { mutableStateOf(false) }
    val done = listOf(notifOn, locationOn, micOn, photosOn, powerOn, contactsReady).count { it }

    var deniedForever by remember { mutableStateOf(false) }
    var pendingSingle by remember { mutableStateOf<String?>(null) }

    // ── Lansatoare (unul pentru fiecare fel de cerere) ──
    val single = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        refresh++
        val p = pendingSingle
        pendingSingle = null
        if (!ok && p != null && !rationale(p)) deniedForever = true
        // Agenda bifată: urmează numărul tău, pe loc.
        if (ok && p == Manifest.permission.READ_CONTACTS) numberOpen = true
    }
    fun askSingle(p: String) {
        pendingSingle = p
        single.launch(p)
    }
    val multi = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        refresh++
        // Locația: după „precisă”, Android cere „în fundal” separat (pe 11+ deschide pagina de setări).
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true &&
            Build.VERSION.SDK_INT >= 29 &&
            !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        ) {
            askSingle(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        // Pozele pe 14+: accesul parțial e un răspuns bun, nu un refuz.
        val partialOk = Build.VERSION.SDK_INT >= 34 && grants[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] == true
        if (!partialOk && grants.any { (p, ok) -> !ok && !rationale(p) }) deniedForever = true
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
        when (g) {
            Gear.Notifications -> {
                if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                    askSingle(Manifest.permission.POST_NOTIFICATIONS)
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
                    multi.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                } else if (!bgOn && Build.VERSION.SDK_INT >= 29) {
                    // Android 11+: sistemul deschide pagina „Se permite tot timpul”.
                    askSingle(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            }
            Gear.Microphone -> askSingle(Manifest.permission.RECORD_AUDIO)
            Gear.Photos -> when {
                Build.VERSION.SDK_INT >= 34 -> multi.launch(
                    arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                )
                Build.VERSION.SDK_INT >= 33 -> askSingle(Manifest.permission.READ_MEDIA_IMAGES)
                else -> askSingle(Manifest.permission.READ_EXTERNAL_STORAGE)
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
            Gear.Contacts -> {
                if (!contactsGranted) askSingle(Manifest.permission.READ_CONTACTS)
                else numberOpen = true
            }
        }
    }

    fun stateOf(g: Gear): Boolean = when (g) {
        Gear.Notifications -> notifOn
        Gear.Location -> locationOn
        Gear.Microphone -> micOn
        Gear.Photos -> photosOn
        Gear.Battery -> powerOn
        Gear.Contacts -> contactsReady
    }

    fun detailOf(g: Gear): String? = when (g) {
        Gear.Notifications -> if (notifPermOn && !notifEnabled) "Sunt oprite din setările Android — pornește-le de acolo." else null
        Gear.Location -> if (fineOn && !bgOn) "Mai lipsește „Tot timpul” — apasă din nou și alege-l." else null
        Gear.Microphone -> null
        Gear.Photos -> if (photosPartial) "Acces parțial — e suficient. Poți alege mai multe oricând." else null
        Gear.Battery -> when {
            !batteryOn -> null
            !fsiOn -> "Bateria e gata. Mai lipsește alarma pe tot ecranul."
            else -> null
        }
        Gear.Contacts -> when {
            contactsReady -> contactsStatus.ifBlank { if (contactsSyncedAt > 0) "comparată ${Fmt.freshness(contactsSyncedAt)}" else "se compară la prima ocazie cu net" }
            contactsGranted && !hasNumber -> "Mai lipsește numărul tău — apasă și scrie-l."
            contactsGranted && !contactsOn -> "Agenda e bifată. Mai lipsește „Pot fi găsit după număr”."
            else -> null
        }
    }

    /** „Gata”: numărul declarat → Prefs, listare pe site (amprentă), apoi prima comparare a agendei. */
    fun saveNumber(input: String) {
        val normalized = PhoneNumbers.normalize(input, PhoneNumbers.defaultCountryCode(context))
        if (normalized == null) { toast.show("Număr invalid. Scrie-l ca +40 7xx xxx xxx."); return }
        numberBusy = true
        scope.launch {
            try {
                app.prefs.setPhoneDeclared(normalized)
                app.prefs.setContactsOn(true)
                val r = Discovery.register(app)
                if (r.isSuccess) {
                    app.prefs.setContactsStatus("")
                    ContactsSync.scheduleIfOn(app)
                    ContactsSync.runNow(app)
                    numberOpen = false
                    toast.show("Agenda se compară acum. Prietenii apar singuri.")
                } else {
                    app.prefs.setContactsOn(false)
                    toast.show(Discovery.humanError(r.exceptionOrNull() ?: Exception()))
                }
            } catch (_: Exception) {
                app.prefs.setContactsOn(false)
                toast.show("Nu a mers. Verifică internetul și încearcă din nou.")
            }
            numberBusy = false
            refresh++
        }
    }

    val guideVideo = remember { Media.mediaUrl("guide.mp4") ?: FALLBACK_VIDEO }
    val guidePoster = remember { Media.mediaUrl("guide.jpg") ?: FALLBACK_POSTER }

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
                        Text(
                            "Șase bife. O singură dată. Apoi FORJA nu te mai întrerupe.",
                            style = Body.copy(fontSize = 14.sp, lineHeight = 19.sp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Column(Modifier.padding(horizontal = 20.dp)) {
                // Progresul echipării
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionLabel("Echipare")
                    Text(
                        "$done din $GEAR_COUNT",
                        style = monoLabel(9, 0.12f).copy(color = if (done == GEAR_COUNT) Positive else Accent2)
                    )
                }
                Spacer(Modifier.height(8.dp))
                ProgressBar(progress = done / GEAR_COUNT.toFloat())
                Spacer(Modifier.height(12.dp))

                ForjaCard(Modifier.fillMaxWidth(), stroke = StrokeCard, padding = 6.dp) {
                    Gear.entries.forEachIndexed { i, g ->
                        if (i > 0) Divider()
                        GearRow(
                            g = g,
                            on = stateOf(g),
                            detail = detailOf(g),
                            onActivate = { request(g) }
                        )
                    }
                }

                // Agenda: numărul tău — declarat de tine, păstrat pe site doar ca amprentă.
                if (numberOpen && contactsGranted) {
                    Spacer(Modifier.height(12.dp))
                    NumberPanel(
                        initial = phoneDeclared.ifBlank { verifiedPhone ?: PhoneNumbers.simNumber(context).orEmpty() },
                        verified = verifiedPhone != null,
                        busy = numberBusy,
                        onDone = ::saveNumber,
                        onLater = { numberOpen = false }
                    )
                }

                // Ecranul de setări rămâne la îndemână cât timp mai e ceva de bifat.
                if (deniedForever && done < GEAR_COUNT) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Android a închis dialogul pentru una dintre bife. O poți porni doar din setările aplicației.",
                        style = BodyTiny.copy(color = TextSecondary)
                    )
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton(
                        "Deschide setările Android",
                        onClick = {
                            openSafe(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    "Tot ce e sensibil rămâne pe telefonul tău. FORJA nu citește mesajele, parolele sau conținutul ecranului.",
                    style = TitleModule.copy(fontSize = 17.sp, lineHeight = 23.sp)
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Bateria și alarma pe ecran se deschid în setările Android — nicio aplicație nu le poate porni singură. Focusul își cere accesul special direct din modulul lui.",
                        style = BodyTiny.copy(color = TextDim), modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    InfoDot(
                        title = "De ce se deschid Setările?",
                        text = "Scoaterea de la optimizarea bateriei și alarma pe tot ecranul sunt permisiuni speciale Android. Din motive de siguranță, doar tu le poți porni din Setări — nicio aplicație nu le poate activa singură. Fără ele, alarma de dimineață poate rămâne mută. Le poți lăsa și pe mai târziu."
                    )
                }
                // v4.0 pasul 2 — „Sincronizare în cont”: opțional, implicit oprit; nimic de aici nu condiționează continuarea.
                Spacer(Modifier.height(24.dp))
                SyncSection(Modifier.fillMaxWidth())

                Spacer(Modifier.height(20.dp))
                PrimaryButton(
                    if (done == GEAR_COUNT) "Gata — la datorie" else "Continuă în FORJA",
                    onClick = onBack, modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** „Numărul tău”: câmp E.164, explicație onestă (declarat ≠ verificat), „Gata” / „Mai târziu”. */
@Composable
private fun NumberPanel(initial: String, verified: Boolean, busy: Boolean, onDone: (String) -> Unit, onLater: () -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    ForjaCard(Modifier.fillMaxWidth(), fill = Surface2) {
        SectionLabel("Numărul tău")
        Spacer(Modifier.height(8.dp))
        TextField(
            value = value,
            onValueChange = { value = it.filter { ch -> ch.isDigit() || ch == '+' || ch == ' ' } },
            singleLine = true,
            enabled = !busy,
            placeholder = { Text("+40 7xx xxx xxx", style = BodySmall) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            textStyle = BodyStrong.copy(fontSize = 15.sp),
            modifier = Modifier.fillMaxWidth().clip(SecondaryShape).border(1.dp, StrokeCardStrong, SecondaryShape),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Surface1, unfocusedContainerColor = Surface1,
                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                cursorColor = Accent2,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
            )
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (verified) "Verificat prin SMS. Serverul păstrează o amprentă a numărului, nu numărul."
            else "Declarat de tine, nu verificat prin SMS. Serverul păstrează o amprentă, nu numărul. Numele din agendă rămân pe telefon.",
            style = BodyTiny.copy(color = TextSecondary)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Prietenia apare singură doar când și el te are în agendă. Altfel îi trimiți codul tău.",
            style = BodyTiny.copy(color = TextDim)
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(if (busy) "Se salvează" else "Gata", onClick = { onDone(value) }, small = true, enabled = !busy, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            SecondaryButton("Mai târziu", onClick = onLater, padV = 10.dp)
        }
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x0DFFFFFF)))
}

/** Un rând de echipament: icon, titlu/subtitlu, „Bifează” și căsuța pătrată de bifat. */
@Composable
private fun GearRow(g: Gear, on: Boolean, detail: String?, onActivate: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (on) Modifier else Modifier.pressable(onActivate, scaleDown = 0.99f, haptic = false))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(if (on) Color(0x1F2FBE71) else Surface2),
            contentAlignment = Alignment.Center
        ) {
            Icon(g.icon, contentDescription = null, tint = if (on) Positive else Accent2, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(g.title, style = BodyStrong.copy(fontSize = 14.sp))
            Text(g.sub, style = BodyTiny.copy(color = TextSecondary))
            if (detail != null) {
                Spacer(Modifier.height(3.dp))
                Text(detail, style = BodyTiny.copy(color = if (on) Positive else EmberHot))
            }
        }
        Spacer(Modifier.width(10.dp))
        if (!on) {
            Box(
                Modifier.clip(RoundedCornerShape(10.dp)).background(AccentGradient).pressable(onActivate)
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) { Text("Bifează", style = ButtonTextSmall) }
            Spacer(Modifier.width(10.dp))
        }
        GearCheckbox(on = on)
    }
}

/** Căsuța de bifat: pătrat 22dp, colțuri 4dp; plină cu bifă când e gata. */
@Composable
private fun GearCheckbox(on: Boolean) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .size(22.dp)
            .clip(shape)
            .background(if (on) Positive else Surface2)
            .border(1.dp, if (on) Positive else StrokeCardStrong, shape),
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
