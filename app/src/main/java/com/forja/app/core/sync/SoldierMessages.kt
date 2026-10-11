package com.forja.app.core.sync

import java.util.Calendar

/**
 * Mesaje de notificare pentru toate serviciile FORJA — voice-ul Soldățelului.
 *
 * Se rotește periodic din bucla SyncService și din fiecare serviciu de fundal.
 * Fără repetiții imediate (ține ultimele 3 alegeri).
 * Adaptare pe oră: dimineață / prânz / seară / noapte.
 * Adapte pe context: somn / focus / antrenament / colectare / generic.
 *
 * Regula: activitățile sensibile (mic, cameră, GPS, ecran) au text FIX
 * care descrie exact ce se întâmplă. Aici e doar pentru status non-sensitive.
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
        "Soldatul se ridică. Restul lumii visează încă. 🪖",
        "Un mic pas înainte, soldat. Asta e tot ce trebuie azi.",
        "Hidratează-te. Soldatul cu apă bate pe cel setos. 💧",
        "5 minute de stretching = 50% din armură. Fă-le.",
        "Ziua e lungă, soldat. Pașii mici te duc departe. 🥾",
    )

    // ── Prânz (12:00 – 17:59) ────────────────────────────────────────────────
    private val afternoon = listOf(
        "Misiunea avansează. Ritmul e al tău. 🪖",
        "Apă, soldat. Armura fără apă e doar metal. 💧",
        "Prânzul e o repausă, nu un refugiu.",
        "La jumătatea drumului. Generalul nu se oprește.",
        "Efortul de azi e muniția de mâine. 📈",
        "O pauză scurtă, soldat. Apoi se continuă. ⏸️",
        "Soldatul nu negociază cu inercia. Mișcă-te.",
        "FORJA e în post. Tu — în mișcare. 💪",
        "Un pahar de apă. Acum. Nu „mai târziu". 💧",
        "Mușchii se antrenează și la pahar de apă. Hidratează-te.",
        "Ochii de la ecran, soldat. 20 de secunde, la fereastră. 👁️",
        "Ritmul se ține cu pași mici, nu cu sprinturi. 🪖",
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
        "Un duș rece la final = o zi câștigată. 🚿",
        "Detensionează. Mușchii mulțumesc, soldat. 🧘",
        "Seara e a ta, soldat. Fă ce te încarcă. ⚡",
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
        "Telefonul pe masă, soldat. Somnul e sacru. 📵",
        "Noaptea se reface ziua. Fă-o în pace. 🌙",
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
        "Transpirația e armura care se forjează. 🔥",
        "Ultima repetare e cea care te schimbă. 💪",
    )

    // ── Somn (sesiune activă) ────────────────────────────────────────────────
    private val sleep = listOf(
        "Monitorizez. Tu doarme. Simplu. 🌙",
        "Soldatul doarme, misiunea continuă. 💤",
        "Zgomotele le notez eu. Tu nu te trezești. 📋",
        "Tabăra e liniștită. Recuperează-te. 🏕️",
        "Noapte bună, soldat. Mâine e o zi nouă. 🌅",
        "Eu țin ușa închisă. Tu doarme. 🛡️",
        "Corpul se repară. Eu veghez. Tu doarme. 🌙",
        "Fără griji, soldat. Eu țin postul. 💤",
        "Somnul e muniția de dimineață. Odihnește-te. ⭐",
    )

    // ── Focus / concentrare (sesiune activă) ─────────────────────────────────
    private val focus = listOf(
        "Paznicul e în post. Tu — la treabă. 🎯",
        "Distragerea e dușmanul. Eu o țin la distanță. 🛡️",
        "Un singur ecran, un singur scop. Continua. ⚔️",
        "FORJA vede doar ce aplicație e deschisă. Restul — al tău.",
        "Concentrarea e armura, soldat. Nu o lăsa spartă. 🪖",
        "Fără notificări. Fără „doar un minut". Tu și treaba. ⚡",
        "Paznicul nu clipi. Tu nici. Continua. 👁️",
        "Distragerea a fost blocată. Înnapoi la misiune. 🪖",
    )

    // ── Colectare / sincronizare (serviciu de fundal) ────────────────────────
    private val collection = listOf(
        "Sincronizare activă. Datele curg. 📡",
        "FORJA ție socoteala. Tu — viața ta. 🪖",
        "Fiecare pas e notat. Nimeni nu uită nimic. 📋",
        "Colectarea e în curs. Nimeni nu vede nimic. 🛡️",
        "Sincronizare. Fără zgomot, fără alerte. 🌙",
        "FORJA veghează. Tu mergi pe drumul tău. 🪖",
        "Datele se transferă. Restul e liniște. 📡",
        "Sincronizare activă. Soldatul nu stă pe loc. 💪",
    )

    // ── Recuperare / hidratare (generic) ─────────────────────────────────────
    private val recovery = listOf(
        "Hidratarea e combustibilul misiunii. 💧",
        "Recuperarea e antrenament invizibil. 💤",
        "Apă, soldat. Corpol răspunde. 💧",
        "O pauză nu e slăbiciune. E strategie. ⏸️",
        "Mușchii cresc în pauză, nu în efort. 🛡️",
        "Detensionează 5 minute. Mușchii mulțumesc. 🧘",
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
        "Nu renunța, soldat! Progresul se construiește zilnic. 🔥",
        "Încă o zi, încă o victorie. 🏆",
    )

    // ── Titluri care se rotesc (70% FORJA, 30% variante) ────────────────────
    private val titles = listOf(
        "FORJA", "FORJA", "FORJA", "FORJA", "FORJA", "FORJA", "FORJA",
        "Soldățelul", "FORJA · Veghe",
    )

    /**
     * Ultimele 3 mesaje alese — evitate la următoarea alegere ca să nu se
     * repete imediat. Resetat la fiecare oră.
     */
    private val recentPicks = ArrayDeque<String>()
    private var lastHour = -1

    /**
     * Alege un mesaj + titlu nou, evitând ultimele 3 alegeri.
     *
     * @param lastText mesajul anterior (compatibilitate vechi)
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
        return pickFrom(pool, lastText)
    }

    /**
     * Alege un mesaj specific unui serviciu de fundal.
     *
     * @param context "sleep" | "focus" | "collection" | "training" | "" (auto pe oră)
     * @param lastText mesajul anterior (evitare repetiție)
     * @return pereche (titlu, text)
     */
    fun pickForService(context: String = "", lastText: String? = null): Pair<String, String> {
        val pool: List<String> = when (context) {
            "sleep" -> sleep
            "focus" -> focus
            "collection" -> collection
            "training" -> training
            "recovery" -> recovery
            else -> timeOfDayPool()
        }
        return pickFrom(pool, lastText)
    }

    // ── Intern ───────────────────────────────────────────────────────────────

    private fun pickFrom(pool: List<String>, lastText: String?): Pair<String, String> {
        resetHourIfChanged()
        val candidates = pool.filter { it != lastText && !recentPicks.contains(it) }
        val source = if (candidates.isEmpty()) pool.filter { it != lastText } else candidates
        val finalSource = if (source.isEmpty()) pool else source
        val text = finalSource.random()
        recentPicks.addLast(text)
        if (recentPicks.size > 3) recentPicks.removeFirst()
        val title = titles.random()
        return title to text
    }

    private fun resetHourIfChanged() {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (h != lastHour) {
            recentPicks.clear()
            lastHour = h
        }
    }

    private fun timeOfDayPool(): List<String> {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val base = when {
            hour in 5..11 -> morning
            hour in 12..17 -> afternoon
            hour in 18..21 -> evening
            else -> night
        }
        // Amestecăm 20% recovery/generic ca să nu fie doar un singur set
        return base + recovery.shuffled().take(1) + generic.shuffled().take(1)
    }

    private fun isNight(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour >= 22 || hour < 5
    }
}
