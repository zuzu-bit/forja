package com.forja.app.screenshots

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.forja.app.core.designsystem.components.MascotState
import com.forja.app.core.games.asalt.AsaltEngine
import com.forja.app.core.games.asalt.AsaltLevels
import com.forja.app.feature.games.GameOverlay
import com.forja.app.feature.games.GameSamples
import com.forja.app.feature.games.InventoryReadyRow
import com.forja.app.feature.games.LevelMapActions
import com.forja.app.feature.games.LevelMapContent
import com.forja.app.feature.games.asalt.AsaltPlayActions
import com.forja.app.feature.games.asalt.AsaltPlayContent
import com.forja.app.feature.games.asalt.AsaltPlayState
import com.forja.app.feature.games.zid.ZidPlayActions
import com.forja.app.feature.games.zid.ZidPlayContent
import com.forja.app.feature.games.zid.ZidPlayState
import com.forja.app.feature.inventory.InventoryRunContent
import com.forja.app.feature.inventory.InventorySamples
import com.forja.app.feature.inventory.PillState
import com.forja.app.feature.inventory.RunActions
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Jocurile 4.4 (ZID și ASALT) cu datele din GameSamples: cardurile din S2, harta, jocul, pauza, finalul și rândul
 * „Dosarele sunt gata”. Fiecare ecran întreg de două ori: telefonul de referință (PHONE) și S23-ul Lanei (PHONE_S23,
 * 360 × 696 dp — bara de stare și cea cu 3 butoane scăzute). Doar conținutul fără stare: nicio buclă de joc compusă.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE, application = Application::class)
class GameShots {

    // ───────────────────────────── ecranele ─────────────────────────────

    @Composable
    private fun RunCards() = InventoryRunContent(InventorySamples.run.copy(zidLevel = 4, asaltLevel = 2), RunActions())

    @Composable
    private fun ZidMap() = LevelMapContent(GameSamples.zidMap, LevelMapActions())

    @Composable
    private fun ZidPlay(overlay: GameOverlay, mascot: MascotState, pill: PillState? = GameSamples.pill) = ZidPlayContent(
        play = ZidPlayState(GameSamples.zidPlay()),
        overlay = overlay,
        pill = pill,
        mascot = mascot,
        levelLabel = "NIV. 6",
        actions = ZidPlayActions()
    )

    @Composable
    private fun AsaltPlay(overlay: GameOverlay, mascot: MascotState, pill: PillState? = GameSamples.pill) = AsaltPlayContent(
        play = AsaltPlayState(GameSamples.asaltPlay()),
        overlay = overlay,
        pill = pill,
        mascot = mascot,
        levelLabel = "NIV. 3 · POARTA",
        actions = AsaltPlayActions()
    )

    @Composable
    private fun ZidReady() = ZidPlayContent(
        play = ZidPlayState(GameSamples.zidReady()),
        overlay = GameOverlay.Ready,
        pill = GameSamples.pill,
        mascot = MascotState.Idle,
        levelLabel = "NIV. 1",
        actions = ZidPlayActions()
    )

    @Composable
    private fun AsaltReady() = AsaltPlayContent(
        play = AsaltPlayState(AsaltEngine.create(AsaltLevels.byId(1), 3L)),
        overlay = GameOverlay.Ready,
        pill = null,
        mascot = MascotState.Idle,
        levelLabel = "NIV. 1 · PRIMUL ZID",
        actions = AsaltPlayActions()
    )

    @Composable
    private fun AsaltMap() = LevelMapContent(GameSamples.asaltMap, LevelMapActions())

    // ───────────────────────────── PHONE ─────────────────────────────

    @Test fun runCards() = shot("games_run_cards") { RunCards() }
    @Test fun zidMap() = shot("games_zid_map") { ZidMap() }
    @Test fun zidPlay() = shot("games_zid_play") { ZidPlay(GameOverlay.None, MascotState.Idle) }
    @Test fun zidPause() = shot("games_zid_pause") { ZidPlay(GameOverlay.Pause(GameSamples.zidPause), MascotState.Thinking) }
    @Test fun zidWon() = shot("games_zid_won") { ZidPlay(GameOverlay.Result(GameSamples.zidWon), MascotState.Happy) }
    @Test fun asaltPlay() = shot("games_asalt_play") { AsaltPlay(GameOverlay.None, MascotState.Idle) }
    @Test fun asaltWon() = shot("games_asalt_won") { AsaltPlay(GameOverlay.Result(GameSamples.asaltWon), MascotState.Happy) }
    @Test fun inventoryReady() = shot("games_inventory_ready") {
        ZidPlay(GameOverlay.Pause(GameSamples.zidPauseReady), MascotState.Thinking, pill = GameSamples.pillReady)
    }

    // ───────────────────────────── PHONE_S23 (Galaxy S23 al Lanei) ─────────────────────────────

    @Config(qualifiers = PHONE_S23)
    @Test fun runCardsS23() = shot("games_run_cards_s23") { RunCards() }

    @Config(qualifiers = PHONE_S23)
    @Test fun zidMapS23() = shot("games_zid_map_s23") { ZidMap() }

    @Config(qualifiers = PHONE_S23)
    @Test fun zidPlayS23() = shot("games_zid_play_s23") { ZidPlay(GameOverlay.None, MascotState.Idle) }

    @Config(qualifiers = PHONE_S23)
    @Test fun zidPauseS23() = shot("games_zid_pause_s23") { ZidPlay(GameOverlay.Pause(GameSamples.zidPause), MascotState.Thinking) }

    @Config(qualifiers = PHONE_S23)
    @Test fun zidWonS23() = shot("games_zid_won_s23") { ZidPlay(GameOverlay.Result(GameSamples.zidWon), MascotState.Happy) }

    @Config(qualifiers = PHONE_S23)
    @Test fun asaltPlayS23() = shot("games_asalt_play_s23") { AsaltPlay(GameOverlay.None, MascotState.Idle) }

    @Config(qualifiers = PHONE_S23)
    @Test fun asaltWonS23() = shot("games_asalt_won_s23") { AsaltPlay(GameOverlay.Result(GameSamples.asaltWon), MascotState.Happy) }

    @Config(qualifiers = PHONE_S23)
    @Test fun inventoryReadyS23() = shot("games_inventory_ready_s23") {
        ZidPlay(GameOverlay.Pause(GameSamples.zidPauseReady), MascotState.Thinking, pill = GameSamples.pillReady)
    }

    @Config(qualifiers = PHONE_S23)
    @Test fun zidReadyS23() = shot("games_zid_ready_s23") { ZidReady() }

    /** Cel mai înalt card: „Zidul a căzut.” cu mascota de 150 dp și rândul „Dosarele sunt gata”. */
    @Config(qualifiers = PHONE_S23)
    @Test fun zidLostS23() = shot("games_zid_lost_s23") {
        ZidPlay(GameOverlay.Result(GameSamples.zidLost.copy(inventoryReady = true)), MascotState.Sorry, pill = GameSamples.pillReady)
    }

    @Config(qualifiers = PHONE_S23)
    @Test fun zidEndlessS23() = shot("games_zid_endless_s23") { ZidPlay(GameOverlay.Result(GameSamples.zidEndless), MascotState.Happy) }

    @Config(qualifiers = PHONE_S23)
    @Test fun asaltMapS23() = shot("games_asalt_map_s23") { AsaltMap() }

    @Config(qualifiers = PHONE_S23)
    @Test fun asaltReadyS23() = shot("games_asalt_ready_s23") { AsaltReady() }

    @Config(qualifiers = PHONE_S23)
    @Test fun asaltLostS23() = shot("games_asalt_lost_s23") {
        AsaltPlay(GameOverlay.Result(GameSamples.asaltLost.copy(inventoryReady = true)), MascotState.Sorry, pill = GameSamples.pillReady)
    }

    // ───────────────────────────── restul stărilor (PHONE) ─────────────────────────────

    @Test fun zidReady() = shot("games_zid_ready") { ZidReady() }
    @Test fun zidLost() = shot("games_zid_lost") { ZidPlay(GameOverlay.Result(GameSamples.zidLost), MascotState.Sorry) }
    @Test fun zidEndless() = shot("games_zid_endless") { ZidPlay(GameOverlay.Result(GameSamples.zidEndless), MascotState.Happy) }
    @Test fun zidCountdown() = shot("games_zid_countdown") { ZidPlay(GameOverlay.Countdown(2), MascotState.Idle) }
    @Test fun asaltMap() = shot("games_asalt_map") { AsaltMap() }
    @Test fun asaltReady() = shot("games_asalt_ready") { AsaltReady() }
    @Test fun asaltLost() = shot("games_asalt_lost") { AsaltPlay(GameOverlay.Result(GameSamples.asaltLost), MascotState.Sorry) }

    @Test fun inventoryRow() = shot("games_inventory_row", fullScreen = false) {
        Box(Modifier.padding(16.dp).width(360.dp)) { InventoryReadyRow(onOpen = {}) }
    }
}
