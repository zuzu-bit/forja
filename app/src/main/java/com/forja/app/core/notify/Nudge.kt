package com.forja.app.core.notify

import java.util.Calendar

/**
 * Casca alege și scrie (notifications-design.md §E, §H) — obiect pur, fără rețea și fără Android.
 *
 *  - [pick]: filtrează după condiție și după placeholderele prezente, exclude variantele trimise în ultimele 7 zile
 *    (ultimele 20 pe canal), alege determinist după zi (aceeași zi → aceeași variantă). Când tot bazinul a fost folosit
 *    recent, alege varianta folosită cel mai demult, nu tace.
 *  - [render]: completează placeholderele în format românesc (plural cu „de”, ordinale, mii cu spațiu), aplică vocea
 *    și verifică lungimile; o variantă care nu încape nu se alege.
 */
object Nudge {
    const val TITLE_MAX = 40
    const val BODY_MAX = 90
    /** Titlul notificării permanente (restrânsă, lângă pictograma mare): TONE.md, 28 de caractere. */
    const val SYNC_TITLE_MAX = 28
    /** Anti-repetare: nicio variantă de două ori în 7 zile. */
    const val REPEAT_WINDOW_MS = 7L * 24 * 3600_000L
    const val RECENT_PER_CHANNEL = 20

    private val TOKEN = Regex("""\{([a-z_]+)(?:\|([^|}]+)\|([^}]+)|@([mf]))?\}""")

    /** Numele placeholderelor dintr-un text. */
    fun keysOf(text: String): Set<String> = TOKEN.findAll(text).map { it.groupValues[1] }.toSet()

    /** Valoarea zilei („sămânța”): an × 366 + ziua din an, ca [com.forja.app.core.designsystem.components.Tone.ofDay]. */
    fun daySeed(now: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        return cal.get(Calendar.YEAR) * 366L + cal.get(Calendar.DAY_OF_YEAR)
    }

    /** Varianta generică a lui Tone.ofDay: stabilă toată ziua, alta mâine; `salt` separă contextele. */
    fun <T> ofDay(pool: List<T>, seed: Long, salt: Int = 0): T? {
        if (pool.isEmpty()) return null
        return pool[Math.floorMod(seed + salt, pool.size.toLong()).toInt()]
    }

    // ─────────────────────────── Placeholdere ───────────────────────────

    /** Toate valorile prezente pentru `d`. Lipsa sau 0 înseamnă „nu există” — nu scriem niciodată „0 km”. */
    fun values(d: NudgeData): Map<String, Value> {
        val v = HashMap<String, Value>()
        fun put(k: String, text: String?) { if (!text.isNullOrBlank()) v[k] = Value(text.trim()) }
        fun num(k: String, n: Int?) { if (n != null && n > 0) v[k] = Value(Ro.thousands(n), n.toLong()) }
        fun kmv(k: String, km: Double) { if (km >= 0.1) v[k] = Value(Ro.km(km)) }

        d.name?.trim()?.split(' ')?.firstOrNull { it.isNotBlank() }?.let { put("nume", Ro.clip(it, 12)) }
        kmv("km", d.kmToday)
        kmv("km_ieri", d.kmYesterday)
        kmv("km_sapt", d.kmWeek)
        kmv("km_medie", d.kmAvg7)
        if (d.weekTargetKm > 0) num("ramas", kotlin.math.ceil(d.weekTargetKm - d.kmWeek).toInt())
        if (d.mealsToday >= 1 && d.kcalLeft != null) num("kcal_ramase", d.kcalLeft)
        num("mese_azi", d.mealsToday)
        bilant(d)?.let { put("bilant", it) }
        d.workoutToday?.let { put("antrenament", Ro.clip(it, 20)) }
        d.lastPlan?.let { put("plan", Ro.clip(it, 20)) }
        num("seturi", d.setsToday)
        num("focus_min", d.focusMinToday)
        num("copaci", d.treesToday)
        num("focus_sesiune", d.focusSessionMin)
        num("copaci_noi", d.focusSessionTrees)

        d.longest?.takeIf { it.current >= 2 }?.let {
            num("serie_zile", it.current); put("tip_serie", it.kind.label)
        }
        d.risk?.let { num("serie_risc", it.current) }
        d.streak(StreakKind.Meals)?.takeIf { it.current >= 2 }?.let { num("serie_mese", it.current) }
        d.streak(StreakKind.Walk)?.takeIf { it.current >= 2 }?.let { num("serie_mers", it.current) }
        d.streak(StreakKind.Workout)?.takeIf { it.current >= 2 }?.let { num("serie_antrenament", it.current) }
        val old = d.broke?.brokeAt ?: d.oldStreak
        if (old >= 3) num("serie_veche", old)
        d.milestone?.let { num("serie_prag", it.current); put("tip_serie", it.kind.label) }
        if (d.recordOld >= 3) num("record_vechi", d.recordOld)

        val night = d.sleep ?: d.lastNight
        if (night != null) {
            if (night.minutes > 0) put("somn_h", Ro.hm(night.minutes))
            if (night.deepMin > 0) put("profund", Ro.hm(night.deepMin))
            if (night.coverageMin > 0 && night.totalMin > night.coverageMin) {
                put("acoperire", "${Ro.hm(night.coverageMin)} din ${Ro.hm(night.totalMin)}")
            }
            num("evenimente", night.events)
        }
        put("ora_trezire", d.wake)
        d.minutesToBedtime?.takeIf { it in 5..90 }?.let { num("min_stingere", it) }
        put("ora", Ro.clock(d.hour, d.minute))

        num("locuri", d.placesTotal)
        val p = d.place ?: d.newPlaceToday
        if (p != null) {
            if (p.name.isNotBlank()) put("loc", Ro.clip(p.name, 18))
            if (p.stayMin > 0) put("ore_stat", Ro.hm(p.stayMin))
            if (p.visits >= 2) num("vizite", p.visits)
            if (d.place != null) num("locuri", p.total)
        }
        val f = d.friend ?: d.friendMoving
        if (f != null) {
            f.name.trim().split(' ').firstOrNull { it.isNotBlank() }?.let { put("prieten", Ro.clip(it, 12)) }
            f.distanceM?.takeIf { it > 0 }?.let { put("distanta", Ro.distance(it)) }
            put("stare_prieten", stateText(f.state))
        }
        num("mese_gasite", d.mealsFound)
        num("zile_absent", d.absentDays)
        put("categorii", d.categories)
        return v
    }

    /** „3,4 km, 2 mese, 25 min de focus” — doar ce există azi; null dacă ziua e goală. */
    fun bilant(d: NudgeData): String? {
        val parts = buildList {
            if (d.kmToday >= 0.1) add("${Ro.km(d.kmToday)} km")
            if (d.mealsToday > 0) add(Ro.count(d.mealsToday, "masă", "mese"))
            if (d.workoutToday != null) add("un antrenament")
            if (d.focusMinToday > 0) add("${Ro.thousands(d.focusMinToday)} min de focus")
        }
        return if (parts.isEmpty()) null else parts.joinToString(", ")
    }

    /** Starea unui prieten, la persoana a III-a, fără gen: „Aleargă”, „Merge pe jos”, „Pe bicicletă”. */
    fun stateText(state: String?): String? = when (state) {
        "walk" -> "Merge pe jos"
        "run" -> "Aleargă"
        "ride" -> "Pe bicicletă"
        "gym" -> "La sală"
        else -> null
    }

    // ─────────────────────────── Randare ───────────────────────────

    /** Textul completat, sau null dacă lipsește vreun placeholder. */
    fun fill(text: String, values: Map<String, Value>): String? {
        var missing = false
        val out = TOKEN.replace(text) { m ->
            val key = m.groupValues[1]
            val value = values[key]
            if (value == null) { missing = true; return@replace "" }
            val one = m.groupValues[2]
            val many = m.groupValues[3]
            val ord = m.groupValues[4]
            when {
                one.isNotEmpty() -> value.n?.let { Ro.count(it, one, many) } ?: run { missing = true; "" }
                ord == "m" -> value.n?.let { Ro.ordM(it) } ?: run { missing = true; "" }
                ord == "f" -> value.n?.let { Ro.ordF(it) } ?: run { missing = true; "" }
                else -> value.text
            }
        }
        return if (missing) null else out
    }

    /** Varianta completată, cu vocea aplicată, sau null (placeholder lipsă, prea lungă, condiție falsă). */
    fun render(t: Template, d: NudgeData, values: Map<String, Value> = values(d)): Rendered? {
        if (!t.cond(d)) return null
        val bodySrc = t.voices[d.voice] ?: t.body
        val title = fill(t.title, values)?.let(Ro::capitalize) ?: return null
        val body = fill(bodySrc, values)?.let(Ro::capitalize) ?: return null
        val titleMax = if (t.context == NudgeContext.SyncOngoing) SYNC_TITLE_MAX else TITLE_MAX
        if (title.length > titleMax || body.length > BODY_MAX) return null
        val keys = t.placeholders
        return Rendered(
            id = t.id, context = t.context, title = title, body = body, pose = t.pose,
            private = keys.isNotEmpty() || t.context in ALWAYS_PRIVATE,
            localOnly = keys.any { it in LOCAL_ONLY_KEYS } || t.context in LOCAL_ONLY
        )
    }

    private val ALWAYS_PRIVATE = setOf(NudgeContext.SleepReport, NudgeContext.FriendNear, NudgeContext.NewFriend)
    private val LOCAL_ONLY = setOf(NudgeContext.SleepReport, NudgeContext.FriendNear, NudgeContext.NewFriend)
    private val LOCAL_ONLY_KEYS = setOf("somn_h", "profund", "acoperire", "evenimente", "kcal_ramase", "prieten", "distanta", "stare_prieten")

    /** O trimitere reținută pentru anti-repetare. */
    data class Sent(val id: String, val at: Long)

    /**
     * Alege varianta: întâi cele cu date (dacă nu merge niciuna, rezervele), fără cele trimise în ultimele 7 zile,
     * determinist după `seed`. Dacă toate au fost folosite recent, cea folosită cel mai demult.
     */
    fun pick(
        pool: List<Template>,
        d: NudgeData,
        recent: List<Sent>,
        seed: Long,
        salt: Int = 0
    ): Rendered? {
        val values = values(d)
        val ok = pool.mapNotNull { t -> render(t, d, values)?.let { t to it } }
        if (ok.isEmpty()) return null
        val personal = ok.filter { !it.first.reserve }
        val tier = personal.ifEmpty { ok }
        val cutoff = d.now - REPEAT_WINDOW_MS
        val lastUse = HashMap<String, Long>()
        recent.forEach { s -> if (s.at >= (lastUse[s.id] ?: Long.MIN_VALUE)) lastUse[s.id] = s.at }
        val fresh = tier.filter { (lastUse[it.first.id] ?: Long.MIN_VALUE) < cutoff }
        if (fresh.isNotEmpty()) return ofDay(fresh, seed, salt)?.second
        // Bazin epuizat: cea mai veche folosire câștigă (nu tăcem).
        return tier.minByOrNull { lastUse[it.first.id] ?: Long.MIN_VALUE }?.second
    }

    /** Scurtătura pentru un context din bancă. */
    fun pick(context: NudgeContext, d: NudgeData, recent: List<Sent>, seed: Long = daySeed(d.now)): Rendered? =
        pick(NudgeBank.of(context), d, recent, seed, salt = context.ordinal * 7)

    /** Varianta fără date a unui context (versiunea publică de pe ecranul de blocare). */
    fun publicOf(context: NudgeContext, d: NudgeData, seed: Long = daySeed(d.now)): Rendered? {
        val pool = NudgeBank.of(context).filter { it.reserve && it.placeholders.isEmpty() }
        val ok = pool.mapNotNull { render(it, d, emptyMap()) }
        return ofDay(ok, seed, context.ordinal * 7)
    }
}
