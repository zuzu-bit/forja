package com.forja.app.screenshots

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.forja.app.feature.inventory.AccessUi
import com.forja.app.feature.inventory.ApplyConfirmBody
import com.forja.app.feature.inventory.ApplyConfirmUi
import com.forja.app.feature.inventory.DoneActions
import com.forja.app.feature.inventory.DoneUiState
import com.forja.app.feature.inventory.FoldersActions
import com.forja.app.feature.inventory.InvSheetFrame
import com.forja.app.feature.inventory.InventoryDoneContent
import com.forja.app.feature.inventory.InventoryFoldersContent
import com.forja.app.feature.inventory.InventorySamples
import com.forja.app.feature.inventory.LocationBody
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Inventarul 4.4.2 (după „Nu s-a aplicat tot.” pe S23-ul Lanei): rândul „Acces complet” din confirmare (varianta
 * simplă, cea cu pozele din WhatsApp și cea cu un dosar din Documents) și pagina de rezultat care ia locul toast-ului
 * (ceva aplicat + „Permite accesul”, ceva aplicat + „Încearcă din nou”, nimic aplicat) — fiecare la PHONE (393 × 851)
 * și la PHONE_S23 (360 × 696 utili). Foile se desenează pe loc cu InvSheetFrame, peste dosarele din spatele lor.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE, application = Application::class)
class Inventory442Shots {

    // ───────────── confirmarea, cu rândul „Acces complet” ─────────────

    @Composable private fun confirmScene(ui: ApplyConfirmUi, access: AccessUi) = Box(Modifier.fillMaxSize()) {
        InventoryFoldersContent(InventorySamples.folders, FoldersActions())
        InvSheetFrame { ApplyConfirmBody(ui, onApply = {}, onDest = {}, access = access) }
    }

    @Test fun confirmAccess() = shot("inventory442_confirm_access") { confirmScene(InventorySamples.confirm, InventorySamples.access) }
    @Test @Config(qualifiers = PHONE_S23) fun confirmAccessS23() = shot("inventory442_confirm_access_s23") {
        confirmScene(InventorySamples.confirm, InventorySamples.access)
    }

    /** Planul Lanei: 6 din 9 poze sunt ale WhatsApp — rândul spune asta înainte de „Aplică”. */
    @Test fun confirmAccessWhatsApp() = shot("inventory442_confirm_access_whatsapp") {
        confirmScene(InventorySamples.confirmLana, InventorySamples.accessWhatsApp)
    }
    @Test @Config(qualifiers = PHONE_S23) fun confirmAccessWhatsAppS23() = shot("inventory442_confirm_access_whatsapp_s23") {
        confirmScene(InventorySamples.confirmLana, InventorySamples.accessWhatsApp)
    }

    /** Dosarul ales în Documents: fără acces nu se poate, deci rândul n-are „Fără” și „Aplică” cere accesul întâi. */
    @Test fun confirmAccessDocuments() = shot("inventory442_confirm_access_documents") {
        confirmScene(InventorySamples.confirmLanaDocuments, InventorySamples.accessDest)
    }
    @Test @Config(qualifiers = PHONE_S23) fun confirmAccessDocumentsS23() = shot("inventory442_confirm_access_documents_s23") {
        confirmScene(InventorySamples.confirmLanaDocuments, InventorySamples.accessDest)
    }

    /** „Locație” după „Alt dosar…” în Documents/Poze: rândul lui apare ales, sub cele trei. */
    @Test @Config(qualifiers = PHONE_S23) fun locationDocumentsS23() = shot("inventory442_location_documents_s23") {
        Box(Modifier.fillMaxSize()) {
            InventoryFoldersContent(InventorySamples.folders, FoldersActions())
            InvSheetFrame { LocationBody(InventorySamples.locationDocuments, onBack = {}, onPick = {}, onOther = {}) }
        }
    }

    // ───────────── pagina de rezultat (în locul toast-ului „Nu s-a aplicat tot.”) ─────────────

    @Composable private fun resultScene(state: DoneUiState) = InventoryDoneContent(state, DoneActions())

    /** Rezultatul Lanei: 3 mutate + 1 la gunoi, 6 NEMUTATE, „Sunt ale WhatsApp: …”, „Permite accesul”. */
    @Test fun donePartialAccess() = shot("inventory442_done_partial_access") { resultScene(InventorySamples.doneAccess) }
    @Test @Config(qualifiers = PHONE_S23) fun donePartialAccessS23() = shot("inventory442_done_partial_access_s23") {
        resultScene(InventorySamples.doneAccess)
    }

    @Test fun donePartialRetry() = shot("inventory442_done_partial_retry") { resultScene(InventorySamples.doneRetry) }
    @Test @Config(qualifiers = PHONE_S23) fun donePartialRetryS23() = shot("inventory442_done_partial_retry_s23") {
        resultScene(InventorySamples.doneRetry)
    }

    /** Accesul dat, dar 6 tot n-au mers: „3 MUTATE”, rândul general, doar „Înapoi la dosare”. */
    @Test fun doneNoAction() = shot("inventory442_done_no_action") { resultScene(InventorySamples.doneNoAction) }
    @Test @Config(qualifiers = PHONE_S23) fun doneNoActionS23() = shot("inventory442_done_no_action_s23") {
        resultScene(InventorySamples.doneNoAction)
    }

    /** Nimic aplicat: aceeași pagină cu 0, motivul și acțiunea — nu mai sare la dosare. */
    @Test fun doneNothing() = shot("inventory442_done_nothing") { resultScene(InventorySamples.doneNothing) }
    @Test @Config(qualifiers = PHONE_S23) fun doneNothingS23() = shot("inventory442_done_nothing_s23") {
        resultScene(InventorySamples.doneNothing)
    }
}
