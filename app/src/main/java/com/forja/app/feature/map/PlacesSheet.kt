package com.forja.app.feature.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forja.app.core.data.RecommendedPlace
import com.forja.app.core.data.db.PlaceEntity
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*
import com.forja.app.core.util.Fmt

/** Amber-ul locurilor mele (același cu inelul pinului). */
val PlaceAmber = Color(0xFFF3B952)

private val ThresholdOptions = listOf(30 to "30 min", 60 to "1 h", 120 to "2 h", 300 to "5 h")

/**
 * Sheet „Locurile tale”: zonele deblocate, pragul de ședere, fiecare loc editabil
 * (nume, stele, notă), „Pe hartă”, „Recomandă prietenilor”, plus recomandările primite.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacesSheet(
    places: List<PlaceEntity>,
    recommended: List<RecommendedPlace>,
    thresholdMin: Int,
    cellCount: Int,
    onThreshold: (Int) -> Unit,
    onSave: (PlaceEntity) -> Unit,
    onDelete: (PlaceEntity) -> Unit,
    onRecommend: (PlaceEntity) -> Unit,
    onPick: (Double, Double) -> Unit,
    onClose: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Surface1, shape = SheetShape
    ) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .fillMaxHeight(0.9f)
                .verticalScroll(rememberScrollState())
                .imePadding()
        ) {
            Text("Locurile tale", style = TitleModule.copy(fontSize = 20.sp))
            Spacer(Modifier.height(2.dp))
            Text(
                "Din tot orașul ai deblocat $cellCount zone · ${places.size} locuri",
                style = BodySmall.copy(color = TextSecondary)
            )

            Spacer(Modifier.height(16.dp))

            // Pragul: cât trebuie să STAI ca să fie loc.
            ForjaCard(Modifier.fillMaxWidth(), fill = Surface2) {
                SectionLabel("Un loc = ai stat cel puțin")
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThresholdOptions.forEach { (min, label) ->
                        val on = thresholdMin == min
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(ChipShape)
                                .background(if (on) Accent else Surface1)
                                .border(1.dp, if (on) Accent2 else StrokeCardStrong, ChipShape)
                                .pressable({ onThreshold(min) })
                                .padding(vertical = 9.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                style = BodyStrong.copy(fontSize = 13.sp, color = if (on) OnAccent else TextSecondary)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Un loc = ai STAT aici cel puțin atât. Mersul pe stradă nu e vizită.",
                    style = BodyTiny.copy(color = TextDim)
                )
            }

            Spacer(Modifier.height(18.dp))
            SectionLabel("Locurile mele")
            Spacer(Modifier.height(8.dp))

            if (places.isEmpty()) {
                Text(
                    "Niciun loc încă. Locurile apar singure, după ce stai undeva destul. „Acasă e unde te oprești, nu unde treci.”",
                    style = BodySmall.copy(color = TextDim)
                )
            }

            places.forEach { p ->
                key(p.id) {
                    PlaceCard(
                        place = p,
                        onSave = onSave,
                        onDelete = onDelete,
                        onRecommend = onRecommend,
                        onPick = onPick
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            SectionLabel("De la prieteni")
            Spacer(Modifier.height(8.dp))

            if (recommended.isEmpty()) {
                Text(
                    "Când un prieten recomandă un loc, apare aici și pe hartă, cu punct albastru.",
                    style = BodySmall.copy(color = TextDim)
                )
            }

            recommended.forEach { r ->
                key(r.id) {
                    ForjaCard(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        fill = Surface2, padding = 12.dp
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(SleepRem)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.name.ifBlank { "Loc recomandat" }, style = BodyStrong.copy(fontSize = 14.sp))
                                Text(
                                    "de la ${r.ownerName} · ${Fmt.freshness(r.at)}",
                                    style = BodyTiny.copy(color = TextDim)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Pe hartă",
                                style = BodySmall.copy(color = Accent2),
                                modifier = Modifier.pressable({ onPick(r.lat, r.lng) })
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        StarRow(stars = r.stars, size = 16.dp, tint = SleepRem, onPick = null)
                        if (r.note.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(r.note, style = BodySmall.copy(color = TextSecondary))
                        }
                    }
                }
            }
        }
    }
}

/** Un loc al meu: nume + stele + notă editabile, „Pe hartă”, „Recomandă prietenilor”, „Șterge”. */
@Composable
private fun PlaceCard(
    place: PlaceEntity,
    onSave: (PlaceEntity) -> Unit,
    onDelete: (PlaceEntity) -> Unit,
    onRecommend: (PlaceEntity) -> Unit,
    onPick: (Double, Double) -> Unit
) {
    var name by remember(place.id) { mutableStateOf(place.name) }
    var note by remember(place.id) { mutableStateOf(place.note) }
    var stars by remember(place.id) { mutableStateOf(place.stars) }
    var confirmDelete by remember(place.id) { mutableStateOf(false) }
    // Dacă locul se schimbă din afară (alt ecran, sincronizare), preluăm valorile noi.
    LaunchedEffect(place.name, place.note, place.stars) {
        name = place.name; note = place.note; stars = place.stars
    }
    val dirty = name != place.name || note != place.note
    val edited = place.copy(name = name.trim(), note = note.trim(), stars = stars)
    val canRecommend = edited.name.isNotBlank() && edited.stars >= 1

    ForjaCard(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        fill = Surface2, padding = 12.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(PlaceAmber)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "Ai stat ${stayLabel(place.stayMs)} · ultima dată ${Fmt.freshness(place.lastAt)}",
                style = BodyTiny.copy(color = TextDim),
                modifier = Modifier.weight(1f)
            )
            if (place.recommended) {
                Text("recomandat", style = monoLabel(8, 0.12f).copy(color = SleepRem))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                "Pe hartă",
                style = BodySmall.copy(color = Accent2),
                modifier = Modifier.pressable({ onPick(place.lat, place.lng) })
            )
        }

        Spacer(Modifier.height(10.dp))
        PlaceField(
            value = name,
            onValue = { name = it.take(80) },
            label = "Nume",
            placeholder = "ex: Cafeneaua de pe colț"
        )
        Spacer(Modifier.height(8.dp))
        StarRow(stars = stars, size = 24.dp, tint = PlaceAmber) { s ->
            stars = s
            onSave(place.copy(stars = s, name = name.trim(), note = note.trim()))
        }
        Spacer(Modifier.height(8.dp))
        PlaceField(
            value = note,
            onValue = { note = it.take(300) },
            label = "Notă",
            placeholder = "ce e bun aici, cu cine ai fost…",
            singleLine = false
        )

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(
                text = if (place.recommended) "Recomandă din nou" else "Recomandă prietenilor",
                small = true,
                enabled = canRecommend,
                onClick = { onRecommend(edited) },
                modifier = Modifier.weight(1f)
            )
            if (dirty) {
                Spacer(Modifier.width(10.dp))
                SecondaryButton("Salvează", padV = 12.dp, onClick = { onSave(edited) })
            }
        }
        if (!canRecommend) {
            Spacer(Modifier.height(6.dp))
            Text("Pune-i un nume și cel puțin o stea ca să-l poți recomanda.", style = BodyTiny.copy(color = TextDim))
        }

        Spacer(Modifier.height(10.dp))
        if (confirmDelete) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Îl ștergi de tot?", style = BodySmall.copy(color = TextSecondary), modifier = Modifier.weight(1f))
                Text(
                    "Da, șterge",
                    style = BodySmall.copy(color = Error),
                    modifier = Modifier.pressable({ onDelete(place) })
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    "Nu",
                    style = BodySmall.copy(color = TextPrimary),
                    modifier = Modifier.pressable({ confirmDelete = false })
                )
            }
        } else {
            Text(
                "Șterge locul",
                style = BodyTiny.copy(color = TextDim),
                modifier = Modifier.pressable({ confirmDelete = true })
            )
        }
    }
}

/** Rând de 5 stele; `onPick == null` = doar afișare. */
@Composable
fun StarRow(stars: Int, size: Dp, tint: Color = PlaceAmber, onPick: ((Int) -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        for (i in 1..5) {
            val filled = i <= stars
            val m = if (onPick != null) Modifier.pressable({ onPick(i) }) else Modifier
            Icon(
                if (filled) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = "$i stele",
                tint = if (filled) tint else TextDim,
                modifier = m.size(size).padding(end = 2.dp)
            )
        }
    }
}

@Composable
private fun PlaceField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    placeholder: String,
    singleLine: Boolean = true
) {
    Column(Modifier.fillMaxWidth()) {
        Text(label.uppercase(), style = monoLabel(9, 0.14f))
        Spacer(Modifier.height(4.dp))
        TextField(
            value = value,
            onValueChange = onValue,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 2,
            placeholder = { Text(placeholder, style = BodySmall.copy(color = TextDim)) },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            textStyle = BodyStrong.copy(fontSize = 14.sp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(SecondaryShape)
                .border(1.dp, StrokeCardStrong, SecondaryShape),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Surface1, unfocusedContainerColor = Surface1,
                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                cursorColor = Accent2,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
            )
        )
    }
}

/** „2 h 15 min” / „45 min” — onest, fără rotunjiri în sus. */
fun stayLabel(ms: Long): String {
    val totalMin = (ms / 60_000L).toInt()
    return Fmt.durationHm(totalMin.coerceAtLeast(0))
}
