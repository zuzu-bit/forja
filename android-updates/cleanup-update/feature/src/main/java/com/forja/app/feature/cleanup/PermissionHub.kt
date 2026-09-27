package com.forja.app.feature.cleanup

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** UI adapter to the checksum-pinned v23 controller. Grants never imply collection consent. */
private class PermissionController(private val activity: Activity) {
    private val type = activity.javaClass
    private fun method(name: String, vararg args: Class<*>) = type.getDeclaredMethod(name, *args).apply { isAccessible = true }
    private val readSelected = method("getSelected")
    private val readBackground = method("getBackground")
    private val readNotice = method("getNotice")
    private val readStatus = method("getStatus")
    private val change = method("toggle", String::class.java, Boolean::class.javaPrimitiveType!!)
    private val changeBackground = method("setBackground", Boolean::class.javaPrimitiveType!!)
    private val save = method("applyChoices")
    @Suppress("UNCHECKED_CAST") fun selected() = readSelected.invoke(activity) as Set<String>
    fun background() = readBackground.invoke(activity) as Boolean
    fun notice() = readNotice.invoke(activity) as String
    fun status() = readStatus.invoke(activity) as String
    fun toggle(key: String, enabled: Boolean) { change.invoke(activity, key, enabled) }
    fun background(enabled: Boolean) { changeBackground.invoke(activity, enabled) }
    fun save() { save.invoke(activity) }
    fun back() { method("Screen\$lambda\$7\$lambda\$6", type).invoke(null, activity) }
    fun restoreCurrentAccount() {
        val config = Class.forName("com.forja.app.core.data.CollectionSettings")
        val instance = config.getField("INSTANCE").get(null)
        val enabled = config.getMethod("enabled", android.content.Context::class.java).invoke(instance, activity)
        method("setSelected", Set::class.java).invoke(activity, enabled)
        background(false)
    }
    fun stop() {
        val config = Class.forName("com.forja.app.core.data.CollectionSettings")
        config.getMethod("stop", android.content.Context::class.java).invoke(config.getField("INSTANCE").get(null), activity)
        selected().toList().forEach { toggle(it, false) }
        background(false)
    }
}

private val HubInk = Color(0xFF101A18)
private val HubMint = Color(0xFFC9E89C)
private val HubMuted = Color(0xFFAABAB2)
private const val HubSite = "https://forja-insights.forja-22e7ea2d.workers.dev/insights"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionHub(activity: Activity) {
    val controller = remember(activity) { PermissionController(activity) }
    val preferences = remember(activity) { activity.getSharedPreferences(PendingSyncReturn.PREFS, 0) }
    var page by rememberSaveable { mutableIntStateOf(
        if (activity.intent.getBooleanExtra(PendingSyncReturn.EXTRA, false)) 3
        else if (preferences.getBoolean("introduced", false)) 2 else 0
    ) }
    var refresh by remember { mutableIntStateOf(0) }
    var privacy by rememberSaveable { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    var advancedAccess by rememberSaveable { mutableStateOf(false) }
    var feedback by rememberSaveable { mutableStateOf("") }
    var settingsNeeded by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var chosenTree by rememberSaveable { mutableStateOf<String?>(null) }
    var resultOwner by rememberSaveable { mutableStateOf<String?>(null) }
    var locationExcluded by rememberSaveable { mutableStateOf(false) }
    var usageExcluded by rememberSaveable { mutableStateOf(false) }
    var backgroundExcluded by rememberSaveable { mutableStateOf(false) }
    var signedIn by remember { mutableStateOf(FirebaseAuth.getInstance().currentUser?.uid) }
    var stagedOwner by rememberSaveable { mutableStateOf(FirebaseAuth.getInstance().currentUser?.uid) }
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        refresh++
        settingsNeeded = grants.any { (permission, allowed) -> !allowed && !activity.shouldShowRequestPermissionRationale(permission) }
        feedback = if (grants.isNotEmpty() && grants.values.all { it }) "Acces actualizat" else "Alegerea ta este păstrată."
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runCatching {
            activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            check(SyncSetup.readableTree(activity, uri))
            chosenTree = uri.toString()
            feedback = "Dosar ales"
        }.onFailure { feedback = "Alege un dosar accesibil pentru citire." }
    }
    DisposableEffect(activity) {
        val owner = activity as LifecycleOwner
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        val auth = FirebaseAuth.getInstance()
        val listener = FirebaseAuth.AuthStateListener {
            val next = it.currentUser?.uid
            if (next != signedIn || next != stagedOwner) {
                controller.restoreCurrentAccount()
                resultOwner = null; chosenTree = null
                locationExcluded = false; usageExcluded = false; backgroundExcluded = false
                signedIn = next
                stagedOwner = next
            }
            refresh++
        }
        owner.lifecycle.addObserver(observer); auth.addAuthStateListener(listener)
        onDispose { owner.lifecycle.removeObserver(observer); auth.removeAuthStateListener(listener) }
    }
    // Old saved activity state may still point at the removed confirmation page.
    LaunchedEffect(page) { if (page !in 0..3) page = 3 }
    fun has(permission: String) = activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    fun open(intent: Intent) { runCatching { activity.startActivity(intent) }.onFailure { feedback = "Nu am putut deschide această opțiune." } }
    fun appSettings() = open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
    fun request(vararg permissions: String) {
        val missing = permissions.filter { !has(it) }.toMutableList()
        if (Manifest.permission.ACCESS_FINE_LOCATION in missing && Manifest.permission.ACCESS_COARSE_LOCATION !in missing) missing += Manifest.permission.ACCESS_COARSE_LOCATION
        if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray()) else appSettings()
    }
    fun markIntroduced() { preferences.edit().putBoolean("introduced", true).apply() }
    fun leave(save: Boolean) {
        markIntroduced()
        if (save) {
            if (resultOwner != FirebaseAuth.getInstance().currentUser?.uid) {
                controller.restoreCurrentAccount()
                resultOwner = null; chosenTree = null; page = 3
                feedback = "Contul s-a schimbat. Verifică noul cont înainte de activare."
                return
            }
            if (controller.background() && !activity.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) controller.background(false)
            controller.save()
            if (!activity.isFinishing) feedback = controller.notice().ifBlank { "Verifică accesul ales și încearcă din nou." }
        } else controller.back()
    }
    val notificationOn = remember(refresh) { activity.getSystemService(NotificationManager::class.java).areNotificationsEnabled() }
    val locationOn = remember(refresh) { has(Manifest.permission.ACCESS_COARSE_LOCATION) || has(Manifest.permission.ACCESS_FINE_LOCATION) }
    val photosPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    val photosOn = remember(refresh) { has(photosPermission) }
    val photosPartial = remember(refresh) { Build.VERSION.SDK_INT >= 34 && has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) }
    val usageOn = remember(refresh) { ResearchPermissionAccess.usage(activity) }
    val backgroundOn = remember(refresh) { locationOn && (Build.VERSION.SDK_INT < 29 || has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) }
    fun activate() {
        if (busy) return
        val owner = signedIn
        if (owner == null) {
            markIntroduced()
            PendingSyncReturn.afterLogin(activity)
            controller.back()
            return
        }
        busy = true; feedback = ""
        scope.launch {
            try {
                val outcome = SyncSetup.activate(activity, chosenTree?.let(Uri::parse))
                if (outcome.accountChanged || FirebaseAuth.getInstance().currentUser?.uid != owner) {
                    feedback = "Contul s-a schimbat. Verifică noul cont înainte de activare."
                    page = 3
                } else if (outcome.scopes.any { it.state == SyncSetup.State.ERROR }) {
                    feedback = "O parte din sincronizare nu s-a activat. Încearcă din nou."
                    refresh++
                } else {
                    // Permissions are inputs to this explicit sync action, never an implicit grant.
                    if (notificationOn && locationOn && !locationExcluded) controller.toggle("location", true)
                    if (notificationOn && usageOn && !usageExcluded) controller.toggle("app_usage", true)
                    if (!backgroundExcluded && notificationOn && ((locationOn && !locationExcluded) || (usageOn && !usageExcluded))) controller.background(true)
                    resultOwner = owner
                    // Finish the same confirmed action directly; no extra success screen.
                    leave(true)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
              catch (_: Exception) { feedback = "Conectarea nu s-a încheiat. Poți reîncerca." }
            finally { busy = false }
        }
    }
    fun previous() {
        if (busy) return
        when (page) { 0 -> leave(false); else -> page = (page - 1).coerceIn(0, 3) }
    }
    BackHandler { previous() }
    MaterialTheme(colorScheme = darkColorScheme(primary = HubMint, onPrimary = HubInk, background = HubInk,
        surface = Color(0xFF1A2824), onSurface = Color(0xFFF1F5F0), onSurfaceVariant = HubMuted)) {
        Scaffold(containerColor = HubInk, topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (page > 0) IconButton(onClick = ::previous, enabled = !busy) { Text("‹", fontSize = 30.sp) }
                Text("F O R J A", color = HubMint, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (page < 2 || page == 3) TextButton(onClick = { leave(false) }, enabled = !busy) { Text("Mai târziu", color = HubMuted, fontSize = 12.sp) }
                IconButton(onClick = { details = true }, enabled = !busy) { Text("⋯", fontSize = 25.sp) }
            }
        }, bottomBar = {
            Surface(color = HubInk) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (feedback.isNotBlank()) Text(feedback, color = HubMint, fontSize = 12.sp,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    Button(onClick = {
                        when (page) { 0 -> page = 1; 1 -> page = 2; 2 -> { feedback = ""; page = 3 }; else -> activate() }
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(19.dp)) {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp).padding(end = 6.dp), strokeWidth = 2.dp, color = HubInk)
                        Text(when (page) {
                            0 -> "Descoperă FORJA"; 1 -> "Pregătește FORJA"; 2 -> "Continuă"
                            else -> if (busy) "Se conectează…" else if (signedIn == null) "Intră în cont" else "Activează sincronizarea"
                        }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        repeat(4) { index -> Box(Modifier.padding(horizontal = 3.dp).height(4.dp).width(if (minOf(page, 3) == index) 22.dp else 7.dp).clip(CircleShape).background(if (minOf(page, 3) == index) HubMint else HubMuted.copy(alpha = .25f))) }
                    }
                }
            }
        }) { padding ->
            key(page) {
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    when (page) {
                        0 -> {
                            HubArtwork(activity, "welcome", "Mișcare, natură și odihnă")
                            HubHeading("În ritmul tău.", "Mișcare, mese, odihnă și oamenii tăi. În același loc.")
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("Mișcare", "Echilibru", "Împreună").forEach { Surface(color = HubMint.copy(alpha = .09f), shape = CircleShape, modifier = Modifier.weight(1f)) {
                                    Text(it, Modifier.padding(vertical = 12.dp), color = HubMint, fontSize = 12.sp, textAlign = TextAlign.Center)
                                } }
                            }
                        }
                        1 -> {
                            HubArtwork(activity, "sleep", "O lună liniștită deasupra unei perne")
                            HubHeading("Și pauzele contează.", "O noapte întreagă sau un somn după-amiaza. Alegi când începe și când se termină.")
                            Surface(shape = RoundedCornerShape(22.dp)) {
                                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("☾", color = HubMint, fontSize = 34.sp, modifier = Modifier.padding(end = 16.dp))
                                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text("O sesiune. Un singur loc.", fontWeight = FontWeight.SemiBold)
                                        Text("Înregistrarea și raportul audio, în aplicație și pe site.", color = HubMuted, fontSize = 13.sp, lineHeight = 19.sp)
                                    }
                                }
                            }
                        }
                        2 -> {
                            HubHeading("Alege accesul.", "Activezi ce folosești. Poți reveni oricând.")
                            if (settingsNeeded) TextButton(onClick = ::appSettings) { Text("Deschide setările Android") }
                            Surface(shape = RoundedCornerShape(22.dp)) {
                                Column(Modifier.padding(horizontal = 14.dp, vertical = 5.dp)) {
                                    HubPermissionRow("◉", "Locație", if (locationOn && !has(Manifest.permission.ACCESS_FINE_LOCATION)) "Aproximativă" else "Hartă și activități", locationOn) { request(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION) }
                                    HubPermissionRow("▣", "Cameră", "Scanarea meselor", has(Manifest.permission.CAMERA)) { request(Manifest.permission.CAMERA) }
                                    HubPermissionRow("♫", "Microfon", "Sesiunile de somn", has(Manifest.permission.RECORD_AUDIO)) { request(Manifest.permission.RECORD_AUDIO) }
                                    HubPermissionRow("▧", "Fotografii", if (photosPartial && !photosOn) "Selecția ta" else "Galerie și curățenie", photosOn || photosPartial) {
                                        if (Build.VERSION.SDK_INT >= 34) request(photosPermission, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) else request(photosPermission)
                                    }
                                    HubPermissionRow("♡", "Contacte", "Prieteni din agendă", has(Manifest.permission.READ_CONTACTS)) { request(Manifest.permission.READ_CONTACTS) }
                                    HubPermissionRow("◇", "Notificări", "Starea sesiunilor", notificationOn) {
                                        if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) request(Manifest.permission.POST_NOTIFICATIONS)
                                        else open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
                                    }
                                }
                            }
                            TextButton(onClick = { advancedAccess = !advancedAccess }, modifier = Modifier.fillMaxWidth()) {
                                Text("Focus și fundal", Modifier.weight(1f), textAlign = TextAlign.Start); Text(if (advancedAccess) "−" else "+")
                            }
                            if (advancedAccess) Surface(shape = RoundedCornerShape(22.dp)) {
                                Column(Modifier.padding(horizontal = 14.dp, vertical = 5.dp)) {
                                    HubPermissionRow("◷", "Utilizare aplicații", "Rapoarte Focus", usageOn) { open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${activity.packageName}"))) }
                                    HubPermissionRow("□", "Peste aplicații", "Pauzele Focus", Settings.canDrawOverlays(activity)) { open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}"))) }
                                    HubPermissionRow("◎", "Locație în fundal", "Cu ecranul blocat", backgroundOn) { appSettings() }
                                }
                            }
                        }
                        3 -> {
                            HubArtwork(activity, "sync", "Telefonul și laptopul conectate")
                            HubHeading("Tot ce contează.\nȘi pe ecranul mare.", "Activitate și galerie, sincronizate în cont.")
                            Text("Sesiunile de somn pornite de tine, din aplicație sau site, se încarcă și primesc un raport AI.", color = HubMuted, fontSize = 14.sp, lineHeight = 21.sp)
                            if (signedIn == null) Text("Continuăm după conectarea în contul FORJA.", color = HubMint, fontSize = 13.sp)
                            TextButton(onClick = { open(Intent(Intent.ACTION_VIEW, Uri.parse(HubSite))) }, contentPadding = PaddingValues(0.dp)) { Text("Deschide site-ul FORJA ↗") }
                        }

                    }
                }
            }
        }
        if (details) ModalBottomSheet(onDismissRequest = { details = false }, containerColor = Color(0xFF1A2824)) {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Tu alegi", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                TextButton(onClick = { details = false; privacy = true }) { Text("Confidențialitate") }
                TextButton(onClick = { details = false; page = 0 }) { Text("Revezi prezentarea") }
                if (page >= 2) {
                    TextButton(onClick = { details = false; page = 2 }) { Text("Permisiuni") }
                    TextButton(onClick = { details = false; page = 3 }) { Text("Conectare cu site-ul") }
                    if (signedIn != null) {
                        HorizontalDivider(Modifier.padding(vertical = 5.dp))
                        Text("Activitatea trimisă în cont", color = HubMuted, fontSize = 12.sp)
                        HubChoice("Locație și opriri", "location" in controller.selected()) { locationExcluded = !it; controller.toggle("location", it) }
                        HubChoice("Activitate în aplicații", "app_usage" in controller.selected()) { usageExcluded = !it; controller.toggle("app_usage", it) }
                        HubChoice("Continuă în fundal", controller.background()) {
                            backgroundExcluded = !it
                            if (it && !notificationOn) appSettings() else controller.background(it)
                        }
                        TextButton(onClick = { details = false; folder.launch(null); page = 3 }) { Text("Dosar de sincronizat") }
                        TextButton(onClick = {
                            details = false
                            open(Intent().setClassName(activity.packageName, "com.forja.app.feature.research.WebPairActivity"))
                        }) { Text("Somn și organizare din site") }
                        if (controller.selected().isNotEmpty()) TextButton(onClick = { controller.stop(); feedback = "Sincronizarea locației și utilizării este oprită."; details = false }) { Text("Oprește locația și utilizarea") }
                    }
                }
            }
        }
        if (privacy) HubPrivacy { privacy = false }
    }
}

@Composable
private fun HubHeading(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, fontSize = 31.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
        Text(body, color = HubMuted, fontSize = 15.sp, lineHeight = 22.sp)
    }
}

@Composable
private fun HubArtwork(activity: Activity, name: String, description: String, compact: Boolean = false) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, name) {
        value = withContext(Dispatchers.IO) {
            runCatching { activity.assets.open("forja/onboarding-$name.png").use { android.graphics.BitmapFactory.decodeStream(it)?.asImageBitmap() } }.getOrNull()
        }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(if (compact) 2.6f else 1.35f).clip(RoundedCornerShape(28.dp)).background(HubInk)) {
        bitmap?.let { Image(it, contentDescription = description, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
private fun HubPrivacy(dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("Confidențialitate") }, text = {
        Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Permisiunile Android permit accesul la funcțiile alese. Acordarea lor nu pornește înregistrarea sau trimiterea datelor.")
            Text("Butonul Activează sincronizarea trimite activitatea și galeria autorizate în contul tău și pregătește comenzile de somn. Fișierele folosesc doar dosarul ales în Android. Transferurile pot aștepta Wi-Fi, bateria sau conexiunea; activarea nu înseamnă că toate fișierele au ajuns deja pe site.")
            Text("Microfonul pornește când începi sau programezi o sesiune de somn din aplicație ori din propriul cont web. Telefonul arată o notificare cu Oprește. Sesiunea poate include sunetele și vocile persoanelor din apropiere; folosește funcția numai cu acordul lor.")
            Text("Înregistrările autorizate se încarcă și sunt analizate automat pentru raport. Transcrierile pot avea erori; le poți compara cu audio. Nu identificăm persoana care vorbește și nu deducem diagnostice, trăsături psihologice sau stadii ale somnului din înregistrare.")
            Text("Copiile temporare au o perioadă de acces de 24 de ore; eliminarea fizică poate urma procesului de curățare. Poți șterge înregistrările din cont. Oprirea unei funcții împiedică datele noi și nu șterge automat copiile deja primite.")
            Text("Locația partajată cu alte persoane, găsirea prietenilor după număr și mutarea originalelor au acorduri proprii. Acest buton nu pornește partajarea cu partenerul și nu îți face numărul vizibil.")
            Text("După o oprire forțată, repornire a telefonului sau restricție Android poate fi necesară redeschiderea FORJA. Site-ul afișează starea reală; o comandă trimisă nu înseamnă automat că microfonul a pornit.")
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("Am înțeles") } })
}

private object ResearchPermissionAccess {
    fun usage(activity: Activity): Boolean = runCatching {
        activity.getSystemService(android.app.AppOpsManager::class.java).checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(), activity.packageName) == android.app.AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)
}

@Composable
private fun HubPermissionRow(symbol: String, title: String, subtitle: String, allowed: Boolean, activate: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 66.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(HubMint.copy(alpha = if (allowed) .15f else .06f)), contentAlignment = Alignment.Center) {
            Text(if (allowed) "✓" else symbol, color = HubMint, fontSize = 20.sp)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(subtitle, color = HubMuted, fontSize = 12.sp)
        }
        TextButton(onClick = activate) { Text(if (allowed) "Activ" else "Permite", fontSize = 12.sp) }
    }
}

@Composable
private fun HubChoice(title: String, checked: Boolean, changed: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 50.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 14.sp); Switch(checked = checked, onCheckedChange = changed)
    }
}
