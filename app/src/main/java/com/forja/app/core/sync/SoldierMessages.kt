package com.forja.app.core.sync

/**
 * Mesaje de notificare pentru bucla de keepalive — voice-ul FORJA, nu un
 * app de fitness generic. Tonul e cel din ServiceCopy: FORJA vorbește la
 * persoana I, propoziții scurte, metaforă militară subtilă, „tu" (informal).
 *
 * Se rotește la fiecare ~10 min din bucla SyncService. Fără repetiții imediate.
 */
object SoldierMessages {

    /** Intervalul de rotație — ~10 minute între schimbări de mesaj. */
    const val ROTATE_MS = 10 * 60_000L

    // ── Dimineață (05:00 – 11:59) ────────────────────────────────────────────
    private val morning = listOf(
        "Ziua începe. Soldatul e gata de datorie. 🪖",
        "Dimineața face soldatul. Misiunea pornește acum.",
        "Un soldat bun nu așteaptă alarma. Pornește înainte ea.",
        "Cafeaua e prima misiune a zilei. Apoi vine restul. ☕",
        "Tabăra e trează. Tu ești?",
        "Astăzi e al tău. Ia-l. ⚡",
        "Primul pas e mereu cel mai greu. Pornește totuși. 👣",
        "Eu număr orele. Tu le faci. Continua.",
        "Soldatul se ridică. Restul lumii visează încă. 🪖",
        "Un mic pas înainte, soldat. Asta e tot ce trebuie azi.",
    )

    // ── Prânz (12:00 – 17:59) ────────────────────────────────────────────────
    private val afternoon = listOf(
        "Misiunea avansează. Ritmul e al tău. 🪖",
        "Apă, soldat. Armura fără apă e doar metal. 💧",
        "Prânzul e o repausă, nu un refugiu.",
        "La jumătatea drumului. Generalul nu se oprește.",
        "Eu număr repetările. Tu respiră. Continua.",
        "Efortul de azi e muniția de mâine. 📈",
        "O pauză scurtă, soldat. Apoi se continuă. ⏸️",
        "Soldatul nu negociază cu inercia. Mișcă-te.",
        "Făceai mai bine dimineața. Acum e mai bine ca dimineața.",
        "FORJA e în post. Tu — în mișcare. 💪",
    )

    // ── Seară (18:00 – 21:59) ────────────────────────────────────────────────
    private val evening = listOf(
        "Misiunea de azi e terminată. Bine muncit. ⭐",
        "Recuperarea e la fel de importantă ca marșul. 🛡️",
        "Soldatul bun își repară armura seara.",
        "Te-ai câștigat odihna. Ia-o.",
        "Azi ai fost mai bun decât ieri. Asta e tot ce contează. 🏆",
        "FORJA aplaudă. Rareori. Dar azi da. 👏",
        "Seara e pentru mușchi, minte și somn. Toate trei.",
        "Soldatul își pune pelerina. Tu — pe canapea. Relax. 😌",
        "Misiune îndeplinită. Următoarea pornește mâine. 🌅",
        "Eu țin socoteala. Tu te odihnești. Simplu.",
    )

    // ── Noapte (22:00 – 04:59) ───────────────────────────────────────────────
    private val night = listOf(
        "Veghea nopții e activă. Doarme liniștit. 🌙",
        "Corpul se antrenează în somn. Lasă-l. 💤",
        "Soldatul odihnit bate pe cel obosit. Fiecare dată.",
        "Misiunea de noapte: doarme. Fără scuze. ⏰",
        "Noaptea e tabăra. Aici se construiește armura. 🏕️",
        "Și generalul doarme. E rândul tău. 🌙",
        "Eu veghez. Tu doarme. Asta e dealul.",
        "Soldatul doarme, misiunea continuă. 💪",
        "8 ore. Nu 7. Nu 6. 8. Soldatul respectă ordinul.",
        "Fără alarme, fără misiuni. Doar somn. 🌌",
    )

    // ── Antrenament (sesiune activă) ─────────────────────────────────────────
    private val training = listOf(
        "Aici se forjează. Continua. 🔥",
        "Durerea e slăbiciunea care pleacă. ⚔️",
        "Mușchii se fac aici, nu în canapea.",
        "Soldatul nu negociază. Încă o repetare. 💪",
        "Eu urlu. Tu faci. Continua. ⚡",
        "Corpul tău e arma. Antreneaz-o. 🏋️",
        "Generalul nu se oprește la prima grea. Tu nici.",
        "Încă o rundă, soldat. Soldatul numără: una. ⭐",
    )

    // ── Somn (sesiune activă) ────────────────────────────────────────────────
    private val sleep = listOf(
        "Monitorizez. Tu doarme. Simplu. 🌙",
        "Soldatul doarme, misiunea continuă. 💤",
        "Zgomotele le notez eu. Tu nu te trezești. 📋",
        "Tabăra e liniștită. Recuperează-te. 🏕️",
        "Noapte bună, soldat. Mâine e o zi nouă. 🌅",
        "Eu țin ușa închisă. Tu doarme. 🛡️",
    )

    // ── Generic (fallback / personalitate) ───────────────────────────────────
    private val generic = listOf(
        "Disciplina e diferența dintre vis și realitate. 🎯",
        "Un pas înainte, în fiecare zi. Asta e tot.",
        "FORJA te forjează. Zi de zi. 🔨",
        "Ești mai tare decât crezi, soldat.",
        "Soldatul bun nu se plânge. Se antrenează. 💪",
        "Misiunea ta: un pas înainte. Astăzi. ⚡",
        "Soldatul e mic, misiunea e mare. La fel cum ești tu. 🪖",
        "FORJA e în post. Tu — în mișcare.",
    )

    /** Titluri care se rotesc ocazional (70% FORJA, 30% variante). */
    private val titles = listOf(
        "FORJA", "FORJA", "FORJA", "FORJA", "FORJA", "FORJA", "FORJA",
        "Soldățelul", "FORJA · Veghe",
    )

    /**
     * Alege un mesaj + titlu nou, evitând textul anterior.
     *
     * @param lastText mesajul anterior (ca să nu se repete imediat)
     * @param trainingActive dacă e o sesiune de antrenament activă
     * @param sleepActive dacă e o sesiune de somn activă
     * @return pereche (titlu, text)
     */
    fun pickForNow(
        lastText: String? = null,
        trainingActive: Boolean = false,
        sleepActive: Boolean = false,
    ): Pair<String, String> {
        val pool: List<String> = when {
            trainingActive -> training
            sleepActive && isNight() -> sleep
            else -> timeOfDayPool()
        }
        val candidates = pool.filter { it != lastText }
        val text = (if (candidates.isEmpty()) pool else candidates).random()
        val title = titles.random()
        return title to text
    }

    /** Determină setul de mesaje pe baza orei curente. */
    private fun timeOfDayPool(): List<String> {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when {
            hour in 5..11 -> morning
            hour in 12..17 -> afternoon
            hour in 18..21 -> evening
            else -> night
        }
    }

    private fun isNight(): Boolean {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return hour >= 22 || hour < 5
    }
}
