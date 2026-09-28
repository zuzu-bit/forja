package com.forja.app.feature.recovery

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forja.app.core.designsystem.Accent2
import com.forja.app.core.designsystem.BodySmall
import com.forja.app.core.designsystem.BodyStrong
import com.forja.app.core.designsystem.EmberHot
import com.forja.app.core.designsystem.LocalReducedMotion
import com.forja.app.core.designsystem.Positive
import com.forja.app.core.designsystem.SheetShape
import com.forja.app.core.designsystem.StrokeCardStrong
import com.forja.app.core.designsystem.Surface0
import com.forja.app.core.designsystem.Surface1
import com.forja.app.core.designsystem.TextDim
import com.forja.app.core.designsystem.TextDim2
import com.forja.app.core.designsystem.TextPrimary
import com.forja.app.core.designsystem.TextSecondary
import com.forja.app.core.designsystem.TitleModule
import com.forja.app.core.designsystem.components.InfoDot
import com.forja.app.core.designsystem.components.PrimaryButton
import com.forja.app.core.designsystem.components.StampLabel
import com.forja.app.core.designsystem.components.pressable
import com.forja.app.core.designsystem.components.topoBackground
import com.forja.app.core.designsystem.monoLabel
import com.forja.app.core.recovery.FinderLogic
import com.forja.app.core.recovery.FinderState
import com.forja.app.core.recovery.FinderUi

/** Ce e scris la „i” în foaia Găsire (DESIGN-4.4 / lost-phone §5.7) — explicația stă aici, nu pe ecran. */
const val GASIRE_INFO =
    "Cât e semnat contractul, telefonul își lasă pe site ultima poziție și bateria, doar pentru tine. " +
        "De acolo îl suni sau îl urmărești 10 minute; telefonul arată atunci o notificare. " +
        "Închis sau descărcat, rămâne ultima poziție, 7 zile.\n\n" +
        "Adormit, telefonul răspunde la următoarea trezire, de obicei în câteva minute."

/** Culoarea fiecărei stări (rândul din Profil, punctul din foaie, radarul). */
fun FinderState.tone(): Color = when (this) {
    FinderState.Guard -> Positive
    FinderState.NoContract -> TextDim
    FinderState.Incomplete, FinderState.NoLink -> EmberHot
}

/**
 * Radarul găsirii: cercuri concentrice, reperele cardinale și telefonul în centru, în culoarea stării.
 * „Viu” (în gardă, sonerie): o undă pleacă din centru — doar fără „mișcare redusă”.
 */
@Composable
fun FinderRadar(color: Color, size: Dp, modifier: Modifier = Modifier, live: Boolean = true, iconSize: Dp = size * 0.26f) {
    val reduced = LocalReducedMotion.current
    val wave = if (live && !reduced) {
        val t by rememberInfiniteTransition(label = "radar").animateFloat(
            0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "wave"
        )
        t
    } else -1f
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2f
            val c = Offset(r, r)
            val sw = 1.dp.toPx()
            drawCircle(color.copy(alpha = 0.06f), radius = r, center = c)
            listOf(1f to 0.16f, 0.72f to 0.26f, 0.44f to 0.40f).forEach { (k, a) ->
                drawCircle(color.copy(alpha = a), radius = r * k - sw, center = c, style = Stroke(sw))
            }
            // Repere N/E/S/V: liniuțe scurte pe cercul exterior.
            val tick = r * 0.12f
            drawLine(color.copy(alpha = 0.5f), Offset(r, 0f), Offset(r, tick), sw * 1.4f)
            drawLine(color.copy(alpha = 0.5f), Offset(r, 2 * r - tick), Offset(r, 2 * r), sw * 1.4f)
            drawLine(color.copy(alpha = 0.5f), Offset(0f, r), Offset(tick, r), sw * 1.4f)
            drawLine(color.copy(alpha = 0.5f), Offset(2 * r - tick, r), Offset(2 * r, r), sw * 1.4f)
            if (wave >= 0f) {
                drawCircle(color.copy(alpha = 0.45f * (1f - wave)), radius = r * (0.30f + 0.70f * wave), center = c, style = Stroke(sw * 1.6f))
            }
        }
        Box(
            Modifier
                .size(iconSize * 1.9f)
                .clip(CircleShape)
                .background(Surface0)
                .border(1.5.dp, color.copy(alpha = 0.85f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.PhoneAndroid, contentDescription = null, tint = color, modifier = Modifier.size(iconSize))
        }
    }
}

/** Acțiunile foii Găsire. */
class GasireActions(
    val onOpenSite: () -> Unit = {},
    val onProbe: () -> Unit = {},
)

/** Foaia de jos „GĂSIRE” din Profil — se deschide din rândul „Telefonul meu” (în gardă / fără legătură). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GasireSheet(ui: FinderUi, probing: Boolean, actions: GasireActions, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Surface1,
        contentColor = TextPrimary,
        shape = SheetShape,
        scrimColor = Color.Black.copy(alpha = 0.6f),
    ) {
        GasireSheetContent(ui, probing, actions)
    }
}

/** Conținutul foii (fără foaie) — capturat în GasireShots. */
@Composable
fun GasireSheetContent(ui: FinderUi, probing: Boolean, actions: GasireActions, now: Long = System.currentTimeMillis()) {
    val tone = ui.state.tone()
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StampLabel("GĂSIRE", appear = false)
            Spacer(Modifier.weight(1f))
            InfoDot(text = GASIRE_INFO, title = "Găsirea telefonului", size = 22)
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Îl cauți de pe site.", style = TitleModule.copy(fontSize = 26.sp, lineHeight = 29.sp))
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(tone))
                    Spacer(Modifier.width(7.dp))
                    Text(
                        FinderLogic.statusLine(ui.state, ui.lastOkAt, ui.battery, now),
                        style = monoLabel(9, 0.12f).copy(color = tone, lineHeight = 13.sp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            FinderRadar(tone, 84.dp, live = ui.state == FinderState.Guard)
        }
        ui.problem?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = BodySmall.copy(color = TextSecondary))
        }
        Spacer(Modifier.height(22.dp))
        PrimaryButton("Deschide pe site", onClick = actions.onOpenSite, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        // Tot rândul (lățime întreagă, ≥ 48 dp) e butonul „Probă”, nu doar cuvântul.
        val probe = if (probing) Modifier else Modifier.semantics { role = Role.Button }.pressable(actions.onProbe)
        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).then(probe), contentAlignment = Alignment.Center) {
            if (probing) {
                CircularProgressIndicator(color = Accent2, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            } else {
                Text("Probă", style = BodyStrong.copy(color = Accent2, fontSize = 15.sp))
            }
        }
    }
}

/**
 * „GĂSIRE · Aici sunt.” — ecranul pe care îl vede cine găsește telefonul care sună.
 * Un singur buton; după el, „Găsit.” o clipă, apoi ecranul pleacă. `silenced` = o tastă de volum a oprit soneria:
 * „SONERIA S-A OPRIT”, fără buton (comanda s-a închis deja), apoi ecranul pleacă.
 */
@Composable
fun FoundContent(secondsLeft: Int, found: Boolean, silenced: Boolean = false, onFound: () -> Unit) {
    val tone = when {
        found -> Positive
        silenced -> TextDim
        else -> EmberHot
    }
    Box(
        Modifier
            .fillMaxSize()
            .topoBackground(decor = false)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            StampLabel("GĂSIRE", appear = false, color = tone)
            Spacer(Modifier.height(34.dp))
            if (found) {
                Box(
                    Modifier.size(176.dp).clip(CircleShape).background(Positive.copy(alpha = 0.10f))
                        .border(1.5.dp, Positive.copy(alpha = 0.6f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Check, contentDescription = null, tint = Positive, modifier = Modifier.size(64.dp))
                }
            } else {
                FinderRadar(tone, 196.dp, live = secondsLeft > 0 && !silenced)
            }
            Spacer(Modifier.height(34.dp))
            Text(
                if (found) "Găsit." else "Aici sunt.",
                style = TitleModule.copy(fontSize = 38.sp, lineHeight = 41.sp),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(10.dp))
            Text(
                when {
                    found -> "SITE-UL AFLĂ ACUM"
                    secondsLeft > 0 && !silenced -> "TE CAUTĂ CONTUL TĂU · $secondsLeft S"
                    else -> "SONERIA S-A OPRIT"
                },
                style = monoLabel(10, 0.14f).copy(color = if (found) Positive else if (secondsLeft > 0 && !silenced) EmberHot else TextDim),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(40.dp))
            if (!found && !silenced) {
                PrimaryButton("Am găsit telefonul", onClick = onFound, modifier = Modifier.fillMaxWidth())
            } else {
                Spacer(Modifier.height(52.dp))
            }
        }
    }
}

/** Fundalul foii, pentru capturi: aceeași suprafață și rază ca ModalBottomSheet. */
@Composable
fun SheetFrame(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().clip(SheetShape).background(Surface1).border(1.dp, StrokeCardStrong, SheetShape)) {
        Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 36.dp, height = 4.dp).clip(CircleShape).background(TextDim2))
        }
        content()
    }
}
