package com.forja.app.feature.nutrition

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*
import com.forja.app.core.network.FoodComponent
import com.forja.app.core.network.MealAnalysis
import kotlin.math.roundToInt

/** Sheet-ul comun de rezultat AI: componente editabile — cameră, galerie sau scanare. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealResultSheet(
    analysis: MealAnalysis,
    initialMealType: Int,
    onConfirm: (components: List<FoodComponent>, mealType: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var components by remember(analysis) { mutableStateOf(analysis.componente) }
    var mealType by remember { mutableStateOf(initialMealType) }

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
            Text(analysis.fel.ifBlank { "Masa ta" }, style = TitleModule.copy(fontSize = 22.sp))
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourceBadge("ESTIMARE AI", tone = Accent2)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Încredere: ${analysis.incredere} — o poți corecta oricând.",
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
                                if (j == i) scaleFood(cc, (cc.grame - 10).coerceAtLeast(5)) else cc
                            }
                        })
                        Spacer(Modifier.width(6.dp))
                        Text("${c.grame}g", style = heroNumeral(16))
                        Spacer(Modifier.width(6.dp))
                        SecondaryButton("+10g", padV = 6.dp, onClick = {
                            components = components.mapIndexed { j, cc ->
                                if (j == i) scaleFood(cc, cc.grame + 10) else cc
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
            Text(
                "${components.sumOf { it.kcal }} kcal · P ${components.sumOf { it.proteine }} · C ${components.sumOf { it.carbo }} · G ${components.sumOf { it.grasimi }}",
                style = BodyStrong.copy(fontSize = 16.sp)
            )

            Spacer(Modifier.height(14.dp))
            PrimaryButton(
                text = "Confirmă",
                onClick = { onConfirm(components, mealType) },
                modifier = Modifier.fillMaxWidth(),
                enabled = components.isNotEmpty()
            )
        }
    }
}

internal fun scaleFood(c: FoodComponent, newGrams: Int): FoodComponent {
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
