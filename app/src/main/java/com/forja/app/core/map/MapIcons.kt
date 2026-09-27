package com.forja.app.core.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.transform.CircleCropTransformation
import com.forja.app.feature.map.MapMarkers
import org.maplibre.android.maps.Style

/**
 * Registrul de imagini al hărții: bitmapurile avatarelor și pinurilor, cache-uite per cheie (uid, stare, selectat…),
 * adăugate în stil o singură dată și re-adăugate automat după fiecare reîncărcare de stil.
 * Fotografiile prietenilor (users/{uid}.photoUrl) se încarcă prin Coil, decupate rotund; lipsesc → inițiale.
 */
class MapIcons(private val context: Context) {
    private val bitmaps = HashMap<String, Bitmap>()
    private val installed = HashSet<String>()
    private var style: Style? = null

    /** Fotografii decupate rotund, per uid, cu o versiune care intră în cheia iconiței. */
    private val photos = HashMap<String, Pair<Bitmap, Int>>()
    private val photoUrls = HashMap<String, String>()
    private val photoFailed = HashSet<String>()

    /** Un stil nou: nimic nu mai e în el — reinstalăm tot ce știm, dintr-o singură apelare. */
    fun attach(style: Style) {
        this.style = style
        installed.clear()
        if (bitmaps.isNotEmpty()) {
            try {
                style.addImages(HashMap(bitmaps))
                installed.addAll(bitmaps.keys)
            } catch (_: Exception) { }
        }
    }

    fun detach() { style = null; installed.clear() }

    /** Cheia rămâne id-ul imaginii; desenăm bitmapul doar prima dată. */
    fun register(key: String, make: () -> Bitmap): String {
        var b = bitmaps[key]
        if (b == null) {
            b = make()
            bitmaps[key] = b
        }
        val s = style
        if (s != null && key !in installed) {
            try {
                if (s.isFullyLoaded) { s.addImage(key, b); installed.add(key) }
            } catch (_: Exception) { }
        }
        return key
    }

    /** OnStyleImageMissing: dacă motorul cere o imagine pe care o știm, o adăugăm sincron. */
    fun provideMissing(id: String) {
        val b = bitmaps[id] ?: return
        val s = style ?: return
        try { s.addImage(id, b); installed.add(id) } catch (_: Exception) { }
    }

    fun place(stars: Int, mine: Boolean, selected: Boolean): String {
        val s = stars.coerceIn(0, 5)
        return register("p|$s|$mine|$selected") { MapMarkers.placeBitmap(context, s, mine, selected) }
    }

    fun friend(uid: String, name: String, state: String, ghost: Boolean, family: Boolean, selected: Boolean): String {
        val photo = photos[uid]
        val key = "f|$uid|$state|$ghost|$family|$selected|${photo?.second ?: 0}"
        return register(key) { MapMarkers.friendBitmap(context, name, photo?.first, ghost, false, state, family, selected) }
    }

    fun me(ghost: Boolean): String =
        register("f|me|$ghost") { MapMarkers.friendBitmap(context, "Tu", null, ghost, true, "idle", false, false) }

    fun cone(): String = register(MapIds.IMG_CONE) { MapMarkers.coneBitmap(context) }

    fun hasPhoto(uid: String): Boolean = photos.containsKey(uid)

    /**
     * Încarcă fotografia unui prieten (dacă s-a schimbat URL-ul). Întoarce true când avem o poză nouă —
     * cheia iconiței se schimbă și avatarul se redesenează cu ea.
     */
    suspend fun loadPhoto(uid: String, url: String?): Boolean {
        if (url.isNullOrBlank()) {
            if (photos.remove(uid) != null) { photoUrls.remove(uid); return true }
            return false
        }
        if (photoUrls[uid] == url && (photos.containsKey(uid) || uid in photoFailed)) return false
        val sizePx = (44 * context.resources.displayMetrics.density).toInt()
        return try {
            val req = ImageRequest.Builder(context)
                .data(url)
                .size(sizePx)
                .allowHardware(false)
                .transformations(CircleCropTransformation())
                .build()
            val res = context.imageLoader.execute(req)
            val bmp = (res as? SuccessResult)?.drawable?.let { d ->
                (d as? BitmapDrawable)?.bitmap ?: d.toBitmap(sizePx, sizePx)
            }
            photoUrls[uid] = url
            if (bmp != null) {
                val v = (photos[uid]?.second ?: 0) + 1
                photos[uid] = bmp to v
                photoFailed.remove(uid)
                true
            } else {
                photoFailed.add(uid)
                false
            }
        } catch (_: Exception) {
            photoUrls[uid] = url
            photoFailed.add(uid)
            false
        }
    }
}
