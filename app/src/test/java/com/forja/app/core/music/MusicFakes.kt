package com.forja.app.core.music

/*
 * Sesiuni și playere false pentru testele motorului de muzică (Kotlin pur, fără Android).
 */

/** Ziua ei de pe 29.09: un video YouTube (pe pauză) ține tasta media, Spotify e închis, detecția n-a găsit nimic. */
fun lanaSnap(
    preferred: String? = null,
    top: TrackRef? = null,
    learned: LearnedTable = LearnedTable.EMPTY,
    history: Set<String> = emptySet()
) = snap(
    sessions = listOf(session("yt", YOUTUBE, kind = MediaKind.VIDEO, state = PState.PAUSED, title = "Un video", artist = "Un canal")),
    keyTarget = YOUTUBE, preferred = preferred, installed = emptySet(), searchable = emptySet(), top = top,
    learned = learned, history = history
)

const val SPOTIFY = "com.spotify.music"
const val STORYTEL = "grit.storytel.app"
const val YOUTUBE = "com.google.android.youtube"
const val YTM = "com.google.android.apps.youtube.music"

fun session(
    id: String,
    pkg: String,
    kind: MediaKind = MediaKind.MUSIC,
    state: PState = PState.PAUSED,
    title: String? = "Marș de dimineață",
    artist: String? = "Fanfara FORJA",
    actions: Long = 0L,
    mediaId: String? = null,
    speed: Float = 1f
) = SessionView(id = id, pkg = pkg, kind = kind, state = state, actions = actions, title = title, artist = artist, mediaId = mediaId, speed = speed)

val TOP = TrackRef("Marș de dimineață", "Fanfara FORJA", SPOTIFY, "spotify:track:4uLU6hMCjMI75M1A2tKUQC")

fun snap(
    access: Boolean = true,
    sessions: List<SessionView> = emptyList(),
    keyTarget: String? = null,
    keyTokenOutside: Boolean = false,
    preferred: String? = SPOTIFY,
    top: TrackRef? = TOP,
    installed: Set<String> = setOf(SPOTIFY),
    searchable: Set<String> = setOf(SPOTIFY),
    fg: Boolean = true,
    configs: List<ConfigView> = emptyList(),
    learned: LearnedTable = LearnedTable.EMPTY,
    now: Long = 1_700_000_000_000L,
    /** Playere dovedite lipsă (4.4.1): doar așa dispare Spotify din plan. */
    absent: Set<String> = emptySet(),
    history: Set<String> = setOf(SPOTIFY)
) = Snapshot(
    access = access, sdk = 35, fg = fg, sessions = sessions, configs = configs, keyTarget = keyTarget,
    keyTokenOutside = keyTokenOutside, preferredPkg = preferred, top = top, installed = installed, searchable = searchable,
    historyPkgs = history, absent = absent, learned = learned, versions = mapOf(SPOTIFY to "9.0.62"), now = now
)

/**
 * Lumea falsă pentru [StartMachine]: ceas controlat, sesiuni schimbate de test, reacțiile playerelor la comenzi
 * scrise ca reguli ([onSend]). Păstrează tot ce trimite mașina.
 */
class FakePort(var world: Snapshot) : StartPort {
    var clock = 1_000L
    val sent = ArrayList<Step>()
    val undone = ArrayList<UndoTarget>()
    val states = ArrayList<StartState>()
    val events = ArrayList<AttemptEvent>()
    val learned = ArrayList<Triple<String?, Rung, LearnedTable.Outcome>>()
    val successes = ArrayList<Step>()
    var scheduledAt: Long? = null
    var endedCount = 0
    /** Ce face „playerul” când primește o treaptă (poate schimba [world]); întoarce rezultatul apelului. */
    var onSend: (Step) -> SendResult = { SendResult.Sent() }
    /** Pauza cerută ([undo]) ajunge imediat; false = playerul o aplică mai târziu (testul o face de mână). */
    var pauseOnUndo = true

    lateinit var machine: StartMachine

    override fun now() = clock
    override fun wallNow() = 1_700_000_000_000L + clock
    override fun snapshot() = world
    override fun send(step: Step, want: Want): SendResult {
        sent += step
        return onSend(step)
    }
    override fun undo(target: UndoTarget) {
        undone += target
        if (target is UndoTarget.Session && pauseOnUndo) setState(target.id, PState.PAUSED)
    }
    override fun schedule(atMs: Long) { scheduledAt = atMs }
    override fun emit(state: StartState) { states += state }
    override fun log(event: AttemptEvent) { events += event }
    override fun learn(pkg: String?, rung: Rung, outcome: LearnedTable.Outcome) { learned += Triple(pkg, rung, outcome) }
    override fun success(step: Step, want: Want, source: MusicSource, wrongTrack: Boolean) { successes += step }
    override fun ended() { endedCount++ }

    fun setState(id: String, state: PState, kind: MediaKind? = null, title: String? = null) {
        world = world.copy(sessions = world.sessions.map {
            if (it.id == id) it.copy(state = state, kind = kind ?: it.kind, title = title ?: it.title) else it
        })
    }

    fun add(s: SessionView) { world = world.copy(sessions = listOf(s) + world.sessions.filter { it.id != s.id }) }

    /** Trece timpul în pași de 100 ms, anunțând mașina (ca Handler-ul real la fiecare schimbare / termen). */
    fun advance(ms: Long) {
        val end = clock + ms
        while (clock < end) {
            clock += 100
            machine.onChange()
        }
    }

    val last: StartState get() = states.last()
    fun results(): List<String> = events.map { "${it.rung}:${it.result.wire}" }
}
