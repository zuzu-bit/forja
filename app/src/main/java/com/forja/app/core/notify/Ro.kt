package com.forja.app.core.notify

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Gramatica românească a numerelor din notificări (fără Android, testată pe JVM).
 *
 * Pluralul după regula CLDR: 1 → singular („1 zi”); 0 și n % 100 ∈ 1..19 → „n zile”; altfel „n de zile”
 * („20 de zile”, „100 de zile”, „101 zile”, „1 250 de poze”). Miile se despart cu spațiu: „1 250”.
 */
object Ro {
    /** „de” între număr și substantiv: 20–99 și 00 în ultimele două cifre (dar nu la 0). */
    fun needsDe(n: Long): Boolean {
        if (n == 0L) return false
        val r = abs(n) % 100
        return r !in 1..19
    }

    /** „1 zi” · „3 zile” · „20 de zile” · „1 250 de poze”. `one`/`many` pot purta și un adjectiv acordat: „copac nou”/„copaci noi”. */
    fun count(n: Long, one: String, many: String): String = when {
        n == 1L -> "1 $one"
        needsDe(n) -> "${thousands(n)} de $many"
        else -> "${thousands(n)} $many"
    }

    fun count(n: Int, one: String, many: String): String = count(n.toLong(), one, many)

    /** „1 250”, „12 345”, „999”. */
    fun thousands(n: Long): String {
        val neg = n < 0
        val digits = abs(n).toString()
        val sb = StringBuilder()
        digits.forEachIndexed { i, c ->
            if (i > 0 && (digits.length - i) % 3 == 0) sb.append(' ')
            sb.append(c)
        }
        return if (neg) "-$sb" else sb.toString()
    }

    fun thousands(n: Int): String = thousands(n.toLong())

    /** Ordinal masculin: „primul”, „al 2-lea”, „al 25-lea”. */
    fun ordM(n: Long): String = if (n == 1L) "primul" else "al ${thousands(n)}-lea"

    /** Ordinal feminin: „prima”, „a 3-a”. */
    fun ordF(n: Long): String = if (n == 1L) "prima" else "a ${thousands(n)}-a"

    /** Kilometri: o zecimală sub 10 km („3,4”, „5”), fără zecimale de la 10 în sus („12”). */
    fun km(km: Double): String {
        if (km >= 10.0) return thousands(km.roundToInt().toLong())
        val tenths = (km * 10).roundToInt()
        return if (tenths % 10 == 0) (tenths / 10).toString() else String.format(Locale.ROOT, "%d,%d", tenths / 10, tenths % 10)
    }

    /** Durată: „45 min”, „7 h 40 min”, „8 h”. */
    fun hm(minutes: Int): String {
        val m = minutes.coerceAtLeast(0)
        if (m < 60) return "$m min"
        val h = m / 60
        val r = m % 60
        return if (r == 0) "$h h" else "$h h $r min"
    }

    /** Ora ceasului: „07:30”. */
    fun clock(hour: Int, minute: Int): String = String.format(Locale.ROOT, "%02d:%02d", hour, minute)

    /** Distanța până la un prieten, în trepte de 100 m: „300 m”, apoi „1 km”, „1,2 km” (950–999 m → „1 km”, nu „1000 m”). */
    fun distance(meters: Int): String {
        val rounded = ((meters + 50) / 100).coerceAtLeast(1) * 100
        if (rounded < 1000) return "$rounded m"
        return "${km(maxOf(meters, 1000) / 1000.0)} km"
    }

    /** Taie un text lung cu „…” (nume, locuri, planuri). */
    fun clip(s: String, max: Int): String {
        val t = s.trim()
        return if (t.length <= max) t else t.take(max - 1).trimEnd() + "…"
    }

    /** Prima literă mare (titlul și corpul încep propoziții): „al 25-lea loc” → „Al 25-lea loc”. */
    fun capitalize(s: String): String = if (s.isEmpty()) s else s.substring(0, 1).uppercase(Locale.ROOT) + s.substring(1)

    /** Spațiu care nu se rupe la capăt de rând (U+00A0). */
    const val NBSP = '\u00A0'

    // Numărul și unitatea lui: „45 min”, „3,4 km”, „640 kcal”, „400 m”, „15 %” (dar nu „2 mese”: după unitate nu urmează literă).
    private val UNIT = Regex("""(?<=\d) (?=(?:km|min|kcal|kg|cm|h|m|g|%)(?![\p{L}\p{N}]))""")
    // Durata rămâne întreagă: „7 h 32 min” (după ce „7 h” s-a lipit).
    private val HOUR_MIN = Regex("""(?<=\d\u00A0h) (?=\d)""")
    // Miile: „1 250” (Ro.thousands le desparte cu spațiu obișnuit).
    private val GROUP = Regex("""(?<=\d) (?=\d{3}(?!\d))""")

    /**
     * Doar pentru afișare (notificarea, ecoul de pe Panou): lipește numărul de unitatea lui, ca rândul să nu se rupă
     * între „45” și „min”. Textul reținut și testat rămâne cu spații obișnuite.
     */
    fun glue(s: String): String {
        if (s.isEmpty() || s.none { it.isDigit() }) return s
        val nb = NBSP.toString()
        return s.replace(GROUP, nb).replace(UNIT, nb).replace(HOUR_MIN, nb)
    }
}
