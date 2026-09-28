package com.forja.app.feature.nutrition

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Balance
import androidx.compose.material.icons.outlined.Cookie
import androidx.compose.material.icons.outlined.DeliveryDining
import androidx.compose.material.icons.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.Egg
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Face2
import androidx.compose.material.icons.outlined.Fastfood
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.material.icons.outlined.Grass
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocalDining
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.SetMeal
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.TrendingDown
import androidx.compose.material.icons.outlined.TrendingFlat
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Weekend
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*
import kotlin.math.roundToInt

/*
 * Chestionarul-conversație „ca la BitePal” (SPEC-4.2, reperul 361e1358 & co.): bară de progres sus, mascota cu
 * bula întrebării, rânduri mari cu iconiță, selecția = contur olive + fundal pal, buton „Înainte ›”.
 * Pași: gen · vârstă · înălțime · greutate · activitate · obiectiv · dietă · restricții · unde mănânci · ce schimbi ·
 * cum contează activitatea → rezultatul (IMC + rația din Mifflin-St Jeor) → „Personalizez planul” (pofte).
 * Se arată o dată; editabil din „Profilul tău”. Totul se salvează în DataStore-ul `forja_nutrition`.
 */

private const val STEP_SEX = 0
private const val STEP_AGE = 1
private const val STEP_HEIGHT = 2
private const val STEP_WEIGHT = 3
private const val STEP_ACTIVITY = 4
private const val STEP_GOAL = 5
private const val STEP_DIET = 6
private const val STEP_RESTRICTIONS = 7
private const val STEP_WHERE = 8
private const val STEP_CHANGE = 9
private const val STEP_MODE = 10
private const val STEP_RESULT = 11
private const val STEP_PERSONALIZE = 12
private const val STEPS = 13

private val RowShape = RoundedCornerShape(22.dp)
private val PaleFill = Color(0x2E6F855A)

@Composable
fun NutritionOnboarding(
    initial: BodyProfile,
    onDone: (BodyProfile) -> Unit,
    onClose: () -> Unit,
    bottomInset: Dp = 0.dp
) {
    var p by remember { mutableStateOf(initial) }
    var step by remember { mutableStateOf(STEP_SEX) }
    val reduced = LocalReducedMotion.current

    fun next() { if (step < STEPS - 1) step++ }
    fun back() { if (step == 0) onClose() else step-- }

    Box(
        Modifier
            .fillMaxSize()
            .background(Surface0)
            .pointerInput(Unit) { }
    ) {
        when (step) {
            STEP_RESULT -> ResultStep(p, bottomInset, onBack = ::back, onNext = ::next)
            STEP_PERSONALIZE -> PersonalizeStep(p, bottomInset, onBack = ::back, onPick = { c -> onDone(p.copy(cravings = c, done = true)) })
            else -> QuestionStep(
                step = step, p = p, reduced = reduced, bottomInset = bottomInset,
                onBack = ::back, onNext = ::next, onChange = { p = it }
            )
        }
    }
}

// ───────────────────────────── Pașii cu întrebare ─────────────────────────────

@Composable
private fun QuestionStep(
    step: Int, p: BodyProfile, reduced: Boolean, bottomInset: Dp,
    onBack: () -> Unit, onNext: () -> Unit, onChange: (BodyProfile) -> Unit
) {
    val question = when (step) {
        STEP_SEX -> "Cum te înregistrez?"
        STEP_AGE -> "Câți ani ai?"
        STEP_HEIGHT -> "Cât ești de înalt?"
        STEP_WEIGHT -> "Cât cântărești acum?"
        STEP_ACTIVITY -> "Cât te miști într-o săptămână?"
        STEP_GOAL -> "Ce vrei de la rație?"
        STEP_DIET -> "Ce fel de dietă preferi?"
        STEP_RESTRICTIONS -> "Ai restricții sau alergii?"
        STEP_WHERE -> "Unde mănânci de obicei?"
        STEP_CHANGE -> "Ce vrei să schimbi la felul cum mănânci?"
        else -> "Cum să contez mișcarea în rația de zi?"
    }
    val canNext = when (step) {
        STEP_SEX -> p.sex >= 0
        STEP_AGE -> p.age > 0
        STEP_HEIGHT -> p.heightCm > 0
        STEP_WEIGHT -> p.weightKg > 0f
        STEP_ACTIVITY -> p.activity >= 0
        STEP_GOAL -> p.goal >= 0
        STEP_DIET -> p.diet >= 0
        STEP_WHERE -> p.eatWhere >= 0
        else -> true
    }
    val nextLabel = when {
        step == STEP_RESTRICTIONS && p.restrictions.isEmpty() -> "Mănânc orice"
        step == STEP_CHANGE && p.changes.isEmpty() -> "Nimic din astea ›"
        else -> "Înainte ›"
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopRow(progress = (step + 1f) / STEPS, onBack = onBack)
        Spacer(Modifier.height(18.dp))
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Reveal(key = step, index = 0) {
                QuestionBubble(question)
            }
            Spacer(Modifier.height(if (step in listOf(STEP_AGE, STEP_HEIGHT, STEP_WEIGHT)) 36.dp else 28.dp))
            when (step) {
                STEP_SEX -> ChoiceRows(
                    icons = listOf(Icons.Outlined.Face, Icons.Outlined.Face2, Icons.Outlined.AutoAwesome),
                    labels = BodyProfile.SEXES, selected = p.sex, stepKey = step
                ) { onChange(p.copy(sex = it)) }
                STEP_AGE -> NumberPicker(
                    value = p.age.takeIf { it > 0 }?.toFloat() ?: 30f, unit = "ani", range = 14f..90f, step = 1f,
                    format = { "${it.roundToInt()}" }
                ) { onChange(p.copy(age = it.roundToInt())) }
                STEP_HEIGHT -> NumberPicker(
                    value = p.heightCm.takeIf { it > 0 }?.toFloat() ?: 170f, unit = "cm", range = 140f..210f, step = 1f,
                    format = { "${it.roundToInt()}" }
                ) { onChange(p.copy(heightCm = it.roundToInt())) }
                STEP_WEIGHT -> NumberPicker(
                    value = p.weightKg.takeIf { it > 0f } ?: 70f, unit = "kg", range = 40f..160f, step = 0.5f,
                    format = { v -> if (v == v.roundToInt().toFloat()) "${v.roundToInt()}" else Targets.fmtBmi(v) }
                ) { onChange(p.copy(weightKg = (it * 2).roundToInt() / 2f)) }
                STEP_ACTIVITY -> ChoiceRows(
                    icons = listOf(Icons.Outlined.Weekend, Icons.Outlined.DirectionsWalk, Icons.Outlined.DirectionsRun, Icons.Outlined.FitnessCenter),
                    labels = BodyProfile.ACTIVITIES, hints = BodyProfile.ACTIVITY_HINTS, selected = p.activity, stepKey = step
                ) { onChange(p.copy(activity = it)) }
                STEP_GOAL -> ChoiceRows(
                    icons = listOf(Icons.Outlined.TrendingDown, Icons.Outlined.TrendingFlat, Icons.Outlined.TrendingUp),
                    labels = BodyProfile.GOALS, hints = listOf("−15 % din consum", "exact cât arzi", "+10 % peste consum"),
                    selected = p.goal, stepKey = step
                ) { onChange(p.copy(goal = it)) }
                STEP_DIET -> ChoiceRows(
                    icons = listOf(
                        Icons.Outlined.Balance, Icons.Outlined.Eco, Icons.Outlined.Grass, Icons.Outlined.SetMeal,
                        Icons.Outlined.LocalDining, Icons.Outlined.Egg, Icons.Outlined.Grain
                    ),
                    labels = BodyProfile.DIETS, selected = p.diet, stepKey = step
                ) { onChange(p.copy(diet = it)) }
                STEP_RESTRICTIONS -> RestrictionChips(p.restrictions) { onChange(p.copy(restrictions = it)) }
                STEP_WHERE -> ChoiceRows(
                    icons = listOf(Icons.Outlined.Home, Icons.Outlined.DeliveryDining, Icons.Outlined.Restaurant),
                    labels = BodyProfile.EAT_WHERE, selected = p.eatWhere, stepKey = step
                ) { onChange(p.copy(eatWhere = it)) }
                STEP_CHANGE -> MultiRows(
                    icons = listOf(Icons.Outlined.Cookie, Icons.Outlined.Fastfood, Icons.Outlined.Speed, Icons.Outlined.Eco, Icons.Outlined.Psychology),
                    labels = BodyProfile.CHANGES, selected = p.changes, stepKey = step
                ) { onChange(p.copy(changes = it)) }
                STEP_MODE -> ChoiceRows(
                    icons = listOf(Icons.Outlined.Speed, Icons.Outlined.Whatshot),
                    labels = listOf("Smart", "Toate caloriile"),
                    hints = listOf("Doar ce arzi peste nivelul tău obișnuit se adaugă la rație", "Fiecare calorie arsă se adaugă la rația zilei"),
                    badges = listOf("recomandat", null),
                    selected = p.activityMode, stepKey = step
                ) { onChange(p.copy(activityMode = it)) }
            }
            Spacer(Modifier.height(100.dp))
        }
    }
    // Butonul, ca o pastilă jos-centru.
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 22.dp + bottomInset), contentAlignment = Alignment.BottomCenter) {
        PrimaryButton(
            text = nextLabel, onClick = onNext, enabled = canNext,
            modifier = Modifier.widthIn(min = 200.dp)
        )
    }
}

@Composable
private fun TopRow(progress: Float, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "‹", style = TitleModule.copy(fontSize = 34.sp, lineHeight = 34.sp),
            modifier = Modifier.pressable(onBack).padding(horizontal = 10.dp)
        )
        Spacer(Modifier.width(14.dp))
        ProgressBar(progress = progress, modifier = Modifier.weight(1f), height = 8.dp, brush = Brush.horizontalGradient(listOf(Accent2, Positive)))
        Spacer(Modifier.width(10.dp))
    }
}

/** Avatarul mascotei în cerc + bula mare cu întrebarea (colțul de lângă avatar ascuțit). */
@Composable
private fun QuestionBubble(text: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        MascotAvatar(hat = MascotHat.Chef, size = 56.dp)
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 24.dp))
                .background(Surface1)
                .border(1.dp, StrokeCardStrong, RoundedCornerShape(topStart = 6.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 24.dp))
                .padding(horizontal = 20.dp, vertical = 22.dp)
        ) {
            Text(text, style = TitleModule.copy(fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.Black))
        }
    }
}

/** Rânduri mari, o singură alegere. */
@Composable
private fun ChoiceRows(
    icons: List<ImageVector>, labels: List<String>, selected: Int, stepKey: Int,
    hints: List<String>? = null, badges: List<String?>? = null,
    onPick: (Int) -> Unit
) {
    labels.forEachIndexed { i, label ->
        Reveal(key = stepKey, index = i + 1, staggerMs = 50) {
            OptionRow(
                icon = icons.getOrElse(i) { icons.last() }, title = label, hint = hints?.getOrNull(i),
                badge = badges?.getOrNull(i), selected = selected == i, multi = false
            ) { onPick(i) }
        }
    }
}

/** Rânduri mari, alegere multiplă (bilă la dreapta, ca la BitePal). */
@Composable
private fun MultiRows(
    icons: List<ImageVector>, labels: List<String>, selected: Set<String>, stepKey: Int,
    onChange: (Set<String>) -> Unit
) {
    labels.forEachIndexed { i, label ->
        Reveal(key = stepKey, index = i + 1, staggerMs = 50) {
            OptionRow(icon = icons.getOrElse(i) { icons.last() }, title = label, selected = label in selected, multi = true) {
                onChange(if (label in selected) selected - label else selected + label)
            }
        }
    }
}

@Composable
private fun OptionRow(
    icon: ImageVector, title: String, selected: Boolean, multi: Boolean,
    hint: String? = null, badge: String? = null, onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(RowShape)
            .background(if (selected) PaleFill else Surface1)
            .border(if (selected) 2.dp else 1.dp, if (selected) Accent2 else StrokeCardStrong, RowShape)
            .pressable(onClick)
            .padding(horizontal = 18.dp, vertical = if (hint != null) 18.dp else 22.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) Accent2 else EmberHot, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = BodyStrong.copy(fontSize = 19.sp))
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.clip(ChipShape).background(Positive).padding(horizontal = 7.dp, vertical = 3.dp)) {
                        Text(badge.uppercase(), style = monoLabel(8, 0.10f).copy(color = Color(0xFF0A0A0B)))
                    }
                }
            }
            if (hint != null) {
                Spacer(Modifier.height(3.dp))
                Text(hint, style = BodySmall.copy(color = TextSecondary))
            }
        }
        if (multi) {
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.size(22.dp).clip(CircleShape).background(if (selected) Accent2 else SwitchOff),
                contentAlignment = Alignment.Center
            ) {
                if (selected) Text("✓", style = BodyStrong.copy(fontSize = 13.sp, color = OnAccent))
            }
        }
    }
}

/** Cip-uri pentru restricții/alergii (alegere multiplă), pe rânduri care se împachetează. */
@Composable
private fun RestrictionChips(selected: Set<String>, onChange: (Set<String>) -> Unit) {
    // Împachetare simplă pe rânduri de câte 3 — fără FlowRow (nimic experimental).
    BodyProfile.RESTRICTIONS.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.Center) {
            row.forEach { r ->
                val on = r in selected
                Row(
                    Modifier
                        .padding(horizontal = 4.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (on) PaleFill else Surface1)
                        .border(if (on) 2.dp else 1.dp, if (on) Accent2 else StrokeCardStrong, RoundedCornerShape(20.dp))
                        .pressable({ onChange(if (on) selected - r else selected + r) })
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) Accent2 else SwitchOff))
                    Spacer(Modifier.width(8.dp))
                    Text(r, style = BodyStrong.copy(fontSize = 16.sp))
                }
            }
        }
    }
}

/** Numeral mare + slider lat; pasul se rotunjește la `step`. */
@Composable
private fun NumberPicker(
    value: Float, unit: String, range: ClosedFloatingPointRange<Float>, step: Float,
    format: (Float) -> String, onValue: (Float) -> Unit
) {
    var v by remember { mutableStateOf(value.coerceIn(range)) }
    LaunchedEffect(Unit) { onValue(v) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(format(v), style = heroNumeral(72))
            Spacer(Modifier.width(8.dp))
            Text(unit, style = TitleModule.copy(fontSize = 24.sp, color = TextSecondary), modifier = Modifier.padding(bottom = 10.dp))
        }
        Spacer(Modifier.height(22.dp))
        Slider(
            value = v,
            onValueChange = { raw ->
                val snapped = (raw / step).roundToInt() * step
                v = snapped.coerceIn(range)
                onValue(v)
            },
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = Accent2, activeTrackColor = Accent2, inactiveTrackColor = SwitchOff),
            modifier = Modifier.fillMaxWidth().height(44.dp)
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(format(range.start), style = BodyTiny.copy(color = TextDim))
            Text(format(range.endInclusive), style = BodyTiny.copy(color = TextDim))
        }
        Spacer(Modifier.height(18.dp))
        Row {
            SecondaryButton("−", padV = 8.dp, onClick = { v = (v - step).coerceIn(range); onValue(v) })
            Spacer(Modifier.width(12.dp))
            SecondaryButton("+", padV = 8.dp, onClick = { v = (v + step).coerceIn(range); onValue(v) })
        }
    }
}

// ───────────────────────────── Rezultatul: IMC + rația ─────────────────────────────

@Composable
private fun ResultStep(p: BodyProfile, bottomInset: Dp, onBack: () -> Unit, onNext: () -> Unit) {
    val targets = remember(p) { Targets.of(p) }
    val bmi = p.bmi
    val band = Bmi.band(bmi)
    // Banda de sus · cardul care se derulează · bara fixă cu butonul. Butonul nu mai plutește peste rație
    // (acoperea rândul de jos pe 393 dp și mai mult pe S23); textul se stinge sub bară, nu se taie.
    Column(Modifier.fillMaxSize()) {
        // Banda pal-olive de sus, cu progresul și mascota fericită.
        Column(Modifier.fillMaxWidth().background(Color(0xFF1B2417)).statusBarsPadding()) {
            TopRow(progress = (STEP_RESULT + 1f) / STEPS, onBack = onBack)
            Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.BottomCenter) {
                PopIn { Mascot(state = MascotState.Happy, hat = MascotHat.Chef, size = 150.dp) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                // Cardul începe sub bandă, întreg (cu offset negativ, colțurile de sus intrau sub ea).
                Spacer(Modifier.height(14.dp))
                Reveal(index = 0) {
                    ForjaCard(Modifier.fillMaxWidth(), radius = 22.dp, padding = 18.dp) {
                        Text("Indicele tău", style = Body.copy(color = TextSecondary, fontSize = 15.sp))
                        Spacer(Modifier.height(4.dp))
                        Text("Indice de masă corporală", style = BodyTiny.copy(color = TextDim))
                        Spacer(Modifier.height(24.dp))
                        BmiScale(bmi)
                        Spacer(Modifier.height(18.dp))
                        // Caseta verde: titlul categoriei + două propoziții calde, oneste.
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(PaleFill)
                                .padding(16.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(20.dp).clip(CircleShape).background(Positive), contentAlignment = Alignment.Center) {
                                    Text("✓", style = BodyStrong.copy(fontSize = 12.sp, color = Color(0xFF0A0A0B)))
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(band.title, style = BodyStrong.copy(fontSize = 17.sp))
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(band.text, style = Body.copy(color = TextPrimary, fontSize = 15.sp, lineHeight = 21.sp))
                        }
                        Spacer(Modifier.height(20.dp))
                        // Rația vine imediat după indice (e rezultatul de zi cu zi, trebuie să se vadă fără derulare pe
                        // cât se poate), apoi pe ce se sprijină. Kcal pe un rând, macro-urile pe al doilea (încap și pe
                        // 360 dp, fără „G 62 g” rămas singur). Cum e calculată stă la „i”, nu într-un rând gri sub cifre.
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            SectionLabel("Rația ta", color = Accent2, modifier = Modifier.weight(1f))
                            if (targets != null) {
                                InfoDot(
                                    title = "Rația ta",
                                    text = "Mifflin-St Jeor, ${Targets.explain(p)}. Consum ${Targets.fmt(targets.tdee)}\u00A0kcal pe zi, " +
                                        "obiectiv: ${BodyProfile.GOALS[p.goal.coerceIn(0, 2)].lowercase()}. Reper, nu prescripție.",
                                    size = 18
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        if (targets != null) {
                            Text(targets.kcalLabel, style = BodyStrong.copy(fontSize = 22.sp))
                            Spacer(Modifier.height(2.dp))
                            Text(targets.macros, style = Body.copy(color = TextSecondary, fontSize = 15.sp))
                        } else {
                            Text("Lipsesc date. Mergi înapoi și completează.", style = BodyStrong.copy(fontSize = 16.sp))
                        }
                        Spacer(Modifier.height(22.dp))
                        InfoRow(Icons.Outlined.DirectionsRun, "Activitate", BodyProfile.ACTIVITIES.getOrElse(p.activity) { "—" })
                        InfoRow(Icons.Outlined.Eco, "Dietă", BodyProfile.DIETS.getOrElse(p.diet) { "Echilibrată" })
                        InfoRow(Icons.Outlined.LocalFireDepartment, "Metabolism", targets?.let { Bmi.metabolism(it, p) } ?: "—", last = true)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(24.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Surface0)))
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .background(Surface0)
                .navigationBarsPadding()
                .padding(top = 6.dp, bottom = 22.dp + bottomInset),
            contentAlignment = Alignment.Center
        ) {
            PrimaryButton(text = "Înainte ›", onClick = onNext, enabled = targets != null, modifier = Modifier.widthIn(min = 200.dp))
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String, last: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(bottom = if (last) 0.dp else 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = EmberHot, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(label, style = Body.copy(color = TextDim, fontSize = 14.sp))
            Text(value, style = BodyStrong.copy(fontSize = 17.sp))
        }
    }
}

/** Scala IMC 15–40: gradient albastru → verde → amber → roșu, cursorul „Tu: 23,4”, intervalele dedesubt. */
@Composable
private fun BmiScale(bmi: Float) {
    val frac = ((bmi - 15f) / 25f).coerceIn(0f, 1f)
    val label = "Tu: ${Targets.fmtBmi(bmi)}"
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val w = maxWidth
        Column(Modifier.fillMaxWidth()) {
            // Bula neagră cu vârf în jos, centrată pe cursor (ținută în lățimea cardului).
            Box(Modifier.fillMaxWidth().height(44.dp)) {
                val bubbleW = 96.dp
                val x = (w * frac - bubbleW / 2).coerceIn(0.dp, w - bubbleW)
                Column(Modifier.offset(x = x).width(bubbleW), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF17181C))
                            .border(1.dp, StrokeCardStrong, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) { Text(label, style = BodyStrong.copy(fontSize = 16.sp)) }
                    Canvas(Modifier.size(width = 14.dp, height = 7.dp)) {
                        val path = Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2f, size.height); close() }
                        drawPath(path, Color(0xFF17181C))
                    }
                }
            }
            // Gradațiile.
            Box(Modifier.fillMaxWidth().height(16.dp)) {
                listOf(15f to "15", 18.5f to "18,5", 25f to "25", 30f to "30", 40f to "40").forEach { (v, t) ->
                    val f = (v - 15f) / 25f
                    val tx = (w * f - 12.dp).coerceIn(0.dp, w - 24.dp)
                    Text(t, style = BodyTiny.copy(color = TextDim), modifier = Modifier.offset(x = tx).width(24.dp), textAlign = TextAlign.Center)
                }
            }
            Spacer(Modifier.height(6.dp))
            Canvas(Modifier.fillMaxWidth().height(22.dp)) {
                val barH = 10.dp.toPx()
                val y = size.height / 2f
                drawRoundRect(
                    Brush.horizontalGradient(0f to SleepDeep, 0.14f to SleepRem, 0.3f to Positive, 0.5f to Positive, 0.62f to EmberHot, 0.8f to EmberWarm, 1f to Error),
                    topLeft = Offset(0f, y - barH / 2f), size = Size(size.width, barH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barH / 2f)
                )
                val cx = size.width * frac
                drawCircle(Color.White, 10.dp.toPx(), Offset(cx, y))
                drawCircle(Positive, 10.dp.toPx(), Offset(cx, y), style = Stroke(3.dp.toPx()))
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("Subponderal", "Normal", "Supraponderal", "Obezitate").forEach { t ->
                    Text(t, style = BodyTiny.copy(color = if (t == Bmi.band(bmi).label) TextPrimary else TextDim))
                }
            }
        }
    }
}

// ───────────────────────────── „Personalizez planul” ─────────────────────────────

@Composable
private fun PersonalizeStep(p: BodyProfile, bottomInset: Dp, onBack: () -> Unit, onPick: (Int) -> Unit) {
    Column(Modifier.fillMaxSize().background(Color(0xFF15161A)).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("‹", style = TitleModule.copy(fontSize = 34.sp, lineHeight = 34.sp), modifier = Modifier.pressable(onBack).padding(horizontal = 10.dp))
        }
        Spacer(Modifier.height(30.dp))
        Reveal(index = 0) {
            Text(
                "Personalizez\nplanul",
                style = TitleModule.copy(fontSize = 44.sp, lineHeight = 46.sp, fontWeight = FontWeight.Black),
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(40.dp))
        Reveal(index = 1) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(26.dp))
                        .background(Surface1)
                        .border(1.dp, StrokeCardStrong, RoundedCornerShape(26.dp))
                        .padding(horizontal = 22.dp, vertical = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Ai pofte între mese?", style = TitleModule.copy(fontSize = 24.sp, lineHeight = 27.sp, fontWeight = FontWeight.Black))
                    Spacer(Modifier.height(20.dp))
                    BodyProfile.CRAVINGS.forEachIndexed { i, c ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                                .clip(RoundedCornerShape(30.dp))
                                .background(if (p.cravings == i) PaleFill else Surface2)
                                .border(if (p.cravings == i) 2.dp else 1.dp, if (p.cravings == i) Accent2 else StrokeCardStrong, RoundedCornerShape(30.dp))
                                .pressable({ onPick(i) })
                                .padding(vertical = 20.dp),
                            contentAlignment = Alignment.Center
                        ) { Text(c, style = BodyStrong.copy(fontSize = 18.sp)) }
                    }
                }
                Canvas(Modifier.fillMaxWidth().height(14.dp)) {
                    val cx = size.width / 2f
                    val path = Path().apply { moveTo(cx - 14.dp.toPx(), 0f); lineTo(cx + 14.dp.toPx(), 0f); lineTo(cx, size.height); close() }
                    drawPath(path, Surface1)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = bottomInset), contentAlignment = Alignment.BottomCenter) {
            Mascot(state = MascotState.Reading, hat = MascotHat.Chef, size = 190.dp)
        }
    }
}
