package com.forja.app.feature.nutrition

import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*
import com.forja.app.core.network.FoodProduct
import com.forja.app.core.util.Fmt
import java.time.LocalTime

/** Nutriție à la BitePal: poza e regina, codul de bare e adjunctul, baza de date decide. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutritionScreen(onScan: () -> Unit, onPhotograph: () -> Unit = {}) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val app = remember { com.forja.app.ForjaApp.from(context) }
    val vm: NutritionViewModel = viewModel(viewModelStoreOwner = activity)
    val meals by vm.meals.collectAsState()
    val kcal by vm.kcalToday.collectAsState()
    val target by vm.kcalTarget.collectAsState()
    val vmPending by vm.pending.collectAsState()
    val lookupError by vm.lookupError.collectAsState()
    val toast = LocalToast.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val geminiKey by app.prefs.geminiKey.collectAsState(initial = "")
    var keyOpen by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var manualOpen by remember { mutableStateOf(false) }

    // ── Galerie: alegere manuală a unei poze pentru analiză ──
    var galleryAnalyzing by remember { mutableStateOf(false) }
    var galleryAnalysis by remember { mutableStateOf<com.forja.app.core.network.MealAnalysis?>(null) }
    var galleryBytes by remember { mutableStateOf<ByteArray?>(null) }

    val pickLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            galleryAnalyzing = true
            scope.launch {
                val bytes = MealAnalyze.readUri(context, uri)
                if (bytes == null) {
                    galleryAnalyzing = false
                    toast.show("Poza nu s-a putut citi.")
                    return@launch
                }
                galleryBytes = bytes
                when (val res = MealAnalyze.analyzeJpeg(app, bytes)) {
                    is AnalyzeOutcome.Ok -> { galleryAnalyzing = false; galleryAnalysis = res.analysis }
                    is AnalyzeOutcome.Fail -> { galleryAnalyzing = false; toast.show(res.message) }
                }
            }
        }
    }

    LaunchedEffect(lookupError) {
        lookupError?.let {
            toast.show(it)
            vm.clearError()
            searchOpen = true
        }
    }

    // Fundal cu poftă de viață: legume proaspete generate în banca media, sub un văl întunecat —
    // culoarea răzbate, textul rămâne rege.
    val nutriBg = remember { com.forja.app.core.media.Media.mediaUrl("nutri_bg.jpg") }
    Box(Modifier.fillMaxSize().background(Surface0)) {
        if (nutriBg != null) {
            AsyncImage(
                model = nutriBg, contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(Modifier.fillMaxSize().background(Color(0xC20A0A0B)))
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 120.dp)
    ) {
        // Header foto 212dp cu budget kcal
        Box(Modifier.fillMaxWidth().height(212.dp)) {
            AsyncImage(
                model = "https://t3.ftcdn.net/jpg/03/30/19/86/500_F_330198627_aQsy9t5HhOn7TIsd6FEB0FJvKz4IqdhH.jpg",
                contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            BoxScopeBottomScrim()
            TopScrim()
            Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    CountUpNumeral(target = kcal.toFloat(), size = 44, decimals = 0)
                    Text(
                        " / $target kcal azi",
                        style = Body.copy(color = TextSecondary),
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                val progress by animateFloatAsState((kcal.toFloat() / target).coerceIn(0f, 1f), Springs.natural(), label = "kcal")
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(Color(0x33FFFFFF))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(AccentGradient)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (kcal <= target) "mai ai ${target - kcal}"
                    else "peste cu ${kcal - target} — e în regulă, mâine e o zi nouă",
                    style = BodySmall.copy(color = if (kcal <= target) TextSecondary else Error)
                )
            }
            Text(
                "Nutriție",
                style = TitleModule,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(20.dp)
            )
        }

        Spacer(Modifier.height(18.dp))

        // Jurnalul meselor de azi
        SectionLabel("Mesele de azi", Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(10.dp))
        Column(Modifier.padding(horizontal = 20.dp)) {
            for (type in 0..3) {
                val entries = meals.filter { it.mealType == type }
                if (entries.isEmpty() && type == 3) continue
                if (entries.isEmpty()) {
                    // Card cu poftă: poza mesei pe fundal, nu doar text pe negru.
                    val artUrl = remember(type) {
                        com.forja.app.core.media.Media.mediaUrl(
                            when (type) {
                                0 -> "471644726.jpg"          // iaurt cu granola, lumină de dimineață
                                1 -> "330198627.jpg"          // bol cu orez și legume, prânz
                                else -> "meal_dinner.jpg"     // cină caldă, lumânare
                            }
                        )
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                            .height(78.dp)
                            .clip(CardShape)
                            .background(Surface1)
                            .border(1.dp, StrokeCard, CardShape)
                    ) {
                        if (artUrl != null) {
                            AsyncImage(
                                model = artUrl, contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.horizontalGradient(
                                    0f to Color(0xF20C0D0B), 0.5f to Color(0xB80C0D0B), 1f to Color(0x330C0D0B)
                                )
                            )
                        )
                        Row(
                            Modifier.fillMaxSize().padding(horizontal = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    when (type) {
                                        0 -> "Micul dejun"
                                        1 -> "Prânzul"
                                        else -> "Cina"
                                    },
                                    style = BodyStrong
                                )
                                Text("încă nimic — ce-ai zice de ceva bun?", style = BodyTiny.copy(color = TextSecondary))
                            }
                            SecondaryButton("Adaugă", onClick = { searchOpen = true }, padV = 8.dp)
                        }
                    }
                } else {
                    entries.forEach { m ->
                        ForjaCard(Modifier.fillMaxWidth().padding(bottom = 10.dp), padding = 12.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (m.photoPath != null && java.io.File(m.photoPath).exists()) {
                                    AsyncImage(
                                        model = java.io.File(m.photoPath),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(width = 44.dp, height = 54.dp)
                                            .clip(ThumbShape)
                                    )
                                } else {
                                    Icon(
                                        Icons.Filled.Check, contentDescription = null,
                                        tint = Positive, modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(m.name, style = BodyStrong.copy(fontSize = 14.sp), maxLines = 1)
                                    Spacer(Modifier.height(2.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "${mealTypeNames[m.mealType]} · ${Fmt.clock(m.at)} · ${m.grams} g",
                                            style = monoLabel(8, 0.10f).copy(color = TextDim)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        SourceBadge(m.source, tone = if (m.source.startsWith("EXACT")) Positive else TextDim)
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        "P ${m.protein} · C ${m.carbs} · G ${m.fat}",
                                        style = BodyTiny.copy(color = TextSecondary)
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("${m.kcal} kcal", style = BodyStrong.copy(fontSize = 14.sp))
                                    Text(
                                        "șterge",
                                        style = BodyTiny.copy(color = TextDim),
                                        modifier = Modifier.pressable({ vm.deleteMeal(m.id) })
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        val serverOn = app.forjaApi.available
        PrimaryButton(
            text = "Fotografiază masa",
            onClick = {
                if (serverOn || geminiKey.isNotBlank()) onPhotograph() else keyOpen = true
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
        )
        Spacer(Modifier.height(10.dp))
        // Patru drumuri către jurnal — fiecare cu culoarea și iconița lui, nu patru pietre negre.
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            ActionTile(
                icon = Icons.Outlined.PhotoLibrary,
                label = if (galleryAnalyzing) "se analizează…" else "Din galerie",
                tint = Color(0xFFD9A24C),
                onClick = {
                    if (!galleryAnalyzing) pickLauncher.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            ActionTile(
                icon = Icons.Outlined.QrCodeScanner,
                label = "Cod de bare",
                tint = Color(0xFF6FA8A0),
                onClick = onScan,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            ActionTile(
                icon = Icons.Outlined.Search,
                label = "Caută",
                tint = Color(0xFF9DB77E),
                onClick = { searchOpen = true },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            ActionTile(
                icon = Icons.Outlined.Edit,
                label = "Manual",
                tint = Color(0xFFC98A63),
                onClick = { manualOpen = true },
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Cum funcționează analiza", style = BodyTiny.copy(color = TextDim2))
            Spacer(Modifier.width(8.dp))
            InfoDot(
                title = "Cum funcționează",
                text = "Analiza rulează pe serverul FORJA — fără nicio cheie la tine. AI-ul estimează din poză; codul de bare dă valori exacte din OpenFoodFacts. Totul rămâne editabil înainte de salvare."
            )
        }
    }
    }

    if (keyOpen) {
        AiKeySheet(
            onSaved = { key ->
                scope.launch {
                    app.prefs.setGeminiKey(key)
                    keyOpen = false
                    toast.show("Cheie salvată. Fotografiază prima masă!")
                }
            },
            onClose = { keyOpen = false }
        )
    }

    if (searchOpen) {
        FoodSearchSheet(vm = vm, onClose = { searchOpen = false })
    }
    if (manualOpen) {
        ManualAddSheet(vm = vm, onClose = { manualOpen = false })
    }
    vmPending?.let { p ->
        PortionSheet(
            product = p.product,
            source = p.source,
            onConfirm = { mealType, grams ->
                vm.confirmPending(mealType, grams)
                toast.show("Salvat. Poți corecta oricând din jurnal.")
            },
            onDismiss = { vm.dismissPending() }
        )
    }

    // Rezultatul analizei din galerie (poză aleasă manual).
    galleryAnalysis?.let { a ->
        MealResultSheet(
            analysis = a,
            initialMealType = MealAnalyze.mealTypeForTime(System.currentTimeMillis()),
            onConfirm = { components, mealType ->
                scope.launch {
                    val photoPath = galleryBytes?.let { MealAnalyze.savePhoto(context, it) }
                    val meal = MealAnalyze.saveMeal(app, a, components, mealType, System.currentTimeMillis(), photoPath)
                    toast.show("Salvat: ${meal.kcal} kcal.")
                    galleryAnalysis = null
                }
            },
            onDismiss = { galleryAnalysis = null }
        )
    }

}

/** Buton de acțiune cu personalitate: iconiță + tenta lui de culoare, pe sticlă colorată. */
@Composable
private fun ActionTile(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .clip(SecondaryShape)
            .background(tint.copy(alpha = 0.14f))
            .border(1.dp, tint.copy(alpha = 0.38f), SecondaryShape)
            .pressable(onClick)
            .padding(vertical = 13.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = BodyStrong.copy(fontSize = 14.sp))
    }
}

/** Porția: Tot / ½ / ⅓ / ¼ din pachet sau grame — fracțiile sunt UI, nu AI. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortionSheet(
    product: FoodProduct,
    source: String,
    onConfirm: (mealType: Int, grams: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val defaultGrams = product.servingGrams ?: 100
    var grams by remember { mutableStateOf(defaultGrams) }
    var mealType by remember {
        mutableStateOf(
            when (LocalTime.now().hour) {
                in 5..10 -> 0
                in 11..16 -> 1
                in 17..22 -> 2
                else -> 3
            }
        )
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Surface1,
        shape = SheetShape
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(product.name, style = TitleModule.copy(fontSize = 20.sp, lineHeight = 23.sp))
            product.brand?.let { Text(it, style = BodySmall.copy(color = TextSecondary)) }
            Spacer(Modifier.height(6.dp))
            SourceBadge(source, tone = if (source.startsWith("EXACT")) Positive else TextSecondary)

            Spacer(Modifier.height(16.dp))
            SectionLabel("Masa")
            Spacer(Modifier.height(8.dp))
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

            Spacer(Modifier.height(16.dp))
            SectionLabel("Cât ai mâncat?")
            Spacer(Modifier.height(8.dp))
            Row {
                listOf("Tot" to defaultGrams, "½" to defaultGrams / 2, "⅓" to defaultGrams / 3, "¼" to defaultGrams / 4)
                    .forEach { (label, g) ->
                        Box(
                            Modifier
                                .padding(end = 8.dp)
                                .clip(ChipShape)
                                .background(if (grams == g) TabPillActive else Surface2)
                                .pressable({ grams = g })
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(label, style = BodyStrong.copy(color = if (grams == g) Accent2 else TextSecondary))
                        }
                    }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SecondaryButton("−5 g", onClick = { grams = (grams - 5).coerceAtLeast(5) }, padV = 8.dp)
                Spacer(Modifier.width(12.dp))
                Text("$grams g", style = heroNumeral(24))
                Spacer(Modifier.width(12.dp))
                SecondaryButton("+5 g", onClick = { grams += 5 }, padV = 8.dp)
            }

            Spacer(Modifier.height(14.dp))
            val f = grams / 100.0
            Text(
                "${(product.kcal100 * f).toInt()} kcal · P ${(product.protein100 * f).toInt()} · C ${(product.carbs100 * f).toInt()} · G ${(product.fat100 * f).toInt()}",
                style = BodyStrong
            )
            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                text = "Confirmă",
                onClick = { onConfirm(mealType, grams) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** Activarea analizei AI: cheia Gemini a utilizatorului — gratuită, o dată. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiKeySheet(onSaved: (String) -> Unit, onClose: () -> Unit) {
    var key by remember { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Surface1, shape = SheetShape
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Activează analiza pozelor", style = TitleModule.copy(fontSize = 20.sp))
            Spacer(Modifier.height(6.dp))
            Text(
                "FORJA folosește AI-ul Google (Gemini) cu cheia TA gratuită — pozele pleacă doar către contul tău, nu prin serverele FORJA.",
                style = Body
            )
            Spacer(Modifier.height(12.dp))
            Text("PAȘII (2 MINUTE, O SINGURĂ DATĂ)", style = monoLabel(9, 0.14f).copy(color = Accent2))
            Spacer(Modifier.height(6.dp))
            Text(
                "1. Deschide aistudio.google.com/apikey (logat cu contul Google)\n" +
                    "2. Apasă „Create API key\" și copiază codul\n" +
                    "3. Lipește-l aici",
                style = BodySmall.copy(lineHeight = 19.sp)
            )
            Spacer(Modifier.height(14.dp))
            TextField(
                value = key,
                onValueChange = { key = it },
                singleLine = true,
                placeholder = { Text("AIza…", style = BodySmall) },
                textStyle = BodyStrong.copy(fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth().clip(SecondaryShape),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Surface2, unfocusedContainerColor = Surface2,
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    cursorColor = Accent2,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                )
            )
            Spacer(Modifier.height(14.dp))
            PrimaryButton(
                text = "Salvează cheia",
                onClick = { if (key.trim().length > 20) onSaved(key.trim()) },
                modifier = Modifier.fillMaxWidth(),
                enabled = key.trim().length > 20
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FoodSearchSheet(vm: NutritionViewModel, onClose: () -> Unit) {
    val results by vm.searchResults.collectAsState()
    val busy by vm.searchBusy.collectAsState()
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Surface1,
        shape = SheetShape
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).fillMaxHeight(0.85f)) {
            Text("Caută un aliment", style = TitleModule.copy(fontSize = 20.sp))
            Spacer(Modifier.height(12.dp))
            TextField(
                value = query,
                onValueChange = { query = it; vm.search(it) },
                singleLine = true,
                placeholder = { Text("ex: iaurt grecesc", style = Body) },
                textStyle = BodyStrong.copy(fontSize = 15.sp),
                modifier = Modifier.fillMaxWidth().clip(SecondaryShape),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Surface2, unfocusedContainerColor = Surface2,
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    cursorColor = Accent2,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                )
            )
            Spacer(Modifier.height(12.dp))
            if (busy) {
                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent2, modifier = Modifier.size(24.dp))
                }
            }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                results.forEach { p ->
                    ForjaCard(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .pressable({ vm.pickSearchResult(p); onClose() }),
                        padding = 12.dp,
                        fill = Surface2
                    ) {
                        Text(p.name, style = BodyStrong.copy(fontSize = 14.sp), maxLines = 1)
                        Text(
                            "${p.kcal100} kcal / 100 g · P ${p.protein100.toInt()} · C ${p.carbs100.toInt()} · G ${p.fat100.toInt()}" +
                                (p.brand?.let { " · $it" } ?: ""),
                            style = BodyTiny.copy(color = TextSecondary), maxLines = 1
                        )
                    }
                }
                if (!busy && query.length >= 3 && results.isEmpty()) {
                    Text(
                        "Nimic găsit. Încearcă alt nume sau adaugă manual.",
                        style = BodySmall.copy(color = TextDim)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualAddSheet(vm: NutritionViewModel, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }
    var mealType by remember { mutableStateOf(1) }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Surface1,
        shape = SheetShape
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Adaug manual", style = TitleModule.copy(fontSize = 20.sp))
            Spacer(Modifier.height(4.dp))
            Text("Tu știi cel mai bine ce ai în farfurie.", style = BodySmall)
            Spacer(Modifier.height(12.dp))
            @Composable
            fun field(v: String, on: (String) -> Unit, label: String, number: Boolean = true, modifier: Modifier = Modifier) {
                TextField(
                    value = v, onValueChange = on, singleLine = true,
                    placeholder = { Text(label, style = BodySmall) },
                    keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text),
                    textStyle = BodyStrong.copy(fontSize = 14.sp),
                    modifier = modifier.clip(SecondaryShape),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Surface2, unfocusedContainerColor = Surface2,
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                        cursorColor = Accent2,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                    )
                )
            }
            field(name, { name = it }, "Numele mesei", number = false, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row {
                field(kcal, { kcal = it }, "kcal", modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                field(protein, { protein = it }, "P (g)", modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row {
                field(carbs, { carbs = it }, "C (g)", modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                field(fat, { fat = it }, "G (g)", modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
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
            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                text = "Salvează",
                onClick = {
                    if (name.isNotBlank() && kcal.toIntOrNull() != null) {
                        vm.addManual(
                            mealType, name.trim(),
                            kcal.toIntOrNull() ?: 0, protein.toIntOrNull() ?: 0,
                            carbs.toIntOrNull() ?: 0, fat.toIntOrNull() ?: 0, 100
                        )
                        onClose()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
