package com.forja.app.core.inventory

import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ═══════════════ Inventar 4.3 — regulile pure (fără Android): grupare, nume, plafon, editări ═══════════════
// Tot ce e aici e determinist: aceleași intrări → același plan. Partea de Android (MediaStore, EXIF, Geocoder,
// rețea, SAF) stă în PhotoPipeline / DocPipeline / InventoryApply; aici ajung doar id-uri, timpi, coordonate, texte.

/**
 * Constantele inventarului (DESIGN-4.3 §2).
 * - eveniment nou la o pauză > [EVENT_GAP_MS] (4 h) sau la un salt GPS > [JUMP_KM] (25 km) între două mostre;
 * - cel mult [GPS_SAMPLES] (3) mostre GPS pe eveniment, citite doar pentru evenimentele cu ≥ [SMALL_EVENT] elemente vii
 *   (cele mai mici ajung oricum într-un grup de lună, unde locul nu contează);
 * - evenimentele cu < [SMALL_EVENT] (6) elemente vii (care nu sunt la gunoi) din aceeași lună → un „grup de lună”;
 * - ≤ [REPS_PER_CLUSTER] (6) reprezentanți pe grup, ≤ [BATCH_CLUSTERS] (4) grupuri și ≤ [BATCH_THUMBS] (24) imagini pe apel AI;
 * - cel mult [MAX_FOLDERS] (24) dosare în plan, fără „De aruncat”;
 * - capturile de ecran mai vechi de [OLD_SCREENSHOT_MS] (90 de zile) merg la gunoi dacă AI-ul nu spune „da”;
 * - „păstrare: nu” pe un grup trimite grupul la gunoi doar dacă are ≤ [AI_TRASH_MAX] (30) elemente vii (regula de
 *   siguranță a serverului); un grup mai mare rămâne dosar normal, cu numele primit;
 * - miniaturile pentru AI: 384 px, JPEG q70 (coborât până la q40 dacă trebuie), ≤ [THUMB_MAX_BYTES] octeți, adică
 *   ≤ [THUMB_MAX_B64] caractere base64 (limita serverului).
 * Estimarea (sinceră, aceeași formulă ca în design): scanare ≈ [SCAN_PER_MIN] elemente/min
 * + AI ≈ ceil(grupuri / 4) apeluri × [AI_CALL_SEC] s / [AI_PARALLEL] în paralel.
 */
internal object InvRules {
    const val EVENT_GAP_MS = 4L * 3_600_000L
    const val JUMP_KM = 25.0
    const val GPS_SAMPLES = 3
    const val SMALL_EVENT = 6
    const val REPS_PER_CLUSTER = 6
    const val BATCH_CLUSTERS = 4
    const val BATCH_THUMBS = 24
    const val THUMB_SIDE = 384
    const val THUMB_MAX_BYTES = 61_440
    const val THUMB_MAX_B64 = 81_920
    const val AI_TRASH_MAX = 30
    const val MAX_FOLDERS = 24
    const val OLD_SCREENSHOT_MS = 90L * 86_400_000L
    const val SCAN_PER_MIN = 800
    const val AI_CALL_SEC = 12
    const val AI_PARALLEL = 2
    const val DOC_BATCH = 24
    const val DOC_BATCH_PDFS = 6
    const val DOC_BATCH_PDF_BYTES = 4_500_000L
    const val DOC_PDF_MAX = 4L * 1024 * 1024
    const val DOC_SMALL = 3
    const val NAME_MAX = 24
    const val USER_NAME_MAX = 40
    const val PROGRESS_SCALE = 1000
    const val TRASH_ID = "trash"
    const val TRASH_NAME = "De aruncat"
    const val TRASH_THEME = "Gunoi"
    const val SCREENS_NAME = "Capturi de ecran"
    const val SCREENS_THEME = "Capturi"
    const val DIVERSE = "Diverse"
    const val KIND_EVENT = "event"
    const val KIND_MONTH = "month"
    const val KIND_RECEIVED = "received"
    const val KIND_SCREENS = "screens"

    fun scanSec(items: Int): Int = ceil(items * 60.0 / SCAN_PER_MIN).toInt()
    fun photoCalls(groups: Int): Int = ceil(groups / BATCH_CLUSTERS.toDouble()).toInt()
    fun aiSec(calls: Int): Int = ceil(calls * AI_CALL_SEC / AI_PARALLEL.toDouble()).toInt()
    /** Gruparea (EXIF pe ≤ 3 mostre + Geocoder) — doar pentru ponderea în procentul global, nu intră în estimarea afișată. */
    fun groupSec(items: Int): Int = max(2, scanSec(items) / 12)
    fun estimateSec(items: Int, aiCalls: Int, ai: Boolean): Int = scanSec(items) + if (ai) aiSec(aiCalls) else 0
}

/** Texte scurte în română: luni, perioade, numere, nume de dosare curățate, normalizare pentru comparații. */
internal object InvText {
    private val MONTHS = arrayOf("ian", "feb", "mar", "apr", "mai", "iun", "iul", "aug", "sep", "oct", "nov", "dec")
    private val RO: Locale = Locale.forLanguageTag("ro-RO")
    private val MARKS = Regex("\\p{Mn}+")
    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val NAME_BAD = Regex("[^\\p{L}\\p{N} ·.,&'()+-]")
    private val SPACES = Regex("\\s+")
    private val EDGE = charArrayOf('·', '-', ',', '.', ' ', '&', '+')
    private val ACCIDENTAL = listOf("accident", "buzunar", "neagr", "negru", "intunecat", "deget")

    private fun zoned(t: Long, zone: ZoneId) = Instant.ofEpochMilli(t).atZone(zone)

    /** „aug 2023”. */
    fun monthYear(t: Long, zone: ZoneId): String { val d = zoned(t, zone); return "${MONTHS[d.monthValue - 1]} ${d.year}" }
    fun year(t: Long, zone: ZoneId): Int = zoned(t, zone).year
    /** An × 12 + lună (0..11): cheia „aceeași lună”. */
    fun monthKey(t: Long, zone: ZoneId): Int { val d = zoned(t, zone); return d.year * 12 + d.monthValue - 1 }

    /** „12 aug 2023”, „12–14 aug 2023”, „aug–sep 2023”, „dec 2023 – ian 2024”. */
    fun period(from: Long, to: Long, zone: ZoneId): String {
        val a = zoned(min(from, to), zone)
        val b = zoned(max(from, to), zone)
        val ma = MONTHS[a.monthValue - 1]
        val mb = MONTHS[b.monthValue - 1]
        return when {
            a.year == b.year && a.monthValue == b.monthValue && a.dayOfMonth == b.dayOfMonth -> "${a.dayOfMonth} $ma ${a.year}"
            a.year == b.year && a.monthValue == b.monthValue -> "${a.dayOfMonth}–${b.dayOfMonth} $ma ${a.year}"
            a.year == b.year -> "$ma–$mb ${a.year}"
            else -> "$ma ${a.year} – $mb ${b.year}"
        }
    }

    /** Cheia de comparație: fără diacritice, litere mici, doar litere/cifre separate de un spațiu („Nuntă · Aug” → „nunta aug”). */
    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "").lowercase(Locale.ROOT).replace(NON_ALNUM, " ").trim()

    /**
     * Numele de dosar afișat: fără ghilimele, emoji, bare sau caractere de control; spații unice; cel mult [max]
     * caractere (tăiat la ultimul cuvânt întreg când se poate); prima literă mare.
     */
    fun cleanName(raw: String, max: Int = InvRules.NAME_MAX): String {
        var s = raw.replace(NAME_BAD, " ").replace(SPACES, " ").trim().trim(*EDGE)
        if (s.length > max) {
            val cut = s.take(max)
            val sp = cut.lastIndexOf(' ')
            s = (if (sp >= max / 2) cut.substring(0, sp) else cut).trim().trim(*EDGE)
        }
        return s.replaceFirstChar { if (it.isLowerCase()) it.titlecase(RO) else it.toString() }
    }

    /** „3 214” (spațiu nedespărțitor ca separator de mii). */
    fun thousands(n: Int): String {
        val digits = abs(n).toString()
        val sb = StringBuilder()
        digits.forEachIndexed { i, c ->
            if (i > 0 && (digits.length - i) % 3 == 0) sb.append(' ')
            sb.append(c)
        }
        return if (n < 0) "-$sb" else sb.toString()
    }

    /** Pluralul românesc: „1 dosar”, „14 dosare”, „24 de dosare”, „101 dosare”. */
    fun count(n: Int, one: String, many: String): String = when {
        n == 1 -> "1 $one"
        n in 0..19 || n % 100 in 1..19 -> "${thousands(n)} $many"
        else -> "${thousands(n)} de $many"
    }

    /** Verdictul de păstrare al AI-ului: „da” / „nu”; orice altceva (sau lipsă) = „poate”. */
    fun pastrare(raw: String): String = when (normalize(raw)) {
        "da" -> "da"
        "nu" -> "nu"
        else -> "poate"
    }

    /** „nu” cu motiv de poză accidentală (buzunar, neagră, deget) → [DeleteReason.Accidental] în loc de AiSuggested. */
    fun accidental(motiv: String): Boolean { val m = normalize(motiv); return ACCIDENTAL.any { it in m } }

    /** Varianta cea mai des întâlnită (la egalitate, prima alfabetic) — pentru numele unui dosar de documente. */
    fun mostCommon(values: List<String>): String? = values.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
        .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key })?.key
}

internal data class LatLon(val lat: Double, val lon: Double)

/** Geometria grupării: pauze în timp, salturi GPS, alegeri „răspândite în timp”. */
internal object Clustering {
    /** Distanța pe sferă (km). */
    fun haversineKm(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * 6371.0 * asin(sqrt(min(1.0, h)))
    }

    /** [k] indici răspândiți uniform în 0 until [n] (primul, ultimul și cei dintre ei), fără dubluri, crescători. */
    fun spread(n: Int, k: Int): List<Int> = when {
        n <= 0 || k <= 0 -> emptyList()
        k >= n -> (0 until n).toList()
        k == 1 -> listOf(n / 2)
        else -> (0 until k).map { i -> ((i.toDouble() * (n - 1)) / (k - 1)).roundToInt() }.distinct()
    }

    /** Taie o listă sortată după timp acolo unde pauza dintre două elemente vecine depășește [gapMs]. */
    fun <T> splitByGap(sorted: List<T>, time: (T) -> Long, gapMs: Long): List<List<T>> {
        if (sorted.isEmpty()) return emptyList()
        val out = ArrayList<List<T>>()
        var start = 0
        for (i in 1 until sorted.size) {
            if (time(sorted[i]) - time(sorted[i - 1]) > gapMs) {
                out += sorted.subList(start, i).toList()
                start = i
            }
        }
        out += sorted.subList(start, sorted.size).toList()
        return out
    }

    /**
     * Tăieturile unui eveniment la salturi GPS. [times] = timpii elementelor (sortați), [located] = mostrele cu loc
     * (indice în eveniment → coordonate). Pentru fiecare pereche de mostre vecine aflate la > [jumpKm], evenimentul se
     * taie la CEA MAI MARE pauză dintre cele două mostre (acolo s-a făcut, cel mai probabil, drumul). Întoarce
     * indicii unde începe o parte nouă, crescători.
     */
    fun jumpCuts(times: LongArray, located: List<Pair<Int, LatLon>>, jumpKm: Double): List<Int> {
        val cuts = sortedSetOf<Int>()
        val pts = located.sortedBy { it.first }
        for (k in 0 until pts.size - 1) {
            val (i, a) = pts[k]
            val (j, b) = pts[k + 1]
            if (j <= i || haversineKm(a, b) <= jumpKm) continue
            var best = i + 1
            var gap = Long.MIN_VALUE
            for (m in i + 1..j) {
                val g = times[m] - times[m - 1]
                if (g > gap) { gap = g; best = m }
            }
            cuts += best
        }
        return cuts.toList()
    }

    /** Împarte [list] în bucăți care încep la indicii din [cuts]. */
    fun <T> splitAt(list: List<T>, cuts: List<Int>): List<List<T>> {
        if (cuts.isEmpty()) return listOf(list)
        val out = ArrayList<List<T>>()
        var start = 0
        for (c in cuts.sorted()) {
            if (c <= start || c >= list.size) continue
            out += list.subList(start, c).toList()
            start = c
        }
        out += list.subList(start, list.size).toList()
        return out
    }

    fun centroid(points: List<LatLon>): LatLon? =
        if (points.isEmpty()) null else LatLon(points.sumOf { it.lat } / points.size, points.sumOf { it.lon } / points.size)
}

/** Un eveniment (bucată din fluxul camerei) cu centrul mostrelor GPS și, după Geocoder, numele locului. */
internal data class Placed(val items: List<ItemRec>, val center: LatLon?, val place: String? = null)

/** Grup provizoriu (fără GPS, fără detectori) — pentru estimare și pentru „cutiile” din timpul scanării. */
internal class ProvGroup(val ids: List<String>, val needsAi: Boolean)

/** Cele trei fluxuri ale galeriei: capturi de ecran, poze primite (WhatsApp/Telegram/Download…), camera (restul). */
internal class Streams(val screens: List<ItemRec>, val received: List<ItemRec>, val camera: List<ItemRec>)

/** Un pas de plan: un dosar încă nefinalizat (înainte de unirea după nume și de plafonul de 24). */
internal data class Proto(
    val id: String,
    val name: String,
    val theme: String,
    val itemIds: List<String>,
    val from: Long,
    val to: Long,
    val pinned: Boolean = false,
    /** Eticheta după care se unesc dosarele peste plafon: `categorie` de la AI (vocabular fix), altfel tema. */
    val family: String = theme
) {
    val nameKey: String by lazy { InvText.normalize(name).ifBlank { id } }
    val familyKey: String by lazy { InvText.normalize(family) }
}

/** Construirea planului din grupuri + verdicte (poze și documente). */
internal object InvAssemble {
    private val RECEIVED = listOf(
        "whatsapp" to "WhatsApp", "telegram" to "Telegram", "messenger" to "Messenger",
        "viber" to "Viber", "download" to "Download"
    )

    /** Sursa pozelor primite după album / cale relativă; null = cameră sau alt album propriu. */
    fun receivedSource(r: ItemRec): String? {
        val s = (r.bucket + " " + r.relPath).lowercase(Locale.ROOT)
        return RECEIVED.firstOrNull { it.first in s }?.second
    }

    /** Capturile după nume/album/cale (euristica ieftină; detectorul complet e în CleanupEngine). */
    fun looksLikeScreenshot(name: String, bucket: String, relPath: String): Boolean {
        val b = bucket.lowercase(Locale.ROOT)
        val p = relPath.lowercase(Locale.ROOT)
        val n = name.lowercase(Locale.ROOT)
        return b.contains("screenshot") || p.contains("screenshots") || n.startsWith("screenshot")
    }

    fun live(items: List<ItemRec>): Int = items.count { it.localReason == null }

    private val byTime = compareBy<ItemRec>({ it.takenAt }, { it.mediaId }, { it.id })

    fun streams(items: List<ItemRec>): Streams {
        val screens = ArrayList<ItemRec>()
        val received = ArrayList<ItemRec>()
        val camera = ArrayList<ItemRec>()
        for (r in items) when {
            r.screenshot -> screens += r
            receivedSource(r) != null -> received += r
            else -> camera += r
        }
        return Streams(screens.sortedWith(byTime), received.sortedWith(byTime), camera.sortedWith(byTime))
    }

    /** Grupurile provizorii: evenimente la pauze de 4 h, cele mici unite pe lună, primite pe lună, capturi (un grup). */
    fun provisional(items: List<ItemRec>, now: Long, zone: ZoneId): List<ProvGroup> {
        val s = streams(items)
        val out = ArrayList<ProvGroup>()
        val events = Clustering.splitByGap(s.camera, { it.takenAt }, InvRules.EVENT_GAP_MS)
        val (big, small) = events.partition { live(it) >= InvRules.SMALL_EVENT }
        big.forEach { ev -> out += ProvGroup(ev.map { it.id }, true) }
        small.groupBy { InvText.monthKey(it.first().takenAt, zone) }.values.forEach { evs -> out += ProvGroup(evs.flatten().map { it.id }, true) }
        s.received.groupBy { InvText.monthKey(it.takenAt, zone) }.values.forEach { list -> out += ProvGroup(list.map { it.id }, true) }
        if (s.screens.isNotEmpty()) out += ProvGroup(s.screens.map { it.id }, s.screens.any { now - it.takenAt > InvRules.OLD_SCREENSHOT_MS })
        return out
    }

    /**
     * Grupurile finale ale pozelor, ordonate după început („c1”…): evenimentele mari (cu loc), grupurile de lună
     * (evenimentele mici unite după luna primului element), pozele primite pe lună, capturile de ecran (un singur grup).
     */
    fun clusters(events: List<Placed>, received: List<ItemRec>, screens: List<ItemRec>, now: Long, zone: ZoneId): List<ClusterRec> {
        class Raw(val kind: String, val items: List<ItemRec>, val center: LatLon?, val place: String?, val source: String?)
        val raws = ArrayList<Raw>()
        val (big, small) = events.partition { live(it.items) >= InvRules.SMALL_EVENT }
        big.forEach { raws += Raw(InvRules.KIND_EVENT, it.items, it.center, it.place, null) }
        small.groupBy { InvText.monthKey(it.items.first().takenAt, zone) }.values.forEach { evs ->
            raws += Raw(InvRules.KIND_MONTH, evs.flatMap { it.items }.sortedWith(byTime), null, null, null)
        }
        received.sortedWith(byTime).groupBy { InvText.monthKey(it.takenAt, zone) }.values.forEach { list ->
            raws += Raw(InvRules.KIND_RECEIVED, list, null, null, InvText.mostCommon(list.mapNotNull { receivedSource(it) }))
        }
        if (screens.isNotEmpty()) raws += Raw(InvRules.KIND_SCREENS, screens.sortedWith(byTime), null, null, null)
        return raws.filter { it.items.isNotEmpty() }
            .sortedWith(compareBy<Raw>({ it.items.first().takenAt }, { it.kind }, { it.items.first().id }))
            .mapIndexed { i, r ->
                val alive = r.items.filter { it.localReason == null }
                ClusterRec(
                    id = "c${i + 1}",
                    kind = r.kind,
                    itemIds = r.items.map { it.id },
                    from = r.items.first().takenAt,
                    to = r.items.last().takenAt,
                    count = alive.size,
                    videos = alive.count { it.video },
                    lat = r.center?.lat,
                    lon = r.center?.lon,
                    place = r.place,
                    source = r.source,
                    oldScreens = if (r.kind == InvRules.KIND_SCREENS) alive.count { now - it.takenAt > InvRules.OLD_SCREENSHOT_MS } else 0
                )
            }
    }

    /** AI-ul e întrebat doar pentru grupuri cu elemente vii; la capturi, doar dacă există capturi vechi (numele e fix). */
    fun needsAi(c: ClusterRec): Boolean = c.count > 0 && (c.kind != InvRules.KIND_SCREENS || c.oldScreens > 0)

    /** ≤ 6 reprezentanți răspândiți în timp, fără gunoi, întâi poze (video doar când pozele nu ajung); la capturi, cele vechi. */
    fun representatives(c: ClusterRec, items: Map<String, ItemRec>, now: Long, k: Int = InvRules.REPS_PER_CLUSTER): List<ItemRec> {
        var pool = c.itemIds.mapNotNull { items[it] }.filter { it.localReason == null }
        if (c.kind == InvRules.KIND_SCREENS) {
            val old = pool.filter { now - it.takenAt > InvRules.OLD_SCREENSHOT_MS }
            if (old.isNotEmpty()) pool = old
        }
        val images = pool.filter { !it.video }
        val src = if (images.size >= k || images.size == pool.size) images else pool
        return Clustering.spread(src.size, k).map { src[it] }
    }

    /** Indiciile scurte pentru AI (română, ≤ 5): felul grupului, câte poze/video, perioada, sursa. */
    fun hints(c: ClusterRec, zone: ZoneId): List<String> {
        val h = ArrayList<String>()
        when (c.kind) {
            InvRules.KIND_EVENT -> h += "eveniment"
            InvRules.KIND_MONTH -> h += "momente mici din aceeași lună"
            InvRules.KIND_RECEIVED -> h += "poze primite" + (c.source?.let { " ($it)" } ?: "")
            InvRules.KIND_SCREENS -> {
                h += "capturi de ecran"
                if (c.oldScreens > 0) h += "${InvText.count(c.oldScreens, "captură", "capturi")} mai vechi de 90 de zile"
            }
        }
        val photos = c.count - c.videos
        val what = listOfNotNull(
            photos.takeIf { it > 0 }?.let { InvText.count(it, "poză", "poze") },
            c.videos.takeIf { it > 0 }?.let { InvText.count(it, "video", "video") }
        ).joinToString(", ")
        if (what.isNotBlank()) h += what
        h += "perioada: " + InvText.period(c.from, c.to, zone)
        return h.take(5)
    }

    /**
     * Numele fără AI: „<Temă|Diverse> · <lună an>”. Tema locală: „Primite” pentru pozele primite, locul (Geocoder)
     * pentru evenimente, „Video” când grupul are doar video, altfel „Diverse”. Capturile au nume fix.
     */
    fun fallbackName(c: ClusterRec, zone: ZoneId): NameRec {
        if (c.kind == InvRules.KIND_SCREENS) return NameRec(InvRules.SCREENS_NAME, InvRules.SCREENS_THEME)
        val theme = when {
            c.kind == InvRules.KIND_RECEIVED -> "Primite"
            !c.place.isNullOrBlank() -> InvText.cleanName(c.place, 16)
            c.count > 0 && c.videos == c.count -> "Video"
            else -> InvRules.DIVERSE
        }.ifBlank { InvRules.DIVERSE }
        val suffix = " · " + InvText.monthYear(c.from, zone)
        val room = (InvRules.NAME_MAX - suffix.length).coerceAtLeast(4)
        return NameRec(theme.take(room).trim() + suffix, theme)
    }

    /** Loturile AI: în ordinea dată (cele mari întâi), ≤ 4 grupuri și ≤ 24 de imagini (planificate) pe lot. */
    fun pack(clusters: List<ClusterRec>, items: Map<String, ItemRec>, now: Long): List<List<ClusterRec>> {
        val out = ArrayList<List<ClusterRec>>()
        var cur = ArrayList<ClusterRec>()
        var thumbs = 0
        for (c in clusters) {
            val t = representatives(c, items, now).size
            if (cur.isNotEmpty() && (cur.size >= InvRules.BATCH_CLUSTERS || thumbs + t > InvRules.BATCH_THUMBS)) {
                out += cur; cur = ArrayList(); thumbs = 0
            }
            cur += c
            thumbs += t
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    /**
     * Planul pozelor:
     * 1. gunoiul local = verdictele detectorilor (duplicate/similare/neclare/mici), fără favorite;
     * 2. capturile > 90 de zile → [DeleteReason.OldScreenshot], dacă AI-ul nu a spus „da” pentru capturi;
     * 3. „păstrare: nu” (doar verdict AI real) → tot grupul la gunoi, [DeleteReason.AiSuggested] (sau Accidental),
     *    DOAR dacă grupul are ≤ [InvRules.AI_TRASH_MAX] elemente vii; unul mai mare rămâne dosar normal;
     * 4. un dosar pe grup, cu numele AI sau cel de rezervă; apoi [Folders.plan] (unire după nume, plafon 24 — unirea
     *    peste plafon merge după `categorie` când AI-ul a dat una, altfel după temă).
     */
    fun photos(createdAt: Long, items: Map<String, ItemRec>, clusters: List<ClusterRec>, names: Map<String, NameRec>, zone: ZoneId): PlanDoc {
        val reasons = LinkedHashMap<String, DeleteReason>()
        for (c in clusters) for (id in c.itemIds) {
            val r = items[id] ?: continue
            val why = r.localReason
            if (why != null && !r.favorite) reasons[id] = why
        }
        val protos = ArrayList<Proto>()
        val origin = HashMap<String, String>()
        clusters.forEachIndexed { i, c ->
            val fid = "f:${i + 1}"
            val v = names[c.id] ?: fallbackName(c, zone)
            if (c.kind == InvRules.KIND_SCREENS) {
                if (v.pastrare != "da") for (id in c.itemIds) {
                    val r = items[id] ?: continue
                    if (!r.favorite && id !in reasons && createdAt - r.takenAt > InvRules.OLD_SCREENSHOT_MS) reasons[id] = DeleteReason.OldScreenshot
                }
            } else if (v.ai && v.pastrare == "nu" && c.count <= InvRules.AI_TRASH_MAX) {
                val why = if (InvText.accidental(v.motiv)) DeleteReason.Accidental else DeleteReason.AiSuggested
                for (id in c.itemIds) {
                    val r = items[id] ?: continue
                    if (!r.favorite && id !in reasons) reasons[id] = why
                }
            }
            c.itemIds.forEach { origin[it] = fid }
            val screens = c.kind == InvRules.KIND_SCREENS
            val theme = if (screens) InvRules.SCREENS_THEME else v.tema.ifBlank { InvRules.DIVERSE }
            protos += Proto(
                id = fid,
                name = if (screens) InvRules.SCREENS_NAME else v.nume,
                theme = theme,
                itemIds = c.itemIds.filter { it !in reasons },
                from = c.from, to = c.to, pinned = screens,
                family = if (screens) theme else v.categorie.ifBlank { theme }
            )
        }
        return Folders.plan(protos, reasons, origin, items, zone, seq = clusters.size)
    }

    /** Familia unui fișier după extensie / MIME — dosarul de rezervă al documentelor. */
    fun docType(r: ItemRec): String {
        val ext = r.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val m = r.mime.lowercase(Locale.ROOT)
        return when {
            ext == "pdf" || m == "application/pdf" -> "PDF-uri"
            ext in setOf("doc", "docx", "odt", "rtf", "pages") -> "Documente"
            ext in setOf("xls", "xlsx", "ods", "csv", "tsv", "numbers") -> "Tabele"
            ext in setOf("ppt", "pptx", "odp", "key") -> "Prezentări"
            m.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "heic", "webp", "gif", "bmp") -> "Imagini"
            ext in setOf("zip", "rar", "7z", "tar", "gz", "tgz") -> "Arhive"
            m.startsWith("audio/") || ext in setOf("mp3", "m4a", "wav", "ogg", "flac", "aac", "opus") -> "Audio"
            m.startsWith("video/") || ext in setOf("mp4", "mkv", "mov", "avi", "webm", "3gp") -> "Video"
            ext == "apk" -> "Aplicații"
            m.startsWith("text/") || ext in setOf("txt", "md", "json", "xml", "html", "htm", "log", "yaml", "yml") -> "Texte"
            else -> InvRules.DIVERSE
        }
    }

    fun isPdf(r: ItemRec): Boolean =
        r.mime.equals("application/pdf", ignoreCase = true) || r.name.substringAfterLast('.', "").equals("pdf", ignoreCase = true)

    /** Loturile /v1/organize pentru documente: ≤ 24 de fișiere, ≤ 6 PDF-uri (≤ 4 MB fiecare), ≤ 4,5 MB de PDF pe lot. */
    fun docBatches(items: List<ItemRec>): List<List<ItemRec>> {
        val out = ArrayList<List<ItemRec>>()
        var cur = ArrayList<ItemRec>()
        var pdfs = 0
        var pdfBytes = 0L
        for (r in items) {
            val pdf = isPdf(r) && r.bytes in 1..InvRules.DOC_PDF_MAX
            val full = cur.size >= InvRules.DOC_BATCH ||
                (pdf && (pdfs >= InvRules.DOC_BATCH_PDFS || pdfBytes + r.bytes > InvRules.DOC_BATCH_PDF_BYTES))
            if (cur.isNotEmpty() && full) { out += cur; cur = ArrayList(); pdfs = 0; pdfBytes = 0L }
            cur += r
            if (pdf) { pdfs++; pdfBytes += r.bytes }
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    /**
     * Planul documentelor:
     * 1. gunoiul = copiile duplicate (SHA-256, originalul rămâne) + fișierele temporare „~$” + `sterge.recomandat` de la AI;
     * 2. cheia de grupare = `dosar` normalizat, altfel `categorie`, altfel familia fișierului (PDF-uri, Tabele…);
     * 3. dosarele „dosar” cu < [InvRules.DOC_SMALL] fișiere vii se unesc în `categorie` (sau în familie);
     * 4. [Folders.plan]: unire după nume, plafon 24 (după temă + an).
     */
    fun documents(items: List<ItemRec>, verdicts: Map<String, DocVerdictRec>, zone: ZoneId): PlanDoc {
        val reasons = LinkedHashMap<String, DeleteReason>()
        for (r in items) {
            val local = r.localReason
            when {
                local != null -> reasons[r.id] = local
                verdicts[r.id]?.delete == true -> reasons[r.id] = DeleteReason.AiSuggested
            }
        }
        class Key(val key: String, val label: String, val theme: String)
        fun secondary(r: ItemRec): Key {
            val cat = InvText.cleanName(verdicts[r.id]?.categorie.orEmpty())
            val type = docType(r)
            return if (cat.isNotBlank()) Key("c:" + InvText.normalize(cat), cat, cat) else Key("t:" + InvText.normalize(type), type, type)
        }
        fun primary(r: ItemRec): Key {
            val v = verdicts[r.id]
            val dosar = InvText.cleanName(v?.dosar.orEmpty())
            if (dosar.isNotBlank() && InvText.normalize(dosar).isNotBlank()) {
                val cat = InvText.cleanName(v?.categorie.orEmpty())
                return Key("d:" + InvText.normalize(dosar), dosar, cat.ifBlank { docType(r) })
            }
            return secondary(r)
        }
        val sorted = items.sortedWith(compareBy<ItemRec>({ it.path }, { it.id }))
        val first = LinkedHashMap<String, MutableList<Pair<ItemRec, Key>>>()
        for (r in sorted) { val k = primary(r); first.getOrPut(k.key) { ArrayList() } += r to k }
        val groups = LinkedHashMap<String, MutableList<Pair<ItemRec, Key>>>()
        for ((key, members) in first) {
            val alive = members.count { it.first.id !in reasons }
            if (key.startsWith("d:") && alive < InvRules.DOC_SMALL) {
                for ((r, _) in members) { val k = secondary(r); groups.getOrPut(k.key) { ArrayList() } += r to k }
            } else {
                groups.getOrPut(key) { ArrayList() }.addAll(members)
            }
        }
        val protos = ArrayList<Proto>()
        val origin = HashMap<String, String>()
        groups.values.forEachIndexed { i, members ->
            val fid = "f:${i + 1}"
            val name = InvText.mostCommon(members.map { it.second.label }) ?: InvRules.DIVERSE
            val theme = InvText.mostCommon(members.map { it.second.theme }) ?: name
            members.forEach { origin[it.first.id] = fid }
            val times = members.map { it.first.takenAt }
            protos += Proto(fid, name, theme, members.map { it.first.id }.filter { it !in reasons }, times.min(), times.max())
        }
        return Folders.plan(protos, reasons, origin, items.associateBy { it.id }, zone, seq = protos.size)
    }
}

/**
 * Dosarele finale:
 * - [mergeByName]: dosarele cu același nume normalizat devin unul singur (în primul, în ordinea listei);
 * - [cap]: cât timp sunt > 24 de dosare, cel mai mic dosar care are un „frate” se unește cu cel mai mic frate:
 *   întâi aceeași familie și același an („Călătorii · 2023”), apoi aceeași familie („Călătorii”), apoi oricare două
 *   („Diverse · an” sau „Diverse”). Familia = `categorie` de la AI (vocabular fix) sau tema. „Capturi de ecran” nu se
 *   unește niciodată. Egalitățile se rup după mărime, apoi după id, deci rezultatul e determinist;
 * - [plan]: dosarele goale (tot conținutul la gunoi) devin „fantome” — nume păstrat pentru „Păstrează”; ordinea
 *   finală: cele mai noi întâi.
 */
internal object Folders {
    fun absorb(into: Proto, other: Proto): Proto = into.copy(
        itemIds = into.itemIds + other.itemIds,
        from = min(into.from, other.from),
        to = max(into.to, other.to),
        pinned = into.pinned || other.pinned
    )

    fun mergeByName(list: List<Proto>, redirects: MutableMap<String, String>): List<Proto> {
        val byKey = LinkedHashMap<String, Proto>()
        for (p in list) {
            val prev = byKey[p.nameKey]
            if (prev == null) byKey[p.nameKey] = p
            else {
                byKey[p.nameKey] = absorb(prev, p)
                redirects[p.id] = prev.id
            }
        }
        return byKey.values.toList()
    }

    fun cap(input: List<Proto>, max: Int, zone: ZoneId, redirects: MutableMap<String, String>): List<Proto> {
        var list = input
        var guard = 0
        while (list.size > max && guard++ < 100_000) {
            val free = list.filter { !it.pinned }
            if (free.size < 2) break
            val (small, big, level) = pick(free, zone) ?: break
            val year = InvText.year(big.from, zone)
            val name = when (level) {
                1 -> InvText.cleanName("${InvText.cleanName(big.family, 16).ifBlank { InvRules.DIVERSE }} · $year")
                2 -> InvText.cleanName(big.family).ifBlank { InvRules.DIVERSE }
                else -> if (InvText.year(small.from, zone) == year) "${InvRules.DIVERSE} · $year" else InvRules.DIVERSE
            }
            val merged = absorb(big, small).copy(
                name = name,
                theme = if (level == 3) InvRules.DIVERSE else big.family,
                family = if (level == 3) InvRules.DIVERSE else big.family
            )
            redirects[small.id] = big.id
            list = mergeByName(list.filter { it.id != small.id }.map { if (it.id == big.id) merged else it }, redirects)
        }
        return list
    }

    /** (cel mai mic, fratele cel mai mic, nivelul 1..3) sau null. */
    private fun pick(free: List<Proto>, zone: ZoneId): Triple<Proto, Proto, Int>? {
        val bySize = compareBy<Proto>({ it.itemIds.size }, { it.id })
        for (level in 1..3) {
            val groups = free.groupBy { p ->
                when (level) {
                    1 -> p.familyKey + "|" + InvText.year(p.from, zone)
                    2 -> p.familyKey
                    else -> "*"
                }
            }
            var best: Pair<Proto, Proto>? = null
            for (g in groups.values) {
                if (g.size < 2) continue
                val s = g.sortedWith(bySize)
                val cand = s[0] to s[1]
                val cur = best
                if (cur == null || bySize.compare(cand.first, cur.first) < 0) best = cand
            }
            val b = best
            if (b != null) return Triple(b.first, b.second, level)
        }
        return null
    }

    fun sortIds(ids: Collection<String>, items: Map<String, ItemRec>): List<String> =
        ids.distinct().sortedWith(compareBy<String>({ items[it]?.takenAt ?: 0L }, { it }))

    fun plan(
        protos: List<Proto>,
        reasons: Map<String, DeleteReason>,
        origin: Map<String, String>,
        items: Map<String, ItemRec>,
        zone: ZoneId,
        seq: Int
    ): PlanDoc {
        val redirects = LinkedHashMap<String, String>()
        var live = mergeByName(protos.filter { it.itemIds.isNotEmpty() }, redirects)
        live = cap(live, InvRules.MAX_FOLDERS, zone, redirects)
        val byKey = live.associateBy { it.nameKey }
        val ghosts = ArrayList<FolderRec>()
        for (g in protos.filter { it.itemIds.isEmpty() }) {
            val same = byKey[g.nameKey]
            if (same != null) redirects[g.id] = same.id
            else if (ghosts.none { InvText.normalize(it.name) == g.nameKey }) ghosts += FolderRec(g.id, g.name, g.theme)
            else redirects[g.id] = ghosts.first { InvText.normalize(it.name) == g.nameKey }.id
        }
        val folders = live.sortedWith(compareByDescending<Proto> { it.to }.thenBy { it.id })
            .map { FolderRec(it.id, it.name, it.theme, sortIds(it.itemIds, items)) }
        val trashIds = sortIds(reasons.keys, items)
        val trashSet = trashIds.toHashSet()
        return PlanDoc(
            folders = folders,
            trash = FolderRec(InvRules.TRASH_ID, InvRules.TRASH_NAME, InvRules.TRASH_THEME, trashIds, special = true),
            reasons = reasons.filterKeys { it in trashSet },
            origin = origin.filterKeys { it in trashSet },
            redirects = redirects,
            ghosts = ghosts,
            seq = seq
        )
    }
}

/**
 * Editările planului (pure; fiecare întoarce un plan nou, normalizat):
 * - un element stă într-un singur loc: un dosar sau „De aruncat”; ce iese din „De aruncat” își pierde motivul;
 * - ce intră în „De aruncat” își ține minte dosarul de origine; „Păstrează” îl întoarce acolo (urmând unirile),
 *   îl reînvie din „fantomă” dacă dosarul se golise, altfel îl pune în „Diverse”;
 * - „Desfă” trimite elementele în „Diverse · <an>” (după anul fiecăruia);
 * - dosarele golite dispar (devin fantome), în afară de cele create de utilizator, care rămân până la „Desfă”.
 */
internal object PlanEdits {
    private const val TRASH = InvRules.TRASH_ID

    /** Element → dosarul în care stă (sau „trash”). */
    fun index(doc: PlanDoc): HashMap<String, String> {
        val m = HashMap<String, String>()
        doc.folders.forEach { f -> f.itemIds.forEach { m[it] = f.id } }
        doc.trash.itemIds.forEach { m[it] = TRASH }
        return m
    }

    fun count(doc: PlanDoc): Int = doc.folders.sumOf { it.itemIds.size } + doc.trash.itemIds.size

    /** Urmează unirile (dosar unit → dosarul în care a intrat). */
    fun resolve(doc: PlanDoc, id: String?): String? {
        var cur = id ?: return null
        repeat(64) {
            val next = doc.redirects[cur] ?: return cur
            if (next == cur) return cur
            cur = next
        }
        return cur
    }

    fun rename(doc: PlanDoc, folderId: String, name: String): PlanDoc {
        val clean = InvText.cleanName(name, InvRules.USER_NAME_MAX)
        if (clean.isBlank() || doc.folders.none { it.id == folderId }) return doc
        return doc.copy(folders = doc.folders.map { if (it.id == folderId) it.copy(name = clean) else it })
    }

    fun move(doc: PlanDoc, items: Map<String, ItemRec>, ids: List<String>, to: String): PlanDoc {
        if (to == TRASH) return toTrash(doc, items, ids)
        if (doc.folders.none { it.id == to }) return doc
        val idx = index(doc)
        val moving = LinkedHashSet<String>()
        ids.forEach { id -> val at = idx[id]; if (at != null && at != to) moving += id }
        if (moving.isEmpty()) return doc
        val folders = doc.folders.map { f ->
            val kept = if (f.itemIds.any { it in moving }) f.itemIds.filter { it !in moving } else f.itemIds
            when {
                f.id == to -> f.copy(itemIds = kept + moving)
                kept !== f.itemIds -> f.copy(itemIds = kept)
                else -> f
            }
        }
        return normalize(
            doc.copy(
                folders = folders,
                trash = doc.trash.copy(itemIds = doc.trash.itemIds.filter { it !in moving }),
                reasons = doc.reasons - moving,
                origin = doc.origin - moving
            ), items
        )
    }

    fun toTrash(doc: PlanDoc, items: Map<String, ItemRec>, ids: List<String>): PlanDoc {
        val idx = index(doc)
        val moving = LinkedHashMap<String, String>()
        ids.forEach { id -> val at = idx[id]; if (at != null && at != TRASH) moving[id] = at }
        if (moving.isEmpty()) return doc
        val keys = moving.keys
        val folders = doc.folders.map { f -> if (f.itemIds.any { it in keys }) f.copy(itemIds = f.itemIds.filter { it !in keys }) else f }
        return normalize(
            doc.copy(folders = folders, trash = doc.trash.copy(itemIds = doc.trash.itemIds + keys), origin = doc.origin + moving),
            items
        )
    }

    fun keep(doc: PlanDoc, items: Map<String, ItemRec>, ids: List<String>): PlanDoc {
        val inTrash = doc.trash.itemIds.toHashSet()
        val back = ids.filter { it in inTrash }.distinct()
        if (back.isEmpty()) return doc
        val backSet = back.toHashSet()
        var d = doc.copy(
            trash = doc.trash.copy(itemIds = doc.trash.itemIds.filter { it !in backSet }),
            reasons = doc.reasons - backSet,
            origin = doc.origin - backSet
        )
        val byDest = LinkedHashMap<String, MutableList<String>>()
        for (id in back) byDest.getOrPut(resolve(doc, doc.origin[id]) ?: "") { ArrayList() } += id
        for ((dest, list) in byDest) {
            val ghost = if (dest.isEmpty()) null else d.ghosts.firstOrNull { it.id == dest }
            d = when {
                dest.isNotEmpty() && d.folders.any { it.id == dest } ->
                    d.copy(folders = d.folders.map { if (it.id == dest) it.copy(itemIds = it.itemIds + list) else it })
                ghost != null ->
                    d.copy(folders = d.folders + ghost.copy(itemIds = list), ghosts = d.ghosts.filter { it.id != dest })
                else -> addToDiverse(d, list)
            }
        }
        return normalize(d, items)
    }

    private fun addToDiverse(doc: PlanDoc, ids: List<String>): PlanDoc {
        val key = InvText.normalize(InvRules.DIVERSE)
        val existing = doc.folders.firstOrNull { InvText.normalize(it.name) == key }
        if (existing != null) return doc.copy(folders = doc.folders.map { if (it.id == existing.id) it.copy(itemIds = it.itemIds + ids) else it })
        val id = "f:${doc.seq + 1}"
        return doc.copy(seq = doc.seq + 1, folders = doc.folders + FolderRec(id, InvRules.DIVERSE, InvRules.DIVERSE, ids))
    }

    fun newFolder(doc: PlanDoc, items: Map<String, ItemRec>, name: String, ids: List<String>): Pair<PlanDoc, String> {
        val clean = InvText.cleanName(name, InvRules.USER_NAME_MAX).ifBlank { "Dosar nou" }
        val id = "f:${doc.seq + 1}"
        val created = doc.copy(seq = doc.seq + 1, folders = doc.folders + FolderRec(id, clean, clean, user = true))
        return normalize(move(created, items, ids, id), items) to id
    }

    fun merge(doc: PlanDoc, items: Map<String, ItemRec>, fromId: String, intoId: String): PlanDoc {
        if (fromId == intoId) return doc
        val from = doc.folders.firstOrNull { it.id == fromId } ?: return doc
        if (doc.folders.none { it.id == intoId }) return doc
        val folders = doc.folders.filter { it.id != fromId }.map { if (it.id == intoId) it.copy(itemIds = it.itemIds + from.itemIds) else it }
        return normalize(doc.copy(folders = folders, redirects = doc.redirects + (fromId to intoId)), items)
    }

    fun dissolve(doc: PlanDoc, items: Map<String, ItemRec>, folderId: String, zone: ZoneId): PlanDoc {
        val f = doc.folders.firstOrNull { it.id == folderId } ?: return doc
        var d = doc.copy(folders = doc.folders.filter { it.id != folderId })
        val byYear = f.itemIds.groupBy { InvText.year(items[it]?.takenAt ?: 0L, zone) }.toSortedMap()
        val targets = ArrayList<String>()
        for ((y, list) in byYear) {
            val name = "${InvRules.DIVERSE} · $y"
            val key = InvText.normalize(name)
            val existing = d.folders.firstOrNull { InvText.normalize(it.name) == key }
            d = if (existing != null) {
                targets += existing.id
                d.copy(folders = d.folders.map { if (it.id == existing.id) it.copy(itemIds = it.itemIds + list) else it })
            } else {
                val id = "f:${d.seq + 1}"
                targets += id
                d.copy(seq = d.seq + 1, folders = d.folders + FolderRec(id, name, InvRules.DIVERSE, list))
            }
        }
        if (targets.size == 1) d = d.copy(redirects = d.redirects + (folderId to targets[0]))
        return normalize(d, items)
    }

    /** Scoate elementele aplicate (sau dispărute) din plan. */
    fun without(doc: PlanDoc, items: Map<String, ItemRec>, gone: Set<String>): PlanDoc {
        if (gone.isEmpty()) return doc
        return normalize(
            doc.copy(
                folders = doc.folders.map { f -> if (f.itemIds.any { it in gone }) f.copy(itemIds = f.itemIds.filter { it !in gone }) else f },
                trash = doc.trash.copy(itemIds = doc.trash.itemIds.filter { it !in gone })
            ), items
        )
    }

    /** Fiecare element o singură dată (primul loc câștigă, gunoiul la urmă), sortat după timp; dosarele golite → fantome. */
    fun normalize(doc: PlanDoc, items: Map<String, ItemRec>): PlanDoc {
        val seen = HashSet<String>()
        val folders = ArrayList<FolderRec>()
        val ghosts = ArrayList(doc.ghosts)
        for (f in doc.folders) {
            val ids = Folders.sortIds(f.itemIds.filter { it in items && seen.add(it) }, items)
            if (ids.isEmpty() && !f.user) {
                if (ghosts.none { it.id == f.id }) ghosts += f.copy(itemIds = emptyList())
                continue
            }
            folders += f.copy(itemIds = ids)
        }
        val trash = Folders.sortIds(doc.trash.itemIds.filter { it in items && seen.add(it) }, items)
        val trashSet = trash.toHashSet()
        return doc.copy(
            folders = folders,
            trash = doc.trash.copy(itemIds = trash),
            ghosts = ghosts,
            reasons = doc.reasons.filterKeys { it in trashSet },
            origin = doc.origin.filterKeys { it in trashSet }
        )
    }
}
