package com.forja.app.core.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract

/** Un contact din agenda telefonului — doar numele și numărul, citite la cerere, niciodată stocate. */
data class Contact(val name: String, val number: String)

/**
 * Agenda: căutare după nume rostit („ion", „maria popescu", „tata").
 * Nimic nu pleacă de pe telefon — potrivirea se face local, pe loc.
 */
object Contacts {

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Număr de telefon rostit („0722 123 456", „112") → cifre; altfel null. */
    fun asNumber(spoken: String): String? {
        val digits = spoken.replace(Regex("[\\s\\-.()]"), "")
        return if (digits.matches(Regex("\\+?\\d{3,15}"))) digits else null
    }

    fun all(context: Context): List<Contact> {
        if (!hasPermission(context)) return emptyList()
        val out = LinkedHashMap<String, Contact>()
        val cols = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID
        )
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols, null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { c ->
                val iName = c.getColumnIndex(cols[0])
                val iNum = c.getColumnIndex(cols[1])
                val iType = c.getColumnIndex(cols[2])
                val iId = c.getColumnIndex(cols[3])
                while (c.moveToNext()) {
                    val name = c.getString(iName)?.trim().orEmpty()
                    val num = c.getString(iNum)?.trim().orEmpty()
                    if (name.isEmpty() || num.isEmpty()) continue
                    val id = c.getString(iId) ?: name
                    val type = if (iType >= 0) c.getInt(iType) else 0
                    val existing = out[id]
                    // Preferăm numărul de mobil (SMS-urile ajung acolo).
                    if (existing == null || type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) {
                        out[id] = Contact(name, num)
                    }
                }
            }
        } catch (_: Exception) { }
        return out.values.toList()
    }

    /** Cel mai bun contact pentru numele rostit, sau null dacă nimic nu seamănă. */
    fun find(context: Context, spoken: String): Contact? = find(all(context), spoken)

    fun find(contacts: List<Contact>, spoken: String): Contact? = best(contacts, spoken)?.first

    /** Cel mai bun contact și scorul lui (0–100). */
    private fun best(contacts: List<Contact>, spoken: String): Pair<Contact, Int>? {
        val q = VoiceText.normalize(spoken)
        if (q.isBlank()) return null
        var best: Contact? = null
        var bestScore = 0
        for (c in contacts) {
            val s = score(VoiceText.normalize(c.name), q)
            if (s > bestScore) { bestScore = s; best = c }
        }
        val b = best ?: return null
        return if (bestScore >= 50) b to bestScore else null
    }

    /**
     * „ion ajung in zece minute" → (Ion, „ajung in zece minute"): încearcă primele 3, 2, apoi 1 cuvinte
     * ca nume de contact. Null dacă niciun prefix nu seamănă cu cineva din agendă.
     */
    fun resolvePrefix(contacts: List<Contact>, phrase: String): Pair<Contact, String>? {
        val words = VoiceText.normalize(phrase).split(" ").filter { it.isNotBlank() }
        if (words.isEmpty()) return null
        for (n in minOf(3, words.size) downTo 1) {
            val name = words.take(n).joinToString(" ")
            val (c, sc) = best(contacts, name) ?: continue
            // Cerem potrivire clară: un cuvânt = exact un prenume/nume; mai multe = numele întreg.
            if (sc < (if (n == 1) 70 else 80)) continue
            val rest = phrase.trim().split(Regex("\\s+")).drop(n).joinToString(" ")
            return c to rest
        }
        return null
    }

    private fun score(name: String, q: String): Int {
        if (name == q) return 100
        val tokens = name.split(" ").filter { it.isNotBlank() }
        val qTokens = q.split(" ").filter { it.isNotBlank() }
        if (qTokens.size > 1 && qTokens.all { qt -> tokens.any { it == qt } }) return 95
        if (tokens.any { it == q }) return 90
        if (name.startsWith(q)) return 80
        if (q.length >= 3 && tokens.any { it.startsWith(q) }) return 70
        if (q.length >= 4 && name.contains(q)) return 60
        val maxDist = when { q.length >= 6 -> 2; q.length >= 4 -> 1; else -> 0 }
        if (maxDist > 0 && tokens.any { levenshtein(it, q) <= maxDist }) return 50
        if (qTokens.size > 1 && qTokens.first().length >= 4 && tokens.any { levenshtein(it, qTokens.first()) <= 1 }) return 50
        return 0
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
