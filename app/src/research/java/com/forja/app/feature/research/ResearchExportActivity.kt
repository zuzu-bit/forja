package com.forja.app.feature.research

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.forja.app.BuildConfig
import com.forja.app.core.data.CollectionSettings as Config
import org.json.JSONArray

private val Ink = Color(0xFF101A18)
private val Panel = Color(0xFF1A2824)
private val Mint = Color(0xFFB8E6BE)
private val Muted = Color(0xFFAABAB2)

/** First launch and account settings use this same screen; no manual export/review flow. */
class ResearchExportActivity : ComponentActivity() {
    private var selected by mutableStateOf(emptySet<String>())
    private var notice by mutableStateOf("")
    private var status by mutableStateOf("")
    private var pending: String? = null
    private var waitingUsage = false
    private var first = false
    private val selectedUris = mutableMapOf<String, String>()
    private val changes = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "status") status = Config.prefs(this).getString("status", "").orEmpty()
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val key = pending; pending = null
        if (key != null && notifications() && granted(key)) selected = selected + key
        else notice = "Accesul nu este activat. Poți continua și îl poți schimba ulterior."
    }
    private val photos = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { picked(it, "photos") }
    private val files = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked(it, "files") }
    private val usage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        waitingUsage = false
        if (ResearchUsage.allowed(this)) enable("app_usage")
        else notice = "Accesul la utilizare este oprit."
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        first = !Config.seen(this)
        selected = savedInstanceState?.getStringArrayList("selected")?.toSet() ?: Config.enabled(this)
        for (key in listOf("photos", "files")) selectedUris[key] = savedInstanceState?.getString(key) ?: Config.prefs(this).getString(key, "[]").orEmpty()
        pending = savedInstanceState?.getString("pending")
        waitingUsage = savedInstanceState?.getBoolean("waitingUsage") ?: false
        status = Config.prefs(this).getString("status", "").orEmpty()
        Config.prefs(this).registerOnSharedPreferenceChangeListener(changes)
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Mint, onPrimary = Ink, background = Ink, surface = Panel, onSurface = Color(0xFFF1F5F0), onSurfaceVariant = Muted)) { Screen() } }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("selected", ArrayList(selected))
        selectedUris.forEach { (k, v) -> outState.putString(k, v) }
        outState.putString("pending", pending); outState.putBoolean("waitingUsage", waitingUsage)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { Config.prefs(this).unregisterOnSharedPreferenceChangeListener(changes); super.onDestroy() }
    override fun onResume() {
        super.onResume()
        if (!waitingUsage) {
            selected = selected.filter { granted(it) }.toSet()
            val saved = Config.enabled(this)
            val stillAllowed = saved.filter { granted(it) }.toSet()
            if (saved != stillAllowed) { Config.save(this, stillAllowed); Config.resume(this) }
        }
    }
    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun notifications() = NotificationManagerCompat.from(this).areNotificationsEnabled()
    private fun granted(key: String) = when (key) {
        "location" -> has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)
        "audio" -> has(Manifest.permission.RECORD_AUDIO)
        "app_usage" -> ResearchUsage.allowed(this)
        else -> true
    }
    private fun enable(key: String) {
        if (key == "app_usage" && !ResearchUsage.allowed(this)) {
            waitingUsage = true
            try { usage.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName"))) }
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
        if (!notifications()) { notice = "Activează notificările FORJA în Android pentru a vedea colectarea și butonul Oprește."; return }
        selected = selected + key
    }
    private fun toggle(key: String, on: Boolean) {
        if (!on) {
            selected = selected - key
            // Turning off is immediate, even if the user later leaves without saving.
            Config.save(this, Config.enabled(this) - key)
            Config.resume(this)
            return
        }
        when (key) {
            "photos" -> photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            "files" -> files.launch(arrayOf("*/*"))
            else -> enable(key)
        }
    }
    private fun picked(uris: List<Uri>, kind: String) {
        if (uris.isEmpty()) return
        val other = if (kind == "photos") "files" else "photos"
        val otherCount = if (other in selected) JSONArray(selectedUris[other] ?: "[]").length() else 0
        val keep = uris.take(5 - otherCount)
        if (keep.isEmpty()) { notice = "Poți sincroniza maximum 5 fotografii și fișiere în total."; return }
        val persisted = keep.filter { uri ->
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); true }
            catch (_: SecurityException) { false }
        }
        if (persisted.isEmpty()) { notice = "Selecția nu permite acces persistent. Alege fișiere din memoria telefonului."; return }
        selectedUris[kind] = JSONArray(persisted.map { it.toString() }).toString()
        enable(kind)
    }
    private fun applyChoices() {
        Config.save(this, selected.filter { granted(it) }.toSet())
        Config.prefs(this).edit().apply { selectedUris.forEach { (key, value) -> putString(key, value) } }.apply()
        Config.resume(this)
        finish()
    }
    @Composable private fun Screen() {
        BackHandler { Config.save(this, Config.enabled(this)); finish() }
        Scaffold(containerColor = Ink, bottomBar = {
            Surface(color = Ink) { Button(onClick = ::applyChoices, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp), shape = RoundedCornerShape(16.dp)) {
                Text(if (first) "Continuă în FORJA" else "Salvează setările", modifier = Modifier.padding(8.dp), fontSize = 16.sp)
            } }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(top = 18.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("FORJA", color = Mint, letterSpacing = 4.sp, fontWeight = FontWeight.Bold)
                Text(if (first) "Configurează FORJA" else "Permisiuni și sincronizare", fontSize = 28.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold)
                Text("Alege ce sincronizezi", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("După salvare și conectare, FORJA colectează categoriile activate și le trimite automat în contul tău online, inclusiv în fundal, până le oprești. Nu confirmi fiecare trimitere.", color = Muted, lineHeight = 22.sp)
                Choice("location", "Locație și opriri", "Pozițiile și timpul observat în locuri. Serverul afișează ultimele 300 de poziții; golurile nu sunt considerate timp petrecut acolo.", Icons.Outlined.LocationOn)
                Choice("app_usage", "Activitate în aplicații", "Numele aplicațiilor, timpul în prim-plan și deschiderile, începând cu activarea. Actualizare automată la aproximativ un minut.", Icons.Outlined.Schedule)
                Choice("photos", "Fotografii selectate", "Se trimit automat imaginile pe care le alegi aici. Galeria întreagă nu este accesată.", Icons.Outlined.PhotoLibrary)
                Choice("files", "Fișiere selectate", "Se trimit automat fișierele alese și modificările lor. Maximum 5 fotografii/fișiere în total, fiecare de cel mult 5 MB.", Icons.Outlined.FolderOpen)
                Choice("audio", "Microfon live", "Înregistrează și trimite sunetul din jur cât timp este activ, inclusiv în fundal. Notificarea arată înregistrarea; serverul păstrează ultimele 24 de clipuri de 5 secunde.", Icons.Outlined.Mic)
                Text("Oprești oricând din Profil → Permisiuni și sincronizare sau din notificarea FORJA. Ieșirea din cont oprește colectarea. Android o poate întrerupe; starea apare aici, iar reluarea se face când redeschizi aplicația.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
                Text("Datele trimise sunt vizibile în panoul online cu același cont. Sesiunile expiră după 24 de ore și pot fi șterse de acolo. Dezactivarea oprește datele noi; nu șterge ce a fost deja primit. Pot exista costuri de internet mobil.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
                TextButton(onClick = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.INSIGHTS_URL + "/insights"))) }) { Text("Deschide panoul online ↗") }
                if (status.isNotBlank()) Text(status, color = Mint, fontSize = 13.sp)
                if (notice.isNotBlank()) Text(notice, color = Color(0xFFFFBEA9), fontSize = 13.sp)
                if (Config.enabled(this@ResearchExportActivity).isNotEmpty()) OutlinedButton(onClick = { selected = emptySet(); Config.stop(this@ResearchExportActivity) }, modifier = Modifier.fillMaxWidth()) { Text("Oprește toată colectarea") }
            }
        }
    }
    @Composable private fun Choice(key: String, title: String, detail: String, icon: ImageVector) {
        Surface(color = Panel, shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = Mint, modifier = Modifier.size(24.dp))
                    Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                    Switch(checked = key in selected, onCheckedChange = { toggle(key, it) })
                }
                Text(detail, color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
                if (key in selected && key in setOf("photos", "files")) TextButton(onClick = { toggle(key, true) }) { Text("Schimbă selecția") }
            }
        }
    }
}
