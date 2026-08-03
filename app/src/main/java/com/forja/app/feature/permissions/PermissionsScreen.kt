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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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

/** „Pornire FORJA" — cald, cu ghid, un singur buton pentru esențial + panou cu bifare. */
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val toast = LocalToast.current

    var refresh by remember { mutableStateOf(0) }
    var showDetoxHelp by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    fun essentials(): Array<String> {
        val l = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= 33) {
            l.add(Manifest.permission.POST_NOTIFICATIONS)
            l.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else l.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        return l.toTypedArray()
    }

    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val batchLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh++
        // După esențial, cerem și locația în fundal (Android o cere separat).
        if (Build.VERSION.SDK_INT >= 29 &&
            granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    val essentialsOn = remember(refresh) { essentials().all { granted(it) } }
    val bgOn = remember(refresh) {
        Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }
    val usageOn = remember(refresh) { com.forja.app.core.focus.FocusMonitorService.hasUsageAccess(context) }
    val overlayOn = remember(refresh) { Settings.canDrawOverlays(context) }
    val guardOn = remember(refresh) { com.forja.app.core.detox.ForjaGuardService.isEnabled(context) }
    val doneCount = listOf(essentialsOn, bgOn, usageOn, overlayOn, guardOn).count { it }

    val guideVideo = remember { com.forja.app.core.media.Media.mediaUrl("guide.mp4") ?: "" }
    val guidePoster = remember { com.forja.app.core.media.Media.mediaUrl("guide.jpg") }

    Box(Modifier.fillMaxSize().background(Surface0)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 30.dp)
        ) {
            // Ghidul — te întâmpină
            Box(Modifier.fillMaxWidth().height(360.dp)) {
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
                    Text("BINE AI VENIT LA FORJA", style = monoLabel(10, 0.16f).copy(color = Accent2))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Obosit să juggling 5 aplicații ca să-ți ții viața la un loc?",
                        style = TitleModule.copy(fontSize = 24.sp, lineHeight = 28.sp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Antrenament, alergare, mese mai bune, somn mai bun — și motivația ta și-a prietenilor. FORJA le are pe toate. Hai să le pornim, o dată, aici.",
                        style = Body.copy(fontSize = 14.sp, lineHeight = 19.sp)
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            // Un singur panou, cu totul
            Column(Modifier.padding(horizontal = 20.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Pornește FORJA")
                    Text("$doneCount din 5 gata", style = monoLabel(9, 0.12f).copy(color = if (doneCount == 5) Positive else Accent2))
                }
                Spacer(Modifier.height(10.dp))

                Column(
                    Modifier.fillMaxWidth().clip(CardShape).background(Surface1).border(1.dp, StrokeCard, CardShape).padding(6.dp)
                ) {
                    // Butonul mare: tot esențialul dintr-o apăsare
                    PermRow(
                        icon = "✨", title = "Tot esențialul", sub = "cameră, locație, microfon, notificări, galerie",
                        on = essentialsOn && bgOn,
                        onActivate = { batchLauncher.launch(essentials()) }
                    )
                    Divider()
                    PermRow("👁", "Acces la utilizare", "rapoartele de ecran + blocarea din Focus", usageOn) {
                        openSafe(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS), toast)
                    }
                    Divider()
                    PermRow("🪟", "Afișare peste aplicații", "ecranul de blocare, ca la Forest", overlayOn) {
                        openSafe(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")), toast)
                    }
                    Divider()
                    PermRow(
                        "🛡", "Paznicul Detox", "te ajută să reziști tentației alese", guardOn,
                        onHelp = { showDetoxHelp = true }
                    ) {
                        openSafe(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), toast)
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    "Cele speciale (ultimele 3) se deschid în setările Android — nicio aplicație nu le poate porni singură, nici Forest. E protecția telefonului tău. Le poți lăsa și pe mai târziu.",
                    style = BodyTiny.copy(color = TextDim)
                )
                Spacer(Modifier.height(20.dp))
                PrimaryButton(
                    if (doneCount == 5) "Gata — hai în FORJA" else "Continuă în FORJA",
                    onClick = onBack, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Tot ce e sensibil rămâne pe telefonul tău. FORJA nu citește mesajele, parolele sau conținutul ecranului.",
                    style = BodyTiny.copy(color = TextDim2),
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }

        if (showDetoxHelp) {
            DetoxHelpDialog(
                onClose = { showDetoxHelp = false },
                onOpenSettings = { openAppSettings(context, toast) }
            )
        }
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x0DFFFFFF)))
}

@Composable
private fun PermRow(
    icon: String,
    title: String,
    sub: String,
    on: Boolean,
    onHelp: (() -> Unit)? = null,
    onActivate: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(if (on) Color(0x1F2FBE71) else Surface2),
            contentAlignment = Alignment.Center
        ) { Text(icon, fontSize = 18.sp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = BodyStrong.copy(fontSize = 14.sp))
            Text(sub, style = BodyTiny.copy(color = TextSecondary))
        }
        Spacer(Modifier.width(10.dp))
        // „!" mic de ajutor — apare doar dacă permisiunea încă nu e pornită
        if (!on && onHelp != null) {
            Box(
                Modifier.size(26.dp).clip(CircleShape)
                    .background(Color(0x1FFFB300)).border(1.dp, Color(0x4DFFB300), CircleShape)
                    .pressable(onHelp),
                contentAlignment = Alignment.Center
            ) { Text("!", style = BodyStrong.copy(color = Accent2, fontSize = 15.sp)) }
            Spacer(Modifier.width(8.dp))
        }
        if (on) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(Positive),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
        } else {
            Box(
                Modifier.clip(RoundedCornerShape(10.dp)).background(AccentGradient).pressable(onActivate)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) { Text("Permite", style = ButtonTextSmall) }
        }
    }
}

/** Panou cald care explică cei 2 pași de deblocare a Paznicului (setare restricționată Android). */
@Composable
private fun DetoxHelpDialog(onClose: () -> Unit, onOpenSettings: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0xCC060607))
            .clickable(remember { MutableInteractionSource() }, null) { onClose() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .padding(24.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(CardShape)
                .background(Surface1)
                .border(1.dp, StrokeCardStrong, CardShape)
                .clickable(remember { MutableInteractionSource() }, null) {}
                .verticalScroll(rememberScrollState())
                .padding(22.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape)
                        .background(Color(0x1FFFB300)).border(1.dp, Color(0x4DFFB300), CircleShape),
                    contentAlignment = Alignment.Center
                ) { Text("!", style = TitleModule.copy(color = Accent2, fontSize = 24.sp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("PAZNICUL DETOX", style = monoLabel(9, 0.14f).copy(color = Accent2))
                    Spacer(Modifier.height(2.dp))
                    Text("Nu merge comutatorul?", style = TitleModule.copy(fontSize = 20.sp, lineHeight = 24.sp))
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "Fiindcă ai instalat FORJA dintr-un fișier (nu din Play Store), Android blochează la început această permisiune, ca măsură de siguranță. E normal — o deblochezi în 2 pași:",
                style = Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = TextSecondary)
            )
            Spacer(Modifier.height(6.dp))
            StepRow(
                "1", "Deblochează setările restricționate",
                "În Setări → Aplicații → FORJA, apasă meniul ⋮ din dreapta sus și alege „Allow restricted settings”. Dacă îți cere codul telefonului, introdu-l."
            ) {
                SecondaryButton("Deschide setările FORJA", onClick = onOpenSettings, modifier = Modifier.fillMaxWidth())
            }
            Divider()
            StepRow(
                "2", "Pornește Paznicul",
                "Revii aici, în Accesibilitate → „FORJA · Detox de adicție”, iar acum comutatorul se activează normal."
            )
            Spacer(Modifier.height(18.dp))
            PrimaryButton("Am înțeles", onClick = onClose, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun StepRow(n: String, title: String, body: String, content: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(AccentGradient),
            contentAlignment = Alignment.Center
        ) { Text(n, style = BodyStrong.copy(color = Color(0xFF141008), fontSize = 14.sp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = BodyStrong.copy(fontSize = 14.sp))
            Spacer(Modifier.height(3.dp))
            Text(body, style = BodyTiny.copy(color = TextSecondary, lineHeight = 18.sp))
            if (content != null) {
                Spacer(Modifier.height(10.dp))
                content()
            }
        }
    }
}

private fun openSafe(context: Context, intent: Intent, toast: ToastState) {
    try { context.startActivity(intent) } catch (_: Exception) { toast.show("Deschide manual din Setări → Aplicații → FORJA.") }
}

private fun openAppSettings(context: Context, toast: ToastState) {
    val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    openSafe(context, i, toast)
}
