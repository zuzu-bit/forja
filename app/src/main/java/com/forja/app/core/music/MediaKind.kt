package com.forja.app.core.music

/*
 * Clasificatorul „ce fel de sesiune e asta”: muzică, vorbit (carte audio, podcast), video.
 * Kotlin pur (fără Android), ca să poată fi testat pe JVM: stratul Android traduce constantele sistemului în
 * [ContentHint] și [AppCategory] înainte să întrebe.
 */

/** Felul conținutului unei sesiuni media. `code` e forma scurtă din istoricul local. */
enum class MediaKind(val wire: String, val code: String) {
    MUSIC("music", "M"),
    SPOKEN("spoken", "S"),
    VIDEO("video", "V"),
    UNKNOWN("unknown", "U");

    companion object {
        fun ofCode(code: String?): MediaKind? = entries.firstOrNull { it.code == code }
    }
}

/** Tipul de conținut al unui player audio activ (AudioAttributes.CONTENT_TYPE_*), cât cântă. */
enum class ContentHint { MUSIC, SPEECH, MOVIE, NONE }

/** Categoria declarată de aplicație (ApplicationInfo.category). */
enum class AppCategory { AUDIO, VIDEO, OTHER }

object MusicKind {
    const val SPOTIFY = "com.spotify.music"
    const val YT_MUSIC = "com.google.android.apps.youtube.music"
    const val SAMSUNG_MUSIC = "com.sec.android.app.music"
    /** YouTube (video): nu e un player de muzică, dar ține des tasta media (29.09). */
    const val YOUTUBE = "com.google.android.youtube"

    /** Playerele de muzică cunoscute, cu numele afișat (fără să întrebăm sistemul). */
    val MUSIC_APPS: Map<String, String> = linkedMapOf(
        SPOTIFY to "Spotify",
        YT_MUSIC to "YouTube Music",
        "com.apple.android.music" to "Apple Music",
        "deezer.android.app" to "Deezer",
        "com.aspiro.tidal" to "Tidal",
        "com.soundcloud.android" to "SoundCloud",
        "com.amazon.mp3" to "Amazon Music",
        SAMSUNG_MUSIC to "Samsung Music",
        "com.maxmpz.audioplayer" to "Poweramp",
        "in.krosbits.musicolet" to "Musicolet",
        "com.pandora.android" to "Pandora",
        "com.audiomack" to "Audiomack",
        "com.anghami" to "Anghami",
        "com.qobuz.music" to "Qobuz",
        "com.miui.player" to "Mi Music",
        "com.google.android.music" to "Play Music"
    )

    /** Cărți audio și podcasturi: nu sunt „muzica ta”, nu devin niciodată eroul ecranului Muzică. */
    val SPOKEN_APPS: Set<String> = setOf(
        "com.audible.application",
        "grit.storytel.app",
        "com.bookbeat.android",
        "au.com.shiftyjelly.pocketcasts",
        "com.bambuna.podcastaddict",
        "fm.castbox.audiobook.radio.podcast",
        "com.google.android.apps.podcasts",
        "com.spotify.podcasts",
        "com.nextory.app",
        "com.storytel.lite"
    )

    /** Video și browsere: sunetul lor nu e „muzica ta”. */
    val VIDEO_APPS: Set<String> = setOf(
        YOUTUBE,
        "com.android.chrome",
        "com.sec.android.app.sbrowser",
        "org.mozilla.firefox",
        "com.instagram.android",
        "com.facebook.katana",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.netflix.mediaclient"
    )

    private val SPOKEN_ID_PREFIXES = listOf("spotify:episode:", "spotify:show:", "spotify:audiobook:", "spotify:chapter:")
    private val SPOKEN_GENRES = listOf("podcast", "audiobook", "audio book", "carte audio", "carti audio", "cărți audio", "hörbuch")

    /** Capitolele și episoadele sunt lungi; melodiile rareori. */
    const val LONG_MS = 20 * 60_000L

    /**
     * Felul sesiunii, în ordine (primul răspuns câștigă): lista de aplicații vorbite/video, prefixul ID-ului media
     * (spotify:episode:… = vorbit, spotify:track:… = muzică), genul, durata ≥ 20 min, tipul de conținut cât cântă,
     * lista de playere de muzică, categoria aplicației. Necunoscut altfel.
     */
    fun classify(
        pkg: String,
        mediaId: String? = null,
        durationMs: Long = 0L,
        genre: String? = null,
        content: ContentHint = ContentHint.NONE,
        category: AppCategory = AppCategory.OTHER
    ): MediaKind {
        if (pkg in SPOKEN_APPS) return MediaKind.SPOKEN
        if (pkg in VIDEO_APPS) return MediaKind.VIDEO
        val id = mediaId?.trim()?.lowercase()
        if (!id.isNullOrEmpty()) {
            if (SPOKEN_ID_PREFIXES.any { id.startsWith(it) }) return MediaKind.SPOKEN
            if (id.startsWith("spotify:track:")) return MediaKind.MUSIC
        }
        val g = genre?.lowercase()
        if (g != null && SPOKEN_GENRES.any { g.contains(it) }) return MediaKind.SPOKEN
        if (durationMs >= LONG_MS) return MediaKind.SPOKEN
        when (content) {
            ContentHint.SPEECH -> return MediaKind.SPOKEN
            ContentHint.MOVIE -> return MediaKind.VIDEO
            ContentHint.MUSIC -> return MediaKind.MUSIC
            ContentHint.NONE -> Unit
        }
        if (pkg in MUSIC_APPS) return MediaKind.MUSIC
        return when (category) {
            AppCategory.AUDIO -> MediaKind.MUSIC
            AppCategory.VIDEO -> MediaKind.VIDEO
            AppCategory.OTHER -> MediaKind.UNKNOWN
        }
    }

    /** Un pachet din care FORJA poate cere muzică (player cunoscut sau folosit pentru muzică în istoric). */
    fun isMusicPlayer(pkg: String?, history: Set<String> = emptySet()): Boolean =
        pkg != null && pkg !in SPOKEN_APPS && pkg !in VIDEO_APPS && (pkg in MUSIC_APPS || pkg in history)

    /**
     * Forma unui ID media, fără valoarea lui (pentru jurnal): „spotify:track:22” = prefixul + lungimea restului.
     * Nimic din titlu sau din ID nu pleacă de pe telefon.
     */
    fun idShape(mediaId: String?): String? {
        val id = mediaId?.trim()
        if (id.isNullOrEmpty()) return null
        val cut = id.lastIndexOf(':')
        if (cut <= 0 || cut >= id.length - 1) return "other:${id.length}"
        val prefix = id.substring(0, cut + 1)
        return if (prefix.length <= 24 && prefix.all { it.isLetterOrDigit() || it == ':' }) "$prefix${id.length - cut - 1}" else "other:${id.length}"
    }

    /** ID-ul unei piese Spotify („spotify:track:<id>”), dacă ID-ul media are forma asta. */
    fun spotifyTrackId(mediaId: String?): String? {
        val id = mediaId?.trim() ?: return null
        if (!id.startsWith("spotify:track:")) return null
        val rest = id.removePrefix("spotify:track:")
        return rest.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() } }
    }
}
