package com.forja.app.feature.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.forja.app.core.designsystem.Accent
import com.forja.app.core.designsystem.Accent2
import com.forja.app.core.designsystem.Body
import com.forja.app.core.designsystem.BodySmall
import com.forja.app.core.designsystem.EmberHot
import com.forja.app.core.designsystem.Positive
import com.forja.app.core.designsystem.Surface0
import com.forja.app.core.designsystem.Surface1
import com.forja.app.core.designsystem.Surface2
import com.forja.app.core.designsystem.TextDim
import com.forja.app.core.designsystem.TextPrimary
import com.forja.app.core.designsystem.TextSecondary
import com.forja.app.core.designsystem.TitleModule
import com.forja.app.core.designsystem.monoLabel
import com.forja.app.core.designsystem.components.Mascot
import com.forja.app.core.designsystem.components.MascotAvatar
import com.forja.app.core.designsystem.components.MascotState
import com.forja.app.core.designsystem.components.PopIn
import com.forja.app.core.designsystem.components.PrimaryButton
import com.forja.app.core.designsystem.components.ProgressBar
import com.forja.app.core.designsystem.components.PulseGlow
import com.forja.app.core.designsystem.components.Reveal
import com.forja.app.core.designsystem.components.SecondaryButton
import com.forja.app.core.detox.ForjaGuardService
import kotlinx.coroutines.delay

// ════════════════════════════════════════════════════════════════
//  Enumerări
// ════════════════════════════════════════════════════════════════

enum class SetupStage(val index: Int) {
    NOTIFICATIONS(0), LOCATION(1), ACCESSIBILITY(2), MICROPHONE(3),
    PHOTOS(4), FILES(5), BATTERY(6), SOCIAL(7), COMPLETE(8)
}

enum class SetupPhase { INTRO, DEMO, TUTORIAL, VERIFY, SUCCESS }

private fun SetupStage.next(): SetupStage =
    if (this == SetupStage.SOCIAL) SetupStage.COMPLETE else SetupStage.entries[this.ordinal + 1]

// ════════════════════════════════════════════════════════════════
//  Verificare permisiuni
// ════════════════════════════════════════════════════════════════

private fun isGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun isStageGranted(context: Context, stage: SetupStage): Boolean = when (stage) {
    SetupStage.NOTIFICATIONS ->
        Build.VERSION.SDK_INT < 33 || isGranted(context, Manifest.permission.POST_NOTIFICATIONS)
    SetupStage.LOCATION ->
        isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)
    SetupStage.ACCESSIBILITY ->
        ForjaGuardService.isEnabled(context)
    SetupStage.MICROPHONE ->
        isGranted(context, Manifest.permission.RECORD_AUDIO)
    SetupStage.PHOTOS ->
        if (Build.VERSION.SDK_INT >= 33) isGranted(context, Manifest.permission.READ_MEDIA_IMAGES)
        else isGranted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
    SetupStage.FILES ->
        Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()
    SetupStage.BATTERY -> {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    }
    SetupStage.SOCIAL ->
        isGranted(context, Manifest.permission.READ_CONTACTS)
    SetupStage.COMPLETE -> true
}

// ════════════════════════════════════════════════════════════════
//  Ecran principal
// ════════════════════════════════════════════════════════════════

@Composable
fun SetupOnboardingScreen(onFinished: () -> Unit) {
    val context = LocalContext.current

    var stage by remember { mutableStateOf(SetupStage.NOTIFICATIONS) }
    var phase by remember { mutableStateOf(SetupPhase.INTRO) }
    var verifyOk by remember { mutableStateOf(false) }

    fun onPermissionReturned(current: SetupStage) {
        val ok = isStageGranted(context, current)
        verifyOk = ok
        if (ok) phase = SetupPhase.SUCCESS
    }

    // ── Lânșatoare ──
    val single = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        onPermissionReturned(stage)
    }
    val multi = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        // Locație: după „precisă", cere „în fundal" separat.
        if (stage == SetupStage.LOCATION &&
            results[Manifest.permission.ACCESS_FINE_LOCATION] == true &&
            Build.VERSION.SDK_INT >= 29 &&
            !isGranted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        ) {
            single.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            return@rememberLauncherForActivityResult
        }
        onPermissionReturned(stage)
    }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onPermissionReturned(stage)
    }

    fun launchPermission() {
        phase = SetupPhase.VERIFY
        verifyOk = false
        when (stage) {
            SetupStage.NOTIFICATIONS ->
                if (Build.VERSION.SDK_INT >= 33) single.launch(Manifest.permission.POST_NOTIFICATIONS)
                else phase = SetupPhase.SUCCESS
            SetupStage.LOCATION ->
                multi.launch(arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ))
            SetupStage.ACCESSIBILITY ->
                settings.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            SetupStage.MICROPHONE ->
                single.launch(Manifest.permission.RECORD_AUDIO)
            SetupStage.PHOTOS -> when {
                Build.VERSION.SDK_INT >= 34 ->
                    multi.launch(arrayOf(
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                    ))
                Build.VERSION.SDK_INT >= 33 ->
                    single.launch(Manifest.permission.READ_MEDIA_IMAGES)
                else ->
                    single.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            SetupStage.FILES ->
                settings.launch(Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ))
            SetupStage.BATTERY ->
                settings.launch(Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")
                ))
            SetupStage.SOCIAL ->
                single.launch(Manifest.permission.READ_CONTACTS)
            SetupStage.COMPLETE -> {}
        }
    }

    fun advancePhase() {
        when (phase) {
            SetupPhase.INTRO -> phase = SetupPhase.DEMO
            SetupPhase.DEMO -> phase = SetupPhase.TUTORIAL
            else -> {}
        }
    }

    fun skipStage() {
        stage = stage.next()
        phase = SetupPhase.INTRO
        verifyOk = false
    }

    // Auto-progresie din SUCCESS
    LaunchedEffect(phase, stage) {
        if (phase == SetupPhase.SUCCESS) {
            delay(2000)
            skipStage()
        }
    }

    // ── UI ──
    Box(Modifier.fillMaxSize().background(Surface0)) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (stage == SetupStage.COMPLETE) {
                FinalSummary(onFinished)
            } else {
                // Bara de progres
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        SetupCopy.STAGE_LABEL.format(stage.index + 1, SetupCopy.TOTAL_STAGES),
                        style = monoLabel(10, 0.12f)
                    )
                    Text(
                        "${(stage.index + 1) * 100 / SetupCopy.TOTAL_STAGES}%",
                        style = monoLabel(10, 0.12f).copy(color = Accent2)
                    )
                }
                Spacer(Modifier.height(8.dp))
                ProgressBar(progress = (stage.index).toFloat() / SetupCopy.TOTAL_STAGES)
                Spacer(Modifier.height(20.dp))

                // Conținut
                when (phase) {
                    SetupPhase.INTRO -> StageIntro(stage) { advancePhase() }
                    SetupPhase.DEMO -> StageDemo(stage) { advancePhase() }
                    SetupPhase.TUTORIAL -> StageTutorial(stage, onContinue = { launchPermission() }, onSkip = { skipStage() })
                    SetupPhase.VERIFY -> StageVerify(stage, ok = verifyOk, onRetry = { launchPermission() }, onSkip = { skipStage() })
                    SetupPhase.SUCCESS -> StageSuccess(stage)
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════
//  Faze comune
// ════════════════════════════════════════════════════════════════

@Composable
private fun stageMascotState(stage: SetupStage): MascotState = when (stage) {
    SetupStage.NOTIFICATIONS -> MascotState.Talking
    SetupStage.LOCATION -> MascotState.Happy
    SetupStage.ACCESSIBILITY -> MascotState.Angry
    SetupStage.MICROPHONE -> MascotState.Thinking
    SetupStage.PHOTOS -> MascotState.Wink
    SetupStage.FILES -> MascotState.Thinking
    SetupStage.BATTERY -> MascotState.Talking
    SetupStage.SOCIAL -> MascotState.Happy
    SetupStage.COMPLETE -> MascotState.Happy
}

@Composable
private fun StageIntro(stage: SetupStage, onContinue: () -> Unit) {
    val title = when (stage) {
        SetupStage.NOTIFICATIONS -> SetupCopy.Notifications.TITLE
        SetupStage.LOCATION -> SetupCopy.Location.TITLE
        SetupStage.ACCESSIBILITY -> SetupCopy.Accessibility.TITLE
        SetupStage.MICROPHONE -> SetupCopy.Microphone.TITLE
        SetupStage.PHOTOS -> SetupCopy.Photos.TITLE
        SetupStage.FILES -> SetupCopy.Files.TITLE
        SetupStage.BATTERY -> SetupCopy.Battery.TITLE
        SetupStage.SOCIAL -> SetupCopy.Social.TITLE
        SetupStage.COMPLETE -> ""
    }
    val mascotMsg = when (stage) {
        SetupStage.NOTIFICATIONS -> SetupCopy.Notifications.MASCOT
        SetupStage.LOCATION -> SetupCopy.Location.MASCOT
        SetupStage.ACCESSIBILITY -> SetupCopy.Accessibility.MASCOT
        SetupStage.MICROPHONE -> SetupCopy.Microphone.MASCOT
        SetupStage.PHOTOS -> SetupCopy.Photos.MASCOT
        SetupStage.FILES -> SetupCopy.Files.MASCOT
        SetupStage.BATTERY -> SetupCopy.Battery.MASCOT
        SetupStage.SOCIAL -> SetupCopy.Social.MASCOT
        SetupStage.COMPLETE -> ""
    }
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Reveal(index = 0) {
            PulseGlow(radius = 80.dp) {
                Mascot(
                    state = stageMascotState(stage),
                    size = 100.dp
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Reveal(index = 1) {
            Text(title, style = TitleModule, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(12.dp))
        Reveal(index = 2) {
            Text(mascotMsg, style = Body.copy(color = TextSecondary), modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(32.dp))
        Reveal(index = 3) {
            PrimaryButton(SetupCopy.CONTINUE, onClick = onContinue, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun StageDemo(stage: SetupStage, onContinue: () -> Unit) {
    val caption = when (stage) {
        SetupStage.NOTIFICATIONS -> SetupCopy.Notifications.DEMO_CAPTION
        SetupStage.LOCATION -> SetupCopy.Location.DEMO_CAPTION
        SetupStage.ACCESSIBILITY -> SetupCopy.Accessibility.DEMO_CAPTION
        SetupStage.MICROPHONE -> SetupCopy.Microphone.DEMO_CAPTION
        SetupStage.PHOTOS -> SetupCopy.Photos.DEMO_CAPTION
        SetupStage.FILES -> SetupCopy.Files.DEMO_CAPTION
        SetupStage.BATTERY -> SetupCopy.Battery.DEMO_CAPTION
        SetupStage.SOCIAL -> SetupCopy.Social.DEMO_CAPTION
        SetupStage.COMPLETE -> ""
    }
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Demo animată
        when (stage) {
            SetupStage.NOTIFICATIONS -> NotificationDemo()
            SetupStage.LOCATION -> CityMapDemo()
            SetupStage.ACCESSIBILITY -> DetoxDemo()
            SetupStage.MICROPHONE -> SleepDemo()
            SetupStage.PHOTOS -> JournalDemo()
            SetupStage.FILES -> FolderDemo()
            SetupStage.BATTERY -> BatteryDemo()
            SetupStage.SOCIAL -> SocialDemo()
            SetupStage.COMPLETE -> {}
        }
        Spacer(Modifier.height(20.dp))
        Text(caption, style = BodySmall.copy(color = TextDim), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(20.dp))
        PrimaryButton(SetupCopy.GOT_IT, onClick = onContinue, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun StageTutorial(stage: SetupStage, onContinue: () -> Unit, onSkip: () -> Unit) {
    val permission = when (stage) {
        SetupStage.NOTIFICATIONS -> SetupCopy.Notifications.TUTORIAL_PERMISSION
        SetupStage.LOCATION -> SetupCopy.Location.TUTORIAL_PERMISSION
        SetupStage.ACCESSIBILITY -> SetupCopy.Accessibility.TUTORIAL_PERMISSION
        SetupStage.MICROPHONE -> SetupCopy.Microphone.TUTORIAL_PERMISSION
        SetupStage.PHOTOS -> SetupCopy.Photos.TUTORIAL_PERMISSION
        SetupStage.FILES -> SetupCopy.Files.TUTORIAL_PERMISSION
        SetupStage.BATTERY -> SetupCopy.Battery.TUTORIAL_PERMISSION
        SetupStage.SOCIAL -> SetupCopy.Social.TUTORIAL_PERMISSION
        SetupStage.COMPLETE -> ""
    }
    val bullets = when (stage) {
        SetupStage.NOTIFICATIONS -> SetupCopy.Notifications.TUTORIAL_BULLETS
        SetupStage.LOCATION -> SetupCopy.Location.TUTORIAL_BULLETS
        SetupStage.ACCESSIBILITY -> SetupCopy.Accessibility.TUTORIAL_BULLETS
        SetupStage.MICROPHONE -> SetupCopy.Microphone.TUTORIAL_BULLETS
        SetupStage.PHOTOS -> SetupCopy.Photos.TUTORIAL_BULLETS
        SetupStage.FILES -> SetupCopy.Files.TUTORIAL_BULLETS
        SetupStage.BATTERY -> SetupCopy.Battery.TUTORIAL_BULLETS
        SetupStage.SOCIAL -> SetupCopy.Social.TUTORIAL_BULLETS
        SetupStage.COMPLETE -> emptyList()
    }
    val isSettings = stage == SetupStage.ACCESSIBILITY || stage == SetupStage.FILES ||
            stage == SetupStage.BATTERY || stage == SetupStage.SOCIAL
    val buttonLabel = if (isSettings) "Deschide setările" else SetupCopy.CONTINUE

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Eticheta permisiunii
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Surface2)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(permission, style = monoLabel(9, 0.12f).copy(color = EmberHot))
        }
        Spacer(Modifier.height(20.dp))

        // Puncte informative
        bullets.forEachIndexed { i, bullet ->
            Reveal(index = i) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text("•  ", style = Body.copy(color = Accent2))
                    Text(bullet, style = Body.copy(color = TextSecondary))
                }
                if (i < bullets.size - 1) Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(24.dp))
        PrimaryButton(buttonLabel, onClick = onContinue, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        SecondaryButton(SetupCopy.LATER, onClick = onSkip)
    }
}

@Composable
private fun StageVerify(stage: SetupStage, ok: Boolean, onRetry: () -> Unit, onSkip: () -> Unit) {
    val successMsg = when (stage) {
        SetupStage.NOTIFICATIONS -> SetupCopy.Notifications.VERIFY_SUCCESS
        SetupStage.LOCATION -> SetupCopy.Location.VERIFY_SUCCESS
        SetupStage.ACCESSIBILITY -> SetupCopy.Accessibility.VERIFY_SUCCESS
        SetupStage.MICROPHONE -> SetupCopy.Microphone.VERIFY_SUCCESS
        SetupStage.PHOTOS -> SetupCopy.Photos.VERIFY_SUCCESS
        SetupStage.FILES -> SetupCopy.Files.VERIFY_SUCCESS
        SetupStage.BATTERY -> SetupCopy.Battery.VERIFY_SUCCESS
        SetupStage.SOCIAL -> SetupCopy.Social.VERIFY_SUCCESS
        SetupStage.COMPLETE -> ""
    }
    val failMsg = when (stage) {
        SetupStage.NOTIFICATIONS -> SetupCopy.Notifications.VERIFY_FAILURE
        SetupStage.LOCATION -> SetupCopy.Location.VERIFY_FAILURE
        SetupStage.ACCESSIBILITY -> SetupCopy.Accessibility.VERIFY_FAILURE
        SetupStage.MICROPHONE -> SetupCopy.Microphone.VERIFY_FAILURE
        SetupStage.PHOTOS -> SetupCopy.Photos.VERIFY_FAILURE
        SetupStage.FILES -> SetupCopy.Files.VERIFY_FAILURE
        SetupStage.BATTERY -> SetupCopy.Battery.VERIFY_FAILURE
        SetupStage.SOCIAL -> SetupCopy.Social.VERIFY_FAILURE
        SetupStage.COMPLETE -> ""
    }
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (ok) {
            PopIn {
                Mascot(state = MascotState.Happy, size = 80.dp)
            }
            Spacer(Modifier.height(16.dp))
            PopIn(delayMs = 300) {
                Text(successMsg, style = Body.copy(color = Positive, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), modifier = Modifier.fillMaxWidth())
            }
        } else {
            Text(SetupCopy.VERIFYING, style = Body.copy(color = TextDim), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Text(failMsg, style = BodySmall.copy(color = TextSecondary), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))
            PrimaryButton(SetupCopy.RETRY, onClick = onRetry, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            SecondaryButton(SetupCopy.SKIP_STAGE, onClick = onSkip)
        }
    }
}

@Composable
private fun StageSuccess(stage: SetupStage) {
    val label = when (stage) {
        SetupStage.NOTIFICATIONS -> SetupCopy.Notifications.SUCCESS
        SetupStage.LOCATION -> SetupCopy.Location.SUCCESS
        SetupStage.ACCESSIBILITY -> SetupCopy.Accessibility.SUCCESS
        SetupStage.MICROPHONE -> SetupCopy.Microphone.SUCCESS
        SetupStage.PHOTOS -> SetupCopy.Photos.SUCCESS
        SetupStage.FILES -> SetupCopy.Files.SUCCESS
        SetupStage.BATTERY -> SetupCopy.Battery.SUCCESS
        SetupStage.SOCIAL -> SetupCopy.Social.SUCCESS
        SetupStage.COMPLETE -> ""
    }
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        PopIn {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(32.dp)).background(Positive.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text("✓", fontSize = 28.sp, color = Positive)
            }
        }
        Spacer(Modifier.height(12.dp))
        PopIn(delayMs = 200) {
            Text("${label} — complet", style = Body.copy(color = Positive, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
        }
    }
}

// ════════════════════════════════════════════════════════════════
//  Rezumat final
// ════════════════════════════════════════════════════════════════

@Composable
private fun FinalSummary(onFinished: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Mascot(state = MascotState.Happy, size = 100.dp)
        Spacer(Modifier.height(16.dp))
        Text(SetupCopy.Summary.TITLE, style = TitleModule)
        Spacer(Modifier.height(8.dp))
        Text(SetupCopy.Summary.SUB, style = Body.copy(color = TextSecondary), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(24.dp))

        // Grilă permisiuni
        val stages = SetupStage.entries.filter { it != SetupStage.COMPLETE }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            stages.chunked(4).forEach { row ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    row.forEach { s ->
                        val granted = isStageGranted(context, s)
                        Column(
                            Modifier.padding(vertical = 4.dp, horizontal = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (granted) Positive.copy(alpha = 0.2f) else Surface2),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    if (granted) "✓" else "—",
                                    style = BodySmall.copy(
                                        color = if (granted) Positive else TextDim,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                    )
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            val label = when (s) {
                                SetupStage.NOTIFICATIONS -> SetupCopy.Notifications.SUCCESS
                                SetupStage.LOCATION -> SetupCopy.Location.SUCCESS
                                SetupStage.ACCESSIBILITY -> SetupCopy.Accessibility.SUCCESS
                                SetupStage.MICROPHONE -> SetupCopy.Microphone.SUCCESS
                                SetupStage.PHOTOS -> SetupCopy.Photos.SUCCESS
                                SetupStage.FILES -> SetupCopy.Files.SUCCESS
                                SetupStage.BATTERY -> SetupCopy.Battery.SUCCESS
                                SetupStage.SOCIAL -> SetupCopy.Social.SUCCESS
                                else -> ""
                            }
                            Text(label, style = monoLabel(8, 0.06f).copy(color = if (granted) TextPrimary else TextDim))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        PrimaryButton(SetupCopy.FINISH, onClick = onFinished, modifier = Modifier.fillMaxWidth())
    }
}
