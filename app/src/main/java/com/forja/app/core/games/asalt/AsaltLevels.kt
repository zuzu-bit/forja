package com.forja.app.core.games.asalt

/**
 * Un nivel ASALT: rândurile zidului de sus în jos, câte 13 caractere.
 * `.` gol · `a` ladă (1) · `b` sac de nisip (2) · `c` beton (3) · `#` oțel (nu se sparge) · `x` muniție (explodează 3×3)
 * · `w` Lat · `m` Schije · `s` Calm (se sparg dintr-o lovitură și lasă o capsulă).
 */
data class AsaltLevel(val id: Int, val name: String, val rows: List<String>, val fort: Boolean = false) {
    /** Viteza de bază a scânteii (u/s). */
    val baseSpeed: Double get() = 320.0 + 10.0 * (id - 1)
}

/** Cele 12 ziduri (games.md §5.2, verificate cu robotul din teste). Forturile: 4, 8, 12. */
object AsaltLevels {
    const val CHARSET = ".abc#xwms"

    val all: List<AsaltLevel> = listOf(
        AsaltLevel(
            1, "Primul val", listOf(
                "aaaaaaaaaaaaa",
                "aaaaaawaaaaaa",
                "aaaaaaaaaaaaa"
            )
        ),
        AsaltLevel(
            2, "Saci de nisip", listOf(
                "bbbbbbbbbbbbb",
                "a.a.a.a.a.a.a",
                "aaaaaaaaaaaaa",
                ".a.a.a.a.a.a.",
                "aaaaaamaaaaaa"
            )
        ),
        AsaltLevel(
            3, "Poarta", listOf(
                "bbbbbbbbbbbbb",
                "aaaaaaaaaaaaa",
                "aaaa.....aaaa",
                "aaaa.....aaaa",
                "aaaa..s..aaaa",
                "bbbb.....bbbb"
            )
        ),
        AsaltLevel(
            4, "Turnuri", listOf(
                ".bb.......bb.",
                ".ab.......ba.",
                ".ab...x...ba.",
                ".ab..aaa..ba.",
                ".ab.aaaaa.ba.",
                ".aa.aawaa.aa.",
                ".aa.......aa."
            ), fort = true
        ),
        AsaltLevel(
            5, "Cazemata", listOf(
                ".###########.",
                ".#aaaaaaaaa#.",
                ".#abbbbbbba#.",
                ".#abx...xba#.",
                ".#abbbmbbba#.",
                ".#aaaaaaaaa#.",
                ".##.......##."
            )
        ),
        AsaltLevel(
            6, "Câmp minat", listOf(
                "aaaaaaaaaaaaa",
                "ax.aaaxaaa.xa",
                "aaaaaaaaaaaaa",
                "aa.xaaaaax.aa",
                "aaaaaawaaaaaa",
                "xaaaaaaaaaaax"
            )
        ),
        AsaltLevel(
            7, "Tranșee", listOf(
                "bbbbbbbbbbbbb",
                "aaaaaaaaaaaaa",
                "###..###..###",
                "aaaaamaaaaaaa",
                "aaaaaaaaaaaaa",
                ".............",
                "bbbbbbbbbbbbb"
            )
        ),
        AsaltLevel(
            8, "Steagul", listOf(
                "#............",
                "#bbbbbbbb....",
                "#bbbbbbbbbb..",
                "#aaaaaaaaaaa.",
                "#aaaaaaaaa...",
                "#axxaaasa....",
                "#............",
                "#............"
            ), fort = true
        ),
        AsaltLevel(
            9, "Fortul", listOf(
                "c.c.c...c.c.c",
                "ccccc...ccccc",
                "cbbbc...cbbbc",
                "cbabc.x.cbabc",
                "cbbbcaaacbbbc",
                "ccccc.m.ccccc"
            )
        ),
        AsaltLevel(
            10, "Casca", listOf(
                "....ccccc....",
                "..cbbbbbbbc..",
                ".cbbaaaaabbc.",
                ".cbaaawaaabc.",
                "ccccccccccccc",
                ".x.........x."
            )
        ),
        AsaltLevel(
            11, "Nicovala", listOf(
                "ccccccccccc..",
                ".cbbbbbbbbbcc",
                "..cbbbbbbbc..",
                "....cbbbc....",
                "....cxmxc....",
                "...ccbbbcc...",
                "..ccccccccc.."
            )
        ),
        AsaltLevel(
            12, "Cetatea", listOf(
                "#.c.c.#.c.c.#",
                "#ccccc#ccccc#",
                "#cbxbc#cbxbc#",
                "#cbbbc#cbbbc#",
                "#aaaaa.aaaaa#",
                "#aaaawmsaaaa#",
                "###.......###"
            ), fort = true
        )
    )

    fun byId(id: Int): AsaltLevel = all.getOrNull(id - 1) ?: all[0]
}
