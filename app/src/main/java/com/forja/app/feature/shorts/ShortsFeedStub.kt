// TEMP STUB (package F) — deleted at merge; real API = package C
package com.forja.app.feature.shorts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp

@Composable
fun ShortsFeed(modifier: Modifier = Modifier, topOverlay: @Composable () -> Unit = {}, onClose: () -> Unit) {
    Box(modifier.fillMaxSize().background(Color(0xFF000000))) {
        Box(Modifier.statusBarsPadding().padding(start = 16.dp, top = 8.dp)) { topOverlay() }
    }
}

@Composable
fun ShortsFeed(
    modifier: Modifier = Modifier,
    topOverlay: @Composable () -> Unit = {},
    onMusic: () -> Unit,
    musicCover: ImageBitmap? = null,
    onClose: () -> Unit
) {
    ShortsFeed(modifier, topOverlay, onClose)
}
