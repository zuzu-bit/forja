package com.forja.app.feature.nutrition

import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import com.forja.app.core.data.db.MealEntity
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*
import com.forja.app.core.media.Media
import com.forja.app.core.network.FoodProduct
import com.forja.app.core.network.MealReport
import com.forja.app.core.util.Fmt
import java.time.LocalTime

private const val IMG_BOWL = "https://t3.ftcdn.net/jpg/03/30/19/86/500_F_330198627_aQsy9t5HhOn7TIsd6FEB0FJvKz4IqdhH.jpg"
private const val IMG_COOK = "https://t4.ftcdn.net/jpg/05/03/88/17/500_F_503881704_hyhi1pOJrBNqQ0dJqK1Qceno2pa8KWiJ.jpg"

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
    val streak by vm.streak.collectAsState()
    val vmPending by vm.pending.collectAsState()
    val lookupError by vm.lookupError.collectAsState()
    val toast = LocalToast.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val geminiKey by app.prefs.geminiKey.collectAsState(initial = "")
    var keyOpen by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var manualOpen by remember { mutableStateOf(false) }
    var targetOpen by remember { mutableStateOf(false) }

    // ── Galerie: alegere manuală a unei poze pentru analiză ──
    var galleryAnalyzing by remember { mutableStateOf(false) }
    var galleryStages by remember { mutableStateOf(AnalyzeStages.idle) }
    var galleryReport by remember { mutableStateOf<MealReport?>(null) }
    var galleryBytes by remember { mutableStateOf<ByteArray?>(null) }

    val pickLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            galleryAnalyzing = true
            galleryStages = AnalyzeStages.idle
            scope.launch {
                val bytes = MealAnalyze.readUri(context, uri)
                if (bytes == null) {
                    galleryAnalyzing = false
                    toast.show("Poza nu s-a putut citi. Alege alta.")
                    return@launch
                }
                galleryBytes = bytes
                when (val res = MealAnalyze.analyzeJpeg(app, bytes) { galleryStages = it }) {
                    is AnalyzeOutcome.Ok -> { galleryAnalyzing = false; galleryReport = res.report }
                    is AnalyzeOutcome.Fail -> { galleryAnalyzing = false; toast.show(res.message) }
                }
            }
        }
    }
    fun pickFromGallery() {
        if (!galleryAnalyzing) pickLauncher.launch(
            androidx.activity.result.PickVisualMediaRequest(
                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
            )
        )
    }

    LaunchedEffect(lookupError) {
        lookupError?.let {
            toast.show(it)
            vm.clearError()
            searchOpen = true
        }
    }

    val serverOn = app.forjaApi.available
    fun photograph() { if (serverOn || geminiKey.isNotBlank()) onPhotograph() else keyOpen = true }

    Box(Modifier.fillMaxSize().background(Surface0)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 120.dp)
        ) {
            // Header foto — bolul cald, fără cifre peste el (cifrele stau în cardul zilei).
            Box(Modifier.fillMaxWidth().height(168.dp)) {
                AsyncImage(
                    model = IMG_BOWL,
                    contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                BoxScopeBottomScrim()
                TopScrim()
            }

            Spacer(Modifier.height(18.dp))

            // Ordinul de zi al modulului: ștampilă + două bătăi + ordin.
            ModuleHeader(
                stamp = "RAȚIE",
                title = "Mănânci real. Vezi clar.",
                order = "Scanezi codul sau fotografiezi masa. Codul e exact, poza e estimare. Tu confirmi porția.",
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            Spacer(Modifier.height(18.dp))

            // ── Cardul zilei: inel kcal, bare macro, seria, mascota ──
            DayCard(
                meals = meals, kcal = kcal, target = target, streak = streak,
                onTarget = { targetOpen = true },
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            Spacer(Modifier.height(20.dp))

            // Jurnalul meselor de azi
            SectionLabel("Mesele de azi", Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(10.dp))
            Column(Modifier.padding(horizontal = 20.dp)) {
                for (type in 0..3) {
                    val entries = meals.filter { it.mealType == type }
                    if (entries.isEmpty() && type == 3) continue
                    if (entries.isEmpty()) {
                        // Cardurile meselor — aceeași sticlă verde ca butoanele de mai jos.
                        ForjaCard(
                            Modifier.fillMaxWidth().padding(bottom = 10.dp),
                            fill = Color(0x249DB77E), stroke = Color(0x619DB77E), padding = 12.dp
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        when (type) {
                                            0 -> "Micul dejun — încă nimic"
                                            1 -> "Prânzul — încă nimic"
                                            else -> "Cina — încă nimic"
                                        },
                                        style = BodyStrong.copy(color = TextSecondary)
                                    )
                                    Text(mealTypeNames[type], style = monoLabel(8, 0.12f))
                                }
                                SecondaryButton("Adaugă", onClick = { searchOpen = true }, padV = 8.dp)
                            }
                        }
                    } else {
                        entries.forEach { m -> MealRow(m, onDelete = { vm.deleteMeal(m.id) }) }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            // Trei drumuri principale, cu imagini calde: poză, cod, manual.
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                ImageTile("Fotografiază", IMG_BOWL, onClick = { photograph() }, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                ImageTile("Scanează cod", IMG_COOK, onClick = onScan, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                ImageTile(
                    "Adaug manual",
                    Media.mediaUrl("471644726.jpg") ?: IMG_BOWL,
                    onClick = { manualOpen = true }, modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                ActionTile(
                    label = if (galleryAnalyzing) "se analizează…" else "Din galerie",
                    tint = Color(0xFF9DB77E),
                    onClick = { pickFromGallery() },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                ActionTile(
                    label = "Caută",
                    tint = Color(0xFF9DB77E),
                    onClick = { searchOpen = true },
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
                    text = "Poza pleacă la analiză cu model — prin serverul FORJA sau, dacă serverul lipsește, direct la Google cu cheia ta Gemini. Modelul estimează, nu cântărește. Codul de bare dă valori exacte din OpenFoodFacts. Nimic nu se salvează până nu confirmi."
                )
            }

            // Un singur citat cald pe ecran — același toată ziua.
            Spacer(Modifier.height(24.dp))
            WarmQuote(Tone.ofDay(Tone.nutrition), Modifier.padding(horizontal = 20.dp))
        }

        // Ecranul „Analiză…” pentru poza din galerie: mascota + pașii reali.
        if (galleryAnalyzing) {
            Box(
                Modifier.fillMaxSize().background(Color(0x99000000)).padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                AnalyzeStagePanel(galleryStages, Modifier.fillMaxWidth())
            }
        }
    }

    if (keyOpen) {
        AiKeySheet(
            onSaved = { key ->
                scope.launch {
                    app.prefs.setGeminiKey(key)
                    keyOpen = false
                    toast.show("Cheie salvată. Fotografiază prima masă.")
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
    if (targetOpen) {
        TargetSheet(current = target, onSave = { vm.setKcalTarget(it); targetOpen = false }, onClose = { targetOpen = false })
    }
    vmPending?.let { p ->
        PortionSheet(
            product = p.product,
            source = p.source,
            onConfirm = { mealType, grams ->
                vm.confirmPending(mealType, grams)
                toast.show("Salvat. Îl poți șterge oricând din jurnal.")
            },
            onDismiss = { vm.dismissPending() }
        )
    }

    // Rezultatul analizei din galerie (poză aleasă manual).
    galleryReport?.let { r ->
        MealResultSheet(
            report = r,
            initialMealType = MealAnalyze.mealTypeForTime(System.currentTimeMillis()),
            onConfirm = { components, mealType ->
                scope.launch {
                    val photoPath = galleryBytes?.let { MealAnalyze.savePhoto(context, it) }
                    val meal = MealAnalyze.saveMeal(app, r, components, mealType, System.currentTimeMillis(), photoPath)
                    toast.show("Salvat: ${meal.kcal} kcal.")
                    galleryReport = null
                }
            },
            onDismiss = { galleryReport = null },
            onRetake = { galleryReport = null; pickFromGallery() },
            dayTarget = target
        )
    }
}

/** Replica scurtă a bucătarului, după context — fără emoji, fără promisiuni. */
internal fun chefLine(meals: List<MealEntity>, kcal: Int, target: Int, streak: Int, hour: Int): String {
    val types = meals.map { it.mealType }.toSet()
    return when {
        meals.isEmpty() && hour < 11 -> "Rația de azi e pe drum."
        meals.isEmpty() -> "Nimic notat încă. Prima masă contează."
        kcal > target -> "Peste linie azi. Notat, nu judecat."
        streak >= 3 && hour >= 19 -> "$streak zile la rând. Se vede disciplina."
        2 in types && hour >= 19 -> "Cina e notată. Restul e odihnă."
        1 in types && hour in 12..17 -> "Prânz solid. Apa nu se uită."
        0 in types && hour < 12 -> "Micul dejun e bifat. Ține ritmul."
        kcal < target / 2 && hour >= 17 -> "Mai ai loc în rație. Nu sări cina."
        else -> "Ții linia. Continuă la fel."
    }
}

/** Cardul zilei: inel kcal din obiectiv (setabil), bare macro cumulate, seria și mascota cu o replică. */
@Composable
private fun DayCard(
    meals: List<MealEntity>,
    kcal: Int,
    target: Int,
    streak: Int,
    onTarget: () -> Unit,
    modifier: Modifier = Modifier
) {
    val protein = meals.sumOf { it.protein }
    val carbs = meals.sumOf { it.carbs }
    val fat = meals.sumOf { it.fat }
    // Repere orientative din obiectivul zilnic: 25 % P · 45 % C · 30 % G (nu prescripție).
    val pTarget = (target * 0.25 / 4).toInt().coerceAtLeast(1)
    val cTarget = (target * 0.45 / 4).toInt().coerceAtLeast(1)
    val fTarget = (target * 0.30 / 9).toInt().coerceAtLeast(1)
    val hour = remember { LocalTime.now().hour }
    var lineSalt by remember { mutableStateOf(0) }
    val line = remember(meals.size, kcal, target, streak, lineSalt) {
        if (lineSalt == 0) chefLine(meals, kcal, target, streak, hour)
        else CHEF_EXTRA[Math.floorMod(lineSalt, CHEF_EXTRA.size)]
    }

    ForjaCard(modifier.fillMaxWidth(), padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KcalRing(kcal = kcal, target = target, ringSize = 124.dp, key = target)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                MacroBar("PROTEINE", protein, MacroProteinColor, target = pTarget)
                Spacer(Modifier.height(8.dp))
                MacroBar("CARBO", carbs, MacroCarbColor, target = cTarget)
                Spacer(Modifier.height(8.dp))
                MacroBar("GRĂSIMI", fat, MacroFatColor, target = fTarget)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (kcal <= target) "mai ai ${target - kcal} kcal" else "peste cu ${kcal - target} kcal",
                style = BodySmall.copy(color = if (kcal <= target) TextSecondary else Error)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "obiectiv $target",
                style = monoLabel(8, 0.12f).copy(color = Accent2),
                modifier = Modifier
                    .clip(ChipShape)
                    .background(TabPillActive)
                    .pressable(onTarget)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Spacer(Modifier.weight(1f))
            Text(
                when (streak) {
                    0 -> "prima zi începe azi"
                    1 -> "1 zi la rând"
                    else -> "$streak zile la rând"
                },
                style = monoLabel(8, 0.12f).copy(color = if (streak > 0) Positive else TextDim)
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChefMascot(size = 52.dp, modifier = Modifier.pressable({ lineSalt++ }))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("BUCĂTARUL", style = monoLabel(8, 0.14f).copy(color = Accent2))
                Spacer(Modifier.height(2.dp))
                Text(line, style = Body.copy(color = TextPrimary))
            }
        }
    }
}

private val CHEF_EXTRA = listOf(
    "Farfuria de sus, în lumină. Restul fac eu.",
    "Apa nu se uită.",
    "Porția o decizi tu. Eu doar estimez.",
    "Codul de bare e exact. Poza e estimare."
)

/** O masă din jurnal: miniatură (locală), nume, oră, gramaj, sursă, macro-uri, kcal, ștergere. */
@Composable
private fun MealRow(m: MealEntity, onDelete: () -> Unit) {
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
                    modifier = Modifier.pressable(onDelete)
                )
            }
        }
    }
}

/** Tile cu imagine caldă în fundal și eticheta jos — cele trei drumuri principale. */
@Composable
private fun ImageTile(label: String, image: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Radii.card)
    Box(
        modifier
            .height(92.dp)
            .clip(shape)
            .background(Surface1)
            .border(1.dp, Color(0x619DB77E), shape)
            .pressable(onClick)
    ) {
        AsyncImage(model = image, contentDescription = label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color(0x330A0A0B), 0.5f to Color(0xB30A0A0B), 1f to Color(0xF20A0A0B))
            )
        )
        Text(
            label,
            style = BodyStrong.copy(fontSize = 13.sp),
            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)
        )
    }
}

/** Buton de acțiune pe sticlă verde: doar text, curat, în culoarea casei. */
@Composable
private fun ActionTile(
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
        Text(label, style = BodyStrong.copy(fontSize = 14.sp))
    }
}

/** Obiectivul zilnic: pași de 50 kcal, între 1200 și 4500. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetSheet(current: Int, onSave: (Int) -> Unit, onClose: () -> Unit) {
    var value by remember { mutableStateOf(current) }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Surface1, shape = SheetShape
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Obiectivul zilnic", style = TitleModule.copy(fontSize = 20.sp))
            Spacer(Modifier.height(4.dp))
            Text("Un reper, nu o pedeapsă. Îl schimbi oricând.", style = BodySmall)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SecondaryButton("−50", onClick = { value = (value - NutritionPrefs.STEP_KCAL).coerceAtLeast(NutritionPrefs.MIN_KCAL) }, padV = 8.dp)
                Spacer(Modifier.width(14.dp))
                Text("$value kcal", style = heroNumeral(28))
                Spacer(Modifier.width(14.dp))
                SecondaryButton("+50", onClick = { value = (value + NutritionPrefs.STEP_KCAL).coerceAtMost(NutritionPrefs.MAX_KCAL) }, padV = 8.dp)
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton(text = "Salvează", onClick = { onSave(value) }, modifier = Modifier.fillMaxWidth())
        }
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
            SectionLabel("Porția")
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
                "Analiza folosește modelul Gemini (Google) cu cheia ta gratuită. Pozele pleacă direct la contul tău Google, nu prin serverele FORJA. Ce primești e o estimare — o corectezi înainte de salvare.",
                style = Body
            )
            Spacer(Modifier.height(12.dp))
            Text("PAȘII (2 MINUTE, O SINGURĂ DATĂ)", style = monoLabel(9, 0.14f).copy(color = Accent2))
            Spacer(Modifier.height(6.dp))
            Text(
                "1. Deschide aistudio.google.com/apikey (logat cu contul Google)\n" +
                    "2. Apasă „Create API key” și copiază codul\n" +
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
            Text("Adaugă manual", style = TitleModule.copy(fontSize = 20.sp))
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
