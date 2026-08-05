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
    val doneCount = listOf(essentialsOn && bgOn, usageOn, overlayOn).count { it }

    val guideVideo = remember { com.forja.app.core.media.Media.mediaUrl("guide.mp4") ?: "" }
    val guidePoster = remember { com.forja.app.core.media.Media.mediaUrl("guide.jpg") }

    Box(Modifier.fillMaxSize().topoBackground(decor = false)) {
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
                    Text("$doneCount din 3 gata", style = monoLabel(9, 0.12f).copy(color = if (doneCount == 3) Positive else Accent2))
                }
                Spacer(Modifier.height(10.dp))

                Column(
                    Modifier.fillMaxWidth().clip(CardShape).background(Surface1).border(1.dp, StrokeCard, CardShape).padding(6.dp)
                ) {
                    // Butonul mare: tot esențialul dintr-o apăsare
                    PermRow(
                        icon = "✨", title = "Tot esențialul",
                        sub = "scanezi mâncarea, prinzi sforăitul, cureți galeria, alergi pe hartă",
                        on = essentialsOn && bgOn,
                        helpTitle = "Pornește FORJA",
                        helpText = "Ca să devină verde, apasă „Permite” și acceptă tot ce cere Android. Așa deblochezi în aplicație Detoxul și scannerul de mâncare.",
                        onActivate = { batchLauncher.launch(essentials()) }
                    )
                    Divider()
                    PermRow("👁", "Acces la utilizare", "pentru rapoartele de folosire a ecranului", usageOn) {
                        openSafe(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS), toast)
                    }
                    Divider()
                    PermRow("🪟", "Afișare peste aplicații", "pentru Focus și ecranul de Detox", overlayOn) {
                        openSafe(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")), toast)
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    "Tot ce e sensibil rămâne pe telefonul tău. FORJA nu citește mesajele, parolele sau conținutul ecranului.",
                    style = TitleModule.copy(fontSize = 17.sp, lineHeight = 23.sp)
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Ultimele două se deschid în setările Android — nicio aplicație nu le poate porni singură.",
                        style = BodyTiny.copy(color = TextDim), modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    InfoDot(
                        title = "De ce se deschid Setările?",
                        text = "Acces la utilizare și Afișare peste aplicații sunt permisiuni speciale Android. Din motive de siguranță, doar tu le poți porni din Setări — nicio aplicație nu le poate activa singură. Le poți lăsa și pe mai târziu."
                    )
                }
                Spacer(Modifier.height(20.dp))
                PrimaryButton(
                    if (doneCount == 3) "Gata — hai în FORJA" else "Continuă în FORJA",
                    onClick = onBack, modifier = Modifier.fillMaxWidth()
                )
            }
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
    helpTitle: String? = null,
    helpText: String? = null,
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
        // Ajutor: „!" discret (InfoDot) sau butonul special (onHelp) — doar cât permisiunea nu e pornită
        if (!on && helpText != null) {
            InfoDot(title = helpTitle, text = helpText, size = 26)
            Spacer(Modifier.width(8.dp))
        } else if (!on && onHelp != null) {
            Box(
                Modifier.size(26.dp).clip(CircleShape)
                    .background(Color(0x1F6F855A)).border(1.dp, Color(0x4D6F855A), CircleShape)
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

private fun openSafe(context: Context, intent: Intent, toast: ToastState) {
    try { context.startActivity(intent) } catch (_: Exception) { toast.show("Deschide manual din Setări → Aplicații → FORJA.") }
}
