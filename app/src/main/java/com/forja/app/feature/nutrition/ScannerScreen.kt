package com.forja.app.feature.nutrition

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.OverVideoButton
import com.forja.app.core.designsystem.components.SecondaryButton
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/** Scanner cod de bare: ML Kit pe telefon → valori exacte din baza de date. */
@Composable
fun ScannerScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val vm: NutritionViewModel = viewModel(viewModelStoreOwner = activity)
    val pending by vm.pending.collectAsState()
    val busy by vm.lookupBusy.collectAsState()
    val toast = com.forja.app.core.designsystem.components.LocalToast.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) launcher.launch(Manifest.permission.CAMERA)
    }

    Box(Modifier.fillMaxSize().background(Surface0)) {
        if (hasPermission) {
            CameraWithBarcode(onBarcode = { vm.onBarcode(it) })
            // Cadru de țintire
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(width = 260.dp, height = 160.dp)
                    .border(2.dp, Color(0x99FFB300), RoundedCornerShape(16.dp))
            )
            Text(
                "Îndreaptă camera spre codul de bare",
                style = BodyStrong,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = 230.dp)
            )
            if (busy) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(14.dp))
                        .background(OverVideoFill)
                        .padding(18.dp)
                ) {
                    CircularProgressIndicator(color = Accent2, modifier = Modifier.size(26.dp))
                }
            }
        } else {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Fără cameră, fără scanare.", style = TitleModule.copy(fontSize = 22.sp))
                Spacer(Modifier.height(8.dp))
                Text(
                    "FORJA folosește camera doar pentru codul de bare — imaginile nu pleacă de pe telefon.",
                    style = Body
                )
                Spacer(Modifier.height(16.dp))
                SecondaryButton("Dă permisiunea", onClick = { launcher.launch(Manifest.permission.CAMERA) })
            }
        }

        OverVideoButton(
            "Înapoi",
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(16.dp)
        )
    }

    pending?.let { p ->
        PortionSheet(
            product = p.product,
            source = p.source,
            onConfirm = { mealType, grams ->
                vm.confirmPending(mealType, grams)
                toast.show("Salvat: ${p.product.name}.")
                onClose()
            },
            onDismiss = { vm.dismissPending() }
        )
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraWithBarcode(onBarcode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E, Barcode.FORMAT_CODE_128
                )
                .build()
        )
    }
    var lastCode by remember { mutableStateOf("") }
    var lastAt by remember { mutableStateOf(0L) }

    DisposableEffect(Unit) { onDispose { scanner.close() } }

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
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { proxy: ImageProxy ->
                    val mediaImage = proxy.image
                    if (mediaImage == null) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
                    scanner.process(image)
                        .addOnSuccessListener { barcodes ->
                            val code = barcodes.firstOrNull()?.rawValue
                            if (code != null) {
                                val now = System.currentTimeMillis()
                                if (code != lastCode || now - lastAt > 4000) {
                                    lastCode = code
                                    lastAt = now
                                    onBarcode(code)
                                }
                            }
                        }
                        .addOnCompleteListener { proxy.close() }
                }
                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                } catch (_: Exception) { }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        }
    )
}
