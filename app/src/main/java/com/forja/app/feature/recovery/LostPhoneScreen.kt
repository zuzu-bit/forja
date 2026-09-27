package com.forja.app.feature.recovery

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forja.app.BuildConfig
import com.forja.app.ForjaApp
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*
import com.forja.app.core.recovery.LostPhoneRecovery
import com.forja.app.core.recovery.LostPhoneService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * „Telefonul meu” — găsirea telefonului pierdut din panoul online.
 * Opt-in explicit (bifă + buton), implicit OPRIT. Cât e activată, un serviciu vizibil întreabă panoul
 * la 30 s; GPS-ul pornește doar pentru o comandă dată de tine (5, 15 sau 30 de minute), apoi tace.
 */
@Composable
fun LostPhoneScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = remember { ForjaApp.from(context) }
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current

    val owner = app.auth.currentUid
    var enabled by remember { mutableStateOf(LostPhoneRecovery.enabled(context)) }
    var serviceUp by remember { mutableStateOf(LostPhoneService.running) }
    var searching by remember { mutableStateOf(LostPhoneRecovery.activeCommand(context) != null) }
    var status by remember { mutableStateOf(LostPhoneRecovery.status(context)) }
    var name by remember { mutableStateOf(LostPhoneRecovery.name(context)) }
    var consent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }

    // La deschidere: dacă găsirea e activată, serviciul se reconectează singur.
    LaunchedEffect(Unit) { LostPhoneRecovery.resume(context) }

    // Starea vine din serviciu (SharedPreferences) — o citim la 2 s cât timp ecranul e deschis.
    LaunchedEffect(Unit) {
        while (isActive) {
            enabled = LostPhoneRecovery.enabled(context)
            serviceUp = LostPhoneService.running
            searching = LostPhoneRecovery.activeCommand(context) != null
            status = if (enabled) LostPhoneRecovery.status(context) else ""
            delay(2000)
        }
    }

    fun activate() {
        if (busy) return
        busy = true
        info = ""
        scope.launch {
            try {
                check(owner != null && LostPhoneRecovery.owner(context) == owner) { "Conectează-te în FORJA." }
                check(consent) { "Pune bifa de consimțământ." }
                withTimeout(30_000) { LostPhoneRecovery.activate(context, name) }
                enabled = true
                consent = false
                refresh++
                toast.show("Telefonul e înrolat. Îl găsești din panoul online.")
            } catch (_: TimeoutCancellationException) {
                info = "Activarea nu a fost confirmată de panou. Încearcă din nou."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                info = e.message?.takeIf { it.isNotBlank() } ?: "Nu am putut activa găsirea."
            } finally {
                busy = false
            }
        }
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh++
        if (LostPhoneRecovery.exactPermission(context) && LostPhoneRecovery.notices(context) && consent) activate()
        else info = "Permite locația precisă și notificările pentru a continua."
    }

    fun openPanel() {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.INSIGHTS_URL.trimEnd('/') + "/insights"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            toast.show("Nu am găsit un browser. Deschide manual: ${BuildConfig.INSIGHTS_URL}")
        }
    }

    fun openSettings() {
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            toast.show("Deschide manual din Setări → Aplicații → FORJA.")
        }
    }

    val bgMissing = remember(refresh, enabled) { !LostPhoneRecovery.backgroundPermission(context) }

    Box(Modifier.fillMaxSize().topoBackground(decor = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 40.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Reveal(index = 0) { StampLabel("GĂSIRE") }
                SecondaryButton("Înapoi", onClick = onBack, padV = 8.dp)
            }
            Spacer(Modifier.height(14.dp))
            Reveal(index = 1) { Text("Telefonul meu", style = TitleModule) }
            Spacer(Modifier.height(8.dp))
            Reveal(index = 2) {
                Text(
                    "Dacă îl pierzi, îl cauți din panoul online: telefonul răspunde 5, 15 sau 30 de minute, apoi tace.",
                    style = Body.copy(fontSize = 14.sp, lineHeight = 19.sp)
                )
            }
            Spacer(Modifier.height(18.dp))

            // Starea — un rând, ca pe o listă de efectiv.
            ForjaCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(if (!enabled) TextDim2 else if (searching) EmberHot else if (serviceUp) Positive else Accent2)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (enabled) LostPhoneRecovery.name(context) else name.ifBlank { LostPhoneRecovery.defaultName() }, style = BodyStrong.copy(fontSize = 15.sp))
                        Text(
                            when {
                                !enabled -> "Găsirea e oprită."
                                searching -> "Căutare în curs — poziția pleacă spre panou."
                                serviceUp -> "Găsirea e activată. GPS oprit."
                                else -> "Găsirea e activată, serviciul nu rulează."
                            },
                            style = BodyTiny.copy(color = if (enabled) Accent2 else TextDim)
                        )
                    }
                }
                if (enabled && status.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(status, style = BodySmall.copy(color = TextSecondary))
                }
            }

            Spacer(Modifier.height(14.dp))

            if (!enabled) {
                SectionLabel("Înrolare")
                Spacer(Modifier.height(8.dp))
                ForjaCard(Modifier.fillMaxWidth()) {
                    NameField(name, { name = it.take(60) }, "Numele telefonului")
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth().pressable({ consent = !consent }, scaleDown = 0.99f),
                        verticalAlignment = Alignment.Top
                    ) {
                        ConsentBox(on = consent)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Permit contului meu localizarea din panoul online, cu serviciu în fundal și notificare, până dezactivez găsirea.",
                            style = BodySmall.copy(color = TextSecondary), modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Pleacă de pe telefon doar poziția GPS, precizia și bateria — și doar cât durează o căutare pornită de tine din panou.",
                        style = BodyTiny.copy(color = TextDim)
                    )
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton(
                        if (busy) "Se activează…" else "Activează găsirea",
                        enabled = !busy && consent && owner != null && name.isNotBlank(),
                        onClick = {
                            info = ""
                            permissions.launch(
                                arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION) +
                                    if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (owner == null) {
                        Spacer(Modifier.height(8.dp))
                        Text("Conectează-te în FORJA ca să înrolezi telefonul.", style = BodyTiny.copy(color = EmberHot))
                    }
                }
            } else {
                SectionLabel("Comenzi")
                Spacer(Modifier.height(8.dp))
                PrimaryButton("Deschide panoul online", onClick = { openPanel() }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                if (!serviceUp) {
                    SecondaryButton(
                        "Reconectează telefonul",
                        onClick = {
                            LostPhoneRecovery.resume(context)
                            refresh++
                            toast.show("Serviciul găsirii repornește.")
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                }
                SecondaryButton(
                    "Oprește căutarea curentă",
                    onClick = {
                        if (searching) {
                            LostPhoneRecovery.stopSearch(context)
                            searching = false
                            toast.show("Căutarea s-a oprit. Găsirea rămâne activată.")
                        } else {
                            toast.show("Nicio căutare în curs.")
                        }
                    },
                    textColor = if (searching) TextPrimary else TextDim,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                SecondaryButton(
                    "Dezactivează găsirea",
                    onClick = {
                        LostPhoneRecovery.disable(context)
                        enabled = false
                        searching = false
                        consent = false
                        status = ""
                        refresh++
                        toast.show("Găsirea e oprită. Telefonul nu mai răspunde panoului.")
                    },
                    textColor = LogoutText,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (info.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(info, style = BodySmall.copy(color = EmberHot))
            }

            Spacer(Modifier.height(18.dp))
            SectionLabel("Condiții de teren")
            Spacer(Modifier.height(8.dp))
            ForjaCard(Modifier.fillMaxWidth(), stroke = StrokeCard) {
                Text("Telefonul trebuie să aibă internet și locația Android pornită.", style = BodySmall.copy(color = TextSecondary))
                if (bgMissing) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Pentru reconectare după restart: locația „Tot timpul” din setările Android.",
                        style = BodySmall.copy(color = EmberHot)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Serverul păstrează doar amprenta secretului (SHA-256); poziția dispare după 24 h.",
                    style = BodyTiny.copy(color = TextDim)
                )
                Spacer(Modifier.height(10.dp))
                SecondaryButton("Deschide setările Android", onClick = { openSettings() }, modifier = Modifier.fillMaxWidth())
            }

            Spacer(Modifier.height(22.dp))
            Text(
                "„Nu pierzi nimic cât timp știi unde să cauți.”",
                style = TitleModule.copy(fontSize = 17.sp, lineHeight = 23.sp, color = TextSecondary)
            )
        }
    }
}

/** Câmp de text pe tokenii FORJA (etichetă mono + fundal Surface2, fără linii Material). */
@Composable
private fun NameField(value: String, onValue: (String) -> Unit, label: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(label.uppercase(), style = monoLabel(9, 0.14f))
        Spacer(Modifier.height(6.dp))
        TextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            textStyle = BodyStrong.copy(fontSize = 15.sp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(SecondaryShape)
                .border(1.dp, StrokeCardStrong, SecondaryShape),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Surface2,
                unfocusedContainerColor = Surface2,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = Accent2,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            )
        )
    }
}

/** Căsuța de consimțământ: pătrat 22dp, colțuri 4dp; plină cu bifă când e pusă. */
@Composable
private fun ConsentBox(on: Boolean) {
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
