package com.forja.app.feature.workout

import androidx.activity.ComponentActivity
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.Color as GfxColor
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*

/** Hub Antrenament: 3 planuri selectabile + lista de azi + „Începe sesiunea". */
@Composable
fun WorkoutScreen(onStartLive: () -> Unit) {
    val activity = LocalContext.current as ComponentActivity
    val vm: WorkoutViewModel = viewModel(viewModelStoreOwner = activity)
    val plans by vm.plans.collectAsState()
    val planIdx by vm.planIdx.collectAsState()
    val exercises by vm.planExercises.collectAsState()
    var editing by remember { mutableStateOf<com.forja.app.core.data.db.ExerciseEntity?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Surface0)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(bottom = 120.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text("Antrenament", style = TitleModule)
            SectionLabel("Planul tău")
        }

        // Carduri plan 150×188, stroke amber pe selecție, badge ACTIV
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp)) {
            itemsIndexed(plans, key = { _, p -> p.id }) { i, p ->
                val selected = i == planIdx
                val stroke by animateColorAsState(
                    if (selected) Color(0xA6FFB300) else Color(0x12FFFFFF),
                    Springs.natural(), label = "stroke"
                )
                val shape = RoundedCornerShape(Radii.card)
                Box(
                    Modifier
                        .padding(end = 12.dp)
                        .size(width = 150.dp, height = 188.dp)
                        .clip(shape)
                        .border(1.5.dp, stroke, shape)
                        .pressable({ vm.selectPlan(i) })
                ) {
                    AsyncImage(
                        model = p.cover, contentDescription = p.name,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    0f to Color(0x1A0A0A0B), 0.5f to Color(0x8C0A0A0B), 1f to Color(0xF20A0A0B)
                                )
                            )
                    )
                    if (selected) {
                        Box(
                            Modifier
                                .align(Alignment.TopStart)
                                .padding(10.dp)
                                .clip(ChipShape)
                                .background(AccentGradient)
                                .padding(horizontal = 7.dp, vertical = 3.dp)
                        ) {
                            Text("ACTIV", style = monoLabel(8, 0.12f).copy(color = OnAccent))
                        }
                    }
                    Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                        Text(p.name, style = BodyStrong.copy(fontSize = 15.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(3.dp))
                        Text(p.meta, style = monoLabel(8, 0.10f).copy(color = TextSecondary))
                    }
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        val plan = plans.getOrNull(planIdx)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionLabel("Azi · ${plan?.name ?: ""}")
            Text(
                "${exercises.size} EXERCIȚII",
                style = monoLabel(9, 0.12f)
            )
        }
        Spacer(Modifier.height(10.dp))

        // Rânduri exercițiu: thumb 54×66, serii×rep×kg, „VIDEO DEMO · LOOP", play
        Column(Modifier.padding(horizontal = 20.dp)) {
            exercises.forEachIndexed { pos, e ->
                ForjaCard(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    padding = 10.dp
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            model = e.thumb, contentDescription = e.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(width = 54.dp, height = 66.dp)
                                .clip(ThumbShape)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.name, style = BodyStrong.copy(fontSize = 14.sp))
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "${e.sets} SERII × ${e.reps} REP · ${e.load}${if (e.loadLabel == "KG") " KG" else ""}",
                                style = monoLabel(9, 0.10f).copy(color = TextSecondary)
                            )
                            Spacer(Modifier.height(3.dp))
                            Text("ATINGE ✎ CA SĂ EDITEZI", style = monoLabel(8, 0.12f).copy(color = Accent2))
                        }
                        Icon(
                            Icons.Filled.Edit, contentDescription = "Editează",
                            tint = TextSecondary,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Surface2)
                                .border(1.dp, StrokeCardStrong, RoundedCornerShape(10.dp))
                                .pressable({ editing = e })
                                .padding(7.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Filled.PlayArrow, contentDescription = "Pornește",
                            tint = OnAccent,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(AccentGradient)
                                .pressable({ vm.startSession(fromExercise = pos); onStartLive() })
                                .padding(6.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        PrimaryButton(
            text = "Începe sesiunea",
            onClick = { vm.startSession(0); onStartLive() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        )
    }

    editing?.let { ex ->
        ExerciseEditSheet(
            exercise = ex,
            onSave = { sets, reps, load -> vm.updateExercise(ex.id, sets, reps, load); editing = null },
            onClose = { editing = null }
        )
    }
}

/** Editează seriile, repetările și greutatea unui exercițiu, înainte de sesiune. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExerciseEditSheet(
    exercise: com.forja.app.core.data.db.ExerciseEntity,
    onSave: (sets: Int, reps: Int, load: String) -> Unit,
    onClose: () -> Unit
) {
    var sets by remember { mutableStateOf(exercise.sets) }
    var reps by remember { mutableStateOf(exercise.reps) }
    var load by remember { mutableStateOf(exercise.load) }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Surface1, shape = SheetShape
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(exercise.name, style = TitleModule.copy(fontSize = 20.sp))
            Spacer(Modifier.height(4.dp))
            Text("Ajustează-le pe ale tale — se salvează.", style = BodySmall)
            Spacer(Modifier.height(18.dp))

            @Composable
            fun stepper(label: String, value: Int, onMinus: () -> Unit, onPlus: () -> Unit) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    SectionLabel(label)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SecondaryButton("−", onClick = onMinus, padV = 8.dp)
                        Text("$value", style = heroNumeral(30), modifier = Modifier.padding(horizontal = 16.dp))
                        SecondaryButton("+", onClick = onPlus, padV = 8.dp)
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                stepper("Serii", sets, { sets = (sets - 1).coerceAtLeast(1) }, { sets = (sets + 1).coerceAtMost(20) })
                stepper("Repetări", reps, { reps = (reps - 1).coerceAtLeast(1) }, { reps = (reps + 1).coerceAtMost(100) })
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel("Greutate / sarcină")
            Spacer(Modifier.height(8.dp))
            TextField(
                value = load,
                onValueChange = { load = it },
                singleLine = true,
                placeholder = { Text("ex: 62,5 · corp · 2×14", style = BodySmall) },
                textStyle = BodyStrong.copy(fontSize = 15.sp),
                modifier = Modifier.fillMaxWidth().clip(SecondaryShape),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Surface2, unfocusedContainerColor = Surface2,
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    cursorColor = Accent2,
                    focusedIndicatorColor = GfxColor.Transparent, unfocusedIndicatorColor = GfxColor.Transparent
                )
            )
            Spacer(Modifier.height(18.dp))
            PrimaryButton("Salvează", onClick = { onSave(sets, reps, load.trim()) }, modifier = Modifier.fillMaxWidth())
        }
    }
}
