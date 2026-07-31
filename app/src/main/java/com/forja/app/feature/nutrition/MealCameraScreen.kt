package com.forja.app.feature.nutrition

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.forja.app.ForjaApp
import com.forja.app.core.data.db.MealEntity
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*
import com.forja.app.core.network.FoodComponent
import com.forja.app.core.network.GeminiFood
import com.forja.app.core.util.Fmt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalTime
import kotlin.math.roundToInt

/** Fotografiază masa → AI descompune farfuria pe componente editabile (à la BitePal). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealCameraScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val app = remember { ForjaApp.from(context) }
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    val nutritionVm: NutritionViewModel = viewModel(viewModelStoreOwner = activity)

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
    var analysis by remember { mutableStateOf<com.forja.app.core.network.MealAnalysis?>(null) }
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
                        val key = app.prefs.geminiKey.first()
                        val bytes = downscaleJpeg(file)
                        file.delete()
                        if (bytes == null) {
                            analyzing = false
                            toast.show("Poza nu s-a putut citi. Mai încearcă.")
                            return@launch
                        }
                        when (val res = app.geminiFood.analyze(key, bytes)) {
                            is GeminiFood.Result.Ok -> {
                                analyzing = false
                                analysis = res.analysis
                            }
                            is GeminiFood.Result.Fail -> {
                                analyzing = false
                                toast.show(res.message)
                            }
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
                // Declanșator
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
                Text("Poza pleacă DOAR spre analiza AI pe care ai activat-o tu, cu cheia ta.", style = Body)
                Spacer(Modifier.height(16.dp))
                SecondaryButton("Dă permisiunea", onClick = { launcher.launch(Manifest.permission.CAMERA) })
            }
        }

        OverVideoButton(
            "Înapoi", onClick = onClose,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(16.dp)
        )
    }

    // Rezultatul: componente editabile, recalcul live.
    analysis?.let { a ->
        var components by remember(a) { mutableStateOf(a.componente) }
        var mealType by remember {
            mutableStateOf(
                when (LocalTime.now().hour) {
                    in 5..10 -> 0; in 11..16 -> 1; in 17..22 -> 2; else -> 3
                }
            )
        }
        ModalBottomSheet(
            onDismissRequest = { analysis = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Surface1, shape = SheetShape
        ) {
            Column(
                Modifier
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(a.fel.ifBlank { "Masa ta" }, style = TitleModule.copy(fontSize = 22.sp))
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SourceBadge("ESTIMARE AI", tone = Accent2)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Încredere: ${a.incredere} — o poți corecta oricând.",
                        style = BodyTiny.copy(color = TextSecondary)
                    )
                }
                Spacer(Modifier.height(14.dp))

                components.forEachIndexed { i, c ->
                    ForjaCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), fill = Surface2, padding = 12.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.nume, style = BodyStrong.copy(fontSize = 14.sp))
                                Text(
                                    "${c.kcal} kcal · P ${c.proteine} · C ${c.carbo} · G ${c.grasimi}",
                                    style = BodyTiny.copy(color = TextSecondary)
                                )
                            }
                            SecondaryButton("−10g", padV = 6.dp, onClick = {
                                components = components.mapIndexed { j, cc ->
                                    if (j == i) scaleComponent(cc, (cc.grame - 10).coerceAtLeast(5)) else cc
                                }
                            })
                            Spacer(Modifier.width(6.dp))
                            Text("${c.grame}g", style = heroNumeral(16))
                            Spacer(Modifier.width(6.dp))
                            SecondaryButton("+10g", padV = 6.dp, onClick = {
                                components = components.mapIndexed { j, cc ->
                                    if (j == i) scaleComponent(cc, cc.grame + 10) else cc
                                }
                            })
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "×",
                                style = BodyStrong.copy(color = TextDim, fontSize = 16.sp),
                                modifier = Modifier.pressable({
                                    components = components.filterIndexed { j, _ -> j != i }
                                }).padding(4.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row {
                    mealTypeNames.forEachIndexed { i, n ->
                        Box(
                            Modifier
                                .padding(end = 8.dp)
                                .clip(ChipShape)
                                .background(if (i == mealType) TabPillActive else Surface2)
                                .pressable({ mealType = i })
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(n, style = monoLabel(8, 0.10f).copy(color = if (i == mealType) Accent2 else TextDim))
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                val totalKcal = components.sumOf { it.kcal }
                val totalP = components.sumOf { it.proteine }
                val totalC = components.sumOf { it.carbo }
                val totalG = components.sumOf { it.grasimi }
                Text("$totalKcal kcal · P $totalP · C $totalC · G $totalG", style = BodyStrong.copy(fontSize = 16.sp))

                Spacer(Modifier.height(14.dp))
                PrimaryButton(
                    text = "Confirmă",
                    onClick = {
                        scope.launch {
                            app.db.mealDao().insert(
                                MealEntity(
                                    epochDay = Fmt.epochDay(),
                                    mealType = mealType,
                                    name = a.fel.ifBlank { components.joinToString(" + ") { it.nume }.take(48) },
                                    kcal = totalKcal, protein = totalP, carbs = totalC, fat = totalG,
                                    grams = components.sumOf { it.grame },
                                    source = "ESTIMARE AI · POZĂ",
                                    confidence = a.incredere,
                                    at = System.currentTimeMillis()
                                )
                            )
                            toast.show("Salvat: $totalKcal kcal · P $totalP · C $totalC · G $totalG.")
                            analysis = null
                            onClose()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = components.isNotEmpty()
                )
            }
        }
    }
}

private fun scaleComponent(c: FoodComponent, newGrams: Int): FoodComponent {
    if (c.grame <= 0) return c.copy(grame = newGrams)
    val f = newGrams.toDouble() / c.grame
    return c.copy(
        grame = newGrams,
        kcal = (c.kcal * f).roundToInt(),
        proteine = (c.proteine * f).roundToInt(),
        carbo = (c.carbo * f).roundToInt(),
        grasimi = (c.grasimi * f).roundToInt()
    )
}

/** Redimensionează poza la max ~1280px și o comprimă — suficient pentru AI, mic pentru rețea. */
private fun downscaleJpeg(file: File): ByteArray? {
    return try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        var sample = 1
        while (opts.outWidth / sample > 1600 || opts.outHeight / sample > 1600) sample *= 2
        val bmp = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        bmp.recycle()
        out.toByteArray()
    } catch (_: Exception) { null }
}
