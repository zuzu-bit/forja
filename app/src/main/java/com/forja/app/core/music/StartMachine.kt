package com.forja.app.core.music

/**
 * Ce are nevoie mașina de la lume (stratul Android, sau un fals în teste). Totul se cheamă pe același fir.
 */
interface StartPort {
    /** Ceas monoton (ms), pentru termene. */
    fun now(): Long
    /** Ceasul de perete (ms epocă), pentru jurnal. */
    fun wallNow(): Long
    fun snapshot(): Snapshot
    /** Trimite treapta (comanda, tasta, intentul). Nu așteaptă efectul. */
    fun send(step: Step, want: Want): SendResult
    fun undo(target: UndoTarget)
    /** Cheamă [StartMachine.onChange] cel târziu la momentul dat (ceasul monoton). */
    fun schedule(atMs: Long)
    fun emit(state: StartState)
    fun log(event: AttemptEvent)
    fun learn(pkg: String?, rung: Rung, outcome: LearnedTable.Outcome)
    /** Muzica a pornit prin treapta dată (împrumut, coadă FORJA). */
    fun success(step: Step, want: Want, source: MusicSource, wrongTrack: Boolean)
    /** Încercarea s-a oprit (reușită, eșec, așteaptă o atingere): momentul de trimis jurnalul. */
    fun ended()
}

/**
 * Mașina de stări a pornirii (music-start.md §6.5). Pură și sincronă: evenimentele (atingere, schimbarea sesiunilor,
 * un termen atins) intră prin metode, efectele ies prin [StartPort].
 *
 *   IDLE → RESOLVE → TRY(treaptă) → VERIFY(termen)
 *     VERIFY: ținta CÂNTĂ 1,2 s (și a trecut de fereastra de refuz) → PLAYING
 *             tasta doar a trezit playerul, iar intenția cere o piesă anume (TOP 1, lista FORJA) → contextul vechi
 *             tace, planul se reface: S_TOP pe sesiunea acum prezentă
 *             ținta se încarcă (BUFFERING/CONNECTING) → termenul se prelungește, până la plafon
 *             a pornit ceva vorbit/video → îl oprește (UNDO) → următoarea treaptă
 *             a cântat și s-a oprit singură < 3 s → refuzat → următoarea
 *             termen depășit / eroare → următoarea
 *     următoarea invizibilă → TRY; vizibilă → TRY doar dacă atingerea a fost acum < 1,5 s, altfel NEEDS_TAP;
 *     doar terminale rămase → FAILED (cu „Deschide playerul”); nimic → FAILED.
 *
 * O singură încercare odată: o atingere nouă, o pauză sau ieșirea din ecran o anulează.
 */
class StartMachine(private val port: StartPort) {

    var state: StartState = StartState.Idle
        private set

    /** Cine a pornit încercarea curentă (null = nicio încercare). */
    val source: MusicSource? get() = attempt?.source

    val busy: Boolean get() = attempt?.verify != null

    private var attempt: Attempt? = null

    private class Attempt(val want: Want, val source: MusicSource, now: Long, tap: Boolean) {
        var tapAt: Long? = if (tap) now else null
        var phaseAt: Long = now
        val tried = HashSet<String>()
        var verify: Verify? = null
        var waiting: Step? = null
        var lastFail: FailReason? = null
        var inPlayer: Step? = null
    }

    private class Verify(
        val step: Step,
        val sentAt: Long,
        var deadline: Long,
        val cap: Long,
        val baselinePlaying: Set<String>,
        val baselineIds: Set<String>,
        val baselineConfigs: Set<Int>,
        /** Titlul sesiunilor care cântau deja la trimitere (contextul vechi nu e rezultatul unei piese cerute). */
        val baselineTitles: Map<String, String?>,
        val note: String?
    ) {
        var playingId: String? = null
        var firstPlayingAt: Long = 0L
        var firstActiveAt: Long = 0L
        var appearedAt: Long = 0L
        var otherId: String? = null
        var otherAt: Long = 0L
        var configId: Int? = null
        var configAt: Long = 0L
        /** Momentul în care sesiunea trezită de tastă a apărut (treaptă de trezire). */
        var wokeAt: Long = 0L
    }

    private sealed interface Outcome {
        data class Pending(val next: Long) : Outcome
        data class Ok(val session: SessionView?, val wrongTrack: Boolean) : Outcome
        data object Refused : Outcome
        data object Timeout : Outcome
        data class WrongKind(val undo: UndoTarget, val kind: MediaKind?) : Outcome
        /** Tasta a trezit playerul țintă (sesiunea lui e acum în listă): se continuă cu piesa cerută. */
        data class Woke(val session: SessionView) : Outcome
    }

    // ───────────────────────────── Intrări ─────────────────────────────

    /** O intenție nouă. `tap` = pornită de o atingere acum (saltul vizibil e permis 1,5 s). */
    fun start(want: Want, source: MusicSource, tap: Boolean) {
        attempt = null
        val now = port.now()
        val a = Attempt(want, source, now, tap)
        attempt = a
        val snap = port.snapshot()
        if (Planner.alreadyPlaying(want, snap)) {
            val playing = snap.sessions.firstOrNull { it.state == PState.PLAYING && it.kind == MediaKind.MUSIC }
            val step = Step(Rung.ALREADY, playing?.pkg, playing?.id)
            log(a, step, DiagResult.OK, 0L, playing?.kind, "already", snap)
            finishOk(a, step, false)
            return
        }
        advance(a, snap)
    }

    /** Sesiunile, playerele active, vizibilitatea FORJA sau ceasul s-au schimbat. */
    fun onChange() {
        val a = attempt ?: return
        val v = a.verify ?: return
        val snap = port.snapshot()
        when (val o = evaluate(a, v, snap)) {
            is Outcome.Pending -> port.schedule(o.next)
            is Outcome.Ok -> {
                a.verify = null
                val started = o.session?.pkg
                val elsewhere = started != null && v.step.pkg != null && started != v.step.pkg
                // Treapta se învață doar când chiar ea a pornit muzica (nu când a pornit alt player).
                if (!elsewhere) port.learn(v.step.pkg ?: started, v.step.rung, if (o.wrongTrack) LearnedTable.Outcome.MISS else LearnedTable.Outcome.OK)
                val note = if (elsewhere) listOfNotNull(v.note, "started:${Planner.diagPkg(started)}").joinToString(" ") else v.note
                log(a, v.step.copy(pkg = v.step.pkg ?: started), if (o.wrongTrack) DiagResult.WRONG_TRACK else DiagResult.OK, port.now() - v.sentAt, o.session?.kind, note, snap)
                finishOk(a, if (elsewhere || v.step.pkg == null) v.step.copy(pkg = started, sessionId = o.session?.id) else v.step, o.wrongTrack)
            }
            Outcome.Refused -> fail(a, v, snap, DiagResult.REFUSED, FailReason.REFUSED)
            Outcome.Timeout -> fail(a, v, snap, DiagResult.TIMEOUT, FailReason.TIMEOUT)
            is Outcome.WrongKind -> {
                a.verify = null
                port.undo(o.undo)
                log(a, v.step, DiagResult.WRONG_KIND, port.now() - v.sentAt, o.kind, v.note, snap)
                advance(a, port.snapshot())
            }
            is Outcome.Woke -> {
                a.verify = null
                val w = o.session
                // Ce a pornit tasta e contextul ei vechi (altă listă, alt podcast): tace cât pornește piesa cerută —
                // afară de cazul în care chiar piesa cerută cântă deja.
                val track = wantedTrack(a.want, snap)
                if (w.state.activeish && (track == null || !TrackKey.matches(track.title, w.title))) port.undo(UndoTarget.Session(w.id))
                port.learn(v.step.pkg ?: w.pkg, v.step.rung, LearnedTable.Outcome.OK)
                log(a, v.step.copy(pkg = v.step.pkg ?: w.pkg), DiagResult.OK, port.now() - v.sentAt, w.kind, listOfNotNull(v.note, "woke").joinToString(" "), snap)
                // Planul se reface cu sesiunea acum prezentă: TOP 1 / lista FORJA → S_TOP (apoi S_LIKED, S_PLAY…).
                advance(a, port.snapshot())
            }
        }
    }

    /** Atingerea pe „Deschide Spotify” / „Deschide playerul”: face pasul care aștepta. */
    fun tap(): Boolean {
        val a = attempt ?: return false
        val w = a.waiting ?: return false
        val now = port.now()
        a.waiting = null
        a.tapAt = now
        a.phaseAt = now
        run(a, w, port.snapshot())
        return true
    }

    /** FORJA a revenit în față (după un salt în player). */
    fun resumed() {
        val a = attempt ?: return
        val opened = a.inPlayer
        if (opened != null && a.verify == null) {
            val snap = port.snapshot()
            val playing = snap.musicPlaying
            val kind = snap.sessions.firstOrNull { it.state == PState.PLAYING && it.kind == MediaKind.MUSIC }?.kind
            log(a, opened, if (playing) DiagResult.OK else DiagResult.TIMEOUT, 0L, kind, "return", snap)
            attempt = null
            emit(if (playing) StartState.Playing(opened.rung, badgeFor(a.want, opened, false)) else StartState.Idle)
            port.ended()
            return
        }
        onChange()
    }

    /** Anulează încercarea (pauză, altă atingere, ecranul închis). */
    fun cancel() {
        attempt = null
        if (state != StartState.Idle) emit(StartState.Idle)
    }

    /** O încercare e în curs sau așteaptă o atingere. */
    val active: Boolean get() = attempt != null

    /** Uită rezultatul (muzica s-a oprit, altă sursă): discul revine la „pornește”. */
    fun reset() {
        if (attempt != null) return
        if (state != StartState.Idle) emit(StartState.Idle)
    }

    // ───────────────────────────── Scara ─────────────────────────────

    private fun advance(a: Attempt, snap: Snapshot) {
        if (attempt !== a) return
        val now = port.now()
        for (step in Planner.plan(a.want, snap)) {
            if (step.key in a.tried) continue
            if (step.skip != null) {
                a.tried += step.key
                log(a, step, DiagResult.SKIPPED, 0L, null, step.skip, snap)
                continue
            }
            if (!step.rung.visible && now - a.phaseAt > INVISIBLE_START_MS) {
                a.tried += step.key
                log(a, step, DiagResult.SKIPPED, 0L, null, "cap", snap)
                continue
            }
            if (!step.rung.visible && !snap.fg && stoppedTarget(step, snap)) {
                // În fundal, niciodată o comandă nouă către un player oprit (nu primește voie de pornire).
                a.tried += step.key
                log(a, step, DiagResult.SKIPPED, 0L, null, "background", snap)
                continue
            }
            if (step.rung.visible) {
                val tapAt = a.tapAt
                val allowed = snap.fg && tapAt != null && now - tapAt <= TAP_WINDOW_MS
                if (!allowed) {
                    a.waiting = step
                    if (step.rung.terminal) {
                        emit(StartState.Failed(a.lastFail ?: FailReason.NO_PLAYER, step))
                    } else {
                        log(a, step, DiagResult.NEEDS_TAP, 0L, null, null, snap)
                        emit(StartState.NeedsTap(step, a.want))
                    }
                    port.ended()
                    return
                }
            }
            run(a, step, snap)
            return
        }
        attempt = null
        emit(StartState.Failed(a.lastFail ?: FailReason.NO_PLAYER, null))
        port.ended()
    }

    private fun run(a: Attempt, step: Step, snap: Snapshot) {
        if (attempt !== a) return
        a.tried += step.key
        val now = port.now()
        emit(StartState.Starting(step.rung, a.want))
        val result = try {
            port.send(step, a.want)
        } catch (e: Exception) {
            SendResult.Error(e.javaClass.simpleName)
        }
        if (attempt !== a) return
        when (result) {
            is SendResult.Skipped -> {
                log(a, step, DiagResult.SKIPPED, 0L, null, result.reason, snap)
                advance(a, port.snapshot())
            }
            is SendResult.Error -> {
                port.learn(step.pkg, step.rung, LearnedTable.Outcome.FAIL)
                log(a, step, DiagResult.ERROR, port.now() - now, null, result.cls, snap)
                advance(a, port.snapshot())
            }
            is SendResult.Sent -> {
                if (step.rung.terminal) {
                    log(a, step, DiagResult.OK, port.now() - now, null, result.note ?: "opened", snap)
                    a.inPlayer = step
                    emit(StartState.InPlayer(step.pkg))
                    port.ended()
                    return
                }
                val (base, cap) = budget(step.rung)
                val phaseCap = if (step.rung.visible) Long.MAX_VALUE else a.phaseAt + PHASE_CAP_MS
                val capAt = minOf(now + cap, maxOf(phaseCap, now + base))
                val v = Verify(
                    step = step,
                    sentAt = now,
                    deadline = minOf(now + base, capAt),
                    cap = capAt,
                    baselinePlaying = snap.sessions.filter { it.state.activeish }.map { it.id }.toSet(),
                    baselineIds = snap.sessions.map { it.id }.toSet(),
                    baselineConfigs = snap.configs.map { it.id }.toSet(),
                    baselineTitles = snap.sessions.filter { it.state.activeish }.associate { it.id to it.title },
                    note = result.note
                )
                a.verify = v
                port.schedule(v.deadline)
                onChange()
            }
        }
    }

    private fun fail(a: Attempt, v: Verify, snap: Snapshot, result: DiagResult, reason: FailReason) {
        a.verify = null
        a.lastFail = reason
        port.learn(v.step.pkg, v.step.rung, LearnedTable.Outcome.FAIL)
        log(a, v.step, result, port.now() - v.sentAt, v.playingId?.let { snap.session(it)?.kind }, v.note, snap)
        advance(a, port.snapshot())
    }

    private fun finishOk(a: Attempt, step: Step, wrongTrack: Boolean) {
        attempt = null
        emit(StartState.Playing(step.rung, badgeFor(a.want, step, wrongTrack), wrongTrack))
        port.success(step, a.want, a.source, wrongTrack)
        port.ended()
    }

    private fun badgeFor(want: Want, step: Step, wrongTrack: Boolean): Badge = when {
        want is Want.Workout && want.first != null && step.rung == Rung.S_TOP && !wrongTrack -> Badge.FORJA
        step.rung == Rung.S_LIKED || step.rung == Rung.V_LIKED_PLAY || step.rung == Rung.V_PFS_DATA || step.rung == Rung.O_LIKED_PAGE -> Badge.LIKED
        else -> Badge.NONE
    }

    // ───────────────────────────── Verificarea ─────────────────────────────

    private fun evaluate(a: Attempt, v: Verify, s: Snapshot): Outcome {
        val now = port.now()
        val needsMusic = a.want !is Want.Resume
        if (!s.access) return evaluateConfigs(v, s, now, needsMusic)

        // 1) A pornit ceva vorbit sau video care nu cânta înainte (tasta a ajuns la carte): se oprește, mai departe.
        if (needsMusic) {
            val bad = s.sessions.firstOrNull {
                it.state == PState.PLAYING && it.id !in v.baselinePlaying && (it.kind == MediaKind.SPOKEN || it.kind == MediaKind.VIDEO)
            }
            if (bad != null) return Outcome.WrongKind(UndoTarget.Session(bad.id), bad.kind)
        }

        // 2) Ținta: sesiunea pasului; dacă a fost recreată, sesiunile aceluiași player.
        val targets = when {
            v.step.sessionId != null -> s.session(v.step.sessionId)?.let { listOf(it) } ?: s.sessions.filter { it.pkg == v.step.pkg }
            v.step.pkg != null -> s.sessions.filter { it.pkg == v.step.pkg }
            else -> s.sessions.filter { it.id !in v.baselinePlaying }
        }
        if (v.step.rung == Rung.K_PLAY && v.appearedAt == 0L && targets.any { it.id !in v.baselineIds || it.state.activeish }) {
            // Playerul s-a trezit: încă 6 s să cânte.
            v.appearedAt = now
            v.deadline = minOf(maxOf(v.deadline, now + KEY_PLAY_MS), v.cap)
        }
        // 2b) Treaptă de trezire (TOP 1, lista FORJA): tasta nu duce piesa cerută, doar aduce sesiunea playerului. Cât ea
        //     apare (sau începe să cânte), treapta s-a făcut; se așteaptă puțin să pornească, ca pauza să prindă contextul vechi.
        if (wakeOnly(a.want, v.step, s)) {
            val woke = targets.firstOrNull { !it.remote && (it.id !in v.baselineIds || it.state.activeish) }
            if (woke != null) {
                if (v.wokeAt == 0L) v.wokeAt = now
                if (woke.state.activeish || now - v.wokeAt >= WAKE_SETTLE_MS || now >= v.deadline) return Outcome.Woke(woke)
                return Outcome.Pending(minOf(v.wokeAt + WAKE_SETTLE_MS, v.deadline))
            }
        }
        val track = v.step.track
        var next = v.deadline
        for (t in targets) {
            // Contextul de dinainte (cânta deja când s-a cerut piesa) nu e rezultatul comenzii cât timp nu s-a schimbat
            // piesa: nici reușită, nici refuz când se oprește (pauza de după trezire, trecerea la piesa cerută).
            if (track != null && t.id in v.baselineTitles && t.title == v.baselineTitles[t.id] && !TrackKey.matches(track.title, t.title)) {
                if (t.state.transitional) v.deadline = minOf(maxOf(v.deadline, now + BUFFER_GRACE_MS), v.cap)
                continue
            }
            when {
                t.state == PState.PLAYING -> {
                    if (v.playingId != t.id) {
                        v.playingId = t.id
                        v.firstPlayingAt = now
                    }
                    if (v.firstActiveAt == 0L) v.firstActiveAt = now
                    val readyAt = maxOf(v.firstPlayingAt + HOLD_MS, v.firstActiveAt + SETTLE_MS)
                    if (now >= readyAt && t.speed != 0f) {
                        if (needsMusic && (t.kind == MediaKind.SPOKEN || t.kind == MediaKind.VIDEO)) {
                            return Outcome.WrongKind(UndoTarget.Session(t.id), t.kind)
                        }
                        val wrong = track != null && !TrackKey.matches(track.title, t.title)
                        return Outcome.Ok(t, wrong)
                    }
                    // Cât se încarcă sau abia a pornit, termenul nu taie o pornire lentă (plafonul rămâne).
                    v.deadline = minOf(maxOf(v.deadline, readyAt + 500), v.cap)
                    next = minOf(next, readyAt)
                }
                t.state.transitional -> {
                    if (v.firstActiveAt == 0L) v.firstActiveAt = now
                    if (v.playingId == t.id) v.playingId = null
                    v.deadline = minOf(maxOf(v.deadline, now + BUFFER_GRACE_MS), v.cap)
                }
                else -> {
                    val wasOurs = v.playingId == null || v.playingId == t.id
                    if (v.firstActiveAt > 0L && wasOurs && now - v.firstActiveAt < REFUSE_MS) return Outcome.Refused
                    if (v.playingId == t.id) v.playingId = null
                }
            }
        }

        // 3) A pornit altă muzică (tasta a trezit alt player de muzică, sau a apăsat ea play în player): e bine.
        if (needsMusic && a.want !is Want.Probe) {
            val other = s.sessions.firstOrNull { o ->
                o.state == PState.PLAYING && o.kind == MediaKind.MUSIC && o.id !in v.baselinePlaying && targets.none { it.id == o.id }
            }
            if (other != null) {
                if (v.otherId != other.id) {
                    v.otherId = other.id
                    v.otherAt = now
                }
                val readyAt = v.otherAt + SETTLE_MS
                if (now >= readyAt) return Outcome.Ok(other, v.step.track != null)
                next = minOf(next, readyAt)
            } else {
                v.otherId = null
            }
        }

        if (now >= v.deadline) return Outcome.Timeout
        return Outcome.Pending(minOf(next, v.deadline))
    }

    /** Fără acces: doar playerele anonime ale sistemului, cu tipul lor de conținut. */
    private fun evaluateConfigs(v: Verify, s: Snapshot, now: Long, needsMusic: Boolean): Outcome {
        val fresh = s.configs.filter { it.id !in v.baselineConfigs }
        if (needsMusic && fresh.any { it.content == ContentHint.SPEECH || it.content == ContentHint.MOVIE }) {
            val kind = if (fresh.any { it.content == ContentHint.SPEECH }) MediaKind.SPOKEN else MediaKind.VIDEO
            return Outcome.WrongKind(UndoTarget.Key, kind)
        }
        val m = fresh.firstOrNull { it.content == ContentHint.MUSIC || it.content == ContentHint.NONE }
        if (m != null) {
            if (v.configId != m.id) {
                v.configId = m.id
                v.configAt = now
            }
            if (v.firstActiveAt == 0L) v.firstActiveAt = now
            val readyAt = maxOf(v.configAt + HOLD_MS, v.firstActiveAt + SETTLE_MS)
            if (now >= readyAt) return Outcome.Ok(null, false)
            if (v.step.rung == Rung.K_PLAY) v.deadline = minOf(maxOf(v.deadline, readyAt + 500), v.cap)
            return Outcome.Pending(minOf(readyAt, v.deadline))
        }
        if (v.configId != null) {
            if (now - v.firstActiveAt < REFUSE_MS) return Outcome.Refused
            v.configId = null
        }
        if (now >= v.deadline) return Outcome.Timeout
        return Outcome.Pending(v.deadline)
    }

    /**
     * Tasta media (K_TOKEN/K_PLAY), cu acces, când intenția cere o piesă anume: TOP 1 sau prima piesă din lista FORJA.
     * Tasta pornește doar ultimul context al playerului, deci ea doar trezește sesiunea; piesa o cere S_TOP după
     * (music-start.md §6.3 „K_TOKEN or K_PLAY, then S_TOP”; workout-music.md §3.8 treapta 2).
     */
    private fun wakeOnly(want: Want, step: Step, s: Snapshot): Boolean =
        s.access && step.pkg != null && (step.rung == Rung.K_PLAY || step.rung == Rung.K_TOKEN) && wantedTrack(want, s) != null

    /** Piesa cerută de intenție (TOP 1: piesa ta de top; Antrenament: prima din lista FORJA). */
    private fun wantedTrack(want: Want, s: Snapshot): TrackRef? = when (want) {
        Want.Top -> s.top
        is Want.Workout -> want.first
        else -> null
    }

    private fun stoppedTarget(step: Step, s: Snapshot): Boolean {
        val t = s.session(step.sessionId) ?: s.firstOf(step.pkg) ?: return step.rung != Rung.K_PLAY
        return t.state == PState.STOPPED || t.state == PState.NONE
    }

    // ───────────────────────────── Ieșiri ─────────────────────────────

    private fun emit(s: StartState) {
        state = s
        port.emit(s)
    }

    private fun log(a: Attempt, step: Step, result: DiagResult, ms: Long, kind: MediaKind?, err: String?, s: Snapshot) {
        val pkg = step.pkg ?: step.sessionId?.let { s.session(it)?.pkg }
        port.log(
            AttemptEvent(
                at = port.wallNow(),
                want = a.want.wire,
                rung = step.rung.id,
                pkg = pkg,
                ver = pkg?.let { s.versions[it] },
                kind = kind,
                result = result,
                ms = ms.coerceAtLeast(0L),
                err = err?.take(120)
            )
        )
    }

    companion object {
        /** Ținta cântă neîntrerupt atât cât să fie „a pornit”. */
        const val HOLD_MS = 1_200L
        /** …și a trecut de fereastra în care playerele care refuză se opresc singure. */
        const val SETTLE_MS = 2_500L
        /** A cântat și s-a oprit singură mai repede de atât = refuz. */
        const val REFUSE_MS = 3_000L
        /** Un salt vizibil e „rezultatul atingerii” doar în fereastra asta. */
        const val TAP_WINDOW_MS = 1_500L
        /** Treptele invizibile nu mai pornesc după atât de la atingere… */
        const val INVISIBLE_START_MS = 12_000L
        /** …și nu mai așteaptă după plafonul ăsta (încercarea se termină în 15 s). */
        const val PHASE_CAP_MS = 15_000L
        const val KEY_PLAY_MS = 6_000L
        const val BUFFER_GRACE_MS = 2_000L
        /** Sesiunea trezită de tastă a apărut pe pauză: atât se așteaptă să înceapă să cânte, apoi se merge mai departe. */
        const val WAKE_SETTLE_MS = 1_500L

        /** (termenul de bază, plafonul) per treaptă. */
        fun budget(r: Rung): Pair<Long, Long> = when (r) {
            Rung.S_PLAY, Rung.S_BTN, Rung.K_TOKEN -> 4_000L to 12_000L
            Rung.S_TOP, Rung.S_LIKED, Rung.S_ANY -> 6_000L to 12_000L
            Rung.K_PLAY -> 8_000L to 14_000L
            else -> 10_000L to 15_000L
        }
    }
}
