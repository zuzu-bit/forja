package com.forja.app.feature.activities

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forja.app.ForjaApp
import com.forja.app.core.data.db.ActivityEntity
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*
import com.forja.app.core.map.ForjaMap
import com.forja.app.core.map.MapController
import com.forja.app.core.map.MapGeo
import com.forja.app.core.util.Fmt

/** Detaliul unei activități: traseul desenat + toate cifrele. */
@Composable
fun ActivityDetailScreen(activityId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = remember { ForjaApp.from(context) }
    var activity by remember { mutableStateOf<ActivityEntity?>(null) }
    LaunchedEffect(activityId) {
        activity = app.db.activityDao().byId(activityId)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Surface0)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(bottom = 120.dp)
    ) {
        val a = activity
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (a != null) {
                    Icon(sportIcon(a.type), null, tint = Accent2, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(sportLabel(a.type), style = TitleModule.copy(fontSize = 22.sp))
                        Text(
                            java.time.Instant.ofEpochMilli(a.startAt)
                                .atZone(java.time.ZoneId.systemDefault())
                                .format(java.time.format.DateTimeFormatter.ofPattern("d MMM · HH:mm")),
                            style = monoLabel(9, 0.12f)
                        )
                    }
                }
            }
            SecondaryButton("Înapoi", onClick = onBack, padV = 8.dp)
        }

        if (a == null) {
            Text("Se încarcă…", style = Body, modifier = Modifier.padding(20.dp))
            return@Column
        }

        // Traseul pe hartă: aceeași ForjaMap ca harta mare, în mod static (doar pinch, 2D), traseul amber, încadrat cu 35 % margine.
        val points = remember(a.id) { MapGeo.parsePolyline(a.polyline) }
        if (points.size >= 2) {
            val reducedMotion = LocalReducedMotion.current
            val density = LocalDensity.current
            val controller = remember(a.id) {
                MapController(context, staticMode = true).also { c ->
                    c.reducedMotion = reducedMotion
                    c.onStyleReady = {
                        c.setStreets(MapGeo.route(points, "mine"))
                        c.fitBounds(points, with(density) { 24.dp.roundToPx() })
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(260.dp)
                    .clip(RoundedCornerShape(Radii.card))
            ) {
                ForjaMap(controller = controller, modifier = Modifier.fillMaxSize(), bottomInsetDp = 6)
            }
            Spacer(Modifier.height(16.dp))
        }

        // Cifrele
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                SectionLabel("Distanță")
                Row(verticalAlignment = Alignment.Bottom) {
                    CountUpNumeral(target = (a.distanceM / 1000).toFloat(), size = 44, decimals = 2)
                    Text(" km", style = Body, modifier = Modifier.padding(bottom = 6.dp))
                }
            }
            Column {
                SectionLabel("Timp")
                Text(Fmt.durationMs(a.durationS), style = heroNumeral(44))
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                SectionLabel(if (a.type == "ride") "Viteză medie" else "Ritm mediu")
                Text(
                    if (a.type == "ride") {
                        val kmh = if (a.durationS > 0) a.distanceM / a.durationS * 3.6 else 0.0
                        "${Fmt.km(kmh * 1000, 1)} km/h"
                    } else {
                        "${Fmt.pace(if (a.distanceM >= 50) (a.durationS / (a.distanceM / 1000.0)).toLong() else 0)} /km"
                    },
                    style = heroNumeral(30)
                )
            }
            Column {
                SectionLabel("Calorii · estimat")
                Text("${a.kcal}", style = heroNumeral(30))
            }
            Column {
                SectionLabel("Puncte GPS")
                Text("${points.size}", style = heroNumeral(30))
            }
        }
    }
}
