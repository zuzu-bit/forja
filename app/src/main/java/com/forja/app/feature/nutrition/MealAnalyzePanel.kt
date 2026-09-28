package com.forja.app.feature.nutrition

import android.graphics.BitmapFactory
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*
import com.forja.app.core.network.MealItem
import com.forja.app.core.network.MealReport
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/*
 * Ecranul de scanare „ca la BitePal” (SPEC-4.2, reperul ec92eb8f): poza pe un panou deschis, o bandă amber
 * translucidă cu puncte trece de sus în jos (linie luminoasă la marginea de jos), etichetele componentelor
 * apar una câte una peste farfurie când vine răspunsul, cardul-bulă de sus arată kcal + barele C/G/P,
 * iar mascota (bonetă de bucătar) vorbește jos-stânga. Sub mișcare redusă: fără bandă, fără pop, totul instant.
 * Onestitatea nu e în față: SourceBadge și „ce nu se vede” rămân în foaia de rezultat.
 */

// Culorile barelor din cardul-bulă (reperul BitePal, în paleta FORJA): carbo amber, grăsimi albastru, proteine verde.
private val ScanCarb = EmberHot
private val ScanFat = Color(0xFF9DBFE8)
private val ScanProtein = Positive
private val ScanInk = Color(0xFF17181C)
private val ScanInkDim = Color(0xFF6B6F78)
private val ScanTrack = Color(0xFFE6E8EC)
private val ScanRing = Brush.sweepGradient(listOf(Color(0xFFB8E356), EmberHot, Color(0xFFB8E356)))

const val SCAN_SWEEP_MS = 2200
private const val LABEL_STAGGER_MS = 140L
private const val LABEL_BEAT_MS = 700L

/** Cât ține ecranul de scanare după ce a venit rezultatul, ca etichetele să apară toate înainte de foaie. */
fun scanRevealMs(report: MealReport, reduced: Boolean): Long =
    if (reduced) 0L else report.componente.size.coerceAtMost(9) * LABEL_STAGGER_MS + LABEL_BEAT_MS

/**
 * Ecranul de scanare: `report` = null cât se așteaptă; când vine, etichetele și cifrele apar.
 * `error` ≠ null → mesajul uman + detaliul serverului mic dedesubt + „Încearcă din nou” / „Adaug manual”.
 */
@Composable
fun MealScanScreen(
    bytes: ByteArray,
    report: MealReport?,
    error: AnalyzeOutcome.Fail?,
    voice: MascotVoice,
    dayTarget: Int,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onManual: () -> Unit,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp
) {
    val reduced = LocalReducedMotion.current
    val image: ImageBitmap? = remember(bytes) {
        try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() } catch (_: Throwable) { null }
    }
    val items = report?.componente ?: emptyList()
    val kcal = items.sumOf { it.kcal }
    val carbs = items.sumOf { it.carbo }
    val fat = items.sumOf { it.grasimi }
    val protein = items.sumOf { it.proteine }

    // Replicile mascotei se rotesc cât ține scanarea; la rezultat/eroare, replica finală.
    var lineIdx by remember { mutableStateOf(0) }
    LaunchedEffect(report, error) {
        if (report != null || error != null) return@LaunchedEffect
        while (true) {
            delay(1600)
            lineIdx = (lineIdx + 1) % voice.analysis.size
        }
    }
    val line = when {
        error != null -> voice.sorry
        report != null -> voice.done
        else -> voice.analysis[lineIdx % voice.analysis.size]
    }
    val mascotState = when {
        error != null -> MascotState.Sorry
        report != null -> MascotState.Happy
        else -> MascotState.Thinking
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Surface0)
            .pointerInput(Unit) { }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(top = 56.dp, bottom = 12.dp + bottomInset)
                .padding(horizontal = 12.dp)
        ) {
            // Panoul deschis: gradient albastru pal → alb cald, colțuri rotunjite, cromul întunecat rămâne în jur.
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFFD3E6F5), Color(0xFFEAF1F7), Color(0xFFF6F7F9))))
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CalorieBubble(kcal = kcal, carbs = carbs, fat = fat, protein = protein, hasResult = report != null, dayTarget = dayTarget)
                Spacer(Modifier.height(14.dp))
                PlateWithScan(image = image, items = items, scanning = report == null && error == null, reduced = reduced)
                Spacer(Modifier.height(18.dp))
                if (error != null) {
                    ScanError(error, onRetry = onRetry, onManual = onManual)
                } else {
                    Text(
                        if (report == null) "Scanez masa." else "Am citit farfuria.",
                        style = TitleModule.copy(fontSize = 34.sp, lineHeight = 36.sp, color = ScanInk, fontWeight = FontWeight.Black),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (report == null) "Estimare cu model. Tu confirmi porția." else "Corectezi porțiile în foaia care urmează.",
                        style = BodySmall.copy(color = ScanInkDim),
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(Modifier.height(18.dp))
            }
            Spacer(Modifier.height(10.dp))
            // Mascota jos-stânga, cu replica ei.
            MascotSays(
                text = line,
                state = mascotState,
                hat = MascotHat.Chef,
                size = 60.dp,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding()
            )
        }
        OverVideoButton(
            "Înapoi", onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 12.dp, top = 8.dp)
        )
    }
}

/** Cardul-bulă cu vârf în jos: „Calorii” + numeral mare + inel kcal (lime→amber) + barele C/G/P cu grame. */
@Composable
private fun CalorieBubble(kcal: Int, carbs: Int, fat: Int, protein: Int, hasResult: Boolean, dayTarget: Int) {
    val reduced = LocalReducedMotion.current
    val shape = RoundedCornerShape(22.dp)
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Color.White)
                .padding(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Calorii", style = Body.copy(color = ScanInkDim, fontSize = 14.sp))
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        if (hasResult) {
                            CountUpNumeral(target = kcal.toFloat(), size = 40, decimals = 0, color = ScanInk)
                        } else {
                            Text("—", style = heroNumeral(40).copy(color = ScanInkDim))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text("kcal", style = Body.copy(color = ScanInkDim, fontSize = 18.sp), modifier = Modifier.padding(bottom = 4.dp))
                    }
                }
                MacroRing(
                    progress = if (hasResult) kcal.toFloat() / dayTarget.coerceAtLeast(1) else 0f,
                    ringSize = 64.dp, strokeWidth = 8.dp, track = ScanTrack, brush = ScanRing, key = hasResult
                ) {
                    Text(
                        if (hasResult) "${(100f * kcal / dayTarget.coerceAtLeast(1)).roundToInt()}%" else "…",
                        style = BodyStrong.copy(color = ScanInk, fontSize = 12.sp)
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            val maxG = maxOf(carbs, fat, protein, 1)
            Row(Modifier.fillMaxWidth()) {
                LightBar("Carbo", carbs, ScanCarb, maxG, hasResult, reduced, Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                LightBar("Grăsimi", fat, ScanFat, maxG, hasResult, reduced, Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                LightBar("Proteine", protein, ScanProtein, maxG, hasResult, reduced, Modifier.weight(1f))
            }
        }
        // Vârful bulei.
        Canvas(Modifier.fillMaxWidth().height(12.dp)) {
            val cx = size.width / 2f
            val path = Path().apply {
                moveTo(cx - 12.dp.toPx(), 0f)
                lineTo(cx + 12.dp.toPx(), 0f)
                lineTo(cx, size.height)
                close()
            }
            drawPath(path, Color.White)
        }
    }
}

/** Bară scurtă pe fond alb: etichetă, bară colorată, „70 g” — crește când vine rezultatul. */
@Composable
private fun LightBar(label: String, grams: Int, color: Color, max: Int, hasResult: Boolean, reduced: Boolean, modifier: Modifier) {
    val target = if (hasResult) (grams.toFloat() / max).coerceIn(0.04f, 1f) else 0f
    val p by animateFloatAsState(target, if (reduced) snap() else tween(900), label = "bar")
    Column(modifier) {
        Text(label, style = Body.copy(color = ScanInkDim, fontSize = 13.sp))
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(7.dp).clip(CircleShape).background(ScanTrack)) {
            if (p > 0f) Box(Modifier.fillMaxWidth(p).fillMaxHeight().clip(CircleShape).background(color))
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(if (hasResult) "$grams" else "—", style = heroNumeral(20).copy(color = ScanInk))
            Spacer(Modifier.width(3.dp))
            Text("g", style = Body.copy(color = ScanInkDim, fontSize = 13.sp), modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}

/** Poza (pătrat, colțuri mari), banda de scanare peste ea, etichetele componentelor ancorate pe farfurie. */
@Composable
private fun PlateWithScan(image: ImageBitmap?, items: List<MealItem>, scanning: Boolean, reduced: Boolean) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    val shape = RoundedCornerShape(28.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(shape)
            .background(Color(0xFFDDE4EC))
            .onSizeChanged { box = it }
    ) {
        if (image != null) {
            Image(bitmap = image, contentDescription = "Masa fotografiată", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (scanning && !reduced) ScanSweep(Modifier.fillMaxSize())
        // Etichetele: bbox (dacă vine) sau grilă 3×3 peste zona farfuriei, cu un tremur mic, determinist după index.
        if (box.width > 0) {
            items.take(9).forEachIndexed { i, item ->
                val (fx, fy) = labelAnchor(item, i)
                PopIn(delayMs = if (reduced) 0L else i * LABEL_STAGGER_MS, fromScale = 0.5f,
                    modifier = Modifier.offset { IntOffset((fx * box.width).roundToInt(), (fy * box.height).roundToInt()) }
                ) {
                    IngredientLabel(item.nume.ifBlank { "Componentă" })
                }
            }
        }
    }
}

/** Colțul din stânga-sus al etichetei, ca fracție din poză. `bbox` → centrul dreptunghiului; altfel grila. */
private fun labelAnchor(item: MealItem, index: Int): Pair<Float, Float> {
    item.bbox?.let { b ->
        val cx = (b[0] + b[2] / 2f).coerceIn(0.08f, 0.78f)
        val cy = (b[1] + b[3] / 2f).coerceIn(0.06f, 0.86f)
        return (cx - 0.08f) to (cy - 0.04f)
    }
    // Zona farfuriei: 12 %–88 % pe ambele axe. Ordinea grilei: mijloc, apoi în jur — prima etichetă cade pe farfurie.
    val order = listOf(4, 0, 8, 2, 6, 1, 7, 3, 5)
    val cell = order[index % 9]
    val col = cell % 3
    val row = cell / 3
    val jx = ((index * 37) % 11 - 5) / 100f
    val jy = ((index * 53) % 9 - 4) / 100f
    val x = 0.10f + col * 0.26f + jx
    val y = 0.10f + row * 0.28f + jy
    return x.coerceIn(0.02f, 0.62f) to y.coerceIn(0.02f, 0.88f)
}

/** Pastilă albă rotunjită cu text închis — eticheta unei componente. */
@Composable
private fun IngredientLabel(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xF2FFFFFF))
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(text, style = BodyStrong.copy(color = ScanInk, fontSize = 15.sp), maxLines = 1)
    }
}

/** Banda amber translucidă cu puncte, de sus în jos în [SCAN_SWEEP_MS], linia luminoasă la marginea de jos. */
@Composable
private fun ScanSweep(modifier: Modifier = Modifier) {
    val infinite = rememberInfiniteTransition(label = "sweep")
    val t by infinite.animateFloat(
        0f, 1f, infiniteRepeatable(tween(SCAN_SWEEP_MS, easing = LinearEasing), RepeatMode.Restart), label = "t"
    )
    Canvas(modifier) {
        val bandH = size.height * 0.34f
        val bottom = -0.05f * size.height + t * (size.height * 1.15f + bandH) - 0.05f * size.height
        val top = bottom - bandH
        clipRect {
            // Banda: transparentă sus, amber jos.
            drawRect(
                Brush.verticalGradient(listOf(Color(0x00FFB35C), Color(0x33FFB35C), Color(0x8CFFC26B)), startY = top, endY = bottom),
                topLeft = Offset(0f, top), size = Size(size.width, bandH)
            )
            // Punctele: grilă de cerculețe, mai dense spre linie.
            val step = 14.dp.toPx()
            val r = 2.dp.toPx()
            var y = top + step / 2f
            while (y < bottom) {
                val f = ((y - top) / bandH).coerceIn(0f, 1f)
                var x = step / 2f
                while (x < size.width) {
                    drawCircle(Color.White.copy(alpha = 0.10f + 0.45f * f), r, Offset(x, y))
                    x += step
                }
                y += step
            }
            // Linia luminoasă + halou.
            val lineH = 4.dp.toPx()
            drawRect(Color(0x55FFC26B), topLeft = Offset(0f, bottom - lineH * 3), size = Size(size.width, lineH * 3))
            drawRect(Color(0xFFFFC94D), topLeft = Offset(0f, bottom - lineH), size = Size(size.width, lineH))
        }
    }
}

/** Eroare: mesaj uman, motivul serverului mic dedesubt, două butoane. */
@Composable
private fun ScanError(error: AnalyzeOutcome.Fail, onRetry: () -> Unit, onManual: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Nu a mers.",
            style = TitleModule.copy(fontSize = 30.sp, lineHeight = 33.sp, color = ScanInk, fontWeight = FontWeight.Black),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(error.message, style = Body.copy(color = ScanInk, fontSize = 15.sp), textAlign = TextAlign.Center)
        error.detail?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = BodyTiny.copy(color = ScanInkDim), textAlign = TextAlign.Center, maxLines = 3)
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth()) {
            PrimaryButton("Încearcă din nou", onClick = onRetry, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            SecondaryButton("Adaug manual", onClick = onManual, modifier = Modifier.weight(1f))
        }
    }
}
