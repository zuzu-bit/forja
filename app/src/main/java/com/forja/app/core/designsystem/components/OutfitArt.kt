package com.forja.app.core.designsystem.components

// Generat de scratchpad/art/forge.py — ținutele Cascăi (spațiul 100×100 al mascotei). Nu edita de mână.

/** Un strat vectorial: cale SVG, umplere și contur ARGB (0 = fără), grosimea conturului, opacitate. */
class ArtLayer(val d: String, val fill: Long, val stroke: Long, val w: Float, val alpha: Float)

/** O piesă: straturile peste corp; [behind] în spatele corpului; [straps] peste haină (bretele). */
class GearArt(val layers: List<ArtLayer>, val behind: List<ArtLayer> = emptyList(), val straps: List<ArtLayer> = emptyList())

object OutfitArt {
    val gear: Map<String, GearArt> = mapOf(
        "beret_olive" to GearArt(listOf(
            ArtLayer("M26 25 C24 15 36 6 52 7 C66 8 76 14 74 24 C72 30 60 31 48 30 C38 29 28 30 26 25 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M28 24 C27 16 37 9 51 10 C64 11 73 16 71 23 C69 27 59 28 48 27 C39 26 30 27 28 24 Z", 0xFF4A5D3AL, 0L, 0f, 1.0f),
            ArtLayer("M30 18 C36 12 48 10 60 12 C66 13 70 16 70 19 C62 15 48 13 36 16 C33 17 31 18 30 18 Z", 0xFFFFFFFFL, 0L, 0f, 0.1f),
            ArtLayer("M27 24 C34 27 44 28 52 28 C60 28 66 27 71 24 C70 29 60 31 50 31 C40 31 30 30 27 24 Z", 0xFF2F3D27L, 0L, 0f, 1.0f),
            ArtLayer("M62 15 L64 19 L68 19 L65 21.5 L66 25.5 L62.5 23.5 L59 25.5 L60 21.5 L57 19 L61 19 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M62 16.5 L63.5 19.8 L67 19.8 L64.2 21.9 L65.2 25 L62 23 L58.8 25 L59.8 21.9 L57 19.8 L60.5 19.8 Z", 0xFFD9B24CL, 0L, 0f, 1.0f)
        )),
        "beret_maroon" to GearArt(listOf(
            ArtLayer("M26 25 C24 15 36 6 52 7 C66 8 76 14 74 24 C72 30 60 31 48 30 C38 29 28 30 26 25 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M28 24 C27 16 37 9 51 10 C64 11 73 16 71 23 C69 27 59 28 48 27 C39 26 30 27 28 24 Z", 0xFF7A2A3AL, 0L, 0f, 1.0f),
            ArtLayer("M30 18 C36 12 48 10 60 12 C66 13 70 16 70 19 C62 15 48 13 36 16 C33 17 31 18 30 18 Z", 0xFFFFFFFFL, 0L, 0f, 0.1f),
            ArtLayer("M27 24 C34 27 44 28 52 28 C60 28 66 27 71 24 C70 29 60 31 50 31 C40 31 30 30 27 24 Z", 0xFF4F1A26L, 0L, 0f, 1.0f),
            ArtLayer("M62 15 L64 19 L68 19 L65 21.5 L66 25.5 L62.5 23.5 L59 25.5 L60 21.5 L57 19 L61 19 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M62 16.5 L63.5 19.8 L67 19.8 L64.2 21.9 L65.2 25 L62 23 L58.8 25 L59.8 21.9 L57 19.8 L60.5 19.8 Z", 0xFFD9B24CL, 0L, 0f, 1.0f)
        )),
        "beret_black" to GearArt(listOf(
            ArtLayer("M26 25 C24 15 36 6 52 7 C66 8 76 14 74 24 C72 30 60 31 48 30 C38 29 28 30 26 25 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M28 24 C27 16 37 9 51 10 C64 11 73 16 71 23 C69 27 59 28 48 27 C39 26 30 27 28 24 Z", 0xFF1C1C1FL, 0L, 0f, 1.0f),
            ArtLayer("M30 18 C36 12 48 10 60 12 C66 13 70 16 70 19 C62 15 48 13 36 16 C33 17 31 18 30 18 Z", 0xFFFFFFFFL, 0L, 0f, 0.1f),
            ArtLayer("M27 24 C34 27 44 28 52 28 C60 28 66 27 71 24 C70 29 60 31 50 31 C40 31 30 30 27 24 Z", 0xFF000000L, 0L, 0f, 1.0f),
            ArtLayer("M62 15 L64 19 L68 19 L65 21.5 L66 25.5 L62.5 23.5 L59 25.5 L60 21.5 L57 19 L61 19 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M62 16.5 L63.5 19.8 L67 19.8 L64.2 21.9 L65.2 25 L62 23 L58.8 25 L59.8 21.9 L57 19.8 L60.5 19.8 Z", 0xFFD9B24CL, 0L, 0f, 1.0f)
        )),
        "beret_blue" to GearArt(listOf(
            ArtLayer("M26 25 C24 15 36 6 52 7 C66 8 76 14 74 24 C72 30 60 31 48 30 C38 29 28 30 26 25 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M28 24 C27 16 37 9 51 10 C64 11 73 16 71 23 C69 27 59 28 48 27 C39 26 30 27 28 24 Z", 0xFF2F5DA8L, 0L, 0f, 1.0f),
            ArtLayer("M30 18 C36 12 48 10 60 12 C66 13 70 16 70 19 C62 15 48 13 36 16 C33 17 31 18 30 18 Z", 0xFFFFFFFFL, 0L, 0f, 0.1f),
            ArtLayer("M27 24 C34 27 44 28 52 28 C60 28 66 27 71 24 C70 29 60 31 50 31 C40 31 30 30 27 24 Z", 0xFF1E3F75L, 0L, 0f, 1.0f),
            ArtLayer("M62 15 L64 19 L68 19 L65 21.5 L66 25.5 L62.5 23.5 L59 25.5 L60 21.5 L57 19 L61 19 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M62 16.5 L63.5 19.8 L67 19.8 L64.2 21.9 L65.2 25 L62 23 L58.8 25 L59.8 21.9 L57 19.8 L60.5 19.8 Z", 0xFFC0C4CCL, 0L, 0f, 1.0f)
        )),
        "patrol_cap" to GearArt(listOf(
            ArtLayer("M30 27 C30 14 38 9 50 9 C62 9 70 14 70 27 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M32 26 C32 15 39 11 50 11 C61 11 68 15 68 26 Z", 0xFF4A5D3AL, 0L, 0f, 1.0f),
            ArtLayer("M34 16 C40 12 60 12 66 16 C62 13 38 13 34 16 Z", 0xFFFFFFFFL, 0L, 0f, 0.1f),
            ArtLayer("M22 27 L78 27 C78 33 70 35 58 34 L42 34 C30 35 22 33 22 27 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M24 27 L76 27 C76 31.5 69 33 58 32 L42 32 C31 33 24 31.5 24 27 Z", 0xFF2F3D27L, 0L, 0f, 1.0f),
            ArtLayer("M42 19 L58 19 L58 24 L42 24 Z", 0xFF0A0A0BL, 0L, 0f, 0.35f),
            ArtLayer("M43.5 20.2 L56.5 20.2 L56.5 22.8 L43.5 22.8 Z", 0xFFC9B58AL, 0L, 0f, 1.0f)
        )),
        "boonie" to GearArt(listOf(
            ArtLayer("M12 30 C14 24 24 24 32 25 C34 14 42 8 50 8 C58 8 66 14 68 25 C76 24 86 24 88 30 C84 37 70 38 50 38 C30 38 16 37 12 30 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M15 29 C18 26 26 26 33 27 C35 17 42 11 50 11 C58 11 65 17 67 27 C74 26 82 26 85 29 C81 35 68 36 50 36 C32 36 19 35 15 29 Z", 0xFF5E6B45L, 0L, 0f, 1.0f),
            ArtLayer("M33 27 C35 17 42 11 50 11 C58 11 65 17 67 27 C62 24 38 24 33 27 Z", 0xFF4A5D3AL, 0L, 0f, 1.0f),
            ArtLayer("M35 23 C38 26 62 26 65 23", 0L, 0xFF2F3D27L, 1.6f, 1.0f),
            ArtLayer("M18 30 C30 32 70 32 82 30", 0L, 0xFFFFFFFFL, 1.0f, 0.12f)
        )),
        "helmet" to GearArt(listOf(
            ArtLayer("M28.4 57.7 C34 80 66 80 71.6 57.7", 0L, 0xFF3B4A2FL, 2.6f, 1.0f),
            ArtLayer("M19 27 C19 5 81 5 81 27 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M22 26 C22 9 78 9 78 26 Z", 0xFF3B4A2FL, 0L, 0f, 1.0f),
            ArtLayer("M30 20 C34 12 44 9 50 9", 0L, 0xFF55673FL, 1.8f, 1.0f),
            ArtLayer("M15 23 L85 23 L85 30 L15 30 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M18 24 L82 24 L82 29 L18 29 Z", 0xFF3B4A2FL, 0L, 0f, 1.0f),
            ArtLayer("M22 23.5 L78 23.5", 0L, 0xFF0A0A0BL, 1.4f, 1.0f),
            ArtLayer("M46.5 74 L53.5 74 L53.5 79 L46.5 79 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M47.5 75 L52.5 75 L52.5 78 L47.5 78 Z", 0xFFF3B952L, 0L, 0f, 1.0f)
        )),
        "officer_cap" to GearArt(listOf(
            ArtLayer("M26 25 C26 10 38 5 50 5 C62 5 74 10 74 25 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M28 24 C28 12 39 7 50 7 C61 7 72 12 72 24 Z", 0xFF4A5D3AL, 0L, 0f, 1.0f),
            ArtLayer("M32 14 C38 9 62 9 68 14 C62 11 38 11 32 14 Z", 0xFFFFFFFFL, 0L, 0f, 0.12f),
            ArtLayer("M24 22 L76 22 L76 29 L24 29 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M26 23 L74 23 L74 28 L26 28 Z", 0xFFD9B24CL, 0L, 0f, 1.0f),
            ArtLayer("M28 25.5 L72 25.5", 0L, 0xFFA8862EL, 0.8f, 1.0f),
            ArtLayer("M30 29 L70 29 C70 35 62 37 50 37 C38 37 30 35 30 29 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M32 29 L68 29 C68 34 61 35.5 50 35.5 C39 35.5 32 34 32 29 Z", 0xFF1C1C1FL, 0L, 0f, 1.0f),
            ArtLayer("M35 31 C40 33 60 33 65 31", 0L, 0xFFFFFFFFL, 0.9f, 0.25f),
            ArtLayer("M50 11 L52.2 15.6 L57 16.2 L53.5 19.4 L54.4 24 L50 21.8 L45.6 24 L46.5 19.4 L43 16.2 L47.8 15.6 Z", 0xFFD9B24CL, 0L, 0f, 1.0f),
            ArtLayer("M50 13.5 L51.4 16.6 L54.6 17 L52.3 19.1 L52.9 22.2 L50 20.7 L47.1 22.2 L47.7 19.1 L45.4 17 L48.6 16.6 Z", 0xFFA8862EL, 0L, 0f, 1.0f)
        )),
        "aviators" to GearArt(listOf(
            ArtLayer("M27.5 49 C27.5 42 33 39.5 39 40.5 C45 41.5 49 45 49 50 C49 56 45 60 39.5 60 C33 60 27.5 55.5 27.5 49 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M51 50 C51 45 55 41.5 61 40.5 C67 39.5 72.5 42 72.5 49 C72.5 55.5 67 60 60.5 60 C55 60 51 56 51 50 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M29 49 C29 43.5 34 41 39 42 C44 43 47.5 46 47.5 50 C47.5 55 44 58.5 39.5 58.5 C34 58.5 29 54.5 29 49 Z", 0xFF1A2230L, 0L, 0f, 1.0f),
            ArtLayer("M52.5 50 C52.5 46 56 43 61 42 C66 41 71 43.5 71 49 C71 54.5 66 58.5 60.5 58.5 C56 58.5 52.5 55 52.5 50 Z", 0xFF1A2230L, 0L, 0f, 1.0f),
            ArtLayer("M29 49 C29 43.5 34 41 39 42 C44 43 47.5 46 47.5 50 C47.5 55 44 58.5 39.5 58.5 C34 58.5 29 54.5 29 49 Z", 0L, 0xFFD9B24CL, 1.3f, 1.0f),
            ArtLayer("M52.5 50 C52.5 46 56 43 61 42 C66 41 71 43.5 71 49 C71 54.5 66 58.5 60.5 58.5 C56 58.5 52.5 55 52.5 50 Z", 0L, 0xFFD9B24CL, 1.3f, 1.0f),
            ArtLayer("M31.5 46 C34 43.5 38 43.5 41 45", 0L, 0xFFFFFFFFL, 1.4f, 0.4f),
            ArtLayer("M56 45 C59 43.5 63 43.5 66 46", 0L, 0xFFFFFFFFL, 1.4f, 0.4f),
            ArtLayer("M47.5 47.5 C48.5 45.5 51.5 45.5 52.5 47.5", 0L, 0xFFD9B24CL, 1.5f, 1.0f),
            ArtLayer("M28 46.5 L22 44", 0L, 0xFFD9B24CL, 1.3f, 1.0f),
            ArtLayer("M72 46.5 L78 44", 0L, 0xFFD9B24CL, 1.3f, 1.0f)
        )),
        "tactical_glasses" to GearArt(listOf(
            ArtLayer("M26 44.5 C36 41 64 41 74 44.5 L74 50 C71.5 56 64 57.5 58.5 56 C55 55 52.5 52.5 50 52.5 C47.5 52.5 45 55 41.5 56 C36 57.5 28.5 56 26 50 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M28 45.5 C36.5 42.5 63.5 42.5 72 45.5 L72 49.5 C69.5 54.5 63.5 55.5 59 54.2 C55.5 53.2 52.5 51 50 51 C47.5 51 44.5 53.2 41 54.2 C36.5 55.5 30.5 54.5 28 49.5 Z", 0xFF23272EL, 0L, 0f, 1.0f),
            ArtLayer("M30 47 C36 45 46 45 48 46.5", 0L, 0xFF8FD3FFL, 1.2f, 0.35f),
            ArtLayer("M52 46.5 C54 45 64 45 70 47", 0L, 0xFF8FD3FFL, 1.2f, 0.35f),
            ArtLayer("M26 46.5 L21 45.5", 0L, 0xFF0A0A0BL, 1.6f, 1.0f),
            ArtLayer("M74 46.5 L79 45.5", 0L, 0xFF0A0A0BL, 1.6f, 1.0f)
        )),
        "tshirt_khaki" to GearArt(listOf(
            ArtLayer("M12 60 C22 61 30 63 36 64 C42 70 58 70 64 64 C70 63 78 61 88 60 L92 100 L8 100 Z", 0xFFC9B58AL, 0L, 0f, 1.0f),
            ArtLayer("M12 84 C26 90 74 90 88 84 L88 100 L12 100 Z", 0xFFA8946CL, 0L, 0f, 0.4f),
            ArtLayer("M36 64 C42 70 58 70 64 64 C60 66.5 40 66.5 36 64 Z", 0xFFA8946CL, 0L, 0f, 1.0f),
            ArtLayer("M36 64 C42 70 58 70 64 64", 0L, 0xFF0A0A0BL, 2.6f, 1.0f),
            ArtLayer("M12 60 C22 61 30 63 36 64", 0L, 0xFF0A0A0BL, 2.6f, 1.0f),
            ArtLayer("M64 64 C70 63 78 61 88 60", 0L, 0xFF0A0A0BL, 2.6f, 1.0f),
            ArtLayer("M63 72 L78 72 L78 76 L63 76 Z", 0xFF2F3D27L, 0L, 0f, 1.0f),
            ArtLayer("M64.5 73.3 L76.5 73.3 L76.5 74.7 L64.5 74.7 Z", 0xFFC9B58AL, 0L, 0f, 1.0f)
        )),
        "field_jacket" to GearArt(listOf(
            ArtLayer("M12 60 C22 60 30 61 35 62 L50 78 L65 62 C70 61 78 60 88 60 L92 100 L8 100 Z", 0xFF4A5D3AL, 0L, 0f, 1.0f),
            ArtLayer("M12 84 C26 90 74 90 88 84 L88 100 L12 100 Z", 0xFF2F3D27L, 0L, 0f, 0.45f),
            ArtLayer("M35 62 L32.5 64.5 L50 80.5 L50 78 Z", 0xFF6F855AL, 0L, 0f, 1.0f),
            ArtLayer("M65 62 L67.5 64.5 L50 80.5 L50 78 Z", 0xFF6F855AL, 0L, 0f, 1.0f),
            ArtLayer("M35 62 L32.5 64.5 L50 80.5", 0L, 0xFF0A0A0BL, 1.6f, 1.0f),
            ArtLayer("M65 62 L67.5 64.5 L50 80.5", 0L, 0xFF0A0A0BL, 1.6f, 1.0f),
            ArtLayer("M21.5 75.5 L35.0 75.5 L35.0 81.5 L21.5 81.5 Z", 0xFF2F3D27L, 0L, 0f, 1.0f),
            ArtLayer("M21.5 75.5 L35.0 75.5 L35.0 81.5 L21.5 81.5 Z", 0L, 0xFF0A0A0BL, 1.3f, 1.0f),
            ArtLayer("M21.5 75.5 L35.0 75.5", 0L, 0xFF0A0A0BL, 2.0f, 1.0f),
            ArtLayer("M65 75.5 L78.5 75.5 L78.5 81.5 L65 81.5 Z", 0xFF2F3D27L, 0L, 0f, 1.0f),
            ArtLayer("M65 75.5 L78.5 75.5 L78.5 81.5 L65 81.5 Z", 0L, 0xFF0A0A0BL, 1.3f, 1.0f),
            ArtLayer("M65 75.5 L78.5 75.5", 0L, 0xFF0A0A0BL, 2.0f, 1.0f),
            ArtLayer("M50 78 L50 92", 0L, 0xFFC8CAD0L, 1.3f, 1.0f),
            ArtLayer("M50 78 L50 92", 0L, 0xFF0A0A0BL, 0.5f, 0.5f),
            ArtLayer("M35 62 L50 78 L65 62", 0L, 0xFF0A0A0BL, 2.8f, 1.0f),
            ArtLayer("M12 60 C22 60 30 61 35 62", 0L, 0xFF0A0A0BL, 2.8f, 1.0f),
            ArtLayer("M65 62 C70 61 78 60 88 60", 0L, 0xFF0A0A0BL, 2.8f, 1.0f)
        )),
        "camo_shirt" to GearArt(listOf(
            ArtLayer("M12 60 C22 60 30 61 35 62 L50 78 L65 62 C70 61 78 60 88 60 L92 100 L8 100 Z", 0xFF5A6B45L, 0L, 0f, 1.0f),
            ArtLayer("M12 84 C26 90 74 90 88 84 L88 100 L12 100 Z", 0xFF3A4A2EL, 0L, 0f, 0.45f),
            ArtLayer("M35 62 L32.5 64.5 L50 80.5 L50 78 Z", 0xFF6F855AL, 0L, 0f, 1.0f),
            ArtLayer("M65 62 L67.5 64.5 L50 80.5 L50 78 Z", 0xFF6F855AL, 0L, 0f, 1.0f),
            ArtLayer("M35 62 L32.5 64.5 L50 80.5", 0L, 0xFF0A0A0BL, 1.6f, 1.0f),
            ArtLayer("M65 62 L67.5 64.5 L50 80.5", 0L, 0xFF0A0A0BL, 1.6f, 1.0f),
            ArtLayer("M21.5 75.5 L35.0 75.5 L35.0 81.5 L21.5 81.5 Z", 0xFF3A4A2EL, 0L, 0f, 1.0f),
            ArtLayer("M21.5 75.5 L35.0 75.5 L35.0 81.5 L21.5 81.5 Z", 0L, 0xFF0A0A0BL, 1.3f, 1.0f),
            ArtLayer("M21.5 75.5 L35.0 75.5", 0L, 0xFF0A0A0BL, 2.0f, 1.0f),
            ArtLayer("M65 75.5 L78.5 75.5 L78.5 81.5 L65 81.5 Z", 0xFF3A4A2EL, 0L, 0f, 1.0f),
            ArtLayer("M65 75.5 L78.5 75.5 L78.5 81.5 L65 81.5 Z", 0L, 0xFF0A0A0BL, 1.3f, 1.0f),
            ArtLayer("M65 75.5 L78.5 75.5", 0L, 0xFF0A0A0BL, 2.0f, 1.0f),
            ArtLayer("M50 83 m-1.9 0 a1.9 1.9 0 1 0 3.8 0 a1.9 1.9 0 1 0 -3.8 0", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M50 83 m-1.4 0 a1.4 1.4 0 1 0 2.8 0 a1.4 1.4 0 1 0 -2.8 0", 0xFFD9B24CL, 0L, 0f, 1.0f),
            ArtLayer("M50 88 m-1.9 0 a1.9 1.9 0 1 0 3.8 0 a1.9 1.9 0 1 0 -3.8 0", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M50 88 m-1.4 0 a1.4 1.4 0 1 0 2.8 0 a1.4 1.4 0 1 0 -2.8 0", 0xFFD9B24CL, 0L, 0f, 1.0f),
            ArtLayer("M35 62 L50 78 L65 62", 0L, 0xFF0A0A0BL, 2.8f, 1.0f),
            ArtLayer("M12 60 C22 60 30 61 35 62", 0L, 0xFF0A0A0BL, 2.8f, 1.0f),
            ArtLayer("M65 62 C70 61 78 60 88 60", 0L, 0xFF0A0A0BL, 2.8f, 1.0f),
            ArtLayer("M14 64 C18 61 24 63 23 67 C22 70 16 71 14 68 Z", 0xFF7A6238L, 0L, 0f, 1.0f),
            ArtLayer("M76 63 C82 61 86 66 83 69 C80 72 74 70 76 63 Z", 0xFF7A6238L, 0L, 0f, 1.0f),
            ArtLayer("M22 83 C26 80 34 83 32 87 C30 90 22 88 22 83 Z", 0xFF36472CL, 0L, 0f, 1.0f),
            ArtLayer("M66 84 C70 80 78 82 76 87 C74 90 66 89 66 84 Z", 0xFF36472CL, 0L, 0f, 1.0f),
            ArtLayer("M36 84 C40 82 44 86 42 89 C40 91 35 89 36 84 Z", 0xFFB8A878L, 0L, 0f, 1.0f),
            ArtLayer("M56 80 C60 78 64 82 62 85 C60 88 55 86 56 80 Z", 0xFFB8A878L, 0L, 0f, 1.0f),
            ArtLayer("M15 75 C19 73 23 77 21 80 C19 82 15 80 15 75 Z", 0xFFB8A878L, 0L, 0f, 0.8f),
            ArtLayer("M80 74 C84 72 87 76 85 79 C83 81 79 79 80 74 Z", 0xFF36472CL, 0L, 0f, 1.0f),
            ArtLayer("M38 70 C42 68 46 72 44 75 C42 77 37 75 38 70 Z", 0xFF36472CL, 0L, 0f, 0.8f),
            ArtLayer("M57 73 C61 71 65 74 63 77 C61 79 56 77 57 73 Z", 0xFFB8A878L, 0L, 0f, 0.7f)
        )),
        "tactical_vest" to GearArt(listOf(
            ArtLayer("M12 60 C22 60 30 61 35 62 L50 78 L65 62 C70 61 78 60 88 60 L92 100 L8 100 Z", 0xFF4A5D3AL, 0L, 0f, 1.0f),
            ArtLayer("M12 84 C26 90 74 90 88 84 L88 100 L12 100 Z", 0xFF2F3D27L, 0L, 0f, 0.45f),
            ArtLayer("M35 62 L50 78 L65 62", 0L, 0xFF0A0A0BL, 2.8f, 1.0f),
            ArtLayer("M12 60 C22 60 30 61 35 62", 0L, 0xFF0A0A0BL, 2.8f, 1.0f),
            ArtLayer("M65 62 C70 61 78 60 88 60", 0L, 0xFF0A0A0BL, 2.8f, 1.0f),
            ArtLayer("M14 62 C20 60 30 60 36 63 L47 79 L47 100 L8 100 Z", 0xFF2B3324L, 0L, 0f, 1.0f),
            ArtLayer("M86 62 C80 60 70 60 64 63 L53 79 L53 100 L92 100 Z", 0xFF2B3324L, 0L, 0f, 1.0f),
            ArtLayer("M36 63 L47 79 L47 100", 0L, 0xFF0A0A0BL, 2.0f, 1.0f),
            ArtLayer("M64 63 L53 79 L53 100", 0L, 0xFF0A0A0BL, 2.0f, 1.0f),
            ArtLayer("M20 75.5 L34 75.5 L35 82 L21 82 Z", 0xFF3A4530L, 0L, 0f, 1.0f),
            ArtLayer("M20 75.5 L34 75.5 L35 82 L21 82 Z", 0L, 0xFF0A0A0BL, 1.3f, 1.0f),
            ArtLayer("M21 78.5 L34 78.5", 0L, 0xFF0A0A0BL, 1.0f, 1.0f),
            ArtLayer("M66 75.5 L80 75.5 L79 82 L65 82 Z", 0xFF3A4530L, 0L, 0f, 1.0f),
            ArtLayer("M66 75.5 L80 75.5 L79 82 L65 82 Z", 0L, 0xFF0A0A0BL, 1.3f, 1.0f),
            ArtLayer("M66 78.5 L79 78.5", 0L, 0xFF0A0A0BL, 1.0f, 1.0f),
            ArtLayer("M27 84.5 L40 84.5 L40 90.5 L28 90.5 Z", 0xFF3A4530L, 0L, 0f, 1.0f),
            ArtLayer("M27 84.5 L40 84.5 L40 90.5 L28 90.5 Z", 0L, 0xFF0A0A0BL, 1.3f, 1.0f),
            ArtLayer("M60 84.5 L73 84.5 L72 90.5 L60 90.5 Z", 0xFF3A4530L, 0L, 0f, 1.0f),
            ArtLayer("M60 84.5 L73 84.5 L72 90.5 L60 90.5 Z", 0L, 0xFF0A0A0BL, 1.3f, 1.0f),
            ArtLayer("M40 64 L44 70", 0L, 0xFFC9B58AL, 1.4f, 1.0f),
            ArtLayer("M60 64 L56 70", 0L, 0xFFC9B58AL, 1.4f, 1.0f),
            ArtLayer("M35 62 L50 78 L65 62", 0L, 0xFF0A0A0BL, 2.8f, 1.0f),
            ArtLayer("M12 60 C22 60 30 61 35 62", 0L, 0xFF0A0A0BL, 2.8f, 1.0f),
            ArtLayer("M65 62 C70 61 78 60 88 60", 0L, 0xFF0A0A0BL, 2.8f, 1.0f)
        )),
        "dress_jacket" to GearArt(listOf(
            ArtLayer("M12 60 C22 60 30 61 35 62 L50 78 L65 62 C70 61 78 60 88 60 L92 100 L8 100 Z", 0xFF243247L, 0L, 0f, 1.0f),
            ArtLayer("M12 84 C26 90 74 90 88 84 L88 100 L12 100 Z", 0xFF182234L, 0L, 0f, 0.5f),
            ArtLayer("M35 62 L31.5 64.5 L50 81 L50 78 Z", 0xFF3A4D6BL, 0L, 0f, 1.0f),
            ArtLayer("M65 62 L68.5 64.5 L50 81 L50 78 Z", 0xFF3A4D6BL, 0L, 0f, 1.0f),
            ArtLayer("M35 62 L31.5 64.5 L50 81", 0L, 0xFFB5342EL, 1.1f, 1.0f),
            ArtLayer("M65 62 L68.5 64.5 L50 81", 0L, 0xFFB5342EL, 1.1f, 1.0f),
            ArtLayer("M35 62 L31.5 64.5 L50 81", 0L, 0xFF0A0A0BL, 1.6f, 1.0f),
            ArtLayer("M65 62 L68.5 64.5 L50 81", 0L, 0xFF0A0A0BL, 1.6f, 1.0f),
            ArtLayer("M50 82.5 m-2 0 a2 2 0 1 0 4 0 a2 2 0 1 0 -4 0", 0xFFD9B24CL, 0L, 0f, 1.0f),
            ArtLayer("M50 88 m-2 0 a2 2 0 1 0 4 0 a2 2 0 1 0 -4 0", 0xFFD9B24CL, 0L, 0f, 1.0f),
            ArtLayer("M14 61 C18 59 24 59 27 61", 0L, 0xFFD9B24CL, 1.5f, 1.0f),
            ArtLayer("M86 61 C82 59 76 59 73 61", 0L, 0xFFD9B24CL, 1.5f, 1.0f),
            ArtLayer("M14 63 L25 61 L26 66 L15 68 Z", 0xFFA8862EL, 0L, 0f, 1.0f),
            ArtLayer("M86 63 L75 61 L74 66 L85 68 Z", 0xFFA8862EL, 0L, 0f, 1.0f),
            ArtLayer("M35 62 L50 78 L65 62", 0L, 0xFF0A0A0BL, 2.8f, 1.0f),
            ArtLayer("M12 60 C22 60 30 61 35 62", 0L, 0xFF0A0A0BL, 2.8f, 1.0f),
            ArtLayer("M65 62 C70 61 78 60 88 60", 0L, 0xFF0A0A0BL, 2.8f, 1.0f)
        )),
        "parka" to GearArt(listOf(
            ArtLayer("M12 60 C22 60 30 61 35 62 L50 78 L65 62 C70 61 78 60 88 60 L92 100 L8 100 Z", 0xFF6E7A5AL, 0L, 0f, 1.0f),
            ArtLayer("M12 84 C26 90 74 90 88 84 L88 100 L12 100 Z", 0xFF4E5940L, 0L, 0f, 0.45f),
            ArtLayer("M21.5 75.5 L35.0 75.5 L35.0 81.5 L21.5 81.5 Z", 0xFF4E5940L, 0L, 0f, 1.0f),
            ArtLayer("M21.5 75.5 L35.0 75.5 L35.0 81.5 L21.5 81.5 Z", 0L, 0xFF0A0A0BL, 1.3f, 1.0f),
            ArtLayer("M21.5 75.5 L35.0 75.5", 0L, 0xFF0A0A0BL, 2.0f, 1.0f),
            ArtLayer("M65 75.5 L78.5 75.5 L78.5 81.5 L65 81.5 Z", 0xFF4E5940L, 0L, 0f, 1.0f),
            ArtLayer("M65 75.5 L78.5 75.5 L78.5 81.5 L65 81.5 Z", 0L, 0xFF0A0A0BL, 1.3f, 1.0f),
            ArtLayer("M65 75.5 L78.5 75.5", 0L, 0xFF0A0A0BL, 2.0f, 1.0f),
            ArtLayer("M50 78 L50 92", 0L, 0xFFC8CAD0L, 1.3f, 1.0f),
            ArtLayer("M50 78 L50 92", 0L, 0xFF0A0A0BL, 0.5f, 0.5f),
            ArtLayer("M35 62 L50 78 L65 62", 0L, 0xFF0A0A0BL, 2.8f, 1.0f),
            ArtLayer("M12 60 C22 60 30 61 35 62", 0L, 0xFF0A0A0BL, 2.8f, 1.0f),
            ArtLayer("M65 62 C70 61 78 60 88 60", 0L, 0xFF0A0A0BL, 2.8f, 1.0f),
            ArtLayer("M12 58 C20 52 32 50 42 52 C46 53 48 56 50 60 C52 56 54 53 58 52 C68 50 80 52 88 58 C80 56 70 57 64 62 L50 78 L36 62 C30 57 20 56 12 58 Z", 0xFFC9BFA6L, 0L, 0f, 1.0f),
            ArtLayer("M12 58 C20 52 32 50 42 52 C46 53 48 56 50 60 C52 56 54 53 58 52 C68 50 80 52 88 58", 0L, 0xFF0A0A0BL, 2.2f, 1.0f),
            ArtLayer("M16 57 L17 61 M22 54.5 L23 59 M29 52.5 L30 57 M36 52 L37 56.5 M43 53 L44 57.5 M50 56 L50 60.5 M57 53 L56 57.5 M64 52 L63 56.5 M71 52.5 L70 57 M78 54.5 L77 59 M84 57 L83 61", 0L, 0xFF8C826BL, 1.0f, 1.0f)
        )),
        "hoodie" to GearArt(listOf(
            ArtLayer("M12 60 C22 60 30 61 35 62 L50 78 L65 62 C70 61 78 60 88 60 L92 100 L8 100 Z", 0xFF3C3F46L, 0L, 0f, 1.0f),
            ArtLayer("M12 84 C26 90 74 90 88 84 L88 100 L12 100 Z", 0xFF26282EL, 0L, 0f, 0.45f),
            ArtLayer("M50 78 L50 92", 0L, 0xFFC8CAD0L, 1.3f, 1.0f),
            ArtLayer("M50 78 L50 92", 0L, 0xFF0A0A0BL, 0.5f, 0.5f),
            ArtLayer("M35 62 L50 78 L65 62", 0L, 0xFF0A0A0BL, 2.8f, 1.0f),
            ArtLayer("M12 60 C22 60 30 61 35 62", 0L, 0xFF0A0A0BL, 2.8f, 1.0f),
            ArtLayer("M65 62 C70 61 78 60 88 60", 0L, 0xFF0A0A0BL, 2.8f, 1.0f),
            ArtLayer("M38 64 L39 74 M62 64 L61 74", 0L, 0xFFC8CAD0L, 1.2f, 1.0f),
            ArtLayer("M28 82 C36 78 64 78 72 82 L72 92 L28 92 Z", 0xFF2E3137L, 0L, 0f, 1.0f),
            ArtLayer("M28 82 C36 78 64 78 72 82", 0L, 0xFF0A0A0BL, 1.4f, 1.0f)
        ), behind = listOf(
            ArtLayer("M22 62 C16 46 24 30 40 26 C46 24 54 24 60 26 C76 30 84 46 78 62 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M24.5 61 C19.5 47 27 32 41 28.5 C46.5 27 53.5 27 59 28.5 C73 32 80.5 47 75.5 61 Z", 0xFF4A4E5AL, 0L, 0f, 1.0f)
        )),
        "web_belt" to GearArt(listOf(
            ArtLayer("M12 79 C30 84 70 84 88 79 L88 87 C70 92 30 92 12 87 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M13.5 80.2 C30 85 70 85 86.5 80.2 L86.5 85.8 C70 90.6 30 90.6 13.5 85.8 Z", 0xFF4A5D3AL, 0L, 0f, 1.0f),
            ArtLayer("M44.5 79.8 L55.5 79.8 L55.5 87.2 L44.5 87.2 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M45.7 81 L54.3 81 L54.3 86 L45.7 86 Z", 0xFFD9B24CL, 0L, 0f, 1.0f),
            ArtLayer("M48.5 81 L48.5 86", 0L, 0xFFA8862EL, 1.0f, 1.0f)
        )),
        "tactical_belt" to GearArt(listOf(
            ArtLayer("M12 79 C30 84 70 84 88 79 L88 87 C70 92 30 92 12 87 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M13.5 80.2 C30 85 70 85 86.5 80.2 L86.5 85.8 C70 90.6 30 90.6 13.5 85.8 Z", 0xFF1C1C1FL, 0L, 0f, 1.0f),
            ArtLayer("M24 78 L34 78 L34.5 89 L23.5 89 Z", 0xFF2B2F36L, 0L, 0f, 1.0f),
            ArtLayer("M24 78 L34 78 L34.5 89 L23.5 89 Z", 0L, 0xFF0A0A0BL, 1.2f, 1.0f),
            ArtLayer("M66 78 L76 78 L76.5 89 L65.5 89 Z", 0xFF2B2F36L, 0L, 0f, 1.0f),
            ArtLayer("M66 78 L76 78 L76.5 89 L65.5 89 Z", 0L, 0xFF0A0A0BL, 1.2f, 1.0f),
            ArtLayer("M44.5 79.8 L55.5 79.8 L55.5 87.2 L44.5 87.2 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M45.7 81 L54.3 81 L54.3 86 L45.7 86 Z", 0xFF8C8F96L, 0L, 0f, 1.0f),
            ArtLayer("M48.5 81 L48.5 86", 0L, 0xFF0A0A0BL, 1.0f, 1.0f)
        )),
        "sneakers" to GearArt(listOf(
            ArtLayer("M29 83 L47 83 L48 88 C48 93 44 96 38 96 C32 96 27 94 27 90 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M53 83 L71 83 L73 90 C73 94 68 96 62 96 C56 96 52 93 52 88 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M31 84.5 L45.5 84.5 L46.5 88 C46.5 92 43 94.5 38 94.5 C33 94.5 29 93 29 90 Z", 0xFFC8CAD0L, 0L, 0f, 1.0f),
            ArtLayer("M55 84.5 L69.5 84.5 L71 90 C71 93 67 94.5 62 94.5 C57 94.5 54 92 54 88 Z", 0xFFC8CAD0L, 0L, 0f, 1.0f),
            ArtLayer("M29 90 C33 92 44 92 47 89 L47 92 C44 95 32 95 29 93 Z", 0xFFF4F2EEL, 0L, 0f, 1.0f),
            ArtLayer("M53 89 C56 92 68 92 71 90 L71 93 C68 95 56 95 53 92 Z", 0xFFF4F2EEL, 0L, 0f, 1.0f),
            ArtLayer("M33 87 L42 89", 0L, 0xFF6F855AL, 1.6f, 1.0f),
            ArtLayer("M57 87 L66 89", 0L, 0xFF6F855AL, 1.6f, 1.0f)
        )),
        "combat_boots" to GearArt(listOf(
            ArtLayer("M29 78 L47 78 L48 88 C48 93 44 96 38 96 C32 96 27 94 27 90 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M53 78 L71 78 L73 90 C73 94 68 96 62 96 C56 96 52 93 52 88 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M31 79.5 L45.5 79.5 L46.5 88 C46.5 92 43 94.5 38 94.5 C33 94.5 29 93 29 90 Z", 0xFF1C1C1FL, 0L, 0f, 1.0f),
            ArtLayer("M55 79.5 L69.5 79.5 L71 90 C71 93 67 94.5 62 94.5 C57 94.5 54 92 54 88 Z", 0xFF1C1C1FL, 0L, 0f, 1.0f),
            ArtLayer("M29 90 C33 92 44 92 47 89 L47 92 C44 95 32 95 29 93 Z", 0xFF3A3A40L, 0L, 0f, 1.0f),
            ArtLayer("M53 89 C56 92 68 92 71 90 L71 93 C68 95 56 95 53 92 Z", 0xFF3A3A40L, 0L, 0f, 1.0f),
            ArtLayer("M34 81 L42 82.5 M34 84 L42 85.5 M34 87 L42 88.5", 0L, 0xFF8C8F96L, 1.2f, 1.0f),
            ArtLayer("M58 81 L66 82.5 M58 84 L66 85.5 M58 87 L66 88.5", 0L, 0xFF8C8F96L, 1.2f, 1.0f)
        )),
        "desert_boots" to GearArt(listOf(
            ArtLayer("M29 78 L47 78 L48 88 C48 93 44 96 38 96 C32 96 27 94 27 90 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M53 78 L71 78 L73 90 C73 94 68 96 62 96 C56 96 52 93 52 88 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M31 79.5 L45.5 79.5 L46.5 88 C46.5 92 43 94.5 38 94.5 C33 94.5 29 93 29 90 Z", 0xFFD8C9A3L, 0L, 0f, 1.0f),
            ArtLayer("M55 79.5 L69.5 79.5 L71 90 C71 93 67 94.5 62 94.5 C57 94.5 54 92 54 88 Z", 0xFFD8C9A3L, 0L, 0f, 1.0f),
            ArtLayer("M29 90 C33 92 44 92 47 89 L47 92 C44 95 32 95 29 93 Z", 0xFF6B4F2EL, 0L, 0f, 1.0f),
            ArtLayer("M53 89 C56 92 68 92 71 90 L71 93 C68 95 56 95 53 92 Z", 0xFF6B4F2EL, 0L, 0f, 1.0f),
            ArtLayer("M34 81 L42 82.5 M34 84 L42 85.5 M34 87 L42 88.5", 0L, 0xFF4A3620L, 1.2f, 1.0f),
            ArtLayer("M58 81 L66 82.5 M58 84 L66 85.5 M58 87 L66 88.5", 0L, 0xFF4A3620L, 1.2f, 1.0f)
        )),
        "parade_shoes" to GearArt(listOf(
            ArtLayer("M29 83 L47 83 L48 88 C48 93 44 96 38 96 C32 96 27 94 27 90 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M53 83 L71 83 L73 90 C73 94 68 96 62 96 C56 96 52 93 52 88 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M31 84.5 L45.5 84.5 L46.5 88 C46.5 92 43 94.5 38 94.5 C33 94.5 29 93 29 90 Z", 0xFF1C1C1FL, 0L, 0f, 1.0f),
            ArtLayer("M55 84.5 L69.5 84.5 L71 90 C71 93 67 94.5 62 94.5 C57 94.5 54 92 54 88 Z", 0xFF1C1C1FL, 0L, 0f, 1.0f),
            ArtLayer("M32 86 C36 85 42 85 45 86", 0L, 0xFFFFFFFFL, 1.4f, 0.35f),
            ArtLayer("M56 86 C60 85 66 85 69 86", 0L, 0xFFFFFFFFL, 1.4f, 0.35f)
        )),
        "winter_boots" to GearArt(listOf(
            ArtLayer("M29 78 L47 78 L48 88 C48 93 44 96 38 96 C32 96 27 94 27 90 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M53 78 L71 78 L73 90 C73 94 68 96 62 96 C56 96 52 93 52 88 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M31 79.5 L45.5 79.5 L46.5 88 C46.5 92 43 94.5 38 94.5 C33 94.5 29 93 29 90 Z", 0xFF5B4636L, 0L, 0f, 1.0f),
            ArtLayer("M55 79.5 L69.5 79.5 L71 90 C71 93 67 94.5 62 94.5 C57 94.5 54 92 54 88 Z", 0xFF5B4636L, 0L, 0f, 1.0f),
            ArtLayer("M29 90 C33 92 44 92 47 89 L47 92 C44 95 32 95 29 93 Z", 0xFF2F2620L, 0L, 0f, 1.0f),
            ArtLayer("M53 89 C56 92 68 92 71 90 L71 93 C68 95 56 95 53 92 Z", 0xFF2F2620L, 0L, 0f, 1.0f),
            ArtLayer("M34 81 L42 82.5 M34 84 L42 85.5 M34 87 L42 88.5", 0L, 0xFFC9B58AL, 1.2f, 1.0f),
            ArtLayer("M58 81 L66 82.5 M58 84 L66 85.5 M58 87 L66 88.5", 0L, 0xFFC9B58AL, 1.2f, 1.0f),
            ArtLayer("M29 80 L47 80 L47 83 L29 83 Z", 0xFFC9BFA6L, 0L, 0f, 1.0f),
            ArtLayer("M53 80 L71 80 L71 83 L53 83 Z", 0xFFC9BFA6L, 0L, 0f, 1.0f)
        )),
        "backpack" to GearArt(listOf(
            ArtLayer("M6 52 C4 42 14 38 20 42 L22 78 C16 82 6 78 6 72 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M94 52 C96 42 86 38 80 42 L78 78 C84 82 94 78 94 72 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M8 52 C7 44 14 41 19 44 L20.5 76 C16 79 8 76 8 71 Z", 0xFF2F3D27L, 0L, 0f, 1.0f),
            ArtLayer("M92 52 C93 44 86 41 81 44 L79.5 76 C84 79 92 76 92 71 Z", 0xFF2F3D27L, 0L, 0f, 1.0f),
            ArtLayer("M9 56 L18 56 L18 64 L9 64 Z", 0xFF4A5D3AL, 0L, 0f, 1.0f),
            ArtLayer("M82 56 L91 56 L91 64 L82 64 Z", 0xFF4A5D3AL, 0L, 0f, 1.0f),
            ArtLayer("M9 56 L18 56", 0L, 0xFF0A0A0BL, 1.2f, 1.0f),
            ArtLayer("M82 56 L91 56", 0L, 0xFF0A0A0BL, 1.2f, 1.0f)
        ), straps = listOf(
            ArtLayer("M20 60 C26 66 30 74 32 86", 0L, 0xFF0A0A0BL, 4.4f, 1.0f),
            ArtLayer("M80 60 C74 66 70 74 68 86", 0L, 0xFF0A0A0BL, 4.4f, 1.0f),
            ArtLayer("M20 60 C26 66 30 74 32 86", 0L, 0xFF2B3324L, 2.6f, 1.0f),
            ArtLayer("M80 60 C74 66 70 74 68 86", 0L, 0xFF2B3324L, 2.6f, 1.0f),
            ArtLayer("M26 70 L33 70", 0L, 0xFF0A0A0BL, 2.6f, 1.0f),
            ArtLayer("M67 70 L74 70", 0L, 0xFF0A0A0BL, 2.6f, 1.0f)
        )),
        "dog_tags" to GearArt(listOf(
            ArtLayer("M33 62 C38 70 44 76 50 78 C56 76 62 70 67 62", 0L, 0xFFC0C4CCL, 1.1f, 1.0f),
            ArtLayer("M33 62 C38 70 44 76 50 78 C56 76 62 70 67 62", 0L, 0xFF0A0A0BL, 0.4f, 0.5f),
            ArtLayer("M47 76 L53.5 77.5 L52.5 84 L46 82.5 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M47.8 76.9 L52.6 78 L51.8 83 L47 81.9 Z", 0xFFC0C4CCL, 0L, 0f, 1.0f),
            ArtLayer("M48.5 77.5 L50.5 78", 0L, 0xFFFFFFFFL, 0.8f, 0.6f)
        )),
        "ribbons" to GearArt(listOf(
            ArtLayer("M63 65 L76 65 L76 71.5 L63 71.5 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M64 66 L68 66 L68 70.5 L64 70.5 Z", 0xFFB5342EL, 0L, 0f, 1.0f),
            ArtLayer("M68 66 L72 66 L72 70.5 L68 70.5 Z", 0xFFD9B24CL, 0L, 0f, 1.0f),
            ArtLayer("M72 66 L75 66 L75 70.5 L72 70.5 Z", 0xFF2F5DA8L, 0L, 0f, 1.0f)
        )),
        "medal" to GearArt(listOf(
            ArtLayer("M65 65 L74 65 L73 73 L66 73 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M66 66 L73 66 L72.2 72 L66.8 72 Z", 0xFFB5342EL, 0L, 0f, 1.0f),
            ArtLayer("M68.5 66 L70.5 66 L70.1 72 L68.9 72 Z", 0xFFD9B24CL, 0L, 0f, 1.0f),
            ArtLayer("M69.5 72 L69.5 74.5", 0L, 0xFF0A0A0BL, 1.2f, 1.0f),
            ArtLayer("M69.5 79.5 m-5.2 0 a5.2 5.2 0 1 0 10.4 0 a5.2 5.2 0 1 0 -10.4 0", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M69.5 79.5 m-4 0 a4 4 0 1 0 8 0 a4 4 0 1 0 -8 0", 0xFFD9B24CL, 0L, 0f, 1.0f),
            ArtLayer("M69.5 76.6 L70.4 78.7 L72.7 78.8 L70.9 80.3 L71.5 82.5 L69.5 81.2 L67.5 82.5 L68.1 80.3 L66.3 78.8 L68.6 78.7 Z", 0xFFA8862EL, 0L, 0f, 1.0f)
        )),
        "flag_patch" to GearArt(listOf(
            ArtLayer("M63 65 L76 65 L76 72.5 L63 72.5 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M64 66 L68 66 L68 71.5 L64 71.5 Z", 0xFF1F4FA3L, 0L, 0f, 1.0f),
            ArtLayer("M68 66 L72 66 L72 71.5 L68 71.5 Z", 0xFFF2D24BL, 0L, 0f, 1.0f),
            ArtLayer("M72 66 L75 66 L75 71.5 L72 71.5 Z", 0xFFC8322BL, 0L, 0f, 1.0f)
        )),
        "shemagh" to GearArt(listOf(
            ArtLayer("M12 62 C20 56 34 56 42 62 C46 65 54 65 58 62 C66 56 80 56 88 62 C82 66 70 68 62 66 L50 74 L38 66 C30 68 18 66 12 62 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M13.5 61.5 C21 57 33 57 41 62.5 C45.5 65.5 54.5 65.5 59 62.5 C67 57 79 57 86.5 61.5 C80 65 69 66.5 61.5 64.5 L50 72 L38.5 64.5 C31 66.5 20 65 13.5 61.5 Z", 0xFFC9B58AL, 0L, 0f, 1.0f),
            ArtLayer("M18 61 L24 63 M26 59 L30 62.5 M70 62.5 L74 59 M76 63 L82 61 M44 64 L46 66 M54 66 L56 64", 0L, 0xFF4A3620L, 0.9f, 0.7f),
            ArtLayer("M50 72 L46 80 M50 72 L54 80", 0L, 0xFFA8946CL, 1.6f, 1.0f)
        )),
        "headset" to GearArt(listOf(
            ArtLayer("M24 44 C22 22 78 22 76 44", 0L, 0xFF0A0A0BL, 5.0f, 1.0f),
            ArtLayer("M24 44 C22 22 78 22 76 44", 0L, 0xFF2B2F36L, 3.0f, 1.0f),
            ArtLayer("M18 44 L30 44 L30 58 L18 58 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M19.5 45.5 L28.5 45.5 L28.5 56.5 L19.5 56.5 Z", 0xFF1C1C1FL, 0L, 0f, 1.0f),
            ArtLayer("M70 44 L82 44 L82 58 L70 58 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M71.5 45.5 L80.5 45.5 L80.5 56.5 L71.5 56.5 Z", 0xFF1C1C1FL, 0L, 0f, 1.0f),
            ArtLayer("M21.5 48 L26.5 48 L26.5 54 L21.5 54 Z", 0xFF6F855AL, 0L, 0f, 0.9f),
            ArtLayer("M73.5 48 L78.5 48 L78.5 54 L73.5 54 Z", 0xFF6F855AL, 0L, 0f, 0.9f),
            ArtLayer("M28 56 C34 62 40 66 44 66", 0L, 0xFF0A0A0BL, 2.2f, 1.0f),
            ArtLayer("M44 66 m-2 0 a2 2 0 1 0 4 0 a2 2 0 1 0 -4 0", 0xFF0A0A0BL, 0L, 0f, 1.0f)
        )),
    )

    /** Insigna fiecărui grad (indexul = gradul), pe pieptul stâng al hainei. */
    val ranks: List<List<ArtLayer>> = listOf(
        listOf(),  // Recrut
        listOf(
            ArtLayer("M21.5 64 L37.5 64 L37.5 74.5 L21.5 74.5 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M22.5 65 L36.5 65 L36.5 73.5 L22.5 73.5 Z", 0xFF2B3324L, 0L, 0f, 1.0f)
        ),  // Soldat
        listOf(
            ArtLayer("M21.5 64 L37.5 64 L37.5 74.5 L21.5 74.5 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M22.5 65 L36.5 65 L36.5 73.5 L22.5 73.5 Z", 0xFF2B3324L, 0L, 0f, 1.0f),
            ArtLayer("M24.5 70.1 L29.5 67.5 L34.5 70.1", 0L, 0xFFD9B24CL, 1.7f, 1.0f)
        ),  // Fruntaș
        listOf(
            ArtLayer("M21.5 64 L37.5 64 L37.5 74.5 L21.5 74.5 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M22.5 65 L36.5 65 L36.5 73.5 L22.5 73.5 Z", 0xFF2B3324L, 0L, 0f, 1.0f),
            ArtLayer("M24.5 69.3 L29.5 66.7 L34.5 69.3", 0L, 0xFFD9B24CL, 1.7f, 1.0f),
            ArtLayer("M24.5 71.7 L29.5 69.10000000000001 L34.5 71.7", 0L, 0xFFD9B24CL, 1.7f, 1.0f)
        ),  // Caporal
        listOf(
            ArtLayer("M21.5 64 L37.5 64 L37.5 74.5 L21.5 74.5 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M22.5 65 L36.5 65 L36.5 73.5 L22.5 73.5 Z", 0xFF2B3324L, 0L, 0f, 1.0f),
            ArtLayer("M24.5 68.89999999999999 L29.5 66.3 L34.5 68.89999999999999", 0L, 0xFFD9B24CL, 1.7f, 1.0f),
            ArtLayer("M24.5 71.3 L29.5 68.7 L34.5 71.3", 0L, 0xFFD9B24CL, 1.7f, 1.0f),
            ArtLayer("M24.5 73.69999999999999 L29.5 71.1 L34.5 73.69999999999999", 0L, 0xFFD9B24CL, 1.7f, 1.0f)
        ),  // Sergent
        listOf(
            ArtLayer("M21.5 64 L37.5 64 L37.5 74.5 L21.5 74.5 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M22.5 65 L36.5 65 L36.5 73.5 L22.5 73.5 Z", 0xFF2B3324L, 0L, 0f, 1.0f),
            ArtLayer("M24.5 69.3 L29.5 66.7 L34.5 69.3", 0L, 0xFFD9B24CL, 1.7f, 1.0f),
            ArtLayer("M24.5 71.7 L29.5 69.10000000000001 L34.5 71.7", 0L, 0xFFD9B24CL, 1.7f, 1.0f),
            ArtLayer("M24.5 72.3 L34.5 72.3", 0L, 0xFFD9B24CL, 1.7f, 1.0f)
        ),  // Sergent-major
        listOf(
            ArtLayer("M21.5 64 L37.5 64 L37.5 74.5 L21.5 74.5 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M22.5 65 L36.5 65 L36.5 73.5 L22.5 73.5 Z", 0xFF2B3324L, 0L, 0f, 1.0f),
            ArtLayer("M24.5 67.39999999999999 L34.5 67.39999999999999", 0L, 0xFFD9B24CL, 1.7f, 1.0f),
            ArtLayer("M24.5 69.99999999999999 L34.5 69.99999999999999", 0L, 0xFFD9B24CL, 1.7f, 1.0f)
        ),  // Plutonier
        listOf(
            ArtLayer("M21.5 64 L37.5 64 L37.5 74.5 L21.5 74.5 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M22.5 65 L36.5 65 L36.5 73.5 L22.5 73.5 Z", 0xFF2B3324L, 0L, 0f, 1.0f),
            ArtLayer("M24.5 66.8 L34.5 66.8", 0L, 0xFFD9B24CL, 1.7f, 1.0f),
            ArtLayer("M24.5 69.39999999999999 L34.5 69.39999999999999", 0L, 0xFFD9B24CL, 1.7f, 1.0f),
            ArtLayer("M24.5 72.0 L34.5 72.0", 0L, 0xFFD9B24CL, 1.7f, 1.0f)
        ),  // Plutonier-adjutant
        listOf(
            ArtLayer("M21.5 64 L37.5 64 L37.5 74.5 L21.5 74.5 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M22.5 65 L36.5 65 L36.5 73.5 L22.5 73.5 Z", 0xFF2B3324L, 0L, 0f, 1.0f),
            ArtLayer("M29.50 66.00 L30.35 68.04 L32.54 68.21 L30.87 69.64 L31.38 71.79 L29.50 70.64 L27.62 71.79 L28.13 69.64 L26.46 68.21 L28.65 68.04 Z", 0xFFD9B24CL, 0L, 0f, 1.0f)
        ),  // Sublocotenent
        listOf(
            ArtLayer("M21.5 64 L37.5 64 L37.5 74.5 L21.5 74.5 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M22.5 65 L36.5 65 L36.5 73.5 L22.5 73.5 Z", 0xFF2B3324L, 0L, 0f, 1.0f),
            ArtLayer("M26.30 66.00 L27.15 68.04 L29.34 68.21 L27.67 69.64 L28.18 71.79 L26.30 70.64 L24.42 71.79 L24.93 69.64 L23.26 68.21 L25.45 68.04 Z", 0xFFD9B24CL, 0L, 0f, 1.0f),
            ArtLayer("M32.70 66.00 L33.55 68.04 L35.74 68.21 L34.07 69.64 L34.58 71.79 L32.70 70.64 L30.82 71.79 L31.33 69.64 L29.66 68.21 L31.85 68.04 Z", 0xFFD9B24CL, 0L, 0f, 1.0f)
        ),  // Locotenent
        listOf(
            ArtLayer("M21.5 64 L37.5 64 L37.5 74.5 L21.5 74.5 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M22.5 65 L36.5 65 L36.5 73.5 L22.5 73.5 Z", 0xFF2B3324L, 0L, 0f, 1.0f),
            ArtLayer("M25.10 66.50 L25.81 68.22 L27.67 68.37 L26.26 69.58 L26.69 71.38 L25.10 70.42 L23.51 71.38 L23.94 69.58 L22.53 68.37 L24.39 68.22 Z", 0xFFD9B24CL, 0L, 0f, 1.0f),
            ArtLayer("M29.50 66.50 L30.21 68.22 L32.07 68.37 L30.66 69.58 L31.09 71.38 L29.50 70.42 L27.91 71.38 L28.34 69.58 L26.93 68.37 L28.79 68.22 Z", 0xFFD9B24CL, 0L, 0f, 1.0f),
            ArtLayer("M33.90 66.50 L34.61 68.22 L36.47 68.37 L35.06 69.58 L35.49 71.38 L33.90 70.42 L32.31 71.38 L32.74 69.58 L31.33 68.37 L33.19 68.22 Z", 0xFFD9B24CL, 0L, 0f, 1.0f)
        ),  // Căpitan
        listOf(
            ArtLayer("M21.5 64 L37.5 64 L37.5 74.5 L21.5 74.5 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M22.5 65 L36.5 65 L36.5 73.5 L22.5 73.5 Z", 0xFF2B3324L, 0L, 0f, 1.0f),
            ArtLayer("M29.50 65.20 L30.35 67.24 L32.54 67.41 L30.87 68.84 L31.38 70.99 L29.50 69.84 L27.62 70.99 L28.13 68.84 L26.46 67.41 L28.65 67.24 Z", 0xFFC0C4CCL, 0L, 0f, 1.0f),
            ArtLayer("M24.5 72.9 L34.5 72.9", 0L, 0xFFC0C4CCL, 1.5f, 1.0f)
        ),  // Maior
        listOf(
            ArtLayer("M21.5 64 L37.5 64 L37.5 74.5 L21.5 74.5 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M22.5 65 L36.5 65 L36.5 73.5 L22.5 73.5 Z", 0xFF2B3324L, 0L, 0f, 1.0f),
            ArtLayer("M25.10 66.50 L25.81 68.22 L27.67 68.37 L26.26 69.58 L26.69 71.38 L25.10 70.42 L23.51 71.38 L23.94 69.58 L22.53 68.37 L24.39 68.22 Z", 0xFFC0C4CCL, 0L, 0f, 1.0f),
            ArtLayer("M29.50 66.50 L30.21 68.22 L32.07 68.37 L30.66 69.58 L31.09 71.38 L29.50 70.42 L27.91 71.38 L28.34 69.58 L26.93 68.37 L28.79 68.22 Z", 0xFFC0C4CCL, 0L, 0f, 1.0f),
            ArtLayer("M33.90 66.50 L34.61 68.22 L36.47 68.37 L35.06 69.58 L35.49 71.38 L33.90 70.42 L32.31 71.38 L32.74 69.58 L31.33 68.37 L33.19 68.22 Z", 0xFFC0C4CCL, 0L, 0f, 1.0f)
        ),  // Colonel
        listOf(
            ArtLayer("M21.5 64 L37.5 64 L37.5 74.5 L21.5 74.5 Z", 0xFF0A0A0BL, 0L, 0f, 1.0f),
            ArtLayer("M22.5 65 L36.5 65 L36.5 73.5 L22.5 73.5 Z", 0xFF2B3324L, 0L, 0f, 1.0f),
            ArtLayer("M24 72.6 C25 65.5 34 65.5 35 72.6", 0L, 0xFFD9B24CL, 1.5f, 1.0f),
            ArtLayer("M29.50 65.80 L30.29 67.71 L32.35 67.87 L30.78 69.22 L31.26 71.23 L29.50 70.15 L27.74 71.23 L28.22 69.22 L26.65 67.87 L28.71 67.71 Z", 0xFFD9B24CL, 0L, 0f, 1.0f)
        ),  // General
    )
}
