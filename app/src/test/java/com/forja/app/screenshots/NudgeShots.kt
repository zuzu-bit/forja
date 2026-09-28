package com.forja.app.screenshots

import android.app.Application
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forja.app.R
import com.forja.app.core.designsystem.TextDim
import com.forja.app.core.designsystem.components.StampLabel
import com.forja.app.core.designsystem.monoLabel
import com.forja.app.core.notify.Echo
import com.forja.app.core.notify.FriendView
import com.forja.app.core.notify.MascotIcons
import com.forja.app.core.notify.Nudge
import com.forja.app.core.notify.NudgeContext
import com.forja.app.core.notify.NudgeData
import com.forja.app.core.notify.NudgeEchoCard
import com.forja.app.core.notify.NudgeFixtures
import com.forja.app.core.notify.NudgePose
import com.forja.app.core.notify.PlaceView
import com.forja.app.core.notify.Rendered
import com.forja.app.core.notify.Ro
import com.forja.app.core.notify.SleepView
import com.forja.app.core.notify.Streak
import com.forja.app.core.notify.StreakKind
import com.forja.app.core.notify.SyncCopy
import com.forja.app.core.notify.Voice
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/*
 * Casca (pachetul P7): ecoul de pe Panou (trucul Duo), pictogramele mascotei și o planșă de revizie a textelor
 * notificărilor, randate ca în sertarul One UI (temă întunecată). Sertarul adevărat e al sistemului — planșa arată
 * cuvintele, poza și ordinea rândurilor (titlu, rândul restrâns, antetul), nu pixelii Samsung.
 */

private val Shade = Color(0xFF1C1C1E)
private val Card = Color(0xFF2A2A2D)
private val Ink = Color(0xFFF2F2F2)
private val Sub = Color(0xFFB9B9BE)
private val ActionFill = Color(0xFF3A3A3E)

private fun sample(r: Rendered?, fallback: String): Rendered = r ?: Rendered(fallback, NudgeContext.Morning, "—", "—", NudgePose.Talking, false, false)

/** Numele canalelor, exact cum le vede Lana în Setări (aceleași resurse ca ForjaApp.createChannels). */
private data class ChannelNames(val coach: String, val sleep: String, val explore: String, val social: String)

/** Un rând din sertar: antetul (canalul), textul, forma extinsă și butonul, dacă notificarea are unul. */
private data class Item(val channel: String, val r: Rendered, val expanded: String? = null, val action: String? = null)

private fun Triple<String, Rendered, String?>.item() = Item(first, second, third)

/** Mesajele planșei, alese exact cum le-ar alege Casca în ziua din NudgeFixtures.rich. */
private fun board(ch: ChannelNames): List<Item> {
    val rich = NudgeFixtures.rich(9)
    // Locul nou de azi a venit deja pe „Locurile tale” (cardul de mai jos): SyncNotice.refresh îl scoate din date,
    // ca rândul permanent să nu spună aceeași veste a doua oară (NudgeRules.placeAnnounced).
    val sync = Nudge.pick(NudgeContext.SyncOngoing, NudgeFixtures.rich(20).copy(newPlaceToday = null), emptyList())
    val syncText = SyncCopy.compose(setOf("location", "app_usage", "photos"), sync) { k ->
        when (k) { "location" -> "locație"; "app_usage" -> "aplicații"; "photos" -> "fotografii"; "audio" -> "microfon"; else -> "fișiere alese" }
    }
    val syncCard = Rendered("sync", NudgeContext.SyncOngoing, syncText.title, syncText.collapsed, sync?.pose ?: NudgePose.Happy, true, false)
    fun at(h: Int): NudgeData = NudgeFixtures.rich(h)
    // „Oprești de aici.” trimite la butonul notificării: planșa îl desenează, ca în sertar.
    return listOf(Item("Sincronizare activă", syncCard, syncText.big, syncText.action)) + listOf(
        Triple(ch.coach, sample(Nudge.pick(NudgeContext.Morning, rich, emptyList()), "m"), null),
        Triple(ch.coach, sample(Nudge.pick(NudgeContext.Midday, at(13), emptyList()), "d"), null),
        Triple(ch.coach, sample(Nudge.pick(NudgeContext.Evening, at(20), emptyList()), "e"), null),
        Triple(ch.coach, sample(Nudge.pick(NudgeContext.StreakRisk,
            at(20).copy(voice = Voice.Sergent, streaks = listOf(Streak(StreakKind.Workout, 6, doneToday = false))), emptyList()), "r"), null),
        Triple(ch.coach, sample(Nudge.pick(NudgeContext.Milestone,
            at(18).copy(milestone = Streak(StreakKind.Meals, 7, doneToday = true)), emptyList()), "p"), null),
        Triple(ch.sleep, sample(Nudge.pick(NudgeContext.SleepReport,
            NudgeFixtures.clock(7, 40).copy(sleep = SleepView(452, deepMin = 70, coverageMin = 440, totalMin = 452, events = 3)), emptyList()), "s"), null),
        Triple(ch.explore, sample(Nudge.pick(NudgeContext.NewPlace,
            NudgeFixtures.clock(17).copy(place = PlaceView("", 320, 1, 7)), emptyList()), "l"), null),
        Triple(ch.social, sample(Nudge.pick(NudgeContext.FriendNear,
            NudgeFixtures.clock(13).copy(friend = FriendView("u", "Ana", 380, null)), emptyList()), "f"), null),
        Triple(ch.coach, sample(Nudge.pick(NudgeContext.Bedtime,
            NudgeFixtures.clock(22, 35).copy(wake = "07:30", minutesToBedtime = 25), emptyList()), "b"), null),
        Triple(ch.coach, sample(Nudge.pick(NudgeContext.Comeback,
            NudgeFixtures.clock(13).copy(comebackStep = 14), emptyList()), "c"), null)
    ).map { it.item() }
}

@Composable
private fun NotificationCard(item: Item) {
    val r = item.r
    val icon = remember(r.pose) { MascotIcons.render(r.pose, 144).asImageBitmap() }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Card).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(Modifier.weight(1f)) {
            Text("FORJA · ${item.channel}", style = TextStyle(color = Sub, fontSize = 12.sp))
            Spacer(Modifier.height(4.dp))
            // Ro.glue, ca Notifier/SyncNotice: numărul rămâne pe rând cu unitatea.
            Text(Ro.glue(r.title), style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(2.dp))
            Text(Ro.glue(item.expanded ?: r.body), style = TextStyle(color = Sub, fontSize = 14.sp, lineHeight = 19.sp))
            item.action?.let { a ->
                Spacer(Modifier.height(10.dp))
                Box(Modifier.clip(RoundedCornerShape(16.dp)).background(ActionFill).padding(horizontal = 14.dp, vertical = 7.dp)) {
                    Text(a, style = TextStyle(color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Image(icon, contentDescription = null, modifier = Modifier.size(44.dp))
    }
}

@Composable
private fun Board() {
    Column(
        Modifier.fillMaxWidth().background(Shade).verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StampLabel("CASCA · NOTIFICĂRI", rotationDeg = -3f, appear = false)
        Spacer(Modifier.height(4.dp))
        val names = ChannelNames(
            coach = stringResource(R.string.notif_channel_coach),
            sleep = stringResource(R.string.notif_channel_sleep),
            explore = stringResource(R.string.notif_channel_explore),
            social = stringResource(R.string.notif_channel_social)
        )
        board(names).forEach { NotificationCard(it) }
    }
}

@Composable
private fun Icons() {
    Column(Modifier.background(Shade).padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NudgePose.entries.forEach { pose ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val bmp = remember(pose) { MascotIcons.render(pose, 192).asImageBitmap() }
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Card)) {
                        Image(bmp, contentDescription = null, modifier = Modifier.size(48.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(pose.name.uppercase(), style = monoLabel(8).copy(color = TextDim))
                }
            }
        }
    }
}

private val eveningEcho = Echo("4.1", "Evening", NudgePose.Happy, "Raport de seară, Lana.", "Azi: 3,4 km, 2 mese, un antrenament, 45 min de focus. Ziua s-a scris.")
private val angryEcho = Echo("5.2", "StreakRisk", NudgePose.Angry, "Casca e puțin încruntată.", "6 zile de instrucție și azi nimic încă. Zece genuflexiuni o salvează.")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE, application = Application::class)
class NudgeShots {
    @Test fun echo() = shot("casca_echo", fullScreen = false) { Box(Modifier.padding(20.dp)) { NudgeEchoCard(eveningEcho) } }
    @Test fun echoAngry() = shot("casca_echo_angry", fullScreen = false) { Box(Modifier.padding(20.dp)) { NudgeEchoCard(angryEcho) } }
    @Test fun icons() = shot("casca_icons", fullScreen = false) { Icons() }
    /**
     * Planșa e mai înaltă decât telefonul: fereastră de 1600 dp, PNG-ul ia exact înălțimea conținutului (~1 300 dp).
     * Pe 2400 dp, cu fundal pe tot ecranul, captura de 1179 × 7200 px pica cu OutOfMemoryError în CI.
     */
    @Config(qualifiers = "w393dp-h1600dp-xxhdpi")
    @Test fun board() = shot("casca_notificari", fullScreen = false) { Board() }
}

/** Galaxy S23 al Lanei (360 dp): ecoul pe lățimea îngustă și planșa. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE_S23, application = Application::class)
class NudgeShotsS23 {
    @Test fun echo() = shot("casca_echo_s23", fullScreen = false) { Box(Modifier.padding(20.dp)) { NudgeEchoCard(eveningEcho) } }
    @Config(qualifiers = "w360dp-h1600dp-xxhdpi")
    @Test fun board() = shot("casca_notificari_s23", fullScreen = false) { Board() }
}
