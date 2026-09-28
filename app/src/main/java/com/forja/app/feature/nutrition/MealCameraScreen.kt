package com.forja.app.feature.nutrition

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.forja.app.ForjaApp
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*
import com.forja.app.core.network.MealReport
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Fotografiază masa → serverul FORJA o descompune pe componente editabile.
 * După declanșare, poza intră în ecranul de scanare „ca la BitePal” ([MealScanScreen]); la răspuns, foaia de rezultat
 * urcă peste el. Tipul mesei vine din „Adaugă” (prin [NutritionViewModel.pendingMealType]) sau după oră.
 */
@Composable
fun MealCameraScreen(onClose: () -> Unit, onManual: (() -> Unit)? = null) {
    val context = LocalContext.current
    val app = remember { ForjaApp.from(context) }
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    val activity = context as ComponentActivity
    val vm: NutritionViewModel = viewModel(viewModelStoreOwner = activity)
    val voice by vm.voice.collectAsState()
    val mealType = remember { vm.pendingMealType?.also { vm.pendingMealType = null } ?: MealAnalyze.mealTypeForTime(System.currentTimeMillis()) }
    val reduced = LocalReducedMotion.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
    }
    LaunchedEffect(Unit) { if (!hasPermission) launcher.launch(Manifest.permission.CAMERA) }

    var analyzing by remember { mutableStateOf(false) }
    var scanBytes by remember { mutableStateOf<ByteArray?>(null) }   // poza din ecranul de scanare
    var scanReport by remember { mutableStateOf<MealReport?>(null) } // răspunsul, cât apar etichetele
    var scanError by remember { mutableStateOf<AnalyzeOutcome.Fail?>(null) }
    var report by remember { mutableStateOf<MealReport?>(null) }     // foaia de rezultat
    var lastBytes by remember { mutableStateOf<ByteArray?>(null) }
    var scanGen by remember { mutableStateOf(0) }   // „Înapoi” în timpul analizei: răspunsul întârziat nu mai contează
    val dayTarget by vm.kcalTarget.collectAsState()
    val imageCapture = remember { ImageCapture.Builder().build() }

    suspend fun analyze(bytes: ByteArray) {
        lastBytes = bytes
        scanBytes = bytes
        scanReport = null
        scanError = null
        analyzing = true
        val gen = ++scanGen
        val res = MealAnalyze.analyzeJpeg(app, bytes, mealType)
        if (gen != scanGen) { analyzing = false; return }
        when (res) {
            is AnalyzeOutcome.Ok -> {
                scanReport = res.report
                delay(scanRevealMs(res.report, reduced)) // etichetele apar una câte una, apoi foaia urcă
                analyzing = false
                report = res.report
            }
            is AnalyzeOutcome.Fail -> { analyzing = false; scanError = res }
        }
    }

    fun capture() {
        if (analyzing || scanBytes != null) return
        val file = File(context.cacheDir, "meal_${System.currentTimeMillis()}.jpg")
        analyzing = true
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    scope.launch {
                        val raw = try { file.readBytes() } catch (_: Exception) { null }
                        file.delete()
                        val bytes = raw?.let { MealAnalyze.downscale(it) }
                        if (bytes == null) {
                            analyzing = false
                            toast.show("Poza nu s-a putut citi. Mai încearcă.")
                            return@launch
                        }
                        analyze(bytes)
                    }
                }
                override fun onError(exception: ImageCaptureException) {
                    analyzing = false
                    toast.show("Camera n-a putut face poza. Mai încearcă.")
                }
            }
        )
    }

    Box(Modifier.fillMaxSize().background(Surface0)) {
        if (hasPermission) {
            val lifecycleOwner = LocalLifecycleOwner.current
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val providerFuture = ProcessCameraProvider.getInstance(ctx)
                    providerFuture.addListener({
                        val provider = providerFuture.get()
                        val preview = CameraPreview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        try {
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                        } catch (_: Exception) { }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                }
            )
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Cadru de sus, toată farfuria în imagine.",
                    style = BodyStrong,
                    modifier = Modifier
                        .clip(ChipShape)
                        .background(OverVideoFill)
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                )
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .border(3.dp, Color.White, CircleShape)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(if (analyzing) SwitchOff else Color.White)
                        .pressable({ capture() }),
                    contentAlignment = Alignment.Center
                ) {
                    if (analyzing) {
                        val infinite = rememberInfiniteTransition(label = "shimmer")
                        val a by infinite.animateFloat(
                            0.4f, 1f,
                            infiniteRepeatable(tween(750), RepeatMode.Reverse),
                            label = "a"
                        )
                        Box(
                            Modifier
                                .size(28.dp)
                                .graphicsLayer { alpha = a }
                                .clip(CircleShape)
                                .background(AccentGradient)
                        )
                    }
                }
            }
        } else {
            Column(
                Modifier.align(Alignment.Center).padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Fără cameră, fără poze.", style = TitleModule.copy(fontSize = 22.sp))
                Spacer(Modifier.height(8.dp))
                Text("Poza pleacă la analiză cu model și se întoarce ca estimare. Nimic nu se salvează fără confirmarea ta.", style = Body)
                Spacer(Modifier.height(16.dp))
                SecondaryButton("Dă permisiunea", onClick = { launcher.launch(Manifest.permission.CAMERA) })
            }
        }

        OverVideoButton(
            "Înapoi", onClick = onClose,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(16.dp)
        )

        // Ecranul de scanare „ca la BitePal”, peste cameră: poza, banda amber, etichetele, cardul-bulă, mascota.
        scanBytes?.let { bytes ->
            MealScanScreen(
                bytes = bytes, report = scanReport, error = scanError, voice = voice, dayTarget = dayTarget,
                onBack = { scanGen++; analyzing = false; scanBytes = null; scanError = null; scanReport = null },
                onRetry = { scanGen++; analyzing = false; scanBytes = null; scanError = null; scanReport = null },
                onManual = { scanGen++; analyzing = false; scanBytes = null; scanError = null; if (onManual != null) onManual() else onClose() }
            )
        }
    }

    report?.let { r ->
        MealResultSheet(
            report = r,
            initialMealType = mealType,
            onConfirm = { components, mealType ->
                scope.launch {
                    val photoPath = lastBytes?.let { MealAnalyze.savePhoto(context, it) }
                    val meal = MealAnalyze.saveMeal(app, r, components, mealType, System.currentTimeMillis(), photoPath)
                    toast.show("Salvat: ${meal.kcal} kcal · P ${meal.protein} · C ${meal.carbs} · G ${meal.fat}.")
                    report = null
                    onClose()
                }
            },
            onDismiss = { report = null; scanBytes = null; scanReport = null },
            onRetake = { report = null; scanBytes = null; scanReport = null },
            dayTarget = dayTarget
        )
    }
}
