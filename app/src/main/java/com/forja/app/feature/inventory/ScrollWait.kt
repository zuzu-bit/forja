package com.forja.app.feature.inventory

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.forja.app.core.inventory.InvStage
import com.forja.app.core.inventory.Inventory
import com.forja.app.core.music.Music
import com.forja.app.feature.shorts.ShortsFeed

/**
 * S3a — Scroll: feed-ul vertical FORJA (shorts + „Recruții”), cu pastila de progres sus-stânga (ieșirea vizibilă;
 * „Înapoi” închide feed-ul) și discul „muzica ta” în șina din dreapta, care deschide S3c.
 */
@Composable
fun InventoryScrollScreen(onOpenInventory: (InvPage) -> Unit, onMusic: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val progress by Inventory.progress.collectAsState()
    val track by Music.nowPlaying.collectAsState()
    LaunchedEffect(Unit) { if (Music.hasAccess(context)) Music.ensureStarted(context) }
    val art = track?.art
    val cover = remember(art) { art?.asImageBitmap() }
    val pill = pillStateOf(progress)
    ShortsFeed(
        modifier = Modifier.fillMaxSize(),
        topOverlay = {
            if (pill != null) {
                InvProgressPill(
                    pill,
                    onClick = { onOpenInventory(if (progress?.stage == InvStage.Ready) InvPage.Folders else InvPage.Run) },
                    overVideo = true
                )
            } else {
                InvIconButton(InvIcons.Close, "Închide", onClose, iconSize = 18.dp)
            }
        },
        onMusic = onMusic,
        musicCover = cover,
        onClose = onClose
    )
}
