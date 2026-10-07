package com.forja.app.core.research

import android.Manifest
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.forja.app.ForjaApp
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*
import kotlinx.coroutines.launch

private data class LabSourceOption(val source: String, val label: String, val description: String)
private val labOptions = listOf(
    LabSourceOption("ACTIVITY", "Fitness / Activity", "Evenimentele activităților și antrenamentelor colectate deja de FORJA."),
    LabSourceOption("SLEEP", "Sleep", "Evenimente și rapoarte ale modulului Sleep. Nu acordă acces la microfon."),
    LabSourceOption("NUTRITION", "Nutrition", "Mese, produse, coduri de bare și apă din jurnalul tău FORJA."),
    LabSourceOption("APP", "Applications", "Tranziții între aplicații, cu acces Usage Stats acordat în setările Android."),
    LabSourceOption("NOTIFICATION", "Notifications", "Titlul, textul și metadatele notificărilor pe care Android le expune. Pot conține mesaje și nume."),
    LabSourceOption("MEDIA", "Media", "Metadate și miniaturi din fotografiile și videoclipurile permise. Originalele rămân pe telefon și pot fi solicitate de investigator."),
    LabSourceOption("LOCATION", "Location", "Coordonate, precizie și traseul observat cât timp sesiunea vizibilă rulează și Android permite."),
    LabSourceOption("NETWORK", "Network", "Schimbări de conectivitate și informații Wi-Fi oferite de Android; SSID/BSSID pot lipsi."),
    LabSourceOption("BLUETOOTH", "Bluetooth", "Scanări periodice autorizate: nume, identificatori și RSSI unde Android permite."),
    LabSourceOption("CONTACT", "Contacts", "Contactele din conturile de laborator, doar cu permisiunea Contacte."),
    LabSourceOption("FILE", "Files", "Metadatele documentelor dintr-un folder ales explicit. Originalele sunt transferate numai la cerere.")
)

/** A separate, transparent opt-in surface; normal fitness permission workflows are unchanged. */
@Composable
fun LabConsentCard() {
    val context = LocalContext.current
    val controller = remember { ForjaApp.from(context).labResearch }
    val scope = rememberCoroutineScope()
    val session by controller.session.collectAsState()
    val pending by controller.pendingCount.collectAsState()
    val sync by controller.syncStatus.collectAsState()
    val running by LabObservers.running.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    var invitation by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("${Build.MANUFACTURER} ${Build.MODEL} Lab") }
    var consent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmSource by remember { mutableStateOf<LabSourceOption?>(null) }
    var confirmAssociation by remember { mutableStateOf<LabAssociation?>(null) }
    var permissionSource by remember { mutableStateOf<String?>(null) }
    var permissionAssociation by remember { mutableStateOf<LabAssociation?>(null) }
    var treeAssociation by remember { mutableStateOf<LabAssociation?>(null) }
    var settingsAssociation by remember { mutableStateOf<LabAssociation?>(null) }
    var permissionRevision by remember { mutableIntStateOf(0) }
    val sourceCapabilities = remember(session, running, permissionRevision) { controller.capabilities() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionRevision++
                val authorisedAssociation = settingsAssociation
                settingsAssociation = null
                if (authorisedAssociation != null && controller.isActive() && controller.session.value?.deviceId == authorisedAssociation.deviceId) controller.resume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val source = permissionSource
        val authorisedAssociation = permissionAssociation
        permissionSource = null
        permissionAssociation = null
        if (!controller.isActive() || controller.session.value?.deviceId != authorisedAssociation?.deviceId) {
            error = "Sesiunea s-a schimbat; sursa nu a fost activată."
        } else if (source == "LAB_NOTIFICATION") {
            if (LabObservers.hasVisibleNotificationPermission(context)) controller.resume()
            else error = "Notificarea Lab trebuie permisă în Android înainte de observare."
        } else if (source == "MEDIA_GPS") {
            if (results.values.none { it }) error = "Android nu a acordat acces la locația din media. GPS EXIF rămâne protejat."
        } else if (source != null) {
            val granted = when (source) {
                "LOCATION" -> context.granted(Manifest.permission.ACCESS_FINE_LOCATION) || context.granted(Manifest.permission.ACCESS_COARSE_LOCATION)
                "MEDIA" -> if (Build.VERSION.SDK_INT >= 33) context.granted(Manifest.permission.READ_MEDIA_IMAGES) || context.granted(Manifest.permission.READ_MEDIA_VIDEO) || (Build.VERSION.SDK_INT >= 34 && context.granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)) else context.granted(Manifest.permission.READ_EXTERNAL_STORAGE)
                "BLUETOOTH" -> context.granted(Manifest.permission.ACCESS_FINE_LOCATION) && (Build.VERSION.SDK_INT < 31 || context.granted(Manifest.permission.BLUETOOTH_SCAN))
                "CONTACT" -> context.granted(Manifest.permission.READ_CONTACTS)
                else -> true
            }
            if (granted) runCatching { controller.setSourceEnabled(source, true) }.onFailure { error = it.message }
            else error = "Sursa rămâne oprită: Android nu a acordat permisiunea necesară."
        }
    }
    val chooseFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val authorisedAssociation = treeAssociation
        treeAssociation = null
        if (uri != null && controller.isActive() && controller.session.value?.deviceId == authorisedAssociation?.deviceId) {
            runCatching {
                LabArtifacts.grantTree(context, uri)
                controller.setSourceEnabled("FILE", true)
            }.onFailure { error = it.message ?: "Folderul nu a putut fi autorizat." }
        }
    }

    ForjaCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), padding = 14.dp) {
        Text("Device / Lab Access", style = BodyStrong)
        Spacer(Modifier.height(4.dp))
        Text(if (session == null) "Oprit · doar pentru telefoane de laborator și sesiuni autorizate." else "${session!!.label} · ${if (running) "observare activă" else "observare oprită"}", style = BodyTiny.copy(color = TextSecondary))
        Spacer(Modifier.height(8.dp))
        Text(if (expanded) "restrânge ↑" else "detalii și permisiuni →", style = BodySmall.copy(color = Accent2), modifier = Modifier.pressable(onClick = { expanded = !expanded }))
        if (expanded) {
            Spacer(Modifier.height(12.dp))
            if (session == null) {
                Text("Organizatorul emite un cod de laborator. Asocierea este confirmată de server pentru contul autentificat. Nicio sursă personală nu se activează automat.", style = BodyTiny.copy(color = TextSecondary))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(invitation, { invitation = it }, label = { Text("Cod de laborator") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(label, { label = it }, label = { Text("Nume dispozitiv") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth()) {
                    Checkbox(consent, { consent = it })
                    Text("Confirm că este un telefon de laborator autorizat. Permit asocierea și sincronizarea stării dispozitivului către investigatorii alocați acestei sesiuni.", style = BodyTiny, modifier = Modifier.weight(1f).padding(top = 10.dp))
                }
                PrimaryButton(if (busy) "Se asociază…" else "Asociază dispozitivul", onClick = {
                    busy = true; error = null
                    scope.launch {
                        try { controller.enroll(invitation, label); invitation = ""; consent = false }
                        catch (failure: Exception) { error = failure.message ?: "Asocierea nu a reușit." }
                        finally { busy = false }
                    }
                }, enabled = consent && !busy && controller.available, modifier = Modifier.fillMaxWidth(), small = true)
                if (!controller.available) Text("Serverul Lab prin HTTPS nu este configurat în acest build.", style = BodyTiny.copy(color = TextSecondary))
            } else {
                Text("Device ID: ${session!!.deviceId}\nLab session: ${session!!.labSessionId}\nJurnal local: $pending evenimente în așteptare", style = BodyTiny.copy(color = TextSecondary))
                Spacer(Modifier.height(6.dp))
                Text("Sursele selectate trimit un istoric persistent către sesiunea Lab. Permisiunile Android se acordă separat. Oprirea observării păstrează evenimentele deja înregistrate pentru sincronizare; Deconectează oprește și sincronizarea acestui dispozitiv.", style = BodyTiny.copy(color = TextSecondary))
                Spacer(Modifier.height(10.dp))
                key(permissionRevision) {
                    if (!LabObservers.hasVisibleNotificationPermission(context)) {
                        Text("Observarea necesită o notificare Lab vizibilă cu acțiunea Stop.", style = BodyTiny.copy(color = LogoutText))
                        SecondaryButton("Permite notificarea Lab", onClick = {
                            if (Build.VERSION.SDK_INT >= 33 && !context.granted(Manifest.permission.POST_NOTIFICATIONS)) {
                                permissionSource = "LAB_NOTIFICATION"
                                permissionAssociation = session
                                permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                            } else {
                                settingsAssociation = session
                                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                            }
                        }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(if (running) "Oprește observarea" else "Pornește observarea", onClick = { if (running) controller.pause() else controller.resume() }, modifier = Modifier.weight(1f))
                    SecondaryButton("Sincronizează", onClick = { controller.syncNow() }, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                for (option in labOptions) {
                    val enabled = option.source in session!!.enabledSources
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(option.label, style = BodyStrong)
                            Text(option.description, style = BodyTiny.copy(color = TextSecondary))
                            key(permissionRevision) {
                                val androidPermission = sourceCapabilities.optJSONObject(option.source)?.optString("permission")
                                if (androidPermission != null && option.source !in setOf("APP", "NOTIFICATION")) Text("Android: ${when (androidPermission) { "granted" -> "permis"; "partial" -> "acces parțial"; "missing" -> "necesită permisiune"; else -> "modulul FORJA existent" }}", style = BodyTiny.copy(color = Accent2))
                                if (option.source == "APP") Text(if (context.usageGranted()) "Android: Usage Access acordat" else "Android: necesită Usage Access", style = BodyTiny.copy(color = Accent2))
                                if (option.source == "NOTIFICATION") Text(if (context.notificationGranted()) "Android: listener autorizat" else "Android: necesită acces la notificări", style = BodyTiny.copy(color = Accent2))
                            }
                        }
                        ForjaSwitch(enabled) { value ->
                            if (value) { confirmSource = option; confirmAssociation = session }
                            else runCatching {
                                controller.setSourceEnabled(option.source, false)
                                if (option.source == "FILE") LabArtifacts.clearTree(context)
                            }.onFailure { error = it.message }
                        }
                    }
                }
                if ("MEDIA" in session!!.enabledSources && Build.VERSION.SDK_INT >= 29) {
                    Text("GPS EXIF este disponibil doar dacă autorizezi separat locația din media.", style = BodyTiny.copy(color = TextSecondary))
                    Text("Permite locația din media →", style = BodySmall.copy(color = Accent2), modifier = Modifier.pressable(onClick = {
                        permissionSource = "MEDIA_GPS"
                        permissionAssociation = session
                        permissions.launch(arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION))
                    }))
                    Spacer(Modifier.height(10.dp))
                }
                if ("FILE" in session!!.enabledSources) SecondaryButton("Alege alt folder autorizat", onClick = { treeAssociation = session; chooseFolder.launch(null) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                SecondaryButton("Deconectează Lab", onClick = {
                    scope.launch { controller.disconnect() }
                }, textColor = LogoutText, modifier = Modifier.fillMaxWidth())
            }
            (error ?: sync.error)?.let { Text(it.take(350), style = BodyTiny.copy(color = LogoutText), modifier = Modifier.padding(top = 8.dp)) }
        }
    }

    confirmSource?.let { option ->
        AlertDialog(onDismissRequest = { confirmSource = null }, title = { Text("Permite ${option.label}") },
            text = { Text("${option.description}\n\nDatele observate vor fi sincronizate și păstrate în istoricul sesiunii Lab pentru investigatorii alocați. Poți opri sursa sau deconecta dispozitivul din Profile.") },
            dismissButton = { TextButton(onClick = { confirmSource = null }) { Text("Anulează") } },
            confirmButton = { TextButton(onClick = confirm@{
                confirmSource = null
                error = null
                val authorisedAssociation = confirmAssociation
                confirmAssociation = null
                if (!controller.isActive() || controller.session.value?.deviceId != authorisedAssociation?.deviceId) {
                    error = "Sesiunea s-a schimbat; consimțământul nu a fost activat."
                    return@confirm
                }
                when (option.source) {
                    "FILE" -> { treeAssociation = authorisedAssociation; chooseFolder.launch(null) }
                    "APP", "NOTIFICATION" -> {
                        runCatching {
                            controller.setSourceEnabled(option.source, true)
                            val granted = if (option.source == "APP") context.usageGranted() else context.notificationGranted()
                            if (!granted) {
                                settingsAssociation = authorisedAssociation
                                context.startActivity(Intent(if (option.source == "APP") Settings.ACTION_USAGE_ACCESS_SETTINGS else Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            }
                        }.onFailure { error = it.message }
                    }
                    else -> {
                        val required = when (option.source) {
                            "CONTACT" -> arrayOf(Manifest.permission.READ_CONTACTS)
                            "LOCATION" -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                            "BLUETOOTH" -> if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                            "MEDIA" -> if (Build.VERSION.SDK_INT >= 34) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) else if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                            else -> emptyArray()
                        }
                        if (required.isEmpty()) runCatching { controller.setSourceEnabled(option.source, true) }.onFailure { error = it.message }
                        else { permissionSource = option.source; permissionAssociation = authorisedAssociation; permissions.launch(required) }
                    }
                }
            }) { Text("Permit pentru Lab") } })
    }
}

private fun android.content.Context.granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
private fun android.content.Context.usageGranted(): Boolean {
    val manager = getSystemService(AppOpsManager::class.java)
    val status = if (Build.VERSION.SDK_INT >= 29) manager.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
    else manager.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
    return status == AppOpsManager.MODE_ALLOWED
}
private fun android.content.Context.notificationGranted(): Boolean = if (Build.VERSION.SDK_INT >= 27) getSystemService(android.app.NotificationManager::class.java)
    .isNotificationListenerAccessGranted(ComponentName(this, LabNotificationListener::class.java))
else NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
