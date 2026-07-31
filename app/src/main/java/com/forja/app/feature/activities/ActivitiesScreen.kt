package com.forja.app.feature.activities

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forja.app.ForjaApp
import com.forja.app.core.data.db.ActivityEntity
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*
import com.forja.app.core.util.Fmt
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun sportLabel(type: String) = when (type) {
    "walk" -> "Mers"
    "ride" -> "Ciclism"
    else -> "Alergare"
}

@Composable
fun sportIcon(type: String) = when (type) {
    "walk" -> Icons.AutoMirrored.Filled.DirectionsWalk
    "ride" -> Icons.AutoMirrored.Filled.DirectionsBike
    else -> Icons.AutoMirrored.Filled.DirectionsRun
}

/** Istoricul activităților — jurnalul tău de mișcare, à la Strava. */
@Composable
fun ActivitiesScreen(onOpenDetail: (Long) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = remember { ForjaApp.from(context) }
    val activities by app.db.activityDao().all().collectAsState(initial = emptyList())
    val weekM by app.db.activityDao().distanceSince(Fmt.startOfWeekMillis()).collectAsState(initial = 0.0)

    Column(
        Modifier
            .fillMaxSize()
            .background(Surface0)
            .statusBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Activitățile tale", style = TitleModule)
                Text(
                    "SĂPTĂMÂNA ASTA: ${Fmt.km(weekM)} KM · ${activities.count { it.startAt >= Fmt.startOfWeekMillis() }} IEȘIRI",
                    style = monoLabel(9, 0.12f).copy(color = Accent2)
                )
            }
            SecondaryButton("Înapoi", onClick = onBack, padV = 8.dp)
        }

        if (activities.isEmpty()) {
            ForjaCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text("Încă nicio tură.", style = BodyStrong)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Apasă GO pe hartă, alege sportul și pornește. Fiecare metru rămâne aici.",
                    style = BodySmall
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 120.dp)
            ) {
                val grouped = activities.groupBy {
                    Instant.ofEpochMilli(it.startAt).atZone(ZoneId.systemDefault()).toLocalDate()
                }
                grouped.forEach { (date, dayActivities) ->
                    item(key = "h$date") {
                        Spacer(Modifier.height(10.dp))
                        SectionLabel(dayLabel(date))
                        Spacer(Modifier.height(8.dp))
                    }
                    items(dayActivities, key = { it.id }) { a ->
                        ActivityRow(a) { onOpenDetail(a.id) }
                    }
                }
            }
        }
    }
}

private fun dayLabel(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "Azi"
        today.minusDays(1) -> "Ieri"
        else -> date.format(DateTimeFormatter.ofPattern("d MMM"))
    }
}

@Composable
private fun ActivityRow(a: ActivityEntity, onClick: () -> Unit) {
    ForjaCard(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .pressable(onClick),
        padding = 12.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(sportIcon(a.type), null, tint = Accent2, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${sportLabel(a.type)} · ${Fmt.km(a.distanceM)} km",
                    style = BodyStrong.copy(fontSize = 15.sp)
                )
                Spacer(Modifier.height(2.dp))
                val metric = if (a.type == "ride") {
                    val kmh = if (a.durationS > 0) a.distanceM / a.durationS * 3.6 else 0.0
                    "viteză ${Fmt.km(kmh * 1000, 1)} km/h"
                } else {
                    "ritm ${Fmt.pace(if (a.distanceM >= 50) (a.durationS / (a.distanceM / 1000.0)).toLong() else 0)} /km"
                }
                Text(
                    "${Fmt.durationMs(a.durationS)} · $metric · ${a.kcal} kcal",
                    style = BodySmall.copy(color = TextSecondary)
                )
            }
            Text(Fmt.clock(a.startAt), style = monoLabel(9, 0.10f))
        }
    }
}
