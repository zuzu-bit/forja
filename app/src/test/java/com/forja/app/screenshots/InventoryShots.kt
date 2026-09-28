package com.forja.app.screenshots

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.forja.app.feature.inventory.DoneActions
import com.forja.app.feature.inventory.FolderActions
import com.forja.app.feature.inventory.FoldersActions
import com.forja.app.feature.inventory.InvProgressPill
import com.forja.app.feature.inventory.InventoryApplyContent
import com.forja.app.feature.inventory.InventoryDoneContent
import com.forja.app.feature.inventory.InventoryFolderContent
import com.forja.app.feature.inventory.InventoryFoldersContent
import com.forja.app.feature.inventory.InventoryRunContent
import com.forja.app.feature.inventory.InventorySamples
import com.forja.app.feature.inventory.InventoryStartContent
import com.forja.app.feature.inventory.MusicActions
import com.forja.app.feature.inventory.MusicWaitContent
import com.forja.app.feature.inventory.RunActions
import com.forja.app.feature.inventory.StartActions
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Inventarul 4.3 (pachetul F) cu datele din InventorySamples — aceleași ecrane ca prototipul
 * (Main, Rulare, Sport, Interval, Muzica, Dosare, DeAruncat, Gata). Miniaturile sunt URI-uri false: pe JVM apar
 * locurile goale ale miniaturilor, restul ecranului e cel real. Foile de jos (ModalBottomSheet) nu intră aici.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE, application = Application::class)
class InventoryShots {

    @Test fun s1Start() = shot("inventory_s1_start") { InventoryStartContent(InventorySamples.start, StartActions()) }
    @Test fun s1Docs() = shot("inventory_s1_docs") { InventoryStartContent(InventorySamples.startDocs, StartActions()) }
    @Test fun s1Running() = shot("inventory_s1_running") { InventoryStartContent(InventorySamples.startRunning, StartActions()) }
    @Test fun s1NoAccess() = shot("inventory_s1_noaccess") { InventoryStartContent(InventorySamples.startNoAccess, StartActions()) }

    @Test fun s2Run() = shot("inventory_s2_run") { InventoryRunContent(InventorySamples.run, RunActions()) }
    @Test fun s2Ready() = shot("inventory_s2_ready") { InventoryRunContent(InventorySamples.runReady, RunActions()) }

    @Test fun s3cMusic() = shot("inventory_s3c_music") { MusicWaitContent(InventorySamples.music, MusicActions()) }
    @Test fun s3cMusicNoAccess() = shot("inventory_s3c_music_noaccess") { MusicWaitContent(InventorySamples.musicNoAccess, MusicActions()) }

    @Test fun s4Folders() = shot("inventory_s4_folders") { InventoryFoldersContent(InventorySamples.folders, FoldersActions()) }
    @Test fun s4Docs() = shot("inventory_s4_docs") { InventoryFoldersContent(InventorySamples.docFolders, FoldersActions()) }

    @Test fun s5Trash() = shot("inventory_s5_trash") { InventoryFolderContent(InventorySamples.trash, FolderActions()) }
    @Test fun s5TrashSelected() = shot("inventory_s5_trash_selected") { InventoryFolderContent(InventorySamples.trashSelected, FolderActions()) }
    /** Redenumirea pe loc: pe un dosar obișnuit („De aruncat” n-are creion). */
    @Test fun s5FolderEditing() = shot("inventory_s5_folder_editing") { InventoryFolderContent(InventorySamples.folderEditing, FolderActions()) }
    @Test fun s5Folder() = shot("inventory_s5_folder") { InventoryFolderContent(InventorySamples.folder, FolderActions()) }

    @Test fun s6Apply() = shot("inventory_s6_apply") { InventoryApplyContent(InventorySamples.apply) }
    @Test fun s6Done() = shot("inventory_s6_done") { InventoryDoneContent(InventorySamples.done, DoneActions()) }

    @Test fun pill() = shot("inventory_pill", fullScreen = false) {
        Box(Modifier.padding(16.dp)) { InvProgressPill(InventorySamples.pill, onClick = {}) }
    }
    @Test fun pillReady() = shot("inventory_pill_ready", fullScreen = false) {
        Box(Modifier.padding(16.dp)) { InvProgressPill(InventorySamples.pillReady, onClick = {}) }
    }
}
