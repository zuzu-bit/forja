package com.forja.app.feature.activities

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.forja.app.ForjaApp
import com.forja.app.core.data.db.ActivityEntity
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*
import com.forja.app.core.util.Fmt
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

private val CartoDarkDetail = XYTileSource(
    "CartoDarkDetail", 1, 20, 256, ".png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/dark_all/",
        "https://b.basemaps.cartocdn.com/dark_all/",
        "https://c.basemaps.cartocdn.com/dark_all/"
    ),
    "© OpenStreetMap contributors © CARTO"
)

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

        // Traseul pe hartă
        val points = remember(a.id) {
            a.polyline.split(';').mapNotNull { pair ->
                val parts = pair.split(',')
                val lat = parts.getOrNull(0)?.toDoubleOrNull()
                val lng = parts.getOrNull(1)?.toDoubleOrNull()
                if (lat != null && lng != null) GeoPoint(lat, lng) else null
            }
        }
        if (points.size >= 2) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(260.dp)
                    .clip(RoundedCornerShape(Radii.card))
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        MapView(ctx).apply {
                            setTileSource(CartoDarkDetail)
                            setMultiTouchControls(true)
                            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
                            val warm = ColorMatrix(
                                floatArrayOf(
                                    1.10f, 0f, 0f, 0f, 8f,
                                    0f, 0.98f, 0f, 0f, 4f,
                                    0f, 0f, 0.86f, 0f, 0f,
                                    0f, 0f, 0f, 1f, 0f
                                )
                            )
                            overlayManager.tilesOverlay.setColorFilter(ColorMatrixColorFilter(warm))
                            val glow = Polyline().apply {
                                outlinePaint.color = android.graphics.Color.parseColor("#29FF9E2D")
                                outlinePaint.strokeWidth = 13 * ctx.resources.displayMetrics.density
                                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                                setPoints(points)
                            }
                            val line = Polyline().apply {
                                outlinePaint.color = android.graphics.Color.parseColor("#FFB300")
                                outlinePaint.strokeWidth = 4.5f * ctx.resources.displayMetrics.density
                                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                                setPoints(points)
                            }
                            overlays.add(glow)
                            overlays.add(line)
                            post {
                                zoomToBoundingBox(BoundingBox.fromGeoPoints(points).increaseByScale(1.35f), false)
                            }
                        }
                    }
                )
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
                SectionLabel("Calorii")
                Text("${a.kcal}", style = heroNumeral(30))
            }
            Column {
                SectionLabel("Puncte GPS")
                Text("${points.size}", style = heroNumeral(30))
            }
        }
    }
}
