package com.forja.app.feature.nutrition

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*
import com.forja.app.core.network.FoodComponent
import com.forja.app.core.network.MealCheck
import com.forja.app.core.network.MealItem
import com.forja.app.core.network.MealReport
import kotlin.math.roundToInt

/**
 * Foaia comună de rezultat (cameră, galerie): inel kcal, bare P/C/G(+fibre), componente cu gramaj editabil
 * (stepper −10/+10 și slider fin) care recalculează proporțional, scor de rație, sfat, ce nu se vede, porția.
 * Răspuns v1 → doar ce există (inel, bare, componente); widgeturile v2 se ascund când câmpurile lipsesc.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealResultSheet(
    report: MealReport,
    initialMealType: Int,
    onConfirm: (components: List<MealItem>, mealType: Int) -> Unit,
    onDismiss: () -> Unit,
    onRetake: (() -> Unit)? = null,
    dayTarget: Int = NutritionPrefs.DEFAULT_KCAL
) {
    // Fiecare linie ține și baza din care se scalează (răspunsul modelului sau ultima corectură manuală):
    // gramajul nou se calculează mereu din bază, nu din valorile deja rotunjite — altfel rotunjirile se adună
    // la fiecare pas de slider și 150 g → 5 g → 150 g nu s-ar mai întoarce la valorile de plecare.
    var rows by remember(report) { mutableStateOf(report.componente.map { EditableItem(base = it, item = it) }) }
    val components = rows.map { it.item }
    var mealType by remember { mutableStateOf(initialMealType) }
    var manual by remember(report) { mutableStateOf(false) }

    val kcal = components.sumOf { it.kcal }
    val protein = components.sumOf { it.proteine }
    val carbs = components.sumOf { it.carbo }
    val fat = components.sumOf { it.grasimi }
    val fibre = if (report.hasFibre) components.sumOf { it.fibre ?: 0 } else null
    val macroMax = maxOf(protein, carbs, fat, fibre ?: 0, 1)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Surface1, shape = SheetShape
    ) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // ── Titlul + sursa, onest ──
            PopIn {
                Text(report.fel.ifBlank { "Masa ta" }, style = TitleModule.copy(fontSize = 22.sp, lineHeight = 25.sp))
            }
            Spacer(Modifier.height(6.dp))
            Reveal(index = 1, key = report) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SourceBadge(report.sourceLabel, tone = Accent2)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Încredere ${report.incredere}. Corectează ce nu se potrivește.",
                        style = BodyTiny.copy(color = TextSecondary)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            // ── Inelul kcal + barele macro ──
            Reveal(index = 2, key = report) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MacroRing(
                        progress = kcal.toFloat() / dayTarget.coerceAtLeast(1),
                        ringSize = 124.dp, strokeWidth = 10.dp, key = report
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$kcal", style = heroNumeral(30))
                            Text("kcal", style = monoLabel(8, 0.14f).copy(color = TextDim))
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        MacroBar("PROTEINE", protein, MacroProteinColor, max = macroMax, key = report)
                        Spacer(Modifier.height(8.dp))
                        MacroBar("CARBO", carbs, MacroCarbColor, max = macroMax, key = report)
                        Spacer(Modifier.height(8.dp))
                        MacroBar("GRĂSIMI", fat, MacroFatColor, max = macroMax, key = report)
                        if (fibre != null) {
                            Spacer(Modifier.height(8.dp))
                            MacroBar("FIBRE", fibre, MacroFibreColor, max = macroMax, key = report)
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Din ${dayTarget} kcal pe zi. Estimare, nu cântar.",
                style = BodyTiny.copy(color = TextDim)
            )
            Spacer(Modifier.height(16.dp))

            // ── Componentele, editabile ──
            SectionLabel("Ce e în farfurie")
            Spacer(Modifier.height(8.dp))
            rows.forEachIndexed { i, row ->
                Reveal(index = 3 + i, staggerMs = 60, key = report) {
                    ComponentCard(
                        base = row.base,
                        item = row.item,
                        showFibre = report.hasFibre,
                        manual = manual,
                        onScale = { g -> rows = rows.mapIndexed { j, r -> if (j == i) r.copy(item = r.base.scaledTo(g)) else r } },
                        onEdit = { edited -> rows = rows.mapIndexed { j, r -> if (j == i) EditableItem(base = edited, item = edited) else r } },
                        onRemove = { rows = rows.filterIndexed { j, _ -> j != i } }
                    )
                }
            }
            if (components.isEmpty()) {
                Text("Nimic rămas în farfurie. Refă poza sau adaugă manual.", style = BodySmall.copy(color = TextDim))
            }

            // ── Scorul de rație (v2) ──
            report.scor?.let { s ->
                Spacer(Modifier.height(14.dp))
                Reveal(index = 4 + components.size, key = report) {
                    ForjaCard(Modifier.fillMaxWidth(), fill = Surface2, padding = 14.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ScoreDial(score = s.valoare, size = 80.dp, key = report)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                SectionLabel("Scor de rație", color = Accent2)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    s.motiv.ifBlank { "Fără motiv primit de la model." },
                                    style = Body.copy(color = TextPrimary)
                                )
                            }
                        }
                    }
                }
            }

            // ── Un sfat / Ce nu se vede / Porția (v2, fiecare doar dacă există) ──
            report.sfat?.let { tip ->
                Spacer(Modifier.height(12.dp))
                Reveal(index = 5 + components.size, key = report) {
                    InsightBlock("Un sfat", tip)
                }
            }
            if (report.observatii.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Reveal(index = 6 + components.size, key = report) {
                    InsightBlock("Ce nu se vede", report.observatii.joinToString("\n") { "· $it" })
                }
            }
            report.portie?.let { p ->
                Spacer(Modifier.height(12.dp))
                Reveal(index = 7 + components.size, key = report) {
                    InsightBlock("Porția", p)
                }
            }

            // ── Masa ──
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

            // ── Totalul + verificarea de pe telefon ──
            Spacer(Modifier.height(14.dp))
            Text(
                buildString {
                    append("$kcal kcal · P $protein · C $carbs · G $fat")
                    if (fibre != null) append(" · F $fibre")
                },
                style = BodyStrong.copy(fontSize = 16.sp)
            )
            if (components.isNotEmpty() && !MealCheck.coherent(components)) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Caloriile nu se potrivesc cu macro-urile (≈ ${MealCheck.kcalFromMacros(protein, carbs, fat)} kcal din P/C/G). Verifică gramajele.",
                    style = BodyTiny.copy(color = EmberHot)
                )
            }

            // ── Butoanele ──
            Spacer(Modifier.height(14.dp))
            PrimaryButton(
                text = "Confirmă",
                onClick = { onConfirm(components, mealType) },
                modifier = Modifier.fillMaxWidth(),
                enabled = components.isNotEmpty()
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                if (onRetake != null) {
                    SecondaryButton("Refă poza", onClick = onRetake, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                }
                SecondaryButton(
                    if (manual) "Gata cu editarea" else "Corectează manual",
                    onClick = { manual = !manual },
                    modifier = Modifier.weight(1f),
                    textColor = if (manual) Accent2 else TextPrimary
                )
            }
        }
    }
}

/** O linie editabilă: `base` = referința de scalare (modelul sau ultima corectură manuală), `item` = ce se vede acum. */
private data class EditableItem(val base: MealItem, val item: MealItem)

/** Un bloc scurt de text sub o etichetă mono — sfat, observații, porție. */
@Composable
private fun InsightBlock(label: String, text: String) {
    Column(Modifier.fillMaxWidth()) {
        SectionLabel(label)
        Spacer(Modifier.height(4.dp))
        Text(text, style = Body.copy(color = TextPrimary, lineHeight = 20.sp))
    }
}

/**
 * O componentă: nume + valori, stepper −10/+10 și slider fin pe gramaj — recalcul proporțional din `base`
 * ([onScale] primește gramajul nou, foaia scalează baza), iar în modul manual câmpuri numerice pentru kcal/P/C/G
 * ([onEdit]: valoarea scrisă devine noua bază, la gramajul de acum).
 */
@Composable
private fun ComponentCard(
    base: MealItem,
    item: MealItem,
    showFibre: Boolean,
    manual: Boolean,
    onScale: (grams: Int) -> Unit,
    onEdit: (MealItem) -> Unit,
    onRemove: () -> Unit
) {
    // Capătul sliderului: de trei ori porția de bază, cel puțin 300 g, rotunjit la 50.
    val sliderMax = remember(item.nume) { (maxOf(300, base.grame * 3) / 50 * 50).toFloat() }
    ForjaCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), fill = Surface2, padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.nume.ifBlank { "Componentă" }, style = BodyStrong.copy(fontSize = 14.sp), maxLines = 2)
                Text(
                    buildString {
                        append("${item.kcal} kcal · P ${item.proteine} · C ${item.carbo} · G ${item.grasimi}")
                        if (showFibre) append(" · F ${item.fibre ?: 0}")
                    },
                    style = BodyTiny.copy(color = TextSecondary)
                )
                item.incredere?.takeIf { it.isNotBlank() }?.let {
                    Text("încredere $it", style = monoLabel(8, 0.10f).copy(color = TextDim))
                }
            }
            SecondaryButton("−10g", padV = 6.dp, onClick = { onScale((item.grame - 10).coerceAtLeast(5)) })
            Spacer(Modifier.width(6.dp))
            Text("${item.grame}g", style = heroNumeral(16))
            Spacer(Modifier.width(6.dp))
            SecondaryButton("+10g", padV = 6.dp, onClick = { onScale(item.grame + 10) })
            Spacer(Modifier.width(6.dp))
            Text(
                "×",
                style = BodyStrong.copy(color = TextDim, fontSize = 16.sp),
                modifier = Modifier.pressable(onRemove).padding(4.dp)
            )
        }
        Slider(
            value = item.grame.toFloat().coerceIn(0f, sliderMax),
            onValueChange = { v ->
                val g = ((v / 5f).roundToInt() * 5).coerceAtLeast(5)
                if (g != item.grame) onScale(g)
            },
            valueRange = 0f..sliderMax,
            colors = SliderDefaults.colors(
                thumbColor = Accent2, activeTrackColor = Accent2,
                inactiveTrackColor = SwitchOff
            ),
            modifier = Modifier.fillMaxWidth().height(28.dp)
        )
        if (manual) {
            Spacer(Modifier.height(6.dp))
            Row {
                NumField(item.kcal, "kcal", Modifier.weight(1f)) { onEdit(item.copy(kcal = it)) }
                Spacer(Modifier.width(6.dp))
                NumField(item.proteine, "P", Modifier.weight(1f)) { onEdit(item.copy(proteine = it)) }
                Spacer(Modifier.width(6.dp))
                NumField(item.carbo, "C", Modifier.weight(1f)) { onEdit(item.copy(carbo = it)) }
                Spacer(Modifier.width(6.dp))
                NumField(item.grasimi, "G", Modifier.weight(1f)) { onEdit(item.copy(grasimi = it)) }
            }
            Spacer(Modifier.height(4.dp))
            Text("Valorile pe care le scrii rămân la gramajul de acum.", style = BodyTiny.copy(color = TextDim))
        }
    }
}

@Composable
private fun NumField(value: Int, label: String, modifier: Modifier, onValue: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Column(modifier) {
        Text(label, style = monoLabel(8, 0.12f).copy(color = TextDim))
        TextField(
            value = text,
            onValueChange = { t ->
                text = t.filter { it.isDigit() }.take(5)
                text.toIntOrNull()?.let(onValue)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = BodyStrong.copy(fontSize = 13.sp),
            modifier = Modifier.fillMaxWidth().clip(SecondaryShape),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Surface1, unfocusedContainerColor = Surface1,
                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                cursorColor = Accent2,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
            )
        )
    }
}

/** Compatibilitate: scalarea unei componente v1 (folosită de fluxurile vechi). */
internal fun scaleFood(c: FoodComponent, newGrams: Int): FoodComponent =
    MealItem.from(c).scaledTo(newGrams).toComponent()
