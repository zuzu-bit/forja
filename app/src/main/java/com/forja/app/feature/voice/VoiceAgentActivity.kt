package com.forja.app.feature.voice

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.forja.app.core.designsystem.ForjaTheme
import com.forja.app.core.voice.VoiceAgentRuntime
import com.forja.app.core.voice.VoiceAgentService
import com.forja.app.core.voice.VoiceAgentSettings
import com.forja.app.core.voice.VoiceTelemetry
import com.forja.app.core.voice.ui.ForjaVoiceAccessibilityService
import com.forja.app.core.voice.ui.VoiceUiConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VoiceAgentActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ForjaTheme { VoiceAgentScreen(onBack = ::finish) } }
    }
}

@Composable
private fun VoiceAgentScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val state by VoiceAgentRuntime.state.collectAsState()
    val connected by VoiceUiConnection.connected.collectAsState()
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var autoListen by remember { mutableStateOf(VoiceAgentSettings.isAutoListenEnabled(context)) }
    var microphone by remember { mutableStateOf(hasMicrophone(context)) }
    var localMessage by remember { mutableStateOf("") }
    var pendingStart by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    fun startAgent() {
        if (!connected) {
            localMessage = "Activează întâi serviciul FORJA · Control vocal în Accesibilitate."
            return
        }
        localMessage = ""
        VoiceAgentService.start(context)
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (pendingStart) {
            pendingStart = false
            startAgent()
        }
    }
    fun startWithNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingStart = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startAgent()
    }
    val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        microphone = granted
        if (granted) startWithNotifications()
        else localMessage = "Microfonul este necesar pentru comenzile vocale. Îl poți permite din setările Android ale FORJA."
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                microphone = hasMicrophone(context)
                autoListen = VoiceAgentSettings.isAutoListenEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TextButton(onClick = onBack) { Text("Înapoi") }
            Text("Control vocal", style = MaterialTheme.typography.headlineMedium)
            Text("Deblochezi telefonul cu amprenta, spui ce vrei, FORJA execută în YouTube.")
            Text("Comandă: «FORJA, deschide YouTube și caută documentarul Planeta Pământ». FORJA caută și deschide videoclipul care corespunde titlului.")

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Activare", style = MaterialTheme.typography.titleMedium)
                    Text("Permite microfonul și activează voluntar FORJA · Control vocal în Accesibilitate. În timpul unei comenzi, FORJA citește elementele YouTube și apasă sau scrie în locul tău. Poți opri oricând.")
                    Text("Accesibilitate: ${if (connected) "conectată" else "neconectată"} · Microfon: ${if (microphone) "permis" else "nepermis"}")
                    OutlinedButton(onClick = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).putExtra(
                            "android.provider.extra.EXTRA_ACCESSIBILITY_COMPONENT_NAME",
                            ComponentName(context, ForjaVoiceAccessibilityService::class.java).flattenToString()
                        )
                        runCatching { context.startActivity(intent) }.onFailure {
                            localMessage = "Deschide Setări Android → Accesibilitate → FORJA · Control vocal."
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Deschide setările de Accesibilitate") }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Ascultă după deblocarea telefonului", modifier = Modifier.weight(1f))
                        Switch(checked = autoListen,
                            modifier = Modifier.semantics { contentDescription = "Ascultă după deblocarea telefonului" },
                            onCheckedChange = {
                            autoListen = it
                            VoiceAgentSettings.setAutoListenEnabled(context, it)
                        })
                    }
                    Text("Pornește agentul o dată din acest ecran. Cât timp notificarea lui este activă, după deblocare îți cere o comandă. Android verifică amprenta; FORJA primește doar faptul că telefonul este deblocat. După repornirea telefonului sau oprirea agentului, pornește-l din nou.", style = MaterialTheme.typography.bodySmall)
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Stare", style = MaterialTheme.typography.titleMedium)
                    Text(
                        state.message,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                    if (localMessage.isNotBlank()) Text(localMessage,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    if (state.transcript.isNotBlank()) Text("Ai spus: ${state.transcript}")
                    Button(onClick = {
                        localMessage = ""
                        if (state.running) VoiceAgentService.listen(context)
                        else if (!microphone) audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                        else startWithNotifications()
                    }, enabled = connected && !state.busy && !state.listening, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.running) "Ascultă o comandă" else "Pornește și ascultă")
                    }
                    OutlinedButton(onClick = { VoiceAgentService.cancel(context) }, enabled = state.busy || state.listening, modifier = Modifier.fillMaxWidth()) {
                        Text("Anulează comanda")
                    }
                    Text("Pentru anulare vocală spune «FORJA, oprește» sau «FORJA, anulează». Ai și buton de anulare peste YouTube și în notificare.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { VoiceAgentService.stop(context) }, enabled = state.running, modifier = Modifier.fillMaxWidth()) {
                        Text("Oprește agentul")
                    }
                }
            }

            Text("YouTube trebuie să fie instalat. Folosește un titlu suficient de precis. Dacă rezultatul nu este clar sau apare o autentificare, FORJA se oprește și îți explică motivul. Permisiunile, deblocarea, autentificările și plățile se confirmă de tine.", style = MaterialTheme.typography.bodySmall)
            Text("Recunoașterea vocală folosește serviciul Android instalat și poate necesita internet. FORJA nu păstrează înregistrări audio sau capturi de ecran. Jurnalul etapelor rămâne local și include orele și motivele eșecurilor.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                scope.launch {
                    runCatching {
                        val file = withContext(Dispatchers.IO) { VoiceTelemetry.exportFile(context) }
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.voice.files", file)
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "application/x-ndjson"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = android.content.ClipData.newUri(context.contentResolver, "Telemetrie FORJA", uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(share, "Exportă jurnalul vocal"))
                    }.onFailure { localMessage = "Nu am putut exporta jurnalul: ${it.localizedMessage ?: "eroare de stocare"}." }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Exportă telemetria") }
            TextButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) { Text("Șterge jurnalul local") }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Ștergi jurnalul vocal?") },
        text = { Text("Etapele și motivele eșecurilor salvate pe telefon vor fi șterse.") },
        confirmButton = { TextButton(onClick = {
            confirmClear = false
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { VoiceTelemetry.clear(context) } }
                    .onSuccess { localMessage = "Jurnal șters." }
                    .onFailure { localMessage = "Nu am putut șterge jurnalul local." }
            }
        }) { Text("Șterge") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Păstrează") } }
    )
}

private fun hasMicrophone(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
