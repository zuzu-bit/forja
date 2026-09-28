package com.forja.app.screenshots

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.forja.app.core.designsystem.components.MascotState
import com.forja.app.core.games.asalt.AsaltEngine
import com.forja.app.core.games.asalt.AsaltLevels
import com.forja.app.core.games.zid.ZidEngine
import com.forja.app.core.inventory.BinTick
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
    private fun RunCards(bins: List<BinTick> = InventorySamples.run.bins) =
        InventoryRunContent(InventorySamples.run.copy(zidLevel = 4, asaltLevel = 2, bins = bins), RunActions())

    /** Cele mai lungi nume de dosar din probe (două părți cu „ · ”), ca pe banda aplicării. */
    private val longBins = InventorySamples.photoFolderNames.take(4).map { BinTick(it.first, it.second) }

    /**
     * Fontul din sistem mărit (Setări → Afișaj → Mărime font), peste densitatea profilului. `Density(d, scale)` din
     * Compose 1.7 convertește sp-ul neliniar peste 1,03 (tabelele din Android 14), deci captura crește ca pe S23.
     */
    @Composable
    private fun FontScale(scale: Float, content: @Composable () -> Unit) {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, scale), content = content)
    }

    @Composable
    private fun ZidMap() = LevelMapContent(GameSamples.zidMap, LevelMapActions())

    @Composable
    private fun ZidPlay(
        overlay: GameOverlay,
        mascot: MascotState,
        pill: PillState? = GameSamples.pill,
        engine: ZidEngine = GameSamples.zidPlay()
    ) = ZidPlayContent(
        play = ZidPlayState(engine),
        overlay = overlay,
        pill = pill,
        mascot = mascot,
        // ca în ZidScreen: „RANG n” în „Fără sfârșit”, altfel „NIV. n”
        levelLabel = if (engine.isEndless) "RANG ${engine.rank}" else "NIV. ${engine.level.id}",
        actions = ZidPlayActions()
    )

    @Composable
    private fun ZidEndless() = ZidPlay(GameOverlay.Result(GameSamples.zidEndless), MascotState.Happy, engine = GameSamples.zidEndlessPlay())

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
        levelLabel = "NIV. 1 · ${AsaltLevels.byId(1).name.uppercase()}",
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

    /** Cazul cel mai greu al benzii: patru nume lungi („Plajă · Vama Veche”) pe cutiile de 69 dp ale S23. */
    @Config(qualifiers = PHONE_S23)
    @Test fun runCardsLongS23() = shot("games_run_cards_long_s23") { RunCards(longBins) }

    /** Aceleași nume cu fontul la 130 %: cutia numelui crește cu fontul, nimic nu se taie la un rând. */
    @Config(qualifiers = PHONE_S23)
    @Test fun runCardsFont130S23() = shot("games_run_cards_font130_s23") { FontScale(1.3f) { RunCards(longBins) } }

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
    @Test fun zidEndlessS23() = shot("games_zid_endless_s23") { ZidEndless() }

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
    @Test fun zidEndless() = shot("games_zid_endless") { ZidEndless() }
    @Test fun zidCountdown() = shot("games_zid_countdown") { ZidPlay(GameOverlay.Countdown(2), MascotState.Idle) }
    @Test fun asaltMap() = shot("games_asalt_map") { AsaltMap() }
    @Test fun asaltReady() = shot("games_asalt_ready") { AsaltReady() }
    @Test fun asaltLost() = shot("games_asalt_lost") { AsaltPlay(GameOverlay.Result(GameSamples.asaltLost), MascotState.Sorry) }

    @Test fun inventoryRow() = shot("games_inventory_row", fullScreen = false) {
        Box(Modifier.padding(16.dp).width(360.dp)) { InventoryReadyRow(onOpen = {}) }
    }
}
