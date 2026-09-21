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

@Composable
fun PermissionHub(activity: Activity) {
    val controller = remember(activity) { PermissionController(activity) }
    var refresh by remember { mutableIntStateOf(0) }
    var privacy by rememberSaveable { mutableStateOf(false) }
    var syncOpen by rememberSaveable { mutableStateOf(controller.selected().isNotEmpty()) }
    var feedback by rememberSaveable { mutableStateOf("") }
    var settingsNeeded by rememberSaveable { mutableStateOf(false) }
    val selected = controller.selected()
    val background = controller.background()
    val notice = controller.notice()
    val status = controller.status()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        refresh++
        settingsNeeded = result.any { (permission, allowed) -> !allowed && !activity.shouldShowRequestPermissionRationale(permission) }
        feedback = if (result.isNotEmpty() && result.values.all { it }) "Acces actualizat ✓" else "Poți continua cu accesul ales."
    }
    DisposableEffect(activity) {
        val owner = activity as LifecycleOwner
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    fun has(permission: String) = activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    fun open(intent: Intent) { runCatching { activity.startActivity(intent) }.onFailure { feedback = "Deschide setările FORJA din Android." } }
    fun appSettings() = open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
    fun missingPermissions(permissions: List<String>): List<String> {
        val missing = permissions.filter { !has(it) }.toMutableList()
        // Android 12+ requires both location permissions when upgrading from approximate.
        if (Manifest.permission.ACCESS_FINE_LOCATION in missing && Manifest.permission.ACCESS_COARSE_LOCATION !in missing) missing += Manifest.permission.ACCESS_COARSE_LOCATION
        return missing
    }
    fun request(vararg permissions: String) {
        val missing = missingPermissions(permissions.toList())
        if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray()) else appSettings()
    }
    val notificationOn = remember(refresh) { activity.getSystemService(NotificationManager::class.java).areNotificationsEnabled() }
    val locationOn = remember(refresh) { has(Manifest.permission.ACCESS_COARSE_LOCATION) || has(Manifest.permission.ACCESS_FINE_LOCATION) }
    val cameraOn = remember(refresh) { has(Manifest.permission.CAMERA) }
    val micOn = remember(refresh) { has(Manifest.permission.RECORD_AUDIO) }
    val contactsOn = remember(refresh) { has(Manifest.permission.READ_CONTACTS) }
    val photosPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    val photosOn = remember(refresh) { has(photosPermission) }
    val photosPartial = remember(refresh) { Build.VERSION.SDK_INT >= 34 && has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) }
    val usageOn = remember(refresh) { ResearchPermissionAccess.usage(activity) }
    val overlayOn = remember(refresh) { Settings.canDrawOverlays(activity) }
    val backgroundOn = remember(refresh) { locationOn && (Build.VERSION.SDK_INT < 29 || has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) }
    val ready = listOf(notificationOn, locationOn, cameraOn, micOn, contactsOn, photosOn || photosPartial, usageOn, overlayOn).count { it }
    BackHandler { controller.back() }
    MaterialTheme(colorScheme = darkColorScheme(primary = HubMint, onPrimary = HubInk, background = HubInk, surface = Color(0xFF1A2824), onSurface = Color(0xFFF1F5F0), onSurfaceVariant = HubMuted)) {
        Scaffold(containerColor = HubInk, bottomBar = {
            Surface(color = HubInk) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Button(onClick = { controller.save() }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(18.dp)) { Text("Continuă în FORJA", fontWeight = FontWeight.SemiBold) }
                    TextButton(onClick = { privacy = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Confidențialitate") }
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("FORJA", color = HubMint, fontSize = 12.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold)
                        Text("Totul la îndemână", fontSize = 28.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold)
                    }
                    Surface(shape = CircleShape, color = HubMint.copy(alpha = .12f)) { Text("$ready/8", Modifier.padding(14.dp), color = HubMint, fontWeight = FontWeight.Bold) }
                }
                Text("Activează accesul de care ai nevoie.", color = HubMuted)
                LinearProgressIndicator(progress = { ready / 8f }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape), color = HubMint)
                OutlinedButton(onClick = {
                    val permissions = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_CONTACTS, photosPermission)
                    if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
                    if (Build.VERSION.SDK_INT >= 34) permissions += Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                    val missing = missingPermissions(permissions)
                    if (missing.isEmpty()) feedback = "Accesul Android este pregătit. Focus se activează mai jos." else launcher.launch(missing.toTypedArray())
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(16.dp)) { Text("Activează permisiunile") }
                if(notice.isNotBlank() || feedback.isNotBlank()) Text(notice.ifBlank { feedback }, color = HubMint, fontSize = 13.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if(settingsNeeded) TextButton(onClick = { appSettings() }) { Text("Deschide setările Android") }
                Surface(shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                        HubPermissionRow("◉", "Locație", if(locationOn && !has(Manifest.permission.ACCESS_FINE_LOCATION)) "Aproximativă · hartă" else "Hartă și activități", locationOn) { request(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION) }
                        HubPermissionRow("▣", "Cameră", "Scanarea meselor", cameraOn) { request(Manifest.permission.CAMERA) }
                        HubPermissionRow("♫", "Microfon", "Somn și înregistrări", micOn) { request(Manifest.permission.RECORD_AUDIO) }
                        HubPermissionRow("▧", "Fotografii", if(photosPartial && !photosOn) "Selecția ta" else "Galerie și curățenie", photosOn || photosPartial) { if(Build.VERSION.SDK_INT >= 34) request(photosPermission, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) else request(photosPermission) }
                        HubPermissionRow("♡", "Contacte", "Prieteni din agendă", contactsOn) { request(Manifest.permission.READ_CONTACTS) }
                        HubPermissionRow("◇", "Notificări", "Alerte și sesiuni active", notificationOn) { if(Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) request(Manifest.permission.POST_NOTIFICATIONS) else open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,activity.packageName)) }
                    }
                }
                Text("Focus și fundal", fontWeight = FontWeight.SemiBold)
                Surface(shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                        HubPermissionRow("◷", "Utilizare aplicații", "Rapoarte Focus", usageOn) { open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${activity.packageName}"))) }
                        HubPermissionRow("□", "Peste aplicații", "Pauzele Focus", overlayOn) { open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}"))) }
                        HubPermissionRow("◎", "Locație în fundal", "Partajare cu ecranul blocat", backgroundOn) { appSettings() }
                    }
                }
                Surface(shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        TextButton(onClick = { syncOpen = !syncOpen }, modifier = Modifier.fillMaxWidth()) { Text("Sincronizare în cont", Modifier.weight(1f)); Text(if(selected.isEmpty()) "Oprită" else "${selected.size} active"); Text(if(syncOpen) " −" else " +") }
                        if(syncOpen) {
                            Text("Trimite automat categoriile alese în contul tău. Copiile expiră în 24 h.", color = HubMuted, fontSize = 13.sp)
                            HubChoice("Locație și opriri", "location" in selected) { controller.toggle("location",it) }
                            HubChoice("Activitate în aplicații", "app_usage" in selected) { controller.toggle("app_usage",it) }
                            HubChoice("Continuă și în fundal", background) {
                                if(it && !notificationOn) { feedback = "Permite notificările pentru sesiunile din fundal."; open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,activity.packageName)) }
                                else controller.background(it)
                            }
                            Text(if(background) "Continuă cu notificare până oprești." else "Doar cât FORJA este deschisă.", color = HubMuted, fontSize = 12.sp)
                            if(status.isNotBlank()) Text(status, color = HubMuted, fontSize = 12.sp)
                            if(selected.isNotEmpty()) TextButton(onClick = { controller.stop(); feedback = "Sincronizare oprită" }) { Text("Oprește sincronizarea") }
                        }
                    }
                }
                OutlinedButton(onClick = { open(Intent().setClassName(activity.packageName,"com.forja.app.feature.research.WebPairActivity")) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Text("Audio și fișiere din site  →") }
                TextButton(onClick = { open(Intent(Intent.ACTION_VIEW,Uri.parse("https://forja-insights.forja-22e7ea2d.workers.dev/insights"))) }, modifier = Modifier.fillMaxWidth()) { Text("Deschide site-ul ↗") }

            }
        }
        if(privacy) AlertDialog(onDismissRequest = { privacy = false }, title = { Text("Confidențialitate") }, text = {
            Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Permisiunile Android permit funcțiilor să folosească senzorii și sursele alese. Acordarea lor nu pornește singură înregistrarea, sincronizarea agendei sau partajarea cu alte persoane.")
                Text("Sincronizare: după salvare și autentificare, locația și/sau utilizarea aplicațiilor se trimit automat în contul tău cât FORJA este deschisă. Opțiunea Fundal continuă cu notificare vizibilă. Oprești din acest ecran sau din notificare; ieșirea din cont oprește colectarea.")
                Text("Locația include poziții și opriri observate; site-ul afișează până la 300 de poziții. Utilizarea include numele aplicațiilor, durata în prim-plan și deschiderile de la activare. Actualizările sunt aproximativ la un minut; golurile nu sunt timp măsurat.")
                Text("Datele sunt accesibile cu același cont pe site, unde le poți șterge. Sesiunile expiră după 24 h. Oprirea împiedică datele noi și nu șterge copiile deja primite. Android, bateria și conexiunea pot întrerupe actualizările. Pot exista costuri de internet mobil.")
                Text("Audio, organizarea fișierelor, partajarea cu prietenii sau partenerul și telefonul pierdut au propriile comenzi de activare și oprire. Detaliile și persoanele care primesc datele apar în secțiunea fiecărei funcții.")
            }
        }, confirmButton = { TextButton(onClick = { privacy = false }) { Text("Am înțeles") } })
    }
}

private object ResearchPermissionAccess {
    fun usage(activity: Activity): Boolean = runCatching {
        val ops = activity.getSystemService(android.app.AppOpsManager::class.java)
        ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), activity.packageName) == android.app.AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)
}

@Composable
private fun HubPermissionRow(symbol: String, title: String, subtitle: String, allowed: Boolean, activate: () -> Unit) {
    val color by animateColorAsState(if(allowed) HubMint.copy(alpha=.15f) else Color(0xFF26372F), label="permission state")
    Row(Modifier.fillMaxWidth().heightIn(min = 68.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(13.dp)).background(color), contentAlignment = Alignment.Center) { Text(if(allowed) "✓" else symbol, color = HubMint, fontSize = 20.sp) }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(title,fontWeight=FontWeight.SemiBold,fontSize=14.sp); Text(subtitle,color=HubMuted,fontSize=12.sp) }
        TextButton(onClick=activate) { Text(if(allowed) "Activ" else "Permite",fontSize=12.sp) }
    }
}

@Composable
private fun HubChoice(title: String, checked: Boolean, changed: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=56.dp),verticalAlignment=Alignment.CenterVertically) { Text(title,Modifier.weight(1f),fontSize=14.sp); Switch(checked=checked,onCheckedChange=changed) }
}
