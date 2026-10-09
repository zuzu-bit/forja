package com.forja.app.core.sync

/**
 * Mesaje de notificare pentru Soldățelul — rotire dinamică, bazată pe context.
 *
 * În loc de "FORJA / activ" static, notificarea foreground service-ului
 * rotește prin mesaje cu personalitate, adaptate pe:
 * - ora zilei (dimineață / prânz / seară / noapte)
 * - stare de antrenament sau somn (dacă se detectează)
 *
 * Fiecare mesaj e scurt (~60 chars), cu un emoji, și sună ca un
 * antrenor-motivator cu personalitate militară. Fără repetiții imediate.
 */
object SoldierMessages {

    /** Intervalul de rotație — ~10 minute între schimbări de mesaj. */
    const val ROTATE_MS = 10 * 60_000L

    // ── Dimineață (06:00 – 11:59) ────────────────────────────────────────────
    private val morning = listOf(
        "Soldat, tabăra e trează! Misiunea începe. 🪖",
        "Disciplina de dimineața e armura zilei. 💪",
        "Soldățelul zice: sus pe picioare! ⭐",
        "Un soldat bun nu așteaptă ordine. 🎯",
        "Cotizorul e încălzit. Tu ești gata? 🔥",
        "Misiunea de azi: bate-ți recordul de ieri. 📈",
        "Fiecare pas de dimineața face soldatul mai tare. 👣",
        "Comandantul e pregătit. Tu? 🪖",
        "Ziua de azi e a ta, soldat. Ia-o! ⚡",
        "Treptele de dimineața = kilometri de mâine. 🏔️",
    )

    // ── Prânz (12:00 – 17:59) ────────────────────────────────────────────────
    private val afternoon = listOf(
        "Misiunea avansează, soldat. Continua! 🪖",
        "Un pahar de apă, soldat. Armura are nevoie. 💧",
        "Soldățelul e mândru de ritmul tău. ⭐",
        "La jumătatea drumului. Nu te opri aici. 🎯",
        "Soldatul nu se oprește la primul obstacol. 🔥",
        "Efortul de la prânz = investiția pentru seară. 📈",
        "Comandantul zice: încă o rundă! 💪",
        "Ține ritmul, soldat. Vârful e aproape. ⚡",
        "Nu lăsa inercia să-ți ia locul. 🏋️",
        "Soldățelul vede progresul. Continua! 🪖",
    )

    // ── Seară (18:00 – 21:59) ────────────────────────────────────────────────
    private val evening = listOf(
        "Misiunea de azi e terminată. Bine muncit, soldat! ⭐",
        "Recuperarea e la fel de importantă ca marșul. 🛡️",
        "Soldățelul zice: relaxează-te, soldat. 😌",
        "Un soldat bun se odihnește ca să fie mai tare. 🌙",
        "Misiune îndeplinită. Te-ai câștigat odihna. 🪖",
        "Corpul tău muncește și noaptea. Lasă-l. 💤",
        "Stai puțin, soldat. Efortul de azi s-a plătit. 🏆",
        "Seara e pentru recuperare. Mâine au noi misiuni. 🌅",
        "Soldatul bun își repară armura în seara. 🔧",
        "Bine făcut, soldat. Soldățelul aplaudă. 👏",
    )

    // ── Noapte (22:00 – 05:59) ───────────────────────────────────────────────
    private val night = listOf(
        "Somnul e misiunea secretă a soldatului. 🌙",
        "Soldățelul veghează noaptea. Doarme liniștit. 🪖",
        "Faza de recuperare e activă. Somn bun! 💤",
        "Noaptea e tabăra. Aici se construiește armura. 🏕️",
        "Misiunea de noapte: 8 ore. Fără scuze. ⏰",
        "Și generalul doarme. E rândul tău, soldat. 🌙",
        "Corpul tău se antrenează în somn. Lasă-l. 💪",
        "Tabăra de noapte e liniștită. Recuperează-te. 🌌",
        "Soldatul odihnit e soldatul invincibil. 🛡️",
        "Soldățelul e în pui. E timpul tău. 🌙",
    )

    // ── Antrenament (activ) ───────────────────────────────────────────────────
    private val training = listOf(
        "Ține ritmul, soldat! Mai aproape de vârf! 🔥",
        "Fiecare repetare e un dușman învins! ⚔️",
        "Soldățelul urlă: MAI MULT! 💪",
        "Durerea de azi e puterea de mâine! ⚡",
        "Nu te opri! Comandantul nu se oprește! 🪖",
        "Corpul tău e arma. Antreneaz-o! 🏋️",
        "Soldatul se forjează în foc! 🔥",
        "Încă o repetare! Soldățelul numără! ⭐",
    )

    // ── Somn (sesiune activă) ────────────────────────────────────────────────
    private val sleep = listOf(
        "Monitorizez somnul, soldat. Doarme liniștit. 🌙",
        "Soldățelul veghează. Tu odihnește-te. 🪖",
        "Recuperarea e în desfășurare. Somn bun! 💤",
        "Tabăra de noapte e activă. Recuperează-te. 🏕️",
        "Soldatul doarme, corpul se repară. 🛡️",
        "Noapte bună, soldat. Mâine e o zi nouă. 🌅",
    )

    // ── Generic (fallback / personalitate) ───────────────────────────────────
    private val generic = listOf(
        "Soldățelul e aici. Gata de următoarea misiune? 🪖",
        "Disciplina te separă de ceilalți, soldat. ⭐",
        "Un pas înainte, în fiecare zi. Asta e tot. 🎯",
        "Soldatul bun nu se plânge. Se antrenează. 💪",
        "Astăzi > ieri. Asta e regula. 📈",
        "Soldățelul te vede. Continua! ⚡",
        "FORJA te forjează. Zi de zi. 🔨",
        "Ești mai tare decât crezi, soldat. 🪖",
    )

    /**
     * Alege un mesaj nou, evitând textul anterior.
     *
     * @param lastText mesajul anterior (ca să nu se repete imediat)
     * @param trainingActive dacă e o sesiune de antrenament activă
     * @param sleepActive dacă e o sesiune de somn activă
     * @return mesajul ales
     */
    fun pickForNow(
        lastText: String? = null,
        trainingActive: Boolean = false,
        sleepActive: Boolean = false,
    ): String {
        val pool: List<String> = when {
            trainingActive -> training
            sleepActive && isNight() -> sleep
            else -> timeOfDayPool()
        }
        val candidates = pool.filter { it != lastText }
        val source = if (candidates.isEmpty()) pool else candidates
        return source.random()
    }

    /** Determină setul de mesaje pe baza orei curente. */
    private fun timeOfDayPool(): List<String> {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when {
            hour in 6..11 -> morning
            hour in 12..17 -> afternoon
            hour in 18..21 -> evening
            else -> night
        }
    }

    private fun isNight(): Boolean {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return hour >= 22 || hour < 6
    }
}
