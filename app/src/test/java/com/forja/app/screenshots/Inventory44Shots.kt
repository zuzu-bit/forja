package com.forja.app.screenshots

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.forja.app.feature.inventory.ApplyConfirmBody
import com.forja.app.feature.inventory.ApplyConfirmUi
import com.forja.app.feature.inventory.DoneActions
import com.forja.app.feature.inventory.DoneUiState
import com.forja.app.feature.inventory.FolderActions
import com.forja.app.feature.inventory.FoldersActions
import com.forja.app.feature.inventory.FoldersUiState
import com.forja.app.feature.inventory.InvSheetFrame
import com.forja.app.feature.inventory.InventoryApplyContent
import com.forja.app.feature.inventory.InventoryDoneContent
import com.forja.app.feature.inventory.InventoryFolderContent
import com.forja.app.feature.inventory.InventoryFoldersContent
import com.forja.app.feature.inventory.InventorySamples
import com.forja.app.feature.inventory.InventoryStartContent
import com.forja.app.feature.inventory.LastNBody
import com.forja.app.feature.inventory.LocationBody
import com.forja.app.feature.inventory.LocationUi
import com.forja.app.feature.inventory.StartActions
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Inventarul 4.4 (după testul Lanei pe S23): „Ultimele N” editabil, foaia „Locație”, rândul destinației din
 * confirmare, placa DOCUMENTE „În ordine”, finalul cu sus/jos fixe și calea noii locații — fiecare la PHONE
 * (393 × 851) și la PHONE_S23 (360 × 696 utili, ecranul pe care s-au văzut X-ul sub bara de stare și „Nimic nu
 * s-a șters” lipit de buton). Foile de jos se desenează pe loc cu InvSheetFrame (aceeași foaie, fără fereastra
 * ModalBottomSheet, pe care Robolectric nu o capturează), peste ecranul din spatele lor.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE, application = Application::class)
class Inventory44Shots {

    // ───────────── S1: „Ultimele N” și placa DOCUMENTE ─────────────

    /** Textul mărit din Setări (fontScale 1,3), fără să depindă de cum aplică Robolectric configurația. */
    @Composable private fun LargeText(content: @Composable () -> Unit) {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 1.3f)) { content() }
    }

    @Composable private fun lastNScene(current: Int) = Box(Modifier.fillMaxSize()) {
        InventoryStartContent(InventorySamples.startLastN.copy(lastN = current), StartActions())
        InvSheetFrame { LastNBody(current = current, total = InventorySamples.start.photoCount, onPick = {}) }
    }

    @Test fun lastNSheet() = shot("inventory44_lastn_sheet") { lastNScene(500) }
    @Test @Config(qualifiers = PHONE_S23) fun lastNSheetS23() = shot("inventory44_lastn_sheet_s23") { lastNScene(500) }
    /** Un număr scris de mână (2 500): nicio presetare aleasă, valoarea stă în câmp. */
    @Test fun lastNSheetCustom() = shot("inventory44_lastn_sheet_custom") { lastNScene(2_500) }

    @Test fun startLastN() = shot("inventory44_s1_lastn") { InventoryStartContent(InventorySamples.startLastN, StartActions()) }
    @Test @Config(qualifiers = PHONE_S23) fun startLastNS23() = shot("inventory44_s1_lastn_s23") {
        InventoryStartContent(InventorySamples.startLastN, StartActions())
    }

    @Test fun startDocsOrdered() = shot("inventory44_s1_docs_in_ordine") {
        InventoryStartContent(InventorySamples.startDocsOrdered, StartActions())
    }
    @Test @Config(qualifiers = PHONE_S23) fun startDocsOrderedS23() = shot("inventory44_s1_docs_in_ordine_s23") {
        InventoryStartContent(InventorySamples.startDocsOrdered, StartActions())
    }

    // ───────────── S6: confirmarea cu destinația și foaia „Locație” ─────────────

    @Composable private fun confirmScene(folders: FoldersUiState, ui: ApplyConfirmUi) = Box(Modifier.fillMaxSize()) {
        InventoryFoldersContent(folders, FoldersActions())
        InvSheetFrame { ApplyConfirmBody(ui, onApply = {}, onDest = {}) }
    }

    @Composable private fun locationScene(folders: FoldersUiState, ui: LocationUi) = Box(Modifier.fillMaxSize()) {
        InventoryFoldersContent(folders, FoldersActions())
        InvSheetFrame { LocationBody(ui, onBack = {}, onPick = {}, onOther = {}) }
    }

    @Test fun applyConfirm() = shot("inventory44_apply_confirm") { confirmScene(InventorySamples.folders, InventorySamples.confirm) }
    @Test @Config(qualifiers = PHONE_S23) fun applyConfirmS23() = shot("inventory44_apply_confirm_s23") {
        confirmScene(InventorySamples.folders, InventorySamples.confirm)
    }
    @Test fun applyConfirmDocs() = shot("inventory44_apply_confirm_docs") { confirmScene(InventorySamples.docFolders, InventorySamples.confirmDocs) }
    @Test @Config(qualifiers = PHONE_S23) fun applyConfirmDocsS23() = shot("inventory44_apply_confirm_docs_s23") {
        confirmScene(InventorySamples.docFolders, InventorySamples.confirmDocs)
    }

    @Test fun locationPhotos() = shot("inventory44_location_photos") { locationScene(InventorySamples.folders, InventorySamples.location) }
    @Test @Config(qualifiers = PHONE_S23) fun locationPhotosS23() = shot("inventory44_location_photos_s23") {
        locationScene(InventorySamples.folders, InventorySamples.location)
    }
    /** Poze într-un dosar ales cu „Alt dosar…”: rândul lui apare ales, sub cele trei. */
    @Test fun locationPhotosCustom() = shot("inventory44_location_photos_custom") {
        locationScene(InventorySamples.folders, InventorySamples.locationCustom)
    }
    @Test fun locationDocs() = shot("inventory44_location_docs") { locationScene(InventorySamples.docFolders, InventorySamples.locationDocs) }
    @Test @Config(qualifiers = PHONE_S23) fun locationDocsS23() = shot("inventory44_location_docs_s23") {
        locationScene(InventorySamples.docFolders, InventorySamples.locationDocs)
    }

    // ───────────── Restul paginilor la înălțimea S23 (verificarea de depășire din raportul Inventar §4.3) ─────────────

    @Test @Config(qualifiers = PHONE_S23) fun startS23() = shot("inventory44_s1_start_s23") {
        InventoryStartContent(InventorySamples.start, StartActions())
    }
    /** Prima deschidere pe S23: „Dă acces” / „Alege dosar” ca cuvinte mari pe plăcile înguste. */
    @Test @Config(qualifiers = PHONE_S23) fun startNoAccessS23() = shot("inventory44_s1_noaccess_s23") {
        InventoryStartContent(InventorySamples.startNoAccess, StartActions())
    }
    /** Cel mai rău caz pentru plăci: S23 + text mărit 130 %; „2,1 GB” coboară întreg pe rândul doi, nu se taie. */
    @Test @Config(qualifiers = PHONE_S23) fun startS23LargeText() = shot("inventory44_s1_start_s23_text130") {
        LargeText { InventoryStartContent(InventorySamples.start, StartActions()) }
    }
    @Test @Config(qualifiers = PHONE_S23) fun foldersS23() = shot("inventory44_s4_folders_s23") {
        InventoryFoldersContent(InventorySamples.folders, FoldersActions())
    }
    @Test @Config(qualifiers = PHONE_S23) fun trashS23() = shot("inventory44_s5_trash_s23") {
        InventoryFolderContent(InventorySamples.trash, FolderActions())
    }
    @Test @Config(qualifiers = PHONE_S23) fun applyS23() = shot("inventory44_s6_apply_s23") { InventoryApplyContent(InventorySamples.apply) }
    /** 4.4.1: captura Lanei (0 / 10, „AȘTEPT ACORDUL TĂU”, fără butoane) — așa arată cât dialogul e pe drum. */
    @Test @Config(qualifiers = PHONE_S23) fun applyWaitingS23() = shot("inventory441_s6_waiting_s23") {
        InventoryApplyContent(InventorySamples.apply.copy(done = 0, total = 10, recent = emptyList(), waiting = true))
    }
    /** 4.4.1: fereastra Android n-a apărut nici a doua oară — rândul spune asta, dedesubt cele două butoane, fără derulare. */
    @Test @Config(qualifiers = PHONE_S23) fun applyStuckS23() = shot("inventory441_s6_stuck_s23") {
        InventoryApplyContent(InventorySamples.apply.copy(done = 0, total = 10, recent = emptyList(), waiting = true, stuck = true))
    }

    // ───────────── Finalul: sus și jos fixe, eroul scalat, calea noii locații ─────────────

    @Composable private fun doneScene(state: DoneUiState) = InventoryDoneContent(state, DoneActions())

    @Test fun donePhotos() = shot("inventory44_done_photos") { doneScene(InventorySamples.done) }
    @Test @Config(qualifiers = PHONE_S23) fun donePhotosS23() = shot("inventory44_done_photos_s23") { doneScene(InventorySamples.done) }
    @Test fun donePhotosFailed() = shot("inventory44_done_photos_failed") { doneScene(InventorySamples.doneFailed) }
    @Test @Config(qualifiers = PHONE_S23) fun donePhotosFailedS23() = shot("inventory44_done_photos_failed_s23") {
        doneScene(InventorySamples.doneFailed)
    }
    @Test fun doneDocs() = shot("inventory44_done_docs") { doneScene(InventorySamples.doneDocs) }
    @Test @Config(qualifiers = PHONE_S23) fun doneDocsS23() = shot("inventory44_done_docs_s23") { doneScene(InventorySamples.doneDocs) }
    @Test fun doneDocsFailed() = shot("inventory44_done_docs_failed") { doneScene(InventorySamples.doneDocsFailed) }
    @Test @Config(qualifiers = PHONE_S23) fun doneDocsFailedS23() = shot("inventory44_done_docs_failed_s23") {
        doneScene(InventorySamples.doneDocsFailed)
    }
}
