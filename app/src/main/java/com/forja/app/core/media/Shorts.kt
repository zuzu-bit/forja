package com.forja.app.core.media

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** FRONT = shorts FORJA (stil de viață, disciplină); RECRUȚI = pisicile. */
@Serializable
enum class ShortKind { Front, Recruti }

/** Autorul clipului (Pexels): numele și pagina clipului. */
@Serializable
data class ShortCredit(val name: String, val url: String? = null)

/**
 * Un clip vertical din feed-ul FORJA Shorts (construit în CI de scripts/shorts/build.sh).
 * [url] și [poster] sunt deja rezolvate pe serverul aplicației (Media.mediaUrl), [dur] în secunde.
 * [burned] = legenda e arsă în video; atunci aplicația nu o mai desenează, iar [capTop]/[capLeft]
 * (px în cadrul [w]×[h]) spun unde stă, ca ștampila să se așeze deasupra ei.
 */
@Serializable
data class Short(
    val id: String,
    val kind: ShortKind,
    val url: String,
    val poster: String?,
    val caption: String,
    val credit: ShortCredit?,
    val dur: Float,
    val burned: Boolean = false,
    val capTop: Int? = null,
    val capLeft: Int? = null,
    val w: Int = 720,
    val h: Int = 1280
)

/**
 * Manifestul shorts (R2 forja-media → forja-api /media/shorts_manifest.json), cu cache de 6 h în filesDir.
 * Fără rețea se folosește ultima copie, oricât de veche. Parserul e tolerant: un element stricat se sare,
 * nu strică tot feed-ul.
 */
object Shorts {
    const val MANIFEST_KEY = "shorts_manifest.json"
    private const val CACHE_MS = 6L * 60 * 60 * 1000
    private const val PREFS = "forja_shorts"
    private const val KEY_LIKED = "liked"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val lock = Mutex()
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Clipurile din manifest, în ordinea din fișier (pentru feed: [order]).
     * Copia locală mai nouă de 6 h se folosește fără rețea, în afară de [force].
     * Listă goală = manifestul nu există încă (sau serverul nu e configurat).
     * @throws IOException doar când nu merge rețeaua și nu există nicio copie locală.
     */
    suspend fun load(context: Context, force: Boolean = false): List<Short> = withContext(Dispatchers.IO) {
        lock.withLock {
            val file = cacheFile(context)
            val cached = if (file.isFile) runCatching { file.readText() }.getOrNull() else null
            val age = System.currentTimeMillis() - file.lastModified()
            if (!force && cached != null && age in 0 until CACHE_MS) return@withLock parse(cached)
            val body = try {
                fetch()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (cached != null) return@withLock parse(cached)
                throw e as? IOException ?: IOException(e)
            }
            when {
                // 404: nu e încă publicat; păstrăm ce aveam (un 404 trecător nu golește feed-ul).
                body == null -> cached?.let { parse(it) } ?: emptyList()
                // Răspuns care nu e JSON (pagină de eroare cu 200): îl ignorăm.
                root(body) == null -> cached?.let { parse(it) } ?: throw IOException("Manifest ilizibil")
                else -> {
                    runCatching {
                        val tmp = File(file.parentFile, file.name + ".tmp")
                        tmp.writeText(body)
                        if (!tmp.renameTo(file)) { file.writeText(body); tmp.delete() }
                    }
                    parse(body)
                }
            }
        }
    }

    /** Ultima copie locală, fără rețea și fără excepții (de ex. posterul primului clip într-un card). */
    fun peek(context: Context): List<Short> =
        runCatching { cacheFile(context).takeIf { it.isFile }?.readText()?.let { parse(it) } }.getOrNull() ?: emptyList()

    /** Parser tolerant: acceptă {items:[…]} sau direct […]; elementele fără id/url se sar, duplicatele la fel. */
    fun parse(text: String): List<Short> {
        val items = when (val r = root(text)) {
            is JsonArray -> r
            is JsonObject -> r["items"] as? JsonArray ?: return emptyList()
            else -> return emptyList()
        }
        val seen = HashSet<String>()
        return items.mapNotNull { (it as? JsonObject)?.let(::item) }.filter { seen.add(it.id) }
    }

    /**
     * Ordinea feed-ului: 2 FRONT : 1 RECRUȚI, amestecate, fiecare clip o singură dată pe ciclu.
     * Când un tip se termină, continuă celălalt. [avoidFirst] = id-ul ultimului clip din ciclul precedent,
     * ca același clip să nu apară de două ori la rând la trecerea între cicluri.
     */
    fun order(items: List<Short>, random: Random = Random.Default, avoidFirst: String? = null): List<Short> {
        val unique = items.distinctBy { it.id }
        val front = unique.filter { it.kind == ShortKind.Front }.shuffled(random).toMutableList()
        val cats = unique.filter { it.kind == ShortKind.Recruti }.shuffled(random).toMutableList()
        val out = ArrayList<Short>(unique.size)
        while (front.isNotEmpty() || cats.isNotEmpty()) {
            val wantCat = out.size % 3 == 2
            val source = when {
                wantCat && cats.isNotEmpty() -> cats
                front.isNotEmpty() -> front
                else -> cats
            }
            out += source.removeAt(source.lastIndex)
        }
        if (avoidFirst != null && out.size > 1 && out[0].id == avoidFirst) {
            val swapWith = out.indexOfFirst { it.id != avoidFirst && it.kind == out[0].kind }.takeIf { it > 0 } ?: 1
            val first = out[0]
            out[0] = out[swapWith]
            out[swapWith] = first
        }
        return out
    }

    /** Inimile date local (nu pleacă nicăieri). */
    fun liked(context: Context): Set<String> =
        runCatching { prefs(context).getStringSet(KEY_LIKED, emptySet())?.toSet() }.getOrNull() ?: emptySet()

    fun setLiked(context: Context, id: String, liked: Boolean) {
        runCatching {
            val now = liked(context).toMutableSet()
            if (liked) now += id else now -= id
            prefs(context).edit().putStringSet(KEY_LIKED, now).apply()
        }
    }

    // ── interne ──────────────────────────────────────────────────────────────────────────────

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun cacheFile(context: Context): File =
        File(context.applicationContext.filesDir, "shorts").apply { mkdirs() }.resolve(MANIFEST_KEY)

    /** Corpul manifestului; null la 404. */
    private fun fetch(): String? {
        val url = Media.mediaUrl(MANIFEST_KEY) ?: return null
        val request = Request.Builder().url(url).header("Cache-Control", "no-cache").build()
        client.newCall(request).execute().use { resp ->
            if (resp.code == 404) return null
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return resp.body?.string() ?: throw IOException("Răspuns gol")
        }
    }

    private fun root(text: String): JsonElement? = runCatching { json.parseToJsonElement(text) }.getOrNull()

    private fun item(o: JsonObject): Short? {
        val id = o.str("id")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val url = o.str("url")?.let(::resolve) ?: return null
        val kind = when (o.str("kind")?.trim()?.lowercase()) {
            "recruti", "recruți", "recruţi", "cat", "cats", "pisici" -> ShortKind.Recruti
            else -> ShortKind.Front
        }
        val credit = when (val c = o["credit"]) {
            is JsonObject -> c.str("name")?.trim()?.takeIf { it.isNotEmpty() }?.let { ShortCredit(it, c.str("url")) }
            is JsonPrimitive -> c.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }?.let { ShortCredit(it) }
            else -> null
        }
        val burned = o.bool("burned") ?: false
        return Short(
            id = id,
            kind = kind,
            url = url,
            poster = o.str("poster")?.let(::resolve),
            // Vocea FORJA nu strigă: un „!” rătăcit devine punct.
            caption = o.str("caption")?.trim()?.replace('!', '.').orEmpty(),
            credit = credit,
            dur = o.num("dur")?.toFloat()?.takeIf { it > 0f } ?: 0f,
            burned = burned,
            capTop = if (burned) o.num("capTop")?.toInt() else null,
            capLeft = if (burned) o.num("capLeft")?.toInt() else null,
            w = o.num("w")?.toInt()?.takeIf { it > 0 } ?: 720,
            h = o.num("h")?.toInt()?.takeIf { it > 0 } ?: 1280
        )
    }

    /**
     * Adresele din manifest se leagă de serverul cu care rulează aplicația: „…/media/<cheie>” sau o cheie
     * simplă → Media.mediaUrl(cheie). Alte adrese absolute rămân cum sunt.
     */
    private fun resolve(raw: String): String? {
        val u = raw.trim().takeIf { it.isNotEmpty() } ?: return null
        val key = when {
            "/media/" in u -> u.substringAfterLast("/media/")
            "://" !in u -> u.trimStart('/')
            else -> return u
        }
        if (key.isEmpty()) return null
        return Media.mediaUrl(key) ?: u.takeIf { "://" in it }
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.num(k: String): Double? =
        (this[k] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.trim()?.toDoubleOrNull() }

    private fun JsonObject.bool(k: String): Boolean? =
        (this[k] as? JsonPrimitive)?.let { p ->
            p.booleanOrNull ?: when (p.contentOrNull?.trim()?.lowercase()) {
                "1", "true", "da" -> true
                "0", "false", "nu" -> false
                else -> null
            }
        }
}
