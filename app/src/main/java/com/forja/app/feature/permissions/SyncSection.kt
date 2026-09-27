package com.forja.app.feature.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.forja.app.BuildConfig
import com.forja.app.ForjaApp
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*
import com.forja.app.core.sync.CollectionSettings as Config
import com.forja.app.core.sync.UsageReader
import org.json.JSONArray

/** Cele cinci categorii care pot pleca în cont — fiecare cu propriul comutator, implicit OPRIT. */
private enum class SyncChoice(val key: String, val title: String, val detail: String, val icon: ImageVector) {
    Location(
        "location", "Locație și opriri",
        "Pozițiile și timpul observat în locuri. Serverul afișează ultimele 300 de poziții; golurile nu sunt considerate timp petrecut acolo.",
        Icons.Outlined.Place
    ),
    AppUsage(
        "app_usage", "Activitate în aplicații",
        "Numele aplicațiilor, timpul în prim-plan și deschiderile, începând cu activarea. Actualizare automată la aproximativ un minut.",
        Icons.Outlined.Schedule
    ),
    Photos(
        "photos", "Fotografii selectate",
        "Se trimit automat imaginile pe care le alegi aici. Galeria întreagă nu este accesată.",
        Icons.Outlined.PhotoLibrary
    ),
    Files(
        "files", "Fișiere selectate",
        "Se trimit automat fișierele alese și modificările lor. Maximum 5 fotografii/fișiere în total, fiecare de cel mult 5 MB.",
        Icons.Outlined.FolderOpen
    ),
    Audio(
        "audio", "Microfon live",
        "Înregistrează și trimite sunetul din jur cât timp este activ, inclusiv în fundal. Notificarea arată înregistrarea; serverul păstrează ultimele 24 de clipuri de 5 secunde.",
        Icons.Outlined.Mic
    )
}

/**
 * Starea secțiunii (portul logicii din ecranul de cercetare): setul ales e „în așteptare” până la Salvează;
 * oprirea e imediată; pornirea cere permisiunea sau deschide selectorul.
 */
private class SyncState(private val context: Context) {
    var selected by mutableStateOf(Config.enabled(context))
    var notice by mutableStateOf("")
    /** Crește la fiecare scriere în SharedPreferences (care nu e observabilă) ca ecranul să se recompună. */
    var version by mutableIntStateOf(0)
    var pending: String? = null
    var waitingUsage = false
    val uris = mutableStateMapOf<String, String>().apply {
        for (key in listOf("photos", "files")) put(key, Config.prefs(context).getString(key, "[]").orEmpty())
    }
    private var savedUris: Map<String, String> = uris.toMap()

    lateinit var permission: ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>
    lateinit var photos: ManagedActivityResultLauncher<PickVisualMediaRequest, List<Uri>>
    lateinit var files: ManagedActivityResultLauncher<Array<String>, List<Uri>>
    lateinit var usage: ManagedActivityResultLauncher<Intent, ActivityResult>

    private fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    private fun notifications() = NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun granted(key: String): Boolean = when (key) {
        "location" -> has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)
        "audio" -> has(Manifest.permission.RECORD_AUDIO)
        "app_usage" -> UsageReader.allowed(context)
        else -> true
    }

    /** Ceva de salvat? Doar atunci apare butonul — o resalvare identică nu deschide o sesiune nouă pe server. */
    val changed: Boolean
        get() = selected.filter { granted(it) }.toSet() != Config.enabled(context) || uris.toMap() != savedUris

    fun onPermissionResult() {
        val key = pending; pending = null
        if (key != null && notifications() && granted(key)) selected = selected + key
        else notice = "Accesul nu este activat. Poți continua și îl poți schimba ulterior."
    }

    fun onUsageResult() {
        waitingUsage = false
        if (UsageReader.allowed(context)) enable("app_usage")
        else notice = "Accesul la utilizare este oprit."
    }

    fun enable(key: String) {
        if (key == "app_usage" && !UsageReader.allowed(context)) {
            waitingUsage = true
            try { usage.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
            catch (_: Exception) { waitingUsage = false; notice = "Deschide Setări Android → Acces la utilizare → FORJA." }
            return
        }
        val requests = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) requests += Manifest.permission.POST_NOTIFICATIONS
        when (key) {
            "location" -> if (!granted(key)) requests += listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            "audio" -> if (!granted(key)) requests += Manifest.permission.RECORD_AUDIO
        }
        if (requests.isNotEmpty()) { pending = key; permission.launch(requests.toTypedArray()); return }
        if (!notifications()) { notice = "Activează notificările FORJA în Android pentru a vedea sincronizarea și butonul Oprește."; return }
        notice = ""
        selected = selected + key
    }

    fun toggle(key: String, on: Boolean) {
        if (!on) {
            selected = selected - key
            // Oprirea e imediată, chiar dacă utilizatorul pleacă fără să salveze.
            val saved = Config.enabled(context)
            if (key in saved) { Config.save(context, saved - key); Config.resume(context) }
            version++
            return
        }
        when (key) {
            "photos" -> photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            "files" -> files.launch(arrayOf("*/*"))
            else -> enable(key)
        }
    }

    fun picked(list: List<Uri>, kind: String) {
        if (list.isEmpty()) return
        val other = if (kind == "photos") "files" else "photos"
        val otherCount = if (other in selected) JSONArray(uris[other] ?: "[]").length() else 0
        val keep = list.take(5 - otherCount)
        if (keep.isEmpty()) { notice = "Poți sincroniza maximum 5 fotografii și fișiere în total."; return }
        val persisted = keep.filter { uri ->
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); true }
            catch (_: SecurityException) { false }
        }
        if (persisted.isEmpty()) { notice = "Selecția nu permite acces persistent. Alege fișiere din memoria telefonului."; return }
        uris[kind] = JSONArray(persisted.map { it.toString() }).toString()
        enable(kind)
    }

    /** La revenire: ce nu mai are permisiune iese din set (și din ce e salvat). */
    fun onResume() {
        if (waitingUsage) return
        selected = selected.filter { granted(it) }.toSet()
        val saved = Config.enabled(context)
        val stillAllowed = saved.filter { granted(it) }.toSet()
        if (saved != stillAllowed) { Config.save(context, stillAllowed); Config.resume(context) }
        version++
    }

    /** Salvează doar când s-a schimbat ceva; apoi serviciul pornește dintr-o activitate vizibilă. */
    fun saveChoices(): Boolean {
        if (!changed) return false
        Config.save(context, selected.filter { granted(it) }.toSet())
        Config.prefs(context).edit().apply { uris.forEach { (key, value) -> putString(key, value) } }.apply()
        savedUris = uris.toMap()
        Config.resume(context)
        notice = ""
        version++
        return true
    }

    fun stopAll() {
        selected = emptySet()
        Config.stop(context)
        notice = ""
        version++
    }
}

/** Secțiunea „Sincronizare în cont” din Echipare — sub cele cinci bife; nimic de aici nu e obligatoriu. */
@Composable
fun SyncSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = remember(context) { ForjaApp.from(context) }
    val toast = LocalToast.current
    val st = remember { SyncState(context) }

    st.permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { st.onPermissionResult() }
    st.photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { st.picked(it, "photos") }
    st.files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { st.picked(it, "files") }
    st.usage = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { st.onUsageResult() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) st.onResume() }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val status by app.prefs.syncStatus.collectAsState(initial = "")
    val savedOn = remember(st.version, st.selected) { Config.enabled(context) }
    val changed = remember(st.version, st.selected, st.uris.toMap()) { st.changed }
    val anyOn = savedOn.isNotEmpty() || st.selected.isNotEmpty()

    Column(modifier) {
        SectionLabel("Sincronizare în cont")
        Spacer(Modifier.height(8.dp))
        Text(
            "Datele alese pleacă în contul tău FORJA (site), inclusiv în fundal, până le oprești.",
            style = BodyStrong.copy(fontSize = 14.sp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Nimic nu pornește fără un comutator pornit de tine. Nu confirmi fiecare trimitere.",
            style = BodyTiny.copy(color = TextSecondary)
        )
        Spacer(Modifier.height(12.dp))

        ForjaCard(Modifier.fillMaxWidth(), stroke = StrokeCard, padding = 6.dp) {
            SyncChoice.entries.forEachIndexed { i, c ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x0DFFFFFF)))
                val on = c.key in st.selected
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(if (on) Color(0x1F2FBE71) else Surface2),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(c.icon, contentDescription = null, tint = if (on) Positive else Accent2, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(c.title, style = BodyStrong.copy(fontSize = 14.sp), modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(10.dp))
                        ForjaSwitch(on) { v -> st.toggle(c.key, v) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(c.detail, style = BodyTiny.copy(color = TextSecondary))
                    if (on && (c.key == "photos" || c.key == "files")) {
                        val count = JSONArray(st.uris[c.key] ?: "[]").length()
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (count == 1) "1 element ales" else "$count elemente alese",
                                style = BodyTiny.copy(color = TextDim), modifier = Modifier.weight(1f)
                            )
                            MonoButton("Schimbă selecția", onClick = { st.toggle(c.key, true) }, color = Accent2)
                        }
                    }
                }
            }
        }

        if (status.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (savedOn.isNotEmpty()) Positive else TextDim2))
                Spacer(Modifier.width(8.dp))
                Text(status, style = BodyTiny.copy(color = if (savedOn.isNotEmpty()) Positive else TextSecondary))
            }
        }
        if (st.notice.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(st.notice, style = BodyTiny.copy(color = EmberHot))
        }

        if (changed) {
            Spacer(Modifier.height(12.dp))
            PrimaryButton(
                "Salvează sincronizarea",
                onClick = { if (st.saveChoices()) toast.show("Salvat. Sincronizarea pornește cu ce ai ales.") },
                modifier = Modifier.fillMaxWidth(), small = true
            )
        }
        if (anyOn) {
            Spacer(Modifier.height(8.dp))
            SecondaryButton(
                "Oprește toată sincronizarea",
                onClick = { st.stopAll(); toast.show("Sincronizarea e oprită. Ce a ajuns deja pe site rămâne acolo.") },
                modifier = Modifier.fillMaxWidth(), padV = 10.dp
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Oprești oricând de aici, din Profil → Sincronizare în cont sau din notificarea FORJA. Ieșirea din cont oprește sincronizarea. Android o poate întrerupe; starea apare aici, iar reluarea se face când redeschizi aplicația.",
            style = BodyTiny.copy(color = TextDim)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Datele trimise sunt vizibile în panoul online cu același cont. Sesiunile expiră după 24 de ore și pot fi șterse de acolo. Dezactivarea oprește datele noi; nu șterge ce a fost deja primit. Pot exista costuri de internet mobil.",
            style = BodyTiny.copy(color = TextDim)
        )
        Spacer(Modifier.height(6.dp))
        MonoButton(
            "Deschide panoul online ↗",
            onClick = {
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.INSIGHTS_URL.trimEnd('/') + "/insights"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Exception) { toast.show("Nu am găsit un browser. Deschide manual: ${BuildConfig.INSIGHTS_URL}") }
            },
            color = Accent2
        )
    }
}
