package com.forja.app.screenshots

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.forja.app.core.designsystem.StrokeCard
import com.forja.app.core.designsystem.components.ForjaCard
import com.forja.app.core.designsystem.components.SectionLabel
import com.forja.app.core.recovery.FinderState
import com.forja.app.core.recovery.FinderUi
import com.forja.app.feature.permissions.ContractContent
import com.forja.app.feature.permissions.ContractResignContent
import com.forja.app.feature.permissions.ContractUi
import com.forja.app.feature.profile.FinderRow
import com.forja.app.feature.recovery.FoundContent
import com.forja.app.feature.recovery.GasireActions
import com.forja.app.feature.recovery.GasireSheetContent
import com.forja.app.feature.recovery.SheetFrame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/*
 * Găsirea (4.4) și contractul v3, la ambele profiluri: telefonul de referință (PHONE) și S23-ul Lanei (PHONE_S23).
 * Foile de jos (ModalBottomSheet) sunt ferestre separate — capturăm conținutul lor într-un cadru de foaie, peste
 * fundalul întunecat de 60 % pe care îl pune foaia reală.
 */

/** Momentul „acum” al capturilor: liniile „VĂZUT ACUM …” nu depind de ceasul mașinii de CI. */
private const val NOW = 1_790_000_000_000L

private val guard = FinderUi(FinderState.Guard, lastOkAt = NOW - 60_000L, battery = 64, problem = null)
private val noLink = FinderUi(
    FinderState.NoLink, lastOkAt = NOW - 3 * 3_600_000L, battery = 12,
    problem = "Sincronizarea e oprită. Apasă „Probă”."
)
private val rows = listOf(
    guard,
    FinderUi(FinderState.NoContract, 0L, null, null),
    FinderUi(FinderState.Incomplete, NOW - 60_000L, 80, null),
    noLink,
)

/** Foaia la baza ecranului, peste un fundal întunecat (ca în aplicație). */
@Composable
private fun OverScrim(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.BottomCenter) {
        SheetFrame { content() }
    }
}

abstract class GasireShotsBase(private val tag: String) {

    /** Rândul „Telefonul meu” în cele patru stări, ca în grupul „Cont & date” din Profil. */
    @Test fun profileRows() = shot("gasire_profile_rows$tag", fullScreen = false) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp)) {
            SectionLabel("Telefonul meu · stări")
            Spacer(Modifier.height(10.dp))
            ForjaCard(Modifier.fillMaxWidth(), stroke = StrokeCard, padding = 4.dp) {
                rows.forEach { ui -> FinderRow(ui) {} }
            }
        }
    }

    @Test fun sheetGuard() = shot("gasire_sheet_guard$tag") {
        OverScrim { GasireSheetContent(guard, probing = false, actions = GasireActions(), now = NOW) }
    }

    @Test fun sheetNoLink() = shot("gasire_sheet_nolink$tag") {
        OverScrim { GasireSheetContent(noLink, probing = false, actions = GasireActions(), now = NOW) }
    }

    @Test fun sheetProbing() = shot("gasire_sheet_probing$tag") {
        OverScrim { GasireSheetContent(guard, probing = true, actions = GasireActions(), now = NOW) }
    }

    /** „GĂSIRE · Aici sunt.” — ce vede cine găsește telefonul care sună. */
    @Test fun foundRinging() = shot("gasire_found$tag") { FoundContent(secondsLeft = 45, found = false, onFound = {}) }

    @Test fun foundDone() = shot("gasire_found_done$tag") { FoundContent(secondsLeft = 38, found = true, onFound = {}) }

    /** O tastă de volum a oprit soneria: fără numărătoare, fără buton, ecranul pleacă singur. */
    @Test fun foundSilenced() = shot("gasire_found_silenced$tag") {
        FoundContent(secondsLeft = 0, found = false, silenced = true, onFound = {})
    }

    /** Contractul v3 pentru cine a semnat v2: titlul „la zi” și rândurile marcate NOU / CORECTAT. */
    @Test fun contractResign() = shot("contract_v3_resign$tag") {
        ContractContent(ContractUi(needsResign = true), busy = false, onClose = {}, onSign = {}, onRevoke = {})
    }

    /** Contractul v3 la prima semnare (fără marcaje). */
    @Test fun contractFresh() = shot("contract_v3_fresh$tag") {
        ContractContent(ContractUi(), busy = false, onClose = {}, onSign = {}, onRevoke = {})
    }

    @Test fun contractSigned() = shot("contract_v3_signed$tag") {
        ContractContent(
            ContractUi(signed = true, signedAt = NOW, signedVersion = 3, statusLines = listOf("Sincronizat la 14:32. Sincronizarea este activă.")),
            busy = false, onClose = {}, onSign = {}, onRevoke = {}
        )
    }

    /** Foaia de re-semnare de pe „Azi” (o singură dată după actualizare). */
    @Test fun resignSheet() = shot("contract_v3_resign_sheet$tag") {
        OverScrim { ContractResignContent(busy = false, onSign = {}, onReadAll = {}, onLater = {}) }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE, application = Application::class)
class GasireShots : GasireShotsBase("")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE_S23, application = Application::class)
class GasireShotsS23 : GasireShotsBase("_s23")
