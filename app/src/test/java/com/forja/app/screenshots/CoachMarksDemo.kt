package com.forja.app.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.forja.app.core.designsystem.Accent2
import com.forja.app.core.designsystem.AccentGradient
import com.forja.app.core.designsystem.BarlowCondensed
import com.forja.app.core.designsystem.ButtonShape
import com.forja.app.core.designsystem.OnAccent
import com.forja.app.core.designsystem.StrokeCard
import com.forja.app.core.designsystem.StrokeCardStrong
import com.forja.app.core.designsystem.Surface1
import com.forja.app.core.designsystem.TextPrimary
import com.forja.app.core.designsystem.TextSecondary
import com.forja.app.core.designsystem.UtilFill
import com.forja.app.core.designsystem.components.CoachMarksHost
import com.forja.app.core.designsystem.components.CoachStep
import com.forja.app.core.designsystem.components.Mascot
import com.forja.app.core.designsystem.components.MascotHat
import com.forja.app.core.designsystem.components.MascotState
import com.forja.app.core.designsystem.components.StampLabel
import com.forja.app.core.designsystem.components.coachTarget
import com.forja.app.core.designsystem.components.topoBackground
import com.forja.app.core.designsystem.monoLabel

/*
 * Ecran-demonstrație pentru ghidaj: macheta startului de Inventar (S1, prototipul Main.dc.html) cu pașii din
 * Ghidaj.dc.html. Doar pentru capturi — gazda internă CoachMarksHost (fără DataStore), `startAt` alege pasul.
 * Pasul „album” nu are țintă pe ecran: se sare (de aceea punctele arată 3 pași, nu 4).
 */

internal val DemoSteps = listOf(
    CoachStep("kind", "Alegi: poze sau documente."),
    CoachStep("album", "Pas fără țintă pe ecran: se sare singur."),
    CoachStep("scope", "Tot telefonul sau doar o parte."),
    CoachStep("start", "Analiza merge în fundal. Nimic nu se șterge fără tine."),
)

@Composable
fun CoachMarksDemo(startAt: Int, active: Boolean = true) {
    CoachMarksHost(steps = DemoSteps, active = active, onFinish = {}, startAt = startAt) {
        InventoryStartMock()
    }
}

private val TileShape = RoundedCornerShape(8.dp)
private val ChipShape6 = RoundedCornerShape(6.dp)

@Composable
private fun InventoryStartMock() {
    Column(
        Modifier
            .fillMaxSize()
            .topoBackground()
            .padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SquareButton { Icon(Icons.AutoMirrored.Outlined.ArrowBackIos, null, tint = TextPrimary, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.weight(1f))
            StampLabel("INVENTAR", rotationDeg = -4f)
            Spacer(Modifier.weight(1f))
            SquareButton { Text("i", style = monoLabel(15, 0f).copy(color = Accent2, fontWeight = FontWeight.Bold)) }
        }
        Column(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Box(
                Modifier
                    .clip(TileShape)
                    .background(Surface1)
                    .border(1.dp, StrokeCardStrong, TileShape)
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text(
                    "Ce punem în ordine?",
                    style = androidx.compose.ui.text.TextStyle(
                        fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 24.sp,
                        letterSpacing = 0.01.em, color = TextPrimary
                    )
                )
            }
            Mascot(state = MascotState.Idle, hat = MascotHat.Helmet, size = 176.dp)
        }
        Row(Modifier.fillMaxWidth().coachTarget("kind"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile("3 214", "POZE · 12,4 GB", Icons.Outlined.PhotoLibrary, selected = true, modifier = Modifier.weight(1f))
            Tile("486", "DOCUMENTE · 2,1 GB", Icons.Outlined.Folder, selected = false, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().coachTarget("scope"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Tot", selected = true, modifier = Modifier.weight(1f))
            Chip("Ultimele 500", selected = false, modifier = Modifier.weight(1f))
            Chip("Album", selected = false, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier
                .coachTarget("start")
                .fillMaxWidth()
                .height(58.dp)
                .clip(ButtonShape)
                .background(AccentGradient),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Începe",
                style = androidx.compose.ui.text.TextStyle(
                    fontFamily = BarlowCondensed, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp,
                    letterSpacing = 0.04.em, color = OnAccent
                )
            )
            Spacer(Modifier.width(10.dp))
            Text("~ 25 MIN", style = monoLabel(11, 0.1f).copy(color = OnAccent.copy(alpha = 0.75f)))
        }
    }
}

@Composable
private fun SquareButton(content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(ButtonShape)
            .background(UtilFill)
            .border(1.dp, StrokeCard, ButtonShape),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
private fun Tile(value: String, label: String, icon: ImageVector, selected: Boolean, modifier: Modifier) {
    Column(
        modifier
            // Selecția: contur olive 2 dp + strălucire de 4 dp în afara plăcii (box-shadow 0 0 0 4px din prototip).
            .drawBehind {
                if (selected) {
                    val o = 4.dp.toPx()
                    drawRoundRect(
                        Color(0x246F855A), topLeft = Offset(-o, -o), size = Size(size.width + 2 * o, size.height + 2 * o),
                        cornerRadius = CornerRadius(12.dp.toPx())
                    )
                }
            }
            .height(148.dp)
            .clip(TileShape)
            .background(Surface1)
            .border(if (selected) 2.dp else 1.dp, if (selected) Accent2 else StrokeCardStrong, TileShape)
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Icon(icon, contentDescription = null, tint = Accent2, modifier = Modifier.size(30.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                value,
                style = androidx.compose.ui.text.TextStyle(
                    fontFamily = BarlowCondensed, fontWeight = FontWeight.ExtraBold, fontSize = 40.sp,
                    lineHeight = 40.sp, color = TextPrimary
                )
            )
            Text(label, style = monoLabel(10, 0.14f).copy(color = TextSecondary))
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, modifier: Modifier) {
    Box(
        modifier
            .height(44.dp)
            .clip(ChipShape6)
            .background(if (selected) Color(0x594A5D3A) else Surface1)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Accent2 else StrokeCardStrong, ChipShape6),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = androidx.compose.ui.text.TextStyle(
                fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                letterSpacing = 0.02.em, color = if (selected) OnAccent else TextSecondary
            )
        )
    }
}
