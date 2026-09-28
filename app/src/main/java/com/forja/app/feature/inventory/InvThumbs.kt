package com.forja.app.feature.inventory

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.ImageRequest
import coil.request.Options
import com.forja.app.core.designsystem.Error
import com.forja.app.core.designsystem.Positive
import com.forja.app.core.designsystem.Surface2
import com.forja.app.core.designsystem.EmberWarm
import com.forja.app.core.designsystem.TextDim
import com.forja.app.core.designsystem.TextDim2
import com.forja.app.core.designsystem.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/*
 * Miniaturi REALE: pozele și videoclipurile din MediaStore (ContentResolver.loadThumbnail — memoria cache a
 * sistemului, rapidă și pentru mii de elemente), prima pagină a unui PDF (PdfRenderer), altfel foaia cu tipul.
 * Prin Coil (cache în memorie cu cheie proprie), cu o fabrică de „fetcher” pe cerere — ImageLoader-ul aplicației
 * rămâne neatins.
 */

/** Ce cerem: elementul, tipul lui și latura în pixeli. */
internal data class ThumbKey(val uri: Uri, val mime: String, val px: Int)

private class ThumbFetcher(private val key: ThumbKey, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val ctx = options.context
        val bmp = loadThumbnail(ctx, key) ?: throw IOException("fără miniatură")
        DrawableResult(BitmapDrawable(ctx.resources, bmp), isSampled = true, dataSource = DataSource.DISK)
    }
}

private object ThumbFactory : Fetcher.Factory<ThumbKey> {
    override fun create(data: ThumbKey, options: Options, imageLoader: ImageLoader): Fetcher = ThumbFetcher(data, options)
}

internal fun isPdf(mime: String, uri: Uri): Boolean =
    mime == "application/pdf" || (uri.lastPathSegment?.lowercase()?.endsWith(".pdf") == true)

internal fun isVisual(mime: String): Boolean = mime.startsWith("image/") || mime.startsWith("video/")

/** Miniatura, pe firul apelantului (IO). Null dacă tipul nu are previzualizare. */
internal fun loadThumbnail(ctx: Context, key: ThumbKey): Bitmap? {
    val cr = ctx.contentResolver
    val px = key.px.coerceIn(64, 2048)
    return try {
        when {
            isPdf(key.mime, key.uri) -> pdfFirstPage(cr, key.uri, px)
            Build.VERSION.SDK_INT >= 29 -> cr.loadThumbnail(key.uri, android.util.Size(px, px), null)
            key.mime.startsWith("video/") -> legacyVideoThumb(cr, key.uri)
            key.mime.startsWith("image/") || key.mime.isBlank() -> decodeSampled(cr, key.uri, px)
            else -> null
        }
    } catch (_: Exception) {
        if (key.mime.startsWith("image/")) try { decodeSampled(cr, key.uri, px) } catch (_: Exception) { null } else null
    }
}

@Suppress("DEPRECATION")
private fun legacyVideoThumb(cr: ContentResolver, uri: Uri): Bitmap? {
    val id = try { ContentUris.parseId(uri) } catch (_: Exception) { return null }
    return MediaStore.Video.Thumbnails.getThumbnail(cr, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
}

private fun decodeSampled(cr: ContentResolver, uri: Uri, px: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
}

private fun pdfFirstPage(cr: ContentResolver, uri: Uri, px: Int): Bitmap? {
    val pfd = cr.openFileDescriptor(uri, "r") ?: return null
    try {
        val renderer = PdfRenderer(pfd)
        try {
            if (renderer.pageCount <= 0) return null
            val page = renderer.openPage(0)
            try {
                val w = px
                val h = (px * page.height.toFloat() / page.width.coerceAtLeast(1)).toInt().coerceIn(1, px * 2)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bmp
            } finally {
                page.close()
            }
        } finally {
            renderer.close()
        }
    } finally {
        try { pfd.close() } catch (_: Exception) { }
    }
}

/**
 * Miniatura unui element (poză, video, document). `px` = latura cerută. Fără previzualizare (sau până se
 * încarcă): fundal Surface2; documentele fără previzualizare arată foaia cu insigna tipului.
 */
@Composable
internal fun InvThumb(
    uri: Uri?,
    mime: String,
    modifier: Modifier = Modifier,
    px: Int = 320,
    name: String = "",
    contentScale: ContentScale = ContentScale.Crop
) {
    val ctx = LocalContext.current
    if (uri == null) {
        Box(modifier.background(Surface2))
        return
    }
    val previewable = isVisual(mime) || isPdf(mime, uri) || mime.isBlank()
    if (!previewable) {
        DocSheet(extOf(name, mime), modifier.background(MediaBg))
        return
    }
    var failed by remember(uri) { mutableStateOf(false) }
    val request = remember(uri, mime, px) {
        ImageRequest.Builder(ctx)
            .data(ThumbKey(uri, mime, px))
            .fetcherFactory(ThumbFactory, ThumbKey::class.java)
            .memoryCacheKey("inv:$px:$uri")
            .crossfade(140)
            .build()
    }
    Box(modifier.background(Surface2)) {
        if (failed && !isVisual(mime)) {
            DocSheet(extOf(name, mime), Modifier.fillMaxSize().background(MediaBg))
        } else {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                onError = { failed = true }
            )
            if (failed) {
                Icon(InvIcons.Image, null, tint = TextDim2, modifier = Modifier.align(Alignment.Center).size(22.dp))
            }
        }
    }
}

/** Coil pentru previzualizarea mare (poză întreagă la mărimea ecranului; video/PDF prin miniatură). */
@Composable
internal fun rememberPreviewModel(uri: Uri, mime: String): Any {
    val ctx = LocalContext.current
    return remember(uri, mime) {
        if (mime.startsWith("image/")) {
            ImageRequest.Builder(ctx).data(uri).crossfade(160).build()
        } else {
            ImageRequest.Builder(ctx)
                .data(ThumbKey(uri, mime, 1080))
                .fetcherFactory(ThumbFactory, ThumbKey::class.java)
                .memoryCacheKey("inv:1080:$uri")
                .crossfade(160)
                .build()
        }
    }
}

/** Extensia afișată pe insigna unui document: „PDF”, „DOCX”, „JPG”… */
internal fun extOf(name: String, mime: String): String {
    val fromName = name.substringAfterLast('.', "").takeIf { it.isNotBlank() && it.length <= 5 && '/' !in it }
    if (fromName != null) return fromName.uppercase()
    return when {
        mime == "application/pdf" -> "PDF"
        mime.contains("word") -> "DOCX"
        mime.contains("sheet") || mime.contains("excel") -> "XLSX"
        mime.contains("presentation") || mime.contains("powerpoint") -> "PPTX"
        mime.startsWith("image/") -> mime.substringAfter('/').uppercase().replace("JPEG", "JPG").take(4)
        mime.startsWith("text/") -> "TXT"
        else -> "FIȘ"
    }
}

/** Culoarea insignei de tip (prototipul: PDF roșu, DOCX albastru, JPG amber). */
internal fun extColor(ext: String): Color = when (ext.uppercase()) {
    "PDF" -> Error
    "DOC", "DOCX", "ODT", "RTF", "PAGES" -> DocBlue
    "XLS", "XLSX", "CSV", "ODS", "NUMBERS" -> Positive
    "PPT", "PPTX", "ODP", "KEY" -> EmberWarm
    "JPG", "JPEG", "PNG", "HEIC", "WEBP", "GIF", "BMP" -> Amber
    "TXT", "MD" -> TextSecondary
    else -> TextDim
}

/**
 * Foaia unui document (Dosare.dc.html, varianta Documente): două foi în spate, foaia albă cu colțul îndoit,
 * rânduri gri și insigna colorată a tipului. Desenată pe 120×104 unități, centrată.
 */
@Composable
internal fun DocSheet(ext: String, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val badge = extColor(ext)
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val unit = minOf(size.width / 120f, size.height / 104f)
            val ox = (size.width - 120f * unit) / 2f
            val oy = (size.height - 104f * unit) / 2f
            drawDocSheet(unit, ox, oy, ext, badge, measurer)
        }
    }
}

private fun DrawScope.drawDocSheet(u: Float, ox: Float, oy: Float, ext: String, badge: Color, measurer: TextMeasurer) {
    fun o(x: Float, y: Float) = Offset(ox + x * u, oy + y * u)
    rotate(-7f, pivot = o(50f, 52f)) {
        drawRoundRect(Track, topLeft = o(26f, 12f), size = Size(62f * u, 80f * u), cornerRadius = CornerRadius(3f * u))
    }
    rotate(5f, pivot = o(64f, 52f)) {
        drawRoundRect(Rule, topLeft = o(34f, 12f), size = Size(62f * u, 80f * u), cornerRadius = CornerRadius(3f * u))
    }
    val sheet = Path().apply {
        moveTo(ox + 30f * u, oy + 10f * u)
        lineTo(ox + 78f * u, oy + 10f * u)
        lineTo(ox + 90f * u, oy + 22f * u)
        lineTo(ox + 90f * u, oy + 94f * u)
        quadraticTo(ox + 90f * u, oy + 96f * u, ox + 88f * u, oy + 96f * u)
        lineTo(ox + 30f * u, oy + 96f * u)
        quadraticTo(ox + 28f * u, oy + 96f * u, ox + 28f * u, oy + 94f * u)
        lineTo(ox + 28f * u, oy + 12f * u)
        quadraticTo(ox + 28f * u, oy + 10f * u, ox + 30f * u, oy + 10f * u)
        close()
    }
    drawPath(sheet, Paper)
    val fold = Path().apply {
        moveTo(ox + 78f * u, oy + 10f * u); lineTo(ox + 78f * u, oy + 22f * u); lineTo(ox + 90f * u, oy + 22f * u); close()
    }
    drawPath(fold, PaperFold)
    val lines = floatArrayOf(36f, 30f, 36f, 22f)
    for (i in lines.indices) {
        val y = 32f + i * 10f
        drawLine(PaperInk, o(38f, y), o(38f + lines[i], y), strokeWidth = 3f * u, cap = StrokeCap.Round)
    }
    val w = ext.length * 8f + 12f
    drawRoundRect(badge, topLeft = o(34f, 72f), size = Size(w * u, 16f * u), cornerRadius = CornerRadius(3f * u))
    val style = mono((10f * u / density).coerceAtLeast(6f).toInt(), bold = true, color = com.forja.app.core.designsystem.Surface0)
    val layout = measurer.measure(ext, style)
    drawText(layout, topLeft = Offset(ox + (34f + w / 2f) * u - layout.size.width / 2f, oy + 80f * u - layout.size.height / 2f))
}
