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
import com.forja.app.core.network.MealAnalysis
import kotlinx.coroutines.launch
import java.io.File

/** Fotografiază masa → serverul FORJA o descompune pe componente editabile. */
@Composable
fun MealCameraScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val app = remember { ForjaApp.from(context) }
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current

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
    var analysis by remember { mutableStateOf<MealAnalysis?>(null) }
    var lastBytes by remember { mutableStateOf<ByteArray?>(null) }
    val imageCapture = remember { ImageCapture.Builder().build() }

    fun capture() {
        if (analyzing) return
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
                        lastBytes = bytes
                        when (val res = MealAnalyze.analyzeJpeg(app, bytes)) {
                            is AnalyzeOutcome.Ok -> { analyzing = false; analysis = res.analysis }
                            is AnalyzeOutcome.Fail -> { analyzing = false; toast.show(res.message) }
                        }
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
                if (analyzing) {
                    Spacer(Modifier.height(10.dp))
                    Text("se analizează masa…", style = monoLabel(10, 0.14f).copy(color = Accent2))
                }
            }
        } else {
            Column(
                Modifier.align(Alignment.Center).padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Fără cameră, fără poze.", style = TitleModule.copy(fontSize = 22.sp))
                Spacer(Modifier.height(8.dp))
                Text("Poza pleacă doar spre serverul FORJA, se analizează și dispare.", style = Body)
                Spacer(Modifier.height(16.dp))
                SecondaryButton("Dă permisiunea", onClick = { launcher.launch(Manifest.permission.CAMERA) })
            }
        }

        OverVideoButton(
            "Înapoi", onClick = onClose,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(16.dp)
        )
    }

    analysis?.let { a ->
        MealResultSheet(
            analysis = a,
            initialMealType = MealAnalyze.mealTypeForTime(System.currentTimeMillis()),
            onConfirm = { components, mealType ->
                scope.launch {
                    val photoPath = lastBytes?.let { MealAnalyze.savePhoto(context, it) }
                    val meal = MealAnalyze.saveMeal(app, a, components, mealType, System.currentTimeMillis(), photoPath)
                    toast.show("Salvat: ${meal.kcal} kcal · P ${meal.protein} · C ${meal.carbs} · G ${meal.fat}.")
                    analysis = null
                    onClose()
                }
            },
            onDismiss = { analysis = null }
        )
    }
}
