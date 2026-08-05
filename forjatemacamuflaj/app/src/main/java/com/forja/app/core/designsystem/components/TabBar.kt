package com.forja.app.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.forja.app.core.designsystem.*

enum class ForjaTab(val label: String, val icon: ImageVector) {
    Azi("Azi", Icons.Outlined.Home),
    Antrenament("Antren.", Icons.Outlined.FitnessCenter),
    Harta("Hartă", Icons.Outlined.Map),
    Nutritie("Nutriție", Icons.Outlined.PhotoCamera),
    Somn("Somn", Icons.Outlined.Bedtime),
    Focus("Focus", Icons.Outlined.SelfImprovement),
    Respiro("Respiră", Icons.Outlined.Air)
}

@Composable
fun ForjaTabBar(
    current: ForjaTab?,
    onSelect: (ForjaTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.4f to Color(0xE60A0A0B),
                    1f to Color(0xFF0A0A0B)
                )
            )
            .padding(top = 10.dp)
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TabItem(ForjaTab.Antrenament, current, onSelect)
            TabItem(ForjaTab.Focus, current, onSelect)
            TabItem(ForjaTab.Harta, current, onSelect)
            CenterHomeTab(selected = current == ForjaTab.Azi) { onSelect(ForjaTab.Azi) }
            TabItem(ForjaTab.Nutritie, current, onSelect)
            TabItem(ForjaTab.Somn, current, onSelect)
            TabItem(ForjaTab.Respiro, current, onSelect)
        }
    }
}

@Composable
private fun TabItem(tab: ForjaTab, current: ForjaTab?, onSelect: (ForjaTab) -> Unit) {
    val selected = tab == current
    val tint = if (selected) Accent2 else TextDim
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) TabPillActive else Color.Transparent)
            .pressable({ onSelect(tab) })
            .padding(horizontal = 9.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(tab.icon, contentDescription = tab.label, tint = tint, modifier = Modifier.size(21.dp))
        if (selected) {
            Spacer(Modifier.height(2.dp))
            Text(tab.label, style = monoLabel(8, 0.08f).copy(color = tint), maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun CenterHomeTab(selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(54.dp)
            .shadow(if (selected) 12.dp else 8.dp, CircleShape, spotColor = if (selected) Accent else Color.Black)
            .clip(CircleShape)
            .background(if (selected) Color.Transparent else Color(0xFF16161A))
            .then(if (selected) Modifier.background(Brush.linearGradient(listOf(Accent, Accent2))) else Modifier)
            .border(1.dp, if (selected) Color(0x996F855A) else StrokeOnVideoStrong, CircleShape)
            .pressable(onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            ForjaTab.Azi.icon,
            contentDescription = "Azi",
            tint = if (selected) OnAccent else TextSecondary,
            modifier = Modifier.size(24.dp)
        )
    }
}
