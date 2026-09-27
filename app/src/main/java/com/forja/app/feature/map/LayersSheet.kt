package com.forja.app.feature.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forja.app.core.designsystem.Accent
import com.forja.app.core.designsystem.Accent2
import com.forja.app.core.designsystem.BodySmall
import com.forja.app.core.designsystem.BodyStrong
import com.forja.app.core.designsystem.BodyTiny
import com.forja.app.core.designsystem.ChipShape
import com.forja.app.core.designsystem.OnAccent
import com.forja.app.core.designsystem.SheetShape
import com.forja.app.core.designsystem.StrokeCardStrong
import com.forja.app.core.designsystem.Surface1
import com.forja.app.core.designsystem.Surface2
import com.forja.app.core.designsystem.TextDim
import com.forja.app.core.designsystem.TextSecondary
import com.forja.app.core.designsystem.TitleModule
import com.forja.app.core.designsystem.components.ForjaCard
import com.forja.app.core.designsystem.components.ForjaSwitch
import com.forja.app.core.designsystem.components.SectionLabel
import com.forja.app.core.designsystem.components.pressable
import com.forja.app.core.map.MapLayers
import com.forja.app.core.map.NightMode

/**
 * Foaia „Straturi”: ce se vede pe hartă. Fiecare comutator schimbă doar vizibilitatea stratului — harta nu se reîncarcă.
 * Persistența e în DataStore-ul propriu al hărții (core/map/MapPrefs).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayersSheet(layers: MapLayers, onChange: (MapLayers) -> Unit, onClose: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Surface1, shape = SheetShape
    ) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Straturi", style = TitleModule.copy(fontSize = 20.sp))
            Spacer(Modifier.height(2.dp))
            Text("Alege ce vezi pe teren. Harta rămâne pe loc.", style = BodySmall.copy(color = TextSecondary))
            Spacer(Modifier.height(14.dp))

            ForjaCard(Modifier.fillMaxWidth(), fill = Surface2, padding = 12.dp) {
                LayerRow("Teritorii", "Celulele cucerite, colorate după cum le-ai luat.", layers.territories) {
                    onChange(layers.copy(territories = it))
                }
                LayerRow("Strălucire", "Zonele tale se aprind când te depărtezi.", layers.heat) {
                    onChange(layers.copy(heat = it))
                }
                LayerRow("Străzile tale", "Toate turele salvate, cu amber.", layers.streets) {
                    onChange(layers.copy(streets = it))
                }
                LayerRow("Locuri", "Locurile tale și recomandările prietenilor.", layers.places) {
                    onChange(layers.copy(places = it))
                }
                LayerRow("Prieteni", "Avatarurile lor pe hartă.", layers.friends) {
                    onChange(layers.copy(friends = it))
                }
                LayerRow("Clădiri 3D", "În vederea înclinată, clădirile se ridică.", layers.buildings3d, last = true) {
                    onChange(layers.copy(buildings3d = it))
                }
            }

            Spacer(Modifier.height(14.dp))
            SectionLabel("Mod noapte")
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NightChip("Auto 21:00–06:00", layers.night == NightMode.AUTO, Modifier.weight(1.4f)) { onChange(layers.copy(night = NightMode.AUTO)) }
                NightChip("Pornit", layers.night == NightMode.ON, Modifier.weight(1f)) { onChange(layers.copy(night = NightMode.ON)) }
                NightChip("Oprit", layers.night == NightMode.OFF, Modifier.weight(1f)) { onChange(layers.copy(night = NightMode.OFF)) }
            }
            Spacer(Modifier.height(6.dp))
            Text("Aceeași hartă, alte culori. Nu se reîncarcă nimic.", style = BodyTiny.copy(color = TextDim))

            Spacer(Modifier.height(14.dp))
            ForjaCard(Modifier.fillMaxWidth(), fill = Surface2, padding = 12.dp) {
                LayerRow("Etichete în română", "„București”, nu „Bucharest”.", layers.labelsRo, last = true) {
                    onChange(layers.copy(labelsRo = it))
                }
            }
        }
    }
}

@Composable
private fun LayerRow(title: String, sub: String, on: Boolean, last: Boolean = false, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = if (last) 0.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = BodyStrong.copy(fontSize = 14.sp))
            Text(sub, style = BodyTiny.copy(color = TextDim))
        }
        Spacer(Modifier.width(12.dp))
        ForjaSwitch(checked = on, onCheckedChange = onToggle)
    }
}

@Composable
private fun NightChip(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(ChipShape)
            .background(if (on) Accent else Surface1)
            .border(1.dp, if (on) Accent2 else StrokeCardStrong, ChipShape)
            .pressable(onClick)
            .padding(vertical = 9.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = BodyStrong.copy(fontSize = 12.sp, color = if (on) OnAccent else TextSecondary), maxLines = 1)
    }
}
