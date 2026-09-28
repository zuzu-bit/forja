package com.forja.app.feature.shorts

import android.content.Context
import android.graphics.Matrix
import android.net.Uri
import android.view.TextureView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.File

/**
 * Redarea feed-ului: UN singur ExoPlayer (creat la intrare / ON_RESUME, eliberat la ieșire / ON_PAUSE),
 * un cache comun pe disc (clipurile se reiau din cache la buclă și la întoarcere) și preîncărcarea
 * clipului următor în același cache, ca glisarea să pornească instant.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal object ShortsPlayback {
    private const val CACHE_BYTES = 160L * 1024 * 1024

    @Volatile private var cache: SimpleCache? = null

    /** Un singur SimpleCache pe dosar, pe toată viața procesului (regula Media3). Apel de pe un fir de fundal. */
    private fun cache(context: Context): SimpleCache =
        cache ?: synchronized(this) {
            cache ?: SimpleCache(
                File(context.applicationContext.cacheDir, "shorts_video"),
                LeastRecentlyUsedCacheEvictor(CACHE_BYTES),
                StandaloneDatabaseProvider(context.applicationContext)
            ).also { cache = it }
        }

    /** Sursa de date comună playerului și preîncărcării (atinge discul: apel de pe Dispatchers.IO). */
    fun dataSourceFactory(context: Context): CacheDataSource.Factory {
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("FORJA-Shorts")
        return CacheDataSource.Factory()
            .setCache(cache(context))
            .setUpstreamDataSourceFactory(http)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    fun newPlayer(context: Context, dataSource: CacheDataSource.Factory): ExoPlayer =
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            // Clipuri de ≤ 12 s: pornire rapidă, tot clipul încape în buffer.
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(2_500, 15_000, 700, 1_200).build())
            // Fără focus audio: muzica ta cântă mai departe, dedesubt.
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                false
            )
            .setHandleAudioBecomingNoisy(false)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ONE
                volume = 0f
            }

    /** Aduce clipul întreg în cache. Anularea corutinei oprește descărcarea la următorul bloc citit. */
    suspend fun prefetch(dataSource: CacheDataSource.Factory, url: String) {
        coroutineScope {
            val writer = CacheWriter(dataSource.createDataSource(), DataSpec(Uri.parse(url)), null, null)
            val work = async(Dispatchers.IO) { runCatching { writer.cache() } }
            try {
                work.await()
            } finally {
                writer.cancel()
            }
        }
    }
}

/** Starea TextureView-ului unei pagini: playerul atașat și dimensiunea cadrului, pentru decupare. */
internal class TextureHolder {
    var player: Player? = null
    var videoW = 0
    var videoH = 0
    var pixelRatio = 1f
}

/** Center-crop pe TextureView (ca în Video.kt): clipul umple ecranul păstrând proporțiile. */
internal fun TextureView.cropToFill(holder: TextureHolder) {
    val vw = width
    val vh = height
    if (holder.videoW <= 0 || holder.videoH <= 0 || vw == 0 || vh == 0) return
    val w = holder.videoW * (if (holder.pixelRatio > 0f) holder.pixelRatio else 1f)
    val h = holder.videoH.toFloat()
    val scale = maxOf(vw / w, vh / h)
    val m = Matrix()
    m.setScale(scale * w / vw, scale * h / vh, vw / 2f, vh / 2f)
    setTransform(m)
}
