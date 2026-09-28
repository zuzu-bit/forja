package com.forja.app.core.music

/*
 * Modelul pornirii muzicii (music-start.md §6, workout-music.md §3.8): intenții, trepte, starea vizibilă.
 * Kotlin pur — planificatorul ([Planner]) și mașina de stări ([StartMachine]) se testează pe JVM cu sesiuni false.
 */

/**
 * O treaptă din scara de pornire. `id` e ce scrie jurnalul. Treptele vizibile aduc playerul în față (se fac doar la o
 * atingere); cele terminale doar deschid playerul (nu se verifică).
 */
enum class Rung(val id: String, val visible: Boolean, val terminal: Boolean = false) {
    /** Muzica ta cânta deja: nu se trimite nimic. */
    ALREADY("ALREADY", false),
    S_PLAY("S_PLAY", false),
    S_BTN("S_BTN", false),
    S_TOP("S_TOP", false),
    S_LIKED("S_LIKED", false),
    S_ANY("S_ANY", false),
    K_TOKEN("K_TOKEN", false),
    K_PLAY("K_PLAY", false),
    V_TRACK("V_TRACK", true),
    V_PFS_TOP("V_PFS_TOP", true),
    V_LIKED_PLAY("V_LIKED_PLAY", true),
    V_PFS_DATA("V_PFS_DATA", true),
    V_PFS_ANY("V_PFS_ANY", true),
    O_SESSION("O_SESSION", true, terminal = true),
    O_LIKED_PAGE("O_LIKED_PAGE", true, terminal = true),
    O_LAUNCH("O_LAUNCH", true, terminal = true);

    companion object {
        fun of(id: String?): Rung? = entries.firstOrNull { it.id == id }
    }
}

/** O piesă de cerut playerului: titlu + artist (căutare), ID/URI media când sunt cunoscute. */
data class TrackRef(
    val title: String,
    val artist: String,
    val pkg: String? = null,
    val mediaId: String? = null,
    val uri: String? = null
) {
    val query: String get() = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
}

/** Ce vrea omul (butonul apăsat). */
sealed interface Want {
    val wire: String

    /** Play pe o sesiune arătată: exact sesiunea aceea, niciodată alt player. */
    data class Resume(val sessionId: String) : Want { override val wire = "resume" }

    /** „Pornește muzica”: muzica ta pusă pe pauză, altfel piesa ta de top, altfel Melodii apreciate. */
    data object MyMusic : Want { override val wire = "mymusic" }

    /** Cipul „TOP 1”: piesa cea mai ascultată; autoplay-ul playerului continuă cu piese asemănătoare. */
    data object Top : Want { override val wire = "top" }

    /** „Începe sesiunea” (Antrenament): prima piesă din lista FORJA sau, fără listă, Melodii apreciate. */
    data class Workout(val first: TrackRef?) : Want { override val wire = "workout" }

    /** Proba ascunsă: o singură treaptă, pe playerul dat. */
    data class Probe(val rung: Rung, val pkg: String) : Want { override val wire = "probe" }
}

/** Cine a pornit muzica (împrumutul): Inventarul, Antrenamentul sau proba. */
enum class MusicSource(val wire: String) { INVENTORY("inv"), WORKOUT("workout"), PROBE("probe") }

/** Starea sesiunii (PlaybackState.STATE_*), strânsă la ce contează pentru verificare. */
enum class PState {
    NONE, STOPPED, PAUSED, PLAYING, BUFFERING, CONNECTING, SKIPPING, ERROR;

    /** În curs de pornire (nu încă „cântă”): extinde așteptarea. */
    val transitional: Boolean get() = this == BUFFERING || this == CONNECTING || this == SKIPPING

    /** Pentru afișare (vechea regulă largă): cântă sau pe cale să cânte. */
    val activeish: Boolean get() = this == PLAYING || transitional

    companion object {
        /** Din constantele PlaybackState (0 NONE, 1 STOPPED, 2 PAUSED, 3 PLAYING, 4 FF, 5 REW, 6 BUFFERING, 7 ERROR, 8 CONNECTING, 9–11 SKIPPING). */
        fun of(code: Int?): PState = when (code) {
            1 -> STOPPED
            2 -> PAUSED
            3, 4, 5 -> PLAYING
            6 -> BUFFERING
            7 -> ERROR
            8 -> CONNECTING
            9, 10, 11 -> SKIPPING
            else -> NONE
        }
    }
}

/** O sesiune media văzută de FORJA (cu „Acces la notificări”). */
data class SessionView(
    val id: String,
    val pkg: String,
    val kind: MediaKind,
    val state: PState,
    /** PlaybackState.actions (masca de biți). */
    val actions: Long = 0L,
    val title: String? = null,
    val artist: String? = null,
    val mediaId: String? = null,
    val durationMs: Long = 0L,
    /** Poziția extrapolată în momentul instantaneului. */
    val positionMs: Long = 0L,
    val speed: Float = 1f,
    val remote: Boolean = false
) {
    val hasMetadata: Boolean get() = !title.isNullOrBlank()
    fun can(bit: Long): Boolean = actions == 0L || (actions and bit) != 0L

    companion object {
        const val ACTION_PAUSE = 2L
        const val ACTION_PLAY = 4L
        const val ACTION_PLAY_PAUSE = 512L
        const val ACTION_PLAY_FROM_MEDIA_ID = 1024L
        const val ACTION_PLAY_FROM_SEARCH = 2048L
        const val ACTION_PLAY_FROM_URI = 8192L
    }
}

/** Un player audio activ, anonim (AudioPlaybackConfiguration): fără pachet, dar cu tipul de conținut. */
data class ConfigView(val id: Int, val content: ContentHint)

/** Tot ce știe FORJA în momentul unei decizii. */
data class Snapshot(
    val access: Boolean,
    val sdk: Int = 35,
    /** FORJA e vizibilă (se pot face salturi în player și comenzi către un player oprit). */
    val fg: Boolean = true,
    val sessions: List<SessionView> = emptyList(),
    val configs: List<ConfigView> = emptyList(),
    /** Pachetul care primește tasta media acum (API 33+, cu acces); null = necunoscut. */
    val keyTarget: String? = null,
    /** Sesiunea tastei media e în afara listei active (se poate comanda prin token: K_TOKEN). */
    val keyTokenOutside: Boolean = false,
    /** Playerul tău de muzică (cel mai folosit, ultimul, Spotify…). */
    val preferredPkg: String? = null,
    /** Piesa ta de top (7 zile), cu pachetul și ID-ul media dacă sunt știute. */
    val top: TrackRef? = null,
    /** Playere de muzică instalate. */
    val installed: Set<String> = emptySet(),
    /** Pachete care răspund la MEDIA_PLAY_FROM_SEARCH. */
    val searchable: Set<String> = emptySet(),
    /** Pachete folosite pentru muzică în istoric. */
    val historyPkgs: Set<String> = emptySet(),
    val learned: LearnedTable = LearnedTable.EMPTY,
    /** versionName per pachet (tabelul învățat se leagă de versiune). */
    val versions: Map<String, String> = emptyMap(),
    /** Momentul instantaneului (ms epocă); 0 = acum. */
    val now: Long = 0L
) {
    val clock: Long get() = if (now > 0L) now else System.currentTimeMillis()

    fun session(id: String?): SessionView? = if (id == null) null else sessions.firstOrNull { it.id == id }
    fun firstOf(pkg: String?): SessionView? = if (pkg == null) null else sessions.firstOrNull { it.pkg == pkg }
    /** Sesiunea locală a playerului (nu Spotify Connect / Cast: acolo muzica ar porni pe boxă, nu pe telefon). */
    fun localOf(pkg: String?): SessionView? = if (pkg == null) null else sessions.firstOrNull { it.pkg == pkg && !it.remote }
    val musicPlaying: Boolean
        get() = if (access) sessions.any { it.state == PState.PLAYING && it.kind == MediaKind.MUSIC }
        else configs.any { it.content == ContentHint.MUSIC }

    fun wanted(pkg: String?): Boolean = pkg != null && (pkg == preferredPkg || MusicKind.isMusicPlayer(pkg, historyPkgs))
}

/** Un pas planificat: treapta, playerul, sesiunea, piesa; `skip` = de ce nu se poate (se scrie în jurnal). */
data class Step(
    val rung: Rung,
    val pkg: String? = null,
    val sessionId: String? = null,
    val track: TrackRef? = null,
    val skip: String? = null
) {
    val key: String get() = "${rung.id}|${pkg ?: ""}|${sessionId ?: ""}"
}

/** Semnul de pe disc: lista FORJA, Melodii apreciate, nimic. */
enum class Badge { NONE, FORJA, LIKED }

enum class FailReason { NO_PLAYER, REFUSED, TIMEOUT }

/** Starea vizibilă a pornirii (ecranul Muzică, discul din Antrenament). */
sealed interface StartState {
    data object Idle : StartState
    data class Starting(val rung: Rung, val want: Want) : StartState
    data class Playing(val route: Rung, val badge: Badge = Badge.NONE, val wrongTrack: Boolean = false) : StartState
    /** Următoarea treaptă e vizibilă: o atingere („Deschide Spotify”) o face. */
    data class NeedsTap(val step: Step, val want: Want) : StartState
    /** Playerul a fost deschis (terminal); la întoarcere: cântă sau înapoi la început. */
    data class InPlayer(val pkg: String?) : StartState
    /** „Nu a pornit.” — `open` deschide playerul. */
    data class Failed(val reason: FailReason, val open: Step?) : StartState
}

/** Rezultatul unui pas, cum îl vede serverul (DESIGN-4.4 §3.5). */
enum class DiagResult(val wire: String) {
    OK("ok"), REFUSED("refused"), TIMEOUT("timeout"), WRONG_KIND("wrong_kind"), WRONG_TRACK("wrong_track"),
    ERROR("error"), SKIPPED("skipped"), NEEDS_TAP("needs_tap")
}

/** Un rând de jurnal: fără titluri, fără artiști. */
data class AttemptEvent(
    val at: Long,
    val want: String,
    val rung: String,
    val pkg: String?,
    val ver: String?,
    val kind: MediaKind?,
    val result: DiagResult,
    val ms: Long,
    val err: String? = null
)

/** Ce a întors trimiterea unei trepte (apelul, nu efectul: efectul se verifică separat). */
sealed interface SendResult {
    data class Sent(val note: String? = null) : SendResult
    data class Skipped(val reason: String) : SendResult
    data class Error(val cls: String) : SendResult
}

/** Ce se oprește după o pornire greșită (o carte audio trezită de tasta media). */
sealed interface UndoTarget {
    data class Session(val id: String) : UndoTarget
    /** Fără acces: tasta PAUSE (ajunge la aceeași sesiune ca PLAY). */
    data object Key : UndoTarget
}

/** Ce face discul Play/Pauză. */
enum class ToggleAction { PAUSE, RESUME, START, NONE }

/**
 * Decizia discului Play/Pauză — din starea REALĂ a sesiunii (sau, fără acces, din playerele audio active), niciodată
 * dintr-un steag optimist: în 4.3, după „Pornește muzica”, Play trimitea PAUZĂ la fiecare atingere (H5).
 */
object Transport {
    /** [heroPlaying]: null = nicio sesiune arătată; [audible]: fără acces, se aude un player media. */
    fun toggle(access: Boolean, heroPlaying: Boolean?, audible: Boolean, state: StartState): ToggleAction = when {
        state is StartState.Starting -> ToggleAction.NONE
        access && heroPlaying == true -> ToggleAction.PAUSE
        access && heroPlaying == false -> ToggleAction.RESUME
        !access && audible -> ToggleAction.PAUSE
        else -> ToggleAction.START
    }
}
