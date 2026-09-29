package com.forja.app.core.focus

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Documentele zilei pe care oglinda minții (FocusMirror, ListenMirror) le scrie în Firestore, construite din ce e pe telefon.
 * Funcții pure (fără Android), ca să poată fi testate pe JVM. Formele sunt exact cele citite de server/site/sec-mind.mjs:
 *  - users/{uid}/focus/{zi}   {date, updatedAt, focusMin, detoxMin, grown, withered, hits{pkg:n}, labels{pkg:nume}, sessions[≤ 50]}
 *  - users/{uid}/breath/{zi}  {date, updatedAt, minutes, sessions[{startAt, endAt, pattern, cycles, durationS, completed}]}
 *  - users/{uid}/detox/{zi}   {date, updatedAt, interceptions, byPack{01..04, 18, own: n}, guardOn, addictionOn, streakStart, slips}
 *    — doar numărători: paznicul nu ține niciodată textul prins;
 *  - users/{uid}/detox/words  {onSite: true, words[≤ 200], packs, letter, updatedAt} — DOAR cu Prefs.detoxWordsOnSite;
 *  - users/{uid}/listens/{zi} {date, updatedAt, minutes, count, skips, forja, items[≤ 300]}
 *  - users/{uid}/nudges/{zi}  {date, updatedAt, items[≤ 100]} — mesajele private ale Căștii fără titlu și text.
 */
object MindDocs {
    const val SESSIONS_PER_DAY = 50
    const val LISTENS_PER_DAY = 300
    const val NUDGES_PER_DAY = 100
    const val WORDS_MAX = 200
    const val LETTER_MAX = 4000
    /** Câte zile înapoi se reface oglinda la fiecare trecere. */
    const val DAYS_BACK = 14
    val PACK_CODES = listOf("01", "02", "03", "04", "18", "own")

    fun dayKey(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString()
    fun epochDayKey(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).toString()

    // ───────────── JSON-urile mici din Room (pachete [..], încercări {pkg:n}) — fără org.json, testabile pe JVM ─────────────

    private val PKG = Regex("^[A-Za-z0-9_.]{1,200}$")
    fun rulesJson(pkgs: List<String>): String = pkgs.filter { PKG.matches(it) }.distinct().joinToString(",", "[", "]") { "\"$it\"" }
    fun parseRules(json: String?): List<String> =
        Regex("\"([A-Za-z0-9_.]{1,200})\"").findAll(json ?: "").map { it.groupValues[1] }.distinct().toList()
    fun hitsJson(hits: Map<String, Int>): String =
        hits.filter { PKG.matches(it.key) && it.value > 0 }.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }
    fun parseHits(json: String?): Map<String, Int> =
        Regex("\"([A-Za-z0-9_.]{1,200})\"\\s*:\\s*(\\d{1,9})").findAll(json ?: "").associate { it.groupValues[1] to it.groupValues[2].toInt() }
    fun addHit(json: String?, pkg: String): String {
        if (!PKG.matches(pkg)) return json ?: "{}"
        val m = parseHits(json).toMutableMap(); m[pkg] = (m[pkg] ?: 0) + 1
        return hitsJson(m)
    }

    // ───────────── Istoriile zilnice din Prefs: pădurea „zi:crescuți:uscați” și opririle paznicului „zi:pachet:n” ─────────────

    /** Pădurea pe zile (epochDay → crescuți, uscați); păstrăm 40 de zile. */
    fun forestDecode(s: String?): Map<Long, Pair<Int, Int>> = (s ?: "").split(';').mapNotNull { part ->
        val f = part.split(':'); if (f.size != 3) return@mapNotNull null
        val d = f[0].toLongOrNull() ?: return@mapNotNull null
        d to ((f[1].toIntOrNull() ?: 0) to (f[2].toIntOrNull() ?: 0))
    }.toMap()
    fun forestEncode(m: Map<Long, Pair<Int, Int>>, today: Long): String =
        m.filterKeys { it > today - 40 }.toSortedMap().entries.joinToString(";") { "${it.key}:${it.value.first}:${it.value.second}" }

    /** Opririle paznicului pe zile și pachete (epochDay → pachet → n); păstrăm 40 de zile. Niciun text, doar codul pachetului. */
    fun hitsDecode(s: String?): Map<Long, Map<String, Int>> {
        val out = HashMap<Long, MutableMap<String, Int>>()
        for (part in (s ?: "").split(';')) {
            val f = part.split(':'); if (f.size != 3 || f[1] !in PACK_CODES) continue
            val d = f[0].toLongOrNull() ?: continue
            val n = f[2].toIntOrNull() ?: continue
            out.getOrPut(d) { HashMap() }[f[1]] = n
        }
        return out
    }
    fun hitsEncode(m: Map<Long, Map<String, Int>>, today: Long): String =
        m.filterKeys { it > today - 40 }.toSortedMap().flatMap { (d, packs) -> packs.filter { it.key in PACK_CODES && it.value > 0 }.map { "$d:${it.key}:${it.value}" } }.joinToString(";")
    fun addPackHit(s: String?, today: Long, pack: String): String {
        val code = if (pack in PACK_CODES) pack else "own"
        val m = hitsDecode(s).mapValues { it.value.toMutableMap() }.toMutableMap()
        val day = m.getOrPut(today) { HashMap() }; day[code] = (day[code] ?: 0) + 1
        return hitsEncode(m, today)
    }

    // ───────────── focus/{zi} ─────────────

    data class Session(
        val startAt: Long, val endAt: Long?, val kind: String, val plannedMin: Int, val rules: List<String>,
        val grown: Boolean, val withered: Boolean, val hits: Map<String, Int>, val endedBy: String?
    )

    /**
     * `seen`: pe fel, ultima clipă în care serviciul a atins jurnalul. O sesiune încă deschisă numără cel mult până la
     * max(capătul planificat, seen + [staleMs]) — una rămasă de la un serviciu oprit de Android nu crește la nesfârșit.
     */
    fun focusDoc(date: String, sessions: List<Session>, forest: Pair<Int, Int>?, labels: Map<String, String>, now: Long,
                 seen: Map<String, Long> = emptyMap(), staleMs: Long = 90_000L): Map<String, Any?> {
        val list = sessions.sortedBy { it.startAt }.take(SESSIONS_PER_DAY)
        fun openEnd(s: Session): Long {
            val planned = if (s.plannedMin > 0) s.startAt + s.plannedMin * 60_000L else s.startAt
            val alive = seen[s.kind]?.takeIf { it > 0 }?.let { it + staleMs } ?: now
            return minOf(now, maxOf(planned, alive))
        }
        fun minutes(kind: String) = list.filter { it.kind == kind }.sumOf { (((it.endAt ?: openEnd(it)) - it.startAt).coerceAtLeast(0) / 60_000L).toInt() }
        val hits = HashMap<String, Int>()
        for (s in list) for ((p, n) in s.hits) hits[p] = (hits[p] ?: 0) + n
        val pkgs = (list.flatMap { it.rules } + hits.keys).toSet()
        return mapOf(
            "date" to date, "updatedAt" to now,
            "focusMin" to minutes("focus"), "detoxMin" to minutes("detox"),
            "grown" to (forest?.first ?: list.count { it.grown }), "withered" to (forest?.second ?: list.count { it.withered }),
            "hits" to hits.toMap(),
            "labels" to pkgs.associateWith { (labels[it] ?: it).take(80) },
            "sessions" to list.map {
                mapOf("startAt" to it.startAt, "endAt" to it.endAt, "kind" to it.kind, "plannedMin" to it.plannedMin, "rules" to it.rules.take(30),
                    "grown" to it.grown, "withered" to it.withered, "hits" to it.hits, "endedBy" to it.endedBy)
            },
        )
    }

    // ───────────── breath/{zi} ─────────────

    data class Breath(val startAt: Long, val endAt: Long, val pattern: String, val cycles: Int, val durationS: Int, val completed: Boolean)

    fun breathDoc(date: String, list: List<Breath>, now: Long): Map<String, Any?> {
        val l = list.sortedBy { it.startAt }.take(SESSIONS_PER_DAY)
        return mapOf(
            "date" to date, "updatedAt" to now, "minutes" to l.sumOf { it.durationS } / 60,
            "sessions" to l.map { mapOf("startAt" to it.startAt, "endAt" to it.endAt, "pattern" to it.pattern.take(20), "cycles" to it.cycles, "durationS" to it.durationS, "completed" to it.completed) },
        )
    }

    // ───────────── detox/{zi} și detox/words ─────────────

    fun detoxDoc(date: String, byPack: Map<String, Int>, guardOn: Boolean, addictionOn: Boolean, streakStart: Long, slips: Int, now: Long): Map<String, Any?> {
        val packs = byPack.filter { it.key in PACK_CODES && it.value > 0 }
        return mapOf(
            "date" to date, "updatedAt" to now, "interceptions" to packs.values.sum(), "byPack" to packs,
            "guardOn" to guardOn, "addictionOn" to addictionOn, "streakStart" to streakStart.takeIf { it > 0 }, "slips" to slips,
        )
    }

    /** Cuvintele și scrisoarea — de apelat DOAR când detoxWordsOnSite e pornit. `packs` = pachetele cuprinse întregi în listă. */
    fun wordsDoc(words: String, letter: String, packLists: Map<String, List<String>>, now: Long): Map<String, Any?> {
        val list = words.split("\n", ",").map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.map { it.take(60) }.take(WORDS_MAX)
        val lower = list.map { it.lowercase() }.toSet()
        val packs = packLists.filter { (_, ws) -> ws.isNotEmpty() && ws.all { it.lowercase() in lower } }.keys.sorted()
        return mapOf("onSite" to true, "words" to list, "packs" to packs, "letter" to letter.trim().take(LETTER_MAX), "updatedAt" to now)
    }

    // ───────────── listens/{zi} ─────────────

    data class Listen(val at: Long, val title: String, val artist: String, val app: String?, val durS: Int, val forja: Boolean, val skip: Boolean, val kind: String?)

    fun listensDoc(date: String, items: List<Listen>, now: Long): Map<String, Any?> {
        val l = items.filter { it.title.isNotBlank() }.sortedBy { it.at }.takeLast(LISTENS_PER_DAY)
        val plays = l.filter { !it.skip }
        return mapOf(
            "date" to date, "updatedAt" to now, "minutes" to plays.sumOf { it.durS } / 60, "count" to plays.size,
            "skips" to l.size - plays.size, "forja" to plays.count { it.forja },
            "items" to l.map {
                mapOf("at" to it.at, "title" to it.title.take(120), "artist" to it.artist.take(120), "app" to it.app?.take(40), "durS" to it.durS,
                    "src" to if (it.forja) "forja" else "user", "event" to if (it.skip) "skip" else "play", "kind" to it.kind)
            },
        )
    }

    // ───────────── nudges/{zi} ─────────────

    data class NudgeItem(val at: Long, val id: String, val ctx: String, val channel: String, val title: String?, val body: String?, val private: Boolean, val outcome: String)

    fun nudgesDoc(date: String, items: List<NudgeItem>, now: Long): Map<String, Any?> = mapOf(
        "date" to date, "updatedAt" to now,
        "items" to items.sortedBy { it.at }.takeLast(NUDGES_PER_DAY).map {
            mapOf("at" to it.at, "id" to it.id, "ctx" to it.ctx, "channel" to it.channel, "private" to it.private, "outcome" to it.outcome,
                // Mesajele private (somnul, prietenii, caloriile) urcă doar ca fel: textul lor rămâne pe telefon.
                "title" to if (it.private) null else it.title?.take(160), "body" to if (it.private) null else it.body?.take(400))
        },
    )

    /** Amprenta unui document fără `updatedAt`: se rescrie doar ce s-a schimbat. */
    fun signature(doc: Map<String, Any?>): String = doc.filterKeys { it != "updatedAt" }.toSortedMap().toString().hashCode().toString(16)

    /** Zilele de refăcut: ultimele [DAYS_BACK], azi prima. */
    fun recentDays(now: Long, zone: ZoneId = ZoneId.systemDefault()): List<String> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return (0 until DAYS_BACK).map { today.minusDays(it.toLong()).toString() }
    }
}
