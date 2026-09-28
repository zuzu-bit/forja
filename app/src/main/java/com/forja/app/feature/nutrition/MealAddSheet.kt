package com.forja.app.feature.nutrition

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*

/** Cele patru drumuri ale unei mese. */
enum class MealAddWay { Photo, Gallery, Barcode, Manual }

/**
 * „Adaugă” pe o masă: patru opțiuni mari, tipul mesei pre-selectat (se poate schimba din cipuri).
 * Alegerea pleacă cu tipul mesei la fluxul respectiv — cameră, galerie, scanner sau formular.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealAddSheet(
    initialMealType: Int,
    voice: MascotVoice,
    onPick: (MealAddWay, mealType: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var mealType by remember { mutableStateOf(initialMealType.coerceIn(0, 3)) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Surface1, shape = SheetShape
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            MascotSays(
                text = when (voice) {
                    MascotVoice.Sergent -> "Ce adaugi la ${mealTypeNames[mealType].lowercase()}?"
                    MascotVoice.Camarad -> "Ce ai mâncat la ${mealTypeNames[mealType].lowercase()}?"
                    MascotVoice.Antrenor -> "${mealTypeNames[mealType].lowercase().replaceFirstChar { it.uppercase() }}. Ce punem?"
                    MascotVoice.Ghid -> "Alege cum notăm ${mealTypeNames[mealType].lowercase()}."
                },
                hat = MascotHat.Chef
            )
            Spacer(Modifier.height(14.dp))
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
            AddOption(Icons.Outlined.PhotoCamera, "Fotografiază", "Estimare cu model, porții editabile") { onPick(MealAddWay.Photo, mealType) }
            AddOption(Icons.Outlined.PhotoLibrary, "Din galerie", "O poză deja făcută") { onPick(MealAddWay.Gallery, mealType) }
            AddOption(Icons.Outlined.QrCodeScanner, "Scanează cod", "Exact, din eticheta produsului") { onPick(MealAddWay.Barcode, mealType) }
            AddOption(Icons.Outlined.Edit, "Adaug manual", "Tu scrii valorile") { onPick(MealAddWay.Manual, mealType) }
        }
    }
}

@Composable
private fun AddOption(icon: ImageVector, title: String, hint: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(shape)
            .background(Surface2)
            .border(1.dp, StrokeCardStrong, shape)
            .pressable(onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(TabPillActive),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Accent2, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = BodyStrong.copy(fontSize = 17.sp))
            Text(hint, style = BodySmall.copy(color = TextDim))
        }
    }
}

/** „Cum îți vorbește Bucătarul”: patru carduri (Sergent / Camarad / Antrenor / Ghid), selecția cu contur olive. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSheet(current: MascotVoice, onPick: (MascotVoice) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Surface1, shape = SheetShape
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            MascotSays(text = "Cum vrei să-ți vorbesc?", hat = MascotHat.Chef)
            Spacer(Modifier.height(16.dp))
            val voices = MascotVoice.entries
            for (row in 0 until 2) {
                Row(Modifier.fillMaxWidth()) {
                    for (col in 0 until 2) {
                        val v = voices[row * 2 + col]
                        VoiceCard(v, selected = v == current, modifier = Modifier.weight(1f)) { onPick(v) }
                        if (col == 0) Spacer(Modifier.width(10.dp))
                    }
                }
                if (row == 0) Spacer(Modifier.height(10.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text("Schimbă doar replicile. Regulile și cifrele rămân aceleași.", style = BodyTiny.copy(color = TextDim))
        }
    }
}

@Composable
private fun VoiceCard(v: MascotVoice, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .clip(shape)
            .background(if (selected) TabPillActive else Surface2)
            .border(if (selected) 2.dp else 1.dp, if (selected) Accent2 else StrokeCardStrong, shape)
            .pressable(onClick)
            .padding(vertical = 18.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(84.dp).clip(CircleShape).background(
                when (v) {
                    MascotVoice.Sergent -> Accent.copy(alpha = 0.35f)
                    MascotVoice.Camarad -> EmberHot.copy(alpha = 0.18f)
                    MascotVoice.Antrenor -> Positive.copy(alpha = 0.16f)
                    MascotVoice.Ghid -> SleepRem.copy(alpha = 0.18f)
                }
            ),
            contentAlignment = Alignment.Center
        ) {
            Mascot(
                state = when (v) {
                    MascotVoice.Sergent -> MascotState.Idle
                    MascotVoice.Camarad -> MascotState.Happy
                    MascotVoice.Antrenor -> MascotState.Wink
                    MascotVoice.Ghid -> MascotState.Reading
                },
                hat = if (v == MascotVoice.Sergent) MascotHat.Helmet else MascotHat.Chef,
                size = 68.dp
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(v.label, style = BodyStrong.copy(fontSize = 16.sp))
        Spacer(Modifier.height(2.dp))
        Text(v.hint, style = BodyTiny.copy(color = TextDim), maxLines = 2)
    }
}
