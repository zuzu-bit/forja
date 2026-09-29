package com.forja.app.core.detox

/**
 * Pachetele de cuvinte ale detoxului (foaia „Cuvinte de blocat”) și clasificarea unei opriri a paznicului. Pur, fără Android.
 * Paznicul numără DOAR codul pachetului care a prins („01”…„04”, „18” = lista minimă FORJA, „own” = cuvintele ei):
 * nici textul de pe ecran, nici cuvântul, nici aplicația.
 */
object DetoxPacks {
    data class Pack(val code: String, val name: String, val words: List<String>)

    val PACKS: List<Pack> = listOf(
        Pack("01", "Club & băutură", listOf("shots", "hai la shots", "hai să ne îmbătăm", "ies la băut", "beau ceva", "tequila", "vodka", "whisky", "bere", "club", "chef", "mahmureală", "alcool")),
        Pack("02", "Pariuri & jocuri", listOf("pariuri", "betting", "casino", "ruletă", "poker", "superbet", "betano", "unibet", "fortuna", "mostbet", "1xbet", "mize", "cotă", "bilet", "jackpot")),
        Pack("03", "Conținut +18", listOf("porn", "porno", "xxx", "sex", "xvideos", "pornhub", "onlyfans", "nsfw", "hentai")),
        Pack("04", "Anime & manga", listOf("anime", "manga", "crunchyroll", "mangadex", "9anime", "otaku", "webtoon", "naruto", "one piece")),
    )
    val BY_CODE: Map<String, List<String>> = PACKS.associate { it.code to it.words }

    // Blocklist minimă, pe telefon. Cea mai mare parte o dau cuvintele setate de ea.
    val DOMAINS = listOf(
        "pornhub", "xvideos", "xnxx", "xhamster", "redtube", "youporn",
        "onlyfans", "brazzers", "spankbang", "chaturbate", "stripchat", "fansly"
    )
    val BUILT_IN = listOf("porn", "xxx", "nsfw", "hentai")

    fun parseWords(s: String): List<String> =
        s.split("\n", ",").map { it.trim().lowercase() }.filter { it.length >= 3 }

    /**
     * Codul pachetului care a prins textul `hay` (deja cu litere mici), sau null. Lista FORJA („18”) are întâietate;
     * un cuvânt al ei se socotește la pachetul din care vine, altfel „own”. Întoarce doar codul: textul nu iese de aici.
     */
    fun match(hay: String, userWords: List<String>): String? {
        if (DOMAINS.any { hay.contains(it) } || BUILT_IN.any { hay.contains(it) }) return "18"
        val w = userWords.firstOrNull { it.length >= 3 && hay.contains(it) } ?: return null
        return PACKS.firstOrNull { p -> p.words.any { it.lowercase() == w } }?.code ?: "own"
    }
}
