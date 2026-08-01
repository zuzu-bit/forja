package com.forja.app.core.designsystem.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.forja.app.core.designsystem.LocalReducedMotion
import com.forja.app.core.designsystem.Surface1

/**
 * Suprafață video FORJA: loop mut, poster până la primul cadru,
 * pauză automată când ecranul nu e vizibil, fallback poster la reduced-motion.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoSurface(
    url: String,
    posterUrl: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val reduced = LocalReducedMotion.current
    // Originalele licențiate (fără watermark) înlocuiesc automat preview-urile.
    val manifest by com.forja.app.core.media.Media.manifest.collectAsState()
    val url = remember(url, manifest) { com.forja.app.core.media.Media.resolve(url) }
    val posterUrl = remember(posterUrl, manifest) { posterUrl?.let { com.forja.app.core.media.Media.resolve(it) } }
    val playVideo = enabled && !reduced && url.isNotBlank()

    Box(modifier.background(Surface1)) {
        if (posterUrl != null) {
            AsyncImage(
                model = posterUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        if (playVideo) {
            val context = LocalContext.current
            var firstFrame by remember(url) { mutableStateOf(false) }
            val alpha by animateFloatAsState(if (firstFrame) 1f else 0f, tween(400), label = "vidfade")
            val player = remember(url) {
                ExoPlayer.Builder(context).build().apply {
                    setMediaItem(MediaItem.fromUri(url))
                    repeatMode = Player.REPEAT_MODE_ONE
                    volume = 0f
                    playWhenReady = true
                    prepare()
                }
            }
            DisposableEffect(url) {
                val listener = object : Player.Listener {
                    override fun onRenderedFirstFrame() { firstFrame = true }
                }
                player.addListener(listener)
                onDispose {
                    player.removeListener(listener)
                    player.release()
                }
            }
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner, url) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_PAUSE -> player.pause()
                        Lifecycle.Event.ON_RESUME -> player.play()
                        else -> {}
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    }
                },
                update = { it.player = player },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { this.alpha = alpha }
            )
        }
    }
}

// Scrim erou/jos: transparent 30% → .55 la 64% → .94 la 100%
@Composable
fun BoxScopeBottomScrim(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0.30f to Color.Transparent,
                    0.64f to Color(0x8C0A0A0B),
                    1.0f to Color(0xF00A0A0B)
                )
            )
    )
}

// Scrim sus (status): .5 → transparent la ~30%
@Composable
fun TopScrim(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0.0f to Color(0x800A0A0B),
                    0.30f to Color.Transparent,
                    1.0f to Color.Transparent
                )
            )
    )
}
