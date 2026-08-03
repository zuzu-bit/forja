package com.forja.app.feature.dashboard

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.forja.app.ForjaApp
import com.forja.app.core.data.Friend
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*
import com.forja.app.core.util.Fmt
import com.forja.app.navigation.Route
import kotlinx.coroutines.flow.first

private data class ModuleCard(
    val title: String, val subtitle: String, val image: String, val route: String
)

/** Dashboard „Ziua ta" — date reale: km din activități, mese din jurnal, somn din sesiuni. */
@Composable
fun DashboardScreen(
    onOpenModule: (String) -> Unit,
    onOpenProfile: () -> Unit,
    onOpenMap: () -> Unit,
    onOpenActivities: () -> Unit = {}
) {
    val context = LocalContext.current
    val app = remember { ForjaApp.from(context) }

    val evening = Fmt.isEvening()
    val weekDistance by app.db.activityDao().distanceSince(Fmt.startOfWeekMillis()).collectAsState(initial = 0.0)
    val yesterdayStart = Fmt.startOfDayMillis(1)
    val todayStart = Fmt.startOfDayMillis(0)
    val activitiesToday by app.db.activityDao().since(todayStart).collectAsState(initial = emptyList())
    val lastSleep by app.db.sleepDao().lastFinished().collectAsState(initial = null)
    val kcalToday by app.db.mealDao().kcalForDay(Fmt.epochDay()).collectAsState(initial = 0)
    val lastWorkout by app.db.workoutDao().lastSession().collectAsState(initial = null)
    val mealsToday by app.db.mealDao().mealsForDay(Fmt.epochDay()).collectAsState(initial = emptyList())
    val workoutsWeek by app.db.workoutDao().sessionCountSince(Fmt.startOfWeekMillis()).collectAsState(initial = 0)
    val forest by app.prefs.focusForest.collectAsState(initial = Triple(0, 0, 0))

    var name by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { name = app.prefs.cachedName.first().ifBlank { "Sportiv" } }

    var friends by remember { mutableStateOf<List<Friend>>(emptyList()) }
    LaunchedEffect(Unit) {
        val uid = app.auth.currentUid ?: return@LaunchedEffect
        app.friends.friendsFlow(uid).collect { friends = it }
    }

    val todayKm = activitiesToday.sumOf { it.distanceM } / 1000.0

    val headerVideo = if (evening)
        "https://v.ftcdn.net/04/99/13/67/700_F_499136769_X4Pfv9UFpmLtXcXu0JLdSo80FTPH2BGx_ST.mp4"
    else
        "https://v.ftcdn.net/10/70/20/79/700_F_1070207993_vIfgD2rf5RWK9Sz68WonFo6D78QWfBWy_ST.mp4"
    val headerPoster = if (evening)
        "https://t3.ftcdn.net/jpg/10/16/02/48/500_F_1016024842_sVPfKb4a4gZkZ7XjEjnGtdkeYz1eF2Gz.jpg"
    else
        "https://t4.ftcdn.net/jpg/04/30/39/81/500_F_430398119_8X2LMR6p3pWYrpsvH3DYgYUz32PfnxXl.jpg"

    val modules = listOf(
        ModuleCard(
            "Antrenament",
            lastWorkout?.let { "ultima: ${it.planName.lowercase()}" } ?: "începe primul plan",
            "https://t4.ftcdn.net/jpg/06/22/38/57/500_F_622385753_VgquhCDAoHqLCGy3w8Q9zUEpxDLGfX54.jpg",
            Route.WORKOUT
        ),
        ModuleCard(
            "Nutriție",
            if (kcalToday > 0) "$kcalToday kcal azi" else "scanează prima masă",
            "https://t3.ftcdn.net/jpg/03/30/19/86/500_F_330198627_aQsy9t5HhOn7TIsd6FEB0FJvKz4IqdhH.jpg",
            Route.NUTRITION
        ),
        ModuleCard(
            "Somn",
            lastSleep?.let {
                val min = (((it.endAt ?: it.startAt) - it.startAt) / 60000).toInt()
                Fmt.durationHm(min) + " aseară"
            } ?: "pornește diseară",
            "https://t3.ftcdn.net/jpg/05/62/79/66/500_F_562796663_NJKtdLr9EatSHwup53J47QNnYOCr0ZZ8.jpg",
            Route.SLEEP
        ),
        ModuleCard(
            "Focus",
            "timpul tău, apărat",
            "https://t3.ftcdn.net/jpg/10/16/02/48/500_F_1016024842_sVPfKb4a4gZkZ7XjEjnGtdkeYz1eF2Gz.jpg",
            Route.FOCUS
        )
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(Surface0)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 120.dp)
    ) {
        // Header video 328dp după momentul zilei
        Box(
            Modifier
                .fillMaxWidth()
                .height(328.dp)
        ) {
            VideoSurface(url = headerVideo, posterUrl = headerPoster, modifier = Modifier.fillMaxSize())
            BoxScopeBottomScrim()
            TopScrim()

            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("${Fmt.greeting()}, ${name.split(' ').firstOrNull() ?: ""}".trim(), style = BodyStrong.copy(fontSize = 15.sp))
                    Text(
                        if (evening) "SEARA TA" else "ZIUA TA",
                        style = monoLabel(9, 0.16f).copy(color = Accent2)
                    )
                }
                Box(Modifier.pressable(onOpenProfile)) {
                    Avatar(name = name, size = 40.dp, ring = true)
                }
            }

            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(20.dp)
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    CountUpNumeral(target = todayKm.toFloat(), size = 66, decimals = 1)
                    Spacer(Modifier.width(10.dp))
                    Text("km azi", style = Body.copy(color = TextSecondary), modifier = Modifier.padding(bottom = 10.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (todayKm > 0 || weekDistance > 0) "săptămâna asta: ${Fmt.km(weekDistance)} km · vezi tot →"
                    else "apasă GO pe hartă și pornește prima tură",
                    style = BodySmall.copy(color = TextSecondary),
                    modifier = Modifier.pressable(onOpenActivities)
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        // Bun venit + progresul de azi
        WelcomeAvatar(name = name)
        Spacer(Modifier.height(14.dp))
        MotivationCard()
        Spacer(Modifier.height(18.dp))
        SectionLabel("Progresul de azi", Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(10.dp))
        val sleepStat = lastSleep?.let {
            Fmt.durationHm((((it.endAt ?: it.startAt) - it.startAt) / 60000).toInt())
        } ?: "—"
        val focusMin = forest.first * 15 + forest.third / 60
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp)
        ) {
            StatCard("🍽", "${mealsToday.size}", "mese azi")
            Spacer(Modifier.width(10.dp))
            StatCard("🏃", Fmt.km(todayKm), "km azi")
            Spacer(Modifier.width(10.dp))
            StatCard("🏋", "$workoutsWeek", "antren. săpt.")
            Spacer(Modifier.width(10.dp))
            StatCard("😴", sleepStat, "somn aseară")
            Spacer(Modifier.width(10.dp))
            StatCard("🎯", "${focusMin}m", "focus azi")
        }

        Spacer(Modifier.height(22.dp))

        // Prietenii — reali, cu stare live onestă
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionLabel("Prieteni acum")
            Text(
                "vezi harta →", style = BodySmall.copy(color = Accent2),
                modifier = Modifier.pressable(onOpenMap)
            )
        }
        Spacer(Modifier.height(10.dp))
        if (friends.isEmpty()) {
            ForjaCard(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            ) {
                Text("Încă nimeni aici.", style = BodyStrong)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Invită un prieten cu codul tău din Profil — apăreți unul altuia pe hartă.",
                    style = BodySmall
                )
            }
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp)) {
                items(friends, key = { it.uid }) { f ->
                    Column(
                        Modifier
                            .padding(end = 14.dp)
                            .pressable(onOpenMap),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val active = f.state in setOf("walk", "run", "ride", "gym") &&
                                System.currentTimeMillis() - f.locUpdatedAt < 15 * 60_000
                        Avatar(name = f.name, size = 52.dp, ring = true, live = active)
                        Spacer(Modifier.height(6.dp))
                        Text(f.name.split(' ').first(), style = BodySmall.copy(color = TextPrimary))
                        Text(
                            when {
                                f.ghost -> "fantomă"
                                f.state == "run" -> "aleargă"
                                f.state == "ride" -> "pe roți"
                                f.state == "walk" -> "se plimbă"
                                f.state == "sleep" -> "doarme"
                                else -> Fmt.freshness(f.locUpdatedAt)
                            },
                            style = BodyTiny.copy(color = if (active) Accent2 else TextDim)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        SectionLabel("Modulele tale", Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(10.dp))

        // Grid 2×2 module cu foto reale
        Column(Modifier.padding(horizontal = 20.dp)) {
            for (row in modules.chunked(2)) {
                Row(Modifier.fillMaxWidth()) {
                    for ((i, m) in row.withIndex()) {
                        ModuleTile(
                            m,
                            Modifier
                                .weight(1f)
                                .padding(end = if (i == 0) 10.dp else 0.dp),
                            onClick = { onOpenModule(m.route) }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

/** Avatar-ghid care „dansează" ușor și te întâmpină. */
@Composable
private fun WelcomeAvatar(name: String) {
    val mascot = remember { com.forja.app.core.media.Media.mediaUrl("guide.jpg") }
    val infinite = rememberInfiniteTransition(label = "dance")
    val rot by infinite.animateFloat(-6f, 6f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "rot")
    val sc by infinite.animateFloat(0.98f, 1.05f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "sc")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(64.dp)
                .graphicsLayer { rotationZ = rot; scaleX = sc; scaleY = sc }
                .clip(CircleShape).background(Color(0x1FFFB300)).border(1.dp, Color(0x4DFFB300), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (mascot != null) {
                AsyncImage(
                    model = mascot, contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape)
                )
            } else Text("😄", fontSize = 32.sp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Bun venit, ${name.split(' ').firstOrNull() ?: ""}!".trim(), style = BodyStrong.copy(fontSize = 17.sp))
            Text("Hai să facem ziua asta să conteze.", style = BodySmall.copy(color = TextSecondary))
        }
    }
}

/** Citat motivațional cu imagine în fundal (se schimbă la fiecare deschidere). */
@Composable
private fun MotivationCard() {
    val items = listOf(
        "Disciplina cântărește kilograme; regretul cântărește tone." to "https://t4.ftcdn.net/jpg/06/22/38/57/500_F_622385753_VgquhCDAoHqLCGy3w8Q9zUEpxDLGfX54.jpg",
        "Un pas mic azi bate un plan mare mâine." to "https://t4.ftcdn.net/jpg/04/30/39/81/500_F_430398119_8X2LMR6p3pWYrpsvH3DYgYUz32PfnxXl.jpg",
        "Corpul realizează ce mintea crede." to "https://t3.ftcdn.net/jpg/03/30/19/86/500_F_330198627_aQsy9t5HhOn7TIsd6FEB0FJvKz4IqdhH.jpg",
        "Nu trebuie să fii extraordinar ca să începi, dar trebuie să începi ca să fii extraordinar." to "https://t3.ftcdn.net/jpg/05/62/79/66/500_F_562796663_NJKtdLr9EatSHwup53J47QNnYOCr0ZZ8.jpg"
    )
    val picked = remember { items.random() }
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(130.dp).clip(RoundedCornerShape(Radii.card))
    ) {
        AsyncImage(model = picked.second, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0x66000000), Color(0xE60A0A0B)))
            )
        )
        Text(
            "„${picked.first}”",
            style = TitleModule.copy(fontSize = 16.sp, lineHeight = 22.sp),
            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)
        )
    }
}

@Composable
private fun StatCard(emoji: String, value: String, label: String) {
    Column(
        Modifier.width(98.dp).clip(RoundedCornerShape(Radii.card)).background(Surface1)
            .border(1.dp, StrokeCard, RoundedCornerShape(Radii.card)).padding(vertical = 14.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(emoji, fontSize = 22.sp)
        Spacer(Modifier.height(6.dp))
        Text(value, style = BodyStrong.copy(fontSize = 17.sp), maxLines = 1)
        Text(label, style = BodyTiny.copy(color = TextSecondary), textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
private fun ModuleTile(m: ModuleCard, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Radii.card)
    Box(
        modifier
            .height(118.dp)
            .clip(shape)
            .background(Surface1)
            .border(1.dp, StrokeCard, shape)
            .pressable(onClick)
    ) {
        AsyncImage(
            model = m.image,
            contentDescription = m.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to Color(0x330A0A0B), 0.55f to Color(0xB30A0A0B), 1f to Color(0xF20A0A0B)
                    )
                )
        )
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text(m.title, style = BodyStrong.copy(fontSize = 15.sp))
            Text(m.subtitle, style = BodyTiny.copy(color = TextSecondary))
        }
    }
}
