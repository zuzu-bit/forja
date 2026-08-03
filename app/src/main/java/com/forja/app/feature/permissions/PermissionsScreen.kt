package com.forja.app.feature.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*

/** Un singur loc pentru toate permisiunile — pitch + activare, ca să nu mai bată la cap nicăieri. */
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val toast = LocalToast.current

    var refresh by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val batchLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh++
    }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    fun normalPerms(): Array<String> {
        val l = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= 33) {
            l.add(Manifest.permission.POST_NOTIFICATIONS)
            l.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            l.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return l.toTypedArray()
    }

    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    val normalAllOn = remember(refresh) { normalPerms().all { granted(it) } }

    Column(
        Modifier
            .fillMaxSize()
            .background(Surface0)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(bottom = 40.dp)
    ) {
        // Pitch
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color(0x33FF7A00), Color.Transparent))
                )
                .padding(20.dp)
        ) {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Pornire FORJA", style = TitleModule)
                    SecondaryButton("Înapoi", onClick = onBack, padV = 8.dp)
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "Obosit să juggling 5 aplicații ca să-ți ții viața la un loc?",
                    style = TitleModule.copy(fontSize = 22.sp, lineHeight = 27.sp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Antrenament, alergare, mese mai bune, somn mai bun, motivația ta și a prietenilor, chiar și obiceiurile nesănătoase de telefon — FORJA le are pe toate, într-un singur loc. Pornește-le o dată, aici, și gata cu întrebările prin aplicație.",
                    style = Body.copy(fontSize = 15.sp, lineHeight = 21.sp)
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Cele obișnuite — dintr-o singură apăsare
        SectionLabel("Cele obișnuite · o singură apăsare", Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(8.dp))
        ForjaCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(
                "Cameră, locație, notificări, microfon, galerie — apar pe rând și le accepți din mers.",
                style = BodySmall.copy(color = TextSecondary)
            )
            Spacer(Modifier.height(12.dp))
            if (normalAllOn) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Check, null, tint = Positive, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Toate active ✓", style = BodyStrong.copy(color = Positive))
                }
            } else {
                PrimaryButton(
                    "Activează-le pe toate",
                    onClick = { batchLauncher.launch(normalPerms()) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // Cele speciale — fiecare prin setările Android (Android nu lasă altfel)
        SectionLabel("Cele speciale · un tap fiecare", Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            "Astea deschid setările Android — nicio aplicație nu le poate porni singură (nici Forest). E protecția telefonului tău.",
            style = BodyTiny.copy(color = TextDim),
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(Modifier.height(10.dp))

        val bgOn = remember(refresh) {
            Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
        val usageOn = remember(refresh) { com.forja.app.core.focus.FocusMonitorService.hasUsageAccess(context) }
        val overlayOn = remember(refresh) { Settings.canDrawOverlays(context) }
        val guardOn = remember(refresh) { com.forja.app.core.detox.ForjaGuardService.isEnabled(context) }

        Column(Modifier.padding(horizontal = 20.dp)) {
            SpecialRow("Locație în fundal", "Prietenii te văd pe hartă și când FORJA e închisă.", bgOn) {
                if (Build.VERSION.SDK_INT >= 29) bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                else toast.show("Nu e nevoie pe versiunea ta de Android.")
            }
            SpecialRow("Acces la utilizare", "Rapoartele de ecran și blocarea din Focus.", usageOn) {
                openSafe(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS), toast)
            }
            SpecialRow("Afișare peste alte aplicații", "Ecranul de blocare peste aplicația oprită (ca Forest).", overlayOn) {
                openSafe(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")), toast)
            }
            SpecialRow("Accesibilitate (Detox)", "Paznicul care te ajută să reziști tentației alese.", guardOn) {
                openSafe(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), toast)
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "Tot ce e sensibil rămâne pe telefonul tău. FORJA nu citește mesajele, parolele sau conținutul ecranului.",
            style = BodyTiny.copy(color = TextDim2),
            modifier = Modifier.padding(horizontal = 20.dp)
        )
    }
}

private fun openSafe(context: Context, intent: Intent, toast: ToastState) {
    try { context.startActivity(intent) } catch (_: Exception) { toast.show("Deschide manual din Setări → Aplicații → FORJA.") }
}

@Composable
private fun SpecialRow(title: String, subtitle: String, on: Boolean, onActivate: () -> Unit) {
    ForjaCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = BodyStrong.copy(fontSize = 14.sp))
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = BodyTiny.copy(color = TextSecondary))
            }
            Spacer(Modifier.width(12.dp))
            if (on) {
                Box(
                    Modifier.size(26.dp).clip(CircleShape).background(Positive),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(15.dp)) }
            } else {
                Text(
                    "Activează",
                    style = BodySmall.copy(color = Accent2),
                    modifier = Modifier
                        .clip(ChipShape)
                        .background(TabPillActive)
                        .pressable(onActivate)
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                )
            }
        }
    }
}
