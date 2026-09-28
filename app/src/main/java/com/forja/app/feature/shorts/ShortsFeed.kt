package com.forja.app.feature.shorts

import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import com.forja.app.core.designsystem.Body
import com.forja.app.core.designsystem.BodyStrong
import com.forja.app.core.designsystem.EmberHot
import com.forja.app.core.designsystem.EmberWarm
import com.forja.app.core.designsystem.LocalReducedMotion
import com.forja.app.core.designsystem.TextPrimary
import com.forja.app.core.designsystem.TextSecondary
import com.forja.app.core.designsystem.TitleModule
import com.forja.app.core.designsystem.components.Mascot
import com.forja.app.core.designsystem.components.MascotHat
import com.forja.app.core.designsystem.components.MascotState
import com.forja.app.core.designsystem.components.MonoButton
import com.forja.app.core.designsystem.components.PrimaryButton
import com.forja.app.core.designsystem.components.StampLabel
import com.forja.app.core.designsystem.monoLabel
import com.forja.app.core.media.Short
import com.forja.app.core.media.ShortKind
import com.forja.app.core.media.Shorts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

// Culorile prototipului 4.3 (Scroll.dc.html).
private val Amber = Color(0xFFF3B952)
private val FrontOlive = Color(0xFF8FA876)
private val RailFill = Color(0x6B0A0A0B)      // rgba(10,10,11,.42)
private val StampFill = Color(0x590A0A0B)     // rgba(10,10,11,.35)
private val BarTrack = Color(0x24FFFFFF)      // rgba(255,255,255,.14)
private val DiscBody = Color(0xFF141416)
private val DiscGroove = Color(0xFF2A2B30)

/**
 * FORJA Shorts (S3a): feed vertical pe tot ecranul, „ca TikTok”: shorts FRONT (stil de viață milităros) și
 * RECRUȚI (pisici), în ordinea 2 : 1, amestecate, fără repetări până la capătul listei.
 *
 * Un singur ExoPlayer, reatașat paginii așezate (TextureView, crop-to-fill), clipul următor preîncărcat în
 * cache, buclă, mut implicit (muzica ta cântă dedesubt). Atingere = pauză / pornire, dublă atingere = inimă.
 * Playerul se eliberează la ieșire și la ON_PAUSE. Cu mișcare redusă: clipurile nu pornesc singure, fără
 * animații de scalare. Ieșirea: Înapoi sau pastila din [topOverlay] (sus-stânga) → [onClose].
 */
@Composable
fun ShortsFeed(modifier: Modifier = Modifier, topOverlay: @Composable () -> Unit = {}, onClose: () -> Unit) {
    ShortsFeedContent(modifier, topOverlay, onMusic = null, musicCover = null, onClose = onClose)
}

/**
 * Aceeași, cu discul „muzica ta” în șina din dreapta (prototipul 4.3): [onMusic] deschide S3c,
 * [musicCover] = coperta piesei curente (altfel discul FORJA). Discul se rotește cât clipul rulează.
 */
@Composable
fun ShortsFeed(
    modifier: Modifier = Modifier,
    topOverlay: @Composable () -> Unit = {},
    onMusic: () -> Unit,
    musicCover: ImageBitmap? = null,
    onClose: () -> Unit
) {
    ShortsFeedContent(modifier, topOverlay, onMusic, musicCover, onClose)
}

private sealed interface FeedState {
    data object Loading : FeedState
    data object Empty : FeedState
    data object Failed : FeedState
    data class Ready(val items: List<Short>) : FeedState
}

@Composable
private fun ShortsFeedContent(
    modifier: Modifier,
    topOverlay: @Composable () -> Unit,
    onMusic: (() -> Unit)?,
    musicCover: ImageBitmap?,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val reduced = LocalReducedMotion.current
    val close by rememberUpdatedState(onClose)
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<FeedState>(FeedState.Loading) }
    LaunchedEffect(attempt) {
        state = FeedState.Loading
        state = try {
            val items = Shorts.load(context, force = attempt > 0)
            if (items.isEmpty()) FeedState.Empty else FeedState.Ready(items)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            FeedState.Failed
        }
    }
    BackHandler { close() }
    // Cât stai în feed, ecranul nu se stinge.
    val view = LocalView.current
    DisposableEffect(view) {
        val before = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = before }
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        when (val s = state) {
            is FeedState.Ready -> Feed(s.items, reduced, onMusic, musicCover)
            FeedState.Loading -> {
                // Din cache vine aproape instant: mascota apare doar dacă durează.
                var visible by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { delay(300); visible = true }
                if (visible) {
                    FeedMessage(MascotState.Thinking, text = null, label = "SE ÎNCARCĂ")
                }
            }
            FeedState.Empty -> FeedMessage(
                MascotState.Idle, text = "Încă nu avem clipuri.",
                secondary = "ÎNAPOI" to { close() }
            )
            FeedState.Failed -> FeedMessage(
                MascotState.Sorry, text = "Nu a mers. Încearcă din nou.",
                primary = "Reîncearcă" to { attempt += 1 },
                secondary = "ÎNAPOI" to { close() }
            )
        }
        Box(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 16.dp, top = 8.dp)) {
            topOverlay()
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun Feed(items: List<Short>, reduced: Boolean, onMusic: (() -> Unit)?, musicCover: ImageBitmap?) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val random = remember { Random(System.nanoTime()) }

    // Ordinea (2 FRONT : 1 RECRUȚI), păstrată la recreare; se prelungește cu cicluri noi spre final.
    val byId = remember(items) { items.associateBy { it.id } }
    val savedOrder = rememberSaveable { mutableStateOf(ArrayList<String>()) }
    val order = remember(items) {
        val restored = savedOrder.value.mapNotNull { byId[it] }
        val initial = if (restored.isNotEmpty() && restored.size == savedOrder.value.size) restored else Shorts.order(items, random)
        mutableStateListOf<Short>().apply { addAll(initial) }
    }
    val pager = rememberPagerState(pageCount = { order.size })
    val settled = pager.settledPage

    val liked = remember { mutableStateMapOf<String, Boolean>().apply { Shorts.liked(appContext).forEach { put(it, true) } } }
    fun setLiked(item: Short, on: Boolean) {
        if (on) liked[item.id] = true else liked.remove(item.id)
        Shorts.setLiked(appContext, item.id, on)
    }

    // ── playerul: creat la ON_RESUME, eliberat la ON_PAUSE și la ieșire ──
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_PAUSE -> resumed = false
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var active by remember { mutableIntStateOf(-1) }
    var renderedId by remember { mutableStateOf<String?>(null) }
    var failedId by remember { mutableStateOf<String?>(null) }
    var hasAudio by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var videoSize by remember { mutableStateOf(VideoSize.UNKNOWN) }
    var userPaused by remember { mutableStateOf(reduced) }
    var muted by rememberSaveable { mutableStateOf(true) }
    val progress = remember { mutableFloatStateOf(0f) }

    var dataSource by remember { mutableStateOf<CacheDataSource.Factory?>(null) }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var resumeAt by remember { mutableStateOf<Pair<String, Long>?>(null) }
    LaunchedEffect(resumed) {
        if (!resumed) return@LaunchedEffect
        val ds = dataSource ?: withContext(Dispatchers.IO) { ShortsPlayback.dataSourceFactory(appContext) }.also { dataSource = it }
        val p = ShortsPlayback.newPlayer(context, ds)
        player = p
        try {
            awaitCancellation()
        } finally {
            resumeAt = p.currentMediaItem?.mediaId?.let { it to p.currentPosition }
            player = null
            isPlaying = false
            renderedId = null      // la revenire, posterul rămâne până la primul cadru al noului player
            p.release()
        }
    }

    DisposableEffect(player) {
        val p = player ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { renderedId = p.currentMediaItem?.mediaId }
            override fun onTracksChanged(tracks: Tracks) { hasAudio = tracks.containsType(C.TRACK_TYPE_AUDIO) }
            override fun onVideoSizeChanged(size: VideoSize) { videoSize = size }
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlayerError(error: PlaybackException) { failedId = p.currentMediaItem?.mediaId }
        }
        p.addListener(listener)
        onDispose { p.removeListener(listener) }
    }
    // Fiecare clip nou pornește singur (cu mișcare redusă: doar la atingere).
    LaunchedEffect(settled) { userPaused = reduced }
    // Playerul trece pe pagina așezată; TextureView-ul ei se atașează abia după schimbarea clipului.
    LaunchedEffect(player, settled) {
        val p = player ?: return@LaunchedEffect
        val item = order.getOrNull(settled) ?: return@LaunchedEffect
        if (p.currentMediaItem?.mediaId != item.id) {
            val start = resumeAt?.takeIf { it.first == item.id }?.second ?: 0L
            renderedId = null
            failedId = null
            hasAudio = false
            videoSize = VideoSize.UNKNOWN
            progress.floatValue = 0f
            p.setMediaItem(MediaItem.Builder().setUri(item.url).setMediaId(item.id).build(), start)
            p.prepare()
        }
        resumeAt = null
        active = settled
    }
    LaunchedEffect(player, userPaused) { player?.playWhenReady = !userPaused }
    LaunchedEffect(player, muted) { player?.volume = if (muted) 0f else 1f }
    // Bara de redare: citită doar la desenare, fără recompoziții.
    LaunchedEffect(player, isPlaying) {
        val p = player ?: return@LaunchedEffect
        while (isPlaying) {
            withFrameMillis { }
            val d = p.duration
            if (d > 0 && d != C.TIME_UNSET) progress.floatValue = (p.currentPosition.toFloat() / d).coerceIn(0f, 1f)
        }
    }
    // Preîncărcare: după primul cadru al clipului curent (sau 3 s), următorul intră în cache.
    LaunchedEffect(settled, dataSource) {
        val ds = dataSource ?: return@LaunchedEffect
        val current = order.getOrNull(settled) ?: return@LaunchedEffect
        val next = order.getOrNull(settled + 1) ?: return@LaunchedEffect
        withTimeoutOrNull(3_000) { snapshotFlow { renderedId }.first { it == current.id } }
        ShortsPlayback.prefetch(ds, next.url)
    }
    // Aproape de capăt: încă un ciclu amestecat (fără același clip de două ori la rând).
    LaunchedEffect(settled) {
        if (settled >= order.size - 3) order.addAll(Shorts.order(items, random, avoidFirst = order.lastOrNull()?.id))
        savedOrder.value = ArrayList(order.map { it.id })
    }

    VerticalPager(
        state = pager,
        modifier = Modifier.fillMaxSize(),
        beyondViewportPageCount = 1
    ) { page ->
        val item = order.getOrNull(page) ?: return@VerticalPager
        val isActive = page == active
        ShortPage(
            item = item,
            active = isActive,
            player = if (isActive) player else null,
            rendered = isActive && renderedId == item.id,
            failed = isActive && failedId == item.id,
            paused = isActive && userPaused,
            playing = isActive && isPlaying,
            videoSize = if (isActive) videoSize else VideoSize.UNKNOWN,
            liked = liked[item.id] == true,
            muted = muted,
            hasAudio = isActive && hasAudio,
            reduced = reduced,
            progress = progress,
            onTap = {
                if (isActive && failedId == item.id) {
                    failedId = null
                    player?.prepare()
                } else if (isActive) {
                    userPaused = !userPaused
                }
            },
            onDoubleTap = { setLiked(item, true) },
            onLike = { setLiked(item, it) },
            onMute = { muted = !muted },
            onMusic = onMusic,
            musicCover = musicCover
        )
    }
}

private data class Burst(val id: Long, val at: Offset, val tilt: Float)

@Composable
private fun ShortPage(
    item: Short,
    active: Boolean,
    player: Player?,
    rendered: Boolean,
    failed: Boolean,
    paused: Boolean,
    playing: Boolean,
    videoSize: VideoSize,
    liked: Boolean,
    muted: Boolean,
    hasAudio: Boolean,
    reduced: Boolean,
    progress: FloatState,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onLike: (Boolean) -> Unit,
    onMute: () -> Unit,
    onMusic: (() -> Unit)?,
    musicCover: ImageBitmap?
) {
    val tap by rememberUpdatedState(onTap)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    val bursts = remember { mutableStateListOf<Burst>() }
    var burstSeq by remember { mutableLongStateOf(0L) }
    val tapLabel = if (failed) "Reîncearcă" else if (paused) "Pornește" else "Pauză"
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(item.id) {
                detectTapGestures(
                    onTap = { tap() },
                    onDoubleTap = { at ->
                        burstSeq++
                        bursts += Burst(burstSeq, at, Random.nextInt(-14, 15).toFloat())
                        doubleTap()
                    }
                )
            }
            .semantics {
                contentDescription = item.caption
                stateDescription = if (paused) "pauză" else "rulează"
                onClick(label = tapLabel) { tap(); true }
            }
    ) {
        AsyncImage(
            model = item.poster,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (player != null) VideoLayer(player, item, rendered, videoSize, reduced)
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0x80000000),
                        0.16f to Color.Transparent,
                        (if (item.kind == ShortKind.Recruti) 0.56f else 0.52f) to Color.Transparent,
                        1f to Color(0xD6000000)
                    )
                )
        )
        when {
            failed -> FailedBadge(Modifier.align(Alignment.Center))
            else -> AnimatedVisibility(
                visible = paused,
                enter = fadeIn(tween(if (reduced) 0 else 160)),
                exit = fadeOut(tween(if (reduced) 0 else 160)),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Box(Modifier.size(76.dp).clip(CircleShape).background(RailFill), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(44.dp))
                }
            }
        }
        Overlays(item, active, liked, muted, hasAudio, playing, reduced, onLike, onMute, onMusic, musicCover)
        bursts.forEach { b -> key(b.id) { HeartBurst(b, reduced) { bursts.remove(b) } } }
        if (active) PlaybackBar(progress)
    }
}

@Composable
private fun VideoLayer(player: Player, item: Short, rendered: Boolean, videoSize: VideoSize, reduced: Boolean) {
    // Primul cadru al clipului ÎL înlocuiește pe poster (fără fulger negru între ele).
    val alpha by animateFloatAsState(if (rendered) 1f else 0f, if (reduced) snap() else tween(220), label = "shortVideo")
    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                val holder = TextureHolder()
                tag = holder
                addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> (v as TextureView).cropToFill(holder) }
            }
        },
        update = { tv ->
            val holder = tv.tag as TextureHolder
            if (holder.player !== player) {
                holder.player?.clearVideoTextureView(tv)
                holder.player = player
                player.setVideoTextureView(tv)
            }
            val known = videoSize.width > 0 && videoSize.height > 0
            holder.videoW = if (known) videoSize.width else item.w
            holder.videoH = if (known) videoSize.height else item.h
            holder.pixelRatio = if (known) videoSize.pixelWidthHeightRatio else 1f
            tv.cropToFill(holder)
        },
        onRelease = { tv ->
            (tv.tag as? TextureHolder)?.let { holder ->
                holder.player?.clearVideoTextureView(tv)
                holder.player = null
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha }
    )
}

/** Ștampila, legenda, creditul (jos-stânga) și șina din dreapta, așezate ca în prototip. */
@Composable
private fun Overlays(
    item: Short,
    active: Boolean,
    liked: Boolean,
    muted: Boolean,
    hasAudio: Boolean,
    playing: Boolean,
    reduced: Boolean,
    onLike: (Boolean) -> Unit,
    onMute: () -> Unit,
    onMusic: (() -> Unit)?,
    musicCover: ImageBitmap?
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val capTop = item.capTop
        // Legenda arsă în video: o lăsăm în pace, ștampila și șina urcă deasupra ei (maparea crop-to-fill).
        val burnedTop: Dp? = if (item.burned && capTop != null) {
            val scale = maxOf(maxWidth / item.w, maxHeight / item.h)
            maxHeight / 2 + scale * (capTop - item.h / 2f)
        } else null
        val burnedLeft: Dp = if (burnedTop != null) {
            val scale = maxOf(maxWidth / item.w, maxHeight / item.h)
            maxOf(16.dp, maxWidth / 2 + scale * ((item.capLeft ?: 96) - item.w / 2f))
        } else 16.dp
        val hit = active && !reduced

        if (burnedTop != null) {
            KindStamp(
                item.kind, hit,
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = burnedLeft, bottom = (maxHeight - burnedTop + 10.dp).coerceAtLeast(0.dp))
            )
            Credit(item, Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = burnedLeft, bottom = 30.dp))
        } else {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 84.dp, bottom = 30.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                KindStamp(item.kind, hit)
                if (item.caption.isNotBlank()) Caption(item.caption)
                Credit(item)
            }
        }

        val railBottom = if (burnedTop != null) maxOf(150.dp, maxHeight - burnedTop + 16.dp) else 150.dp
        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 12.dp, bottom = railBottom),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            RailHeart(liked, reduced) { onLike(!liked) }
            SoundButton(muted = muted, hasAudio = hasAudio, onClick = onMute)
            if (onMusic != null) MusicDisc(musicCover, spinning = playing && !reduced, onClick = onMusic)
        }
    }
}

/** „FRONT” olive / „RECRUȚI” amber, pe o plăcuță întunecată, rotită -4°; „lovită” când pagina se așază. */
@Composable
private fun KindStamp(kind: ShortKind, hit: Boolean, modifier: Modifier = Modifier) {
    val color = if (kind == ShortKind.Recruti) Amber else FrontOlive
    val scale = remember { Animatable(1f) }
    LaunchedEffect(hit) {
        if (hit) {
            scale.snapTo(1.35f)
            scale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 900f))
        }
    }
    Box(
        modifier
            .graphicsLayer {
                rotationZ = -4f
                scaleX = scale.value
                scaleY = scale.value
            }
            .background(StampFill)
    ) {
        StampLabel(
            text = if (kind == ShortKind.Recruti) "RECRUȚI" else "FRONT",
            color = color,
            rotationDeg = 0f,
            fontSize = 11,
            tracking = 0.22f,
            appear = false
        )
    }
}

@Composable
private fun Caption(text: String) {
    val density = LocalDensity.current
    val style = remember(density) {
        TitleModule.copy(
            fontSize = 30.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.ExtraBold,      // barlowc_700 = Barlow Condensed Bold (prototipul: 700)
            color = TextPrimary,
            shadow = Shadow(
                color = Color.Black.copy(alpha = 0.6f),
                offset = Offset(0f, with(density) { 2.dp.toPx() }),
                blurRadius = with(density) { 14.dp.toPx() }
            ),
            lineBreak = LineBreak.Heading
        )
    }
    Text(text, style = style, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** „VIDEO · NUME · PEXELS”; atingerea deschide pagina clipului (cerința Pexels: autorul creditat, cu link). */
@Composable
private fun Credit(item: Short, modifier: Modifier = Modifier) {
    val credit = item.credit ?: return
    val uri = LocalUriHandler.current
    val source = if (credit.url?.contains("pexels.com") == true) " · PEXELS" else ""
    val url = credit.url
    Text(
        "VIDEO · ${credit.name.uppercase()}$source",
        style = monoLabel(10, 0.14f).copy(color = TextPrimary.copy(alpha = 0.6f)),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .then(
                if (url != null) {
                    Modifier.clickable(remember { MutableInteractionSource() }, indication = null) { runCatching { uri.openUri(url) } }
                } else {
                    Modifier
                }
            )
            .padding(vertical = 6.dp)
    )
}

@Composable
private fun RailButton(
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(RailFill)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
        content = content
    )
}

/** Inima din șină: la „îmi place” pulsează (1 → 1,28 → 1) și aruncă scântei amber (explozia din prototip). */
@Composable
private fun RailHeart(liked: Boolean, reduced: Boolean, onToggle: () -> Unit) {
    val pop = remember { Animatable(1f) }
    val sparks = remember { Animatable(1f) }
    var before by remember { mutableStateOf(liked) }
    LaunchedEffect(liked) {
        if (liked && !before && !reduced) {
            launch {
                pop.animateTo(1.28f, tween(140, easing = FastOutSlowInEasing))
                pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 600f))
            }
            sparks.snapTo(0f)
            sparks.animateTo(1f, tween(480, easing = FastOutSlowInEasing))
        }
        before = liked
    }
    Box(
        Modifier
            .size(52.dp)
            .drawBehind {
                val t = sparks.value
                if (t < 1f) {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val dist = (22f + 22f * t) * density
                    val r = (3.2f - 2.4f * t) * density
                    for (i in 0 until 8) {
                        val a = (i * 45f + 22.5f) * PI.toFloat() / 180f
                        drawCircle(
                            color = if (i % 2 == 0) Amber else EmberHot,
                            radius = r,
                            center = Offset(c.x + cos(a) * dist, c.y + sin(a) * dist),
                            alpha = (1f - t).coerceIn(0f, 1f)
                        )
                    }
                }
            }
    ) {
        RailButton(description = if (liked) "Nu-mi mai place" else "Îmi place", onClick = onToggle) {
            Icon(
                if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = null,
                tint = if (liked) Amber else TextPrimary,
                modifier = Modifier
                    .size(28.dp)
                    .graphicsLayer {
                        scaleX = pop.value
                        scaleY = pop.value
                    }
            )
        }
    }
}

/** Sunetul: mut implicit. Clipurile fără pistă audio (CI le scoate sunetul) îl arată stins, fără efect. */
@Composable
private fun SoundButton(muted: Boolean, hasAudio: Boolean, onClick: () -> Unit) {
    val description = when {
        !hasAudio -> "Clip fără sunet"
        muted -> "Pornește sunetul"
        else -> "Oprește sunetul"
    }
    Box(Modifier.graphicsLayer { alpha = if (hasAudio) 1f else 0.45f }) {
        RailButton(description = description, enabled = hasAudio, onClick = onClick) {
            Icon(
                if (muted || !hasAudio) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                contentDescription = null,
                tint = TextPrimary,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

/** Discul „muzica ta”: vinil 52 dp, se rotește (6 s pe tură) doar cât clipul rulează. */
@Composable
private fun MusicDisc(cover: ImageBitmap?, spinning: Boolean, onClick: () -> Unit) {
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(spinning) {
        if (!spinning) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameMillis { now ->
                if (last != 0L) angle = (angle + (now - last) * 0.06f) % 360f
                last = now
            }
        }
    }
    val clip = remember { Path() }
    Canvas(
        Modifier
            .size(52.dp)
            .graphicsLayer { rotationZ = angle }
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = "Muzica ta", onClick = onClick)
            .semantics { contentDescription = "Muzica ta" }
    ) {
        val u = size.minDimension / 52f
        val c = Offset(size.width / 2f, size.height / 2f)
        drawCircle(DiscBody, radius = 25f * u, center = c)
        drawCircle(Color.White.copy(alpha = 0.2f), radius = 25f * u, center = c, style = Stroke(2f * u))
        drawCircle(DiscGroove, radius = 19f * u, center = c, style = Stroke(1f * u))
        drawCircle(DiscGroove, radius = 15f * u, center = c, style = Stroke(1f * u))
        if (cover != null) {
            val r = 13f * u
            clip.reset()
            clip.addOval(Rect(c.x - r, c.y - r, c.x + r, c.y + r))
            clipPath(clip) {
                val side = minOf(cover.width, cover.height)
                drawImage(
                    cover,
                    srcOffset = IntOffset((cover.width - side) / 2, (cover.height - side) / 2),
                    srcSize = IntSize(side, side),
                    dstOffset = IntOffset((c.x - r).roundToInt(), (c.y - r).roundToInt()),
                    dstSize = IntSize((2 * r).roundToInt(), (2 * r).roundToInt())
                )
            }
        } else {
            val r = 11f * u
            drawCircle(EmberWarm, radius = r, center = c)
            drawArc(Amber, 180f, 180f, useCenter = true, topLeft = Offset(c.x - r, c.y - r), size = Size(2 * r, 2 * r))
        }
        drawCircle(Color(0xFF0A0A0B), radius = 2.6f * u, center = c)
    }
}

/** Inima mare de la dubla atingere: apare cu arc (scale), urcă puțin și se stinge. */
@Composable
private fun HeartBurst(burst: Burst, reduced: Boolean, onDone: () -> Unit) {
    val scale = remember { Animatable(if (reduced) 1f else 0.2f) }
    val alpha = remember { Animatable(if (reduced) 0f else 1f) }
    val rise = remember { Animatable(0f) }
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(burst.id) {
        if (reduced) {
            alpha.animateTo(1f, tween(120))
            delay(380)
            alpha.animateTo(0f, tween(200))
        } else {
            scale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 700f, visibilityThreshold = 0.01f))
            launch { scale.animateTo(1.3f, tween(380, easing = FastOutSlowInEasing)) }
            launch { rise.animateTo(-72f, tween(380, easing = FastOutSlowInEasing)) }
            alpha.animateTo(0f, tween(360, delayMillis = 40))
        }
        done()
    }
    val half = with(LocalDensity.current) { 56.dp.roundToPx() }
    Icon(
        Icons.Rounded.Favorite,
        contentDescription = null,
        tint = Amber,
        modifier = Modifier
            .offset { IntOffset(burst.at.x.roundToInt() - half, (burst.at.y + rise.value.dp.toPx()).roundToInt() - half) }
            .size(112.dp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                rotationZ = burst.tilt
                this.alpha = alpha.value
            }
    )
}

@Composable
private fun BoxScope.PlaybackBar(progress: FloatState) {
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(3.dp)
            .background(BarTrack)
            .drawBehind { drawRect(Amber, size = Size(size.width * progress.floatValue, size.height)) }
    )
}

@Composable
private fun FailedBadge(modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Mascot(state = MascotState.Sorry, hat = MascotHat.Helmet, size = 88.dp)
        Text("Clip indisponibil.", style = BodyStrong.copy(fontSize = 16.sp))
        Icon(Icons.Rounded.Refresh, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(22.dp))
    }
}

/** Stările fără clipuri: mascota vorbește scurt; o singură acțiune principală. */
@Composable
private fun BoxScope.FeedMessage(
    mascot: MascotState,
    text: String?,
    label: String? = null,
    primary: Pair<String, () -> Unit>? = null,
    secondary: Pair<String, () -> Unit>? = null
) {
    Column(
        Modifier
            .align(Alignment.Center)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Mascot(state = mascot, hat = MascotHat.Helmet, size = 132.dp)
        if (text != null) {
            Spacer(Modifier.height(18.dp))
            Text(text, style = Body.copy(fontSize = 17.sp, lineHeight = 23.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold), textAlign = TextAlign.Center)
        }
        if (label != null) {
            Spacer(Modifier.height(14.dp))
            Text(label, style = monoLabel(10, 0.18f).copy(color = TextSecondary))
        }
        if (primary != null) {
            Spacer(Modifier.height(22.dp))
            PrimaryButton(primary.first, onClick = primary.second, small = true)
        }
        if (secondary != null) {
            Spacer(Modifier.height(if (primary != null) 10.dp else 22.dp))
            MonoButton(secondary.first, onClick = secondary.second)
        }
    }
}
