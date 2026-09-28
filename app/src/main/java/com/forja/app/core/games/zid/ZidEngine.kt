package com.forja.app.core.games.zid

import com.forja.app.core.games.Rng
import com.forja.app.core.games.zid.ZidPieces.KICKS
import com.forja.app.core.games.zid.ZidPieces.RUBBLE
import kotlinx.serialization.Serializable

/** Comenzile jucătorului (gesturile le traduc în ele). */
enum class ZidCmd { Left, Right, RotateCw, RotateCcw, SoftOn, SoftOff, HardDrop, Hold }

/** Faza partidei. Pauza nu e a motorului: bucla pur și simplu nu mai avansează. */
@Serializable
enum class ZidPhase { Ready, Falling, Clearing, Won, Lost }

/**
 * Evenimentele unei partide (sunete, vibrații, mascota). Datele lor stau în motor: `lastCleared`, `combo`, `clearRows`.
 * Sunt constante enum, deci lista refolosită nu alocă nimic în buclă.
 */
enum class ZidEvent { Spawned, Moved, Rotated, Kicked, Blocked, Landed, Locked, HardDropped, Cleared, Combo, Held, DangerOn, DangerOff, Won, Lost }

/** Reglajele care nu țin de nivel. `clearDelayMs` = 0 sub mișcare redusă (rândurile dispar pe loc). */
data class ZidConfig(
    val clearDelayMs: Int = 260,
    val lockDelayMs: Int = 500,
    val maxLockResets: Int = 15,
    val softMs: Int = 40
)

/** Starea completă a unei partide, pentru salvare și reluare (DataStore „forja_games”, cheia `zid_save`). */
@Serializable
data class ZidSave(
    val v: Int = 1,
    val level: Int,
    val rng: Long,
    val cells: String,
    val phase: ZidPhase,
    val kind: Int,
    val rot: Int,
    val x: Int,
    val y: Int,
    val queue: List<Int>,
    val hold: Int,
    val holdUsed: Boolean,
    val score: Int,
    val lines: Int,
    val elapsedMs: Long,
    val gravityAcc: Int,
    val lockAcc: Int,
    val lockResets: Int,
    val lowestY: Int,
    val soft: Boolean,
    val combo: Int,
    val lastCleared: Int,
    val clearRows: List<Int>,
    val clearMsLeft: Int,
    val danger: Boolean,
    val version: Long
)

/**
 * Motorul ZID: tabla 10 × 18 vizibile + 2 rânduri ascunse de apariție, piese dintr-un sac de 7 amestecat cu [Rng],
 * gravitație pe cronometre întregi (ms), întârziere de blocare 500 ms (cel mult 15 reporniri), rezervă o dată pe piesă,
 * scor pe linii × nivel (sau rang), combo, căderea ușoară și bruscă. Fără Android: aceleași intrări dau aceeași partidă.
 *
 * `input(cmd)` se aplică pe loc; `advance(dtMs)` rulează cronometrele. Evenimentele se adună în [events] până la
 * [clearEvents] (lista e refolosită). [version] crește la orice schimbare vizibilă.
 */
class ZidEngine private constructor(val level: ZidLevel, val cfg: ZidConfig, private val rng: Rng) {

    /** Tabla: `cells[y * W + x]`, 0 = gol, 1–7 = piese, 8 = moloz. Rândurile 0–1 sunt ascunse. */
    val cells = ByteArray(W * H)

    var phase: ZidPhase = ZidPhase.Ready
        private set

    // ── piesa curentă (0 = niciuna) ──
    var pieceKind = 0
        private set
    var pieceRot = 0
        private set
    var pieceX = 0
        private set
    var pieceY = 0
        private set
    /** Rândul (cutiei) unde ar ateriza piesa acum. */
    var landingY = 0
        private set

    private val queue = ArrayList<Int>(16)

    var hold = 0
        private set
    var holdUsed = false
        private set

    var score = 0
        private set
    var lines = 0
        private set
    var elapsedMs = 0L
        private set
    var danger = false
        private set
    /** −1 = fără combo; 1, 2, … pentru blocările consecutive care au închis rânduri. */
    var combo = -1
        private set
    /** Câte rânduri a închis ultima blocare (0–4). */
    var lastCleared = 0
        private set
    var version = 0L
        private set

    private var gravityAcc = 0
    private var lockAcc = 0
    private var lockResets = 0
    private var lowestY = 0
    private var soft = false

    /** Rândurile care se închid acum (faza Clearing): primele [clearCount] valori. */
    val clearRows = IntArray(4)
    var clearCount = 0
        private set
    var clearMsLeft = 0
        private set

    /** Pentru animația de prăbușire (doar vizual, nesalvat): cu câte rânduri a coborât conținutul fiecărui rând. */
    val shift = IntArray(H)
    var collapseStamp = 0L
        private set

    private val _events = ArrayList<ZidEvent>(32)
    val events: List<ZidEvent> get() = _events

    fun clearEvents() = _events.clear()

    // ───────────────────────────── Ce vede UI-ul ─────────────────────────────

    val hasPiece: Boolean get() = pieceKind != 0

    /** Piesa i din coadă (0 = următoarea). */
    fun next(i: Int): Int = if (i < queue.size) queue[i] else 0

    val isEndless: Boolean get() = level.goal == ZidGoal.Endless

    /** Rangul în „Fără sfârșit” (1 + linii / 10). */
    val rank: Int get() = 1 + lines / ZidLevels.LINES_PER_RANK

    /** Multiplicatorul scorului: numărul nivelului sau rangul. */
    private val mult: Int get() = if (isEndless) rank else level.id

    val gravityMs: Int get() = if (isEndless) ZidLevels.gravityFor(rank) else level.gravityMs

    /** Rândurile care mai au moloz. */
    val rubbleLeft: Int
        get() {
            var n = 0
            for (y in 0 until H) {
                val o = y * W
                for (x in 0 until W) if (cells[o + x] == RUBBLE.toByte()) { n++; break }
            }
            return n
        }

    /** Ce mai e de făcut: linii rămase (Linii), rânduri de moloz (Sapă), linii făcute (Fără sfârșit). */
    val remaining: Int
        get() = when (level.goal) {
            ZidGoal.Lines -> (level.target - lines).coerceAtLeast(0)
            ZidGoal.Dig -> rubbleLeft
            ZidGoal.Endless -> lines
        }

    fun cell(x: Int, y: Int): Int = cells[y * W + x].toInt()

    /** Stelele (1–3) după o victorie; 0 altfel. */
    val stars: Int get() = if (phase == ZidPhase.Won) level.stars(score, elapsedMs) else 0

    // ───────────────────────────── Comenzi ─────────────────────────────

    /** Ready → Falling (prima atingere). */
    fun start() {
        if (phase != ZidPhase.Ready) return
        phase = ZidPhase.Falling
        spawnNext()
    }

    /** Aplică o comandă pe loc; false dacă nu s-a putut (perete, piesă blocată, rezervă folosită). */
    fun input(cmd: ZidCmd): Boolean {
        if (cmd == ZidCmd.SoftOff) {
            soft = false
            return true
        }
        if (phase != ZidPhase.Falling || pieceKind == 0) {
            if (cmd == ZidCmd.SoftOn) soft = true
            return false
        }
        return when (cmd) {
            ZidCmd.Left -> move(-1)
            ZidCmd.Right -> move(1)
            ZidCmd.RotateCw -> rotate(1)
            ZidCmd.RotateCcw -> rotate(3)
            ZidCmd.SoftOn -> { soft = true; true }
            ZidCmd.SoftOff -> { soft = false; true }
            ZidCmd.HardDrop -> hardDrop()
            ZidCmd.Hold -> holdPiece()
        }
    }

    /** Cronometrele: gravitația, blocarea, închiderea rândurilor. Întoarce evenimentele adunate (lista refolosită). */
    fun advance(dtMs: Int): List<ZidEvent> {
        if (dtMs <= 0) return _events
        when (phase) {
            ZidPhase.Ready, ZidPhase.Won, ZidPhase.Lost -> return _events
            ZidPhase.Clearing -> {
                elapsedMs += dtMs
                clearMsLeft -= dtMs
                if (clearMsLeft <= 0) finishClear()
                return _events
            }
            ZidPhase.Falling -> Unit
        }
        elapsedMs += dtMs
        if (pieceKind == 0) return _events
        var budget = dtMs
        while (budget > 0 && phase == ZidPhase.Falling && pieceKind != 0) {
            if (grounded()) {
                val need = cfg.lockDelayMs - lockAcc
                if (budget >= need) {
                    budget -= need.coerceAtLeast(0)
                    lock()
                    // Timpul rămas trece la piesa nouă (gravitația ei începe de la zero).
                    budget = 0
                } else {
                    lockAcc += budget
                    budget = 0
                }
            } else {
                val interval = if (soft) minOf(gravityMs, cfg.softMs) else gravityMs
                val need = interval - gravityAcc
                if (budget >= need) {
                    budget -= need.coerceAtLeast(0)
                    gravityAcc = 0
                    pieceY++
                    if (soft) score++
                    if (pieceY > lowestY) {
                        lowestY = pieceY
                        lockResets = 0
                    }
                    lockAcc = 0
                    version++
                    if (grounded()) _events.add(ZidEvent.Landed)
                } else {
                    gravityAcc += budget
                    budget = 0
                }
            }
        }
        return _events
    }

    // ───────────────────────────── Regulile ─────────────────────────────

    private fun collides(kind: Int, rot: Int, x: Int, y: Int): Boolean {
        val c = ZidPieces.cells[kind][rot and 3]
        for (i in 0 until 4) {
            val cx = x + c[2 * i]
            val cy = y + c[2 * i + 1]
            if (cx < 0 || cx >= W || cy >= H) return true
            if (cy >= 0 && cells[cy * W + cx].toInt() != 0) return true
        }
        return false
    }

    private fun grounded(): Boolean = pieceKind != 0 && collides(pieceKind, pieceRot, pieceX, pieceY + 1)

    private fun updateLanding() {
        if (pieceKind == 0) return
        var y = pieceY
        while (!collides(pieceKind, pieceRot, pieceX, y + 1)) y++
        landingY = y
    }

    /** După o mutare sau rotire reușită: pe sol, blocarea repornește (de cel mult 15 ori), apoi se blochează la contact. */
    private fun afterShift() {
        updateLanding()
        if (grounded()) {
            if (lockResets < cfg.maxLockResets) {
                lockAcc = 0
                lockResets++
            } else {
                lockAcc = cfg.lockDelayMs
            }
        }
        version++
    }

    private fun move(dx: Int): Boolean {
        if (collides(pieceKind, pieceRot, pieceX + dx, pieceY)) {
            _events.add(ZidEvent.Blocked)
            return false
        }
        pieceX += dx
        _events.add(ZidEvent.Moved)
        afterShift()
        return true
    }

    private fun rotate(turns: Int): Boolean {
        val nr = (pieceRot + turns) and 3
        for (k in 0 until KICKS.size / 2) {
            val kx = KICKS[2 * k]
            val ky = KICKS[2 * k + 1]
            if (!collides(pieceKind, nr, pieceX + kx, pieceY + ky)) {
                pieceRot = nr
                pieceX += kx
                pieceY += ky
                _events.add(ZidEvent.Rotated)
                if (k > 0) _events.add(ZidEvent.Kicked)
                afterShift()
                return true
            }
        }
        _events.add(ZidEvent.Blocked)
        return false
    }

    private fun hardDrop(): Boolean {
        updateLanding()
        val dist = landingY - pieceY
        pieceY = landingY
        score += 2 * dist
        _events.add(ZidEvent.HardDropped)
        lock()
        return true
    }

    private fun holdPiece(): Boolean {
        if (holdUsed) {
            _events.add(ZidEvent.Blocked)
            return false
        }
        val cur = pieceKind
        pieceKind = 0
        if (hold == 0) {
            hold = cur
            spawnNext()
        } else {
            val h = hold
            hold = cur
            spawn(h)
        }
        holdUsed = true
        _events.add(ZidEvent.Held)
        version++
        return true
    }

    private fun lock() {
        val c = ZidPieces.cells[pieceKind][pieceRot]
        var allHidden = true
        for (i in 0 until 4) {
            val cx = pieceX + c[2 * i]
            val cy = pieceY + c[2 * i + 1]
            if (cy >= 0) cells[cy * W + cx] = pieceKind.toByte()
            if (cy >= HIDDEN) allHidden = false
        }
        pieceKind = 0
        holdUsed = false
        gravityAcc = 0
        lockAcc = 0
        _events.add(ZidEvent.Locked)
        version++
        if (allHidden) {
            lose()
            return
        }
        // rânduri pline
        clearCount = 0
        for (y in 0 until H) {
            var full = true
            val o = y * W
            for (x in 0 until W) if (cells[o + x].toInt() == 0) { full = false; break }
            if (full && clearCount < 4) clearRows[clearCount++] = y
        }
        if (clearCount > 0) {
            val n = clearCount
            lines += n
            lastCleared = n
            score += LINE_SCORE[n] * mult
            combo++
            if (combo >= 1) {
                score += 50 * combo * mult
                _events.add(ZidEvent.Combo)
            }
            _events.add(ZidEvent.Cleared)
            if (cfg.clearDelayMs > 0) {
                phase = ZidPhase.Clearing
                clearMsLeft = cfg.clearDelayMs
            } else {
                finishClear()
            }
        } else {
            combo = -1
            lastCleared = 0
            updateDanger()
            spawnNext()
        }
    }

    /** Scoate rândurile închise; ce e deasupra coboară (gravitație naivă). Apoi victoria sau piesa următoare. */
    private fun finishClear() {
        var w = H - 1
        var r = H - 1
        while (r >= 0) {
            var cleared = false
            for (i in 0 until clearCount) if (clearRows[i] == r) { cleared = true; break }
            if (!cleared) {
                if (w != r) System.arraycopy(cells, r * W, cells, w * W, W)
                shift[w] = w - r
                w--
            }
            r--
        }
        while (w >= 0) {
            for (x in 0 until W) cells[w * W + x] = 0
            shift[w] = 0
            w--
        }
        clearCount = 0
        clearMsLeft = 0
        collapseStamp = version + 1
        version++
        phase = ZidPhase.Falling
        val won = when (level.goal) {
            ZidGoal.Lines -> lines >= level.target
            ZidGoal.Dig -> rubbleLeft == 0
            ZidGoal.Endless -> false
        }
        if (won) {
            phase = ZidPhase.Won
            pieceKind = 0
            soft = false
            _events.add(ZidEvent.Won)
            return
        }
        updateDanger()
        spawnNext()
    }

    private fun updateDanger() {
        var top = H
        loop@ for (y in 0 until H) {
            val o = y * W
            for (x in 0 until W) if (cells[o + x].toInt() != 0) { top = y; break@loop }
        }
        val d = top < HIDDEN + DANGER_ROWS
        if (d != danger) {
            danger = d
            _events.add(if (d) ZidEvent.DangerOn else ZidEvent.DangerOff)
            version++
        }
    }

    private fun spawnNext() {
        refill()
        val k = queue.removeAt(0)
        refill()
        spawn(k)
    }

    private fun spawn(kind: Int) {
        pieceKind = kind
        pieceRot = 0
        pieceX = ZidPieces.spawnX(kind)
        pieceY = 0
        gravityAcc = 0
        lockAcc = 0
        lockResets = 0
        if (collides(kind, 0, pieceX, pieceY)) {
            pieceKind = 0
            lose()
            return
        }
        // Un rând în jos pe loc: rândul de jos al piesei apare imediat sus pe tablă.
        if (!collides(kind, 0, pieceX, pieceY + 1)) pieceY++
        lowestY = pieceY
        updateLanding()
        _events.add(ZidEvent.Spawned)
        version++
    }

    private fun lose() {
        phase = ZidPhase.Lost
        soft = false
        _events.add(ZidEvent.Lost)
        version++
    }

    /** Sacul de 7: toate piesele o dată, amestecate Fisher–Yates cu [rng]. */
    private fun refill() {
        while (queue.size < ZidPieces.KINDS) {
            val bag = IntArray(ZidPieces.KINDS) { it + 1 }
            for (i in bag.size - 1 downTo 1) {
                val j = rng.nextInt(i + 1)
                val t = bag[i]; bag[i] = bag[j]; bag[j] = t
            }
            for (k in bag) queue.add(k)
        }
    }

    private fun placeRubble() {
        val rows = level.rubble
        for ((i, row) in rows.withIndex()) {
            val y = H - rows.size + i
            for (x in 0 until W) cells[y * W + x] = if (row.getOrNull(x) == '#') RUBBLE.toByte() else 0
        }
    }

    // ───────────────────────────── Salvare ─────────────────────────────

    fun save(): ZidSave = ZidSave(
        level = level.id,
        rng = rng.state,
        cells = String(CharArray(W * H) { ('0'.code + cells[it]).toChar() }),
        phase = phase,
        kind = pieceKind,
        rot = pieceRot,
        x = pieceX,
        y = pieceY,
        queue = queue.toList(),
        hold = hold,
        holdUsed = holdUsed,
        score = score,
        lines = lines,
        elapsedMs = elapsedMs,
        gravityAcc = gravityAcc,
        lockAcc = lockAcc,
        lockResets = lockResets,
        lowestY = lowestY,
        soft = soft,
        combo = combo,
        lastCleared = lastCleared,
        clearRows = List(clearCount) { clearRows[it] },
        clearMsLeft = clearMsLeft,
        danger = danger,
        version = version
    )

    companion object {
        const val W = 10
        const val H = 20
        const val HIDDEN = 2
        const val VISIBLE = H - HIDDEN
        const val DANGER_ROWS = 4
        val LINE_SCORE = intArrayOf(0, 100, 300, 500, 800)

        /** O partidă nouă, în Ready (piesa apare la prima atingere). */
        fun create(level: ZidLevel, seed: Long, cfg: ZidConfig = ZidConfig()): ZidEngine {
            val e = ZidEngine(level, cfg, Rng(seed))
            e.placeRubble()
            e.refill()
            e.updateDanger()
            e._events.clear()
            return e
        }

        /** Partida salvată, exact cum era. `cfg` vine de la UI (mișcarea redusă se poate schimba între timp). */
        fun restore(s: ZidSave, cfg: ZidConfig = ZidConfig()): ZidEngine {
            require(s.v == 1) { "unknown save version ${s.v}" }
            require(s.cells.length == W * H) { "bad board" }
            val e = ZidEngine(ZidLevels.byId(s.level), cfg, Rng(s.rng))
            for (i in 0 until W * H) {
                val v = s.cells[i] - '0'
                require(v in 0..RUBBLE) { "bad cell" }
                e.cells[i] = v.toByte()
            }
            e.phase = s.phase
            e.pieceKind = s.kind.coerceIn(0, ZidPieces.KINDS)
            e.pieceRot = s.rot and 3
            e.pieceX = s.x
            e.pieceY = s.y
            e.queue.addAll(s.queue.filter { it in 1..ZidPieces.KINDS })
            e.hold = s.hold.coerceIn(0, ZidPieces.KINDS)
            e.holdUsed = s.holdUsed
            e.score = s.score
            e.lines = s.lines
            e.elapsedMs = s.elapsedMs
            e.gravityAcc = s.gravityAcc
            e.lockAcc = s.lockAcc
            e.lockResets = s.lockResets
            e.lowestY = s.lowestY
            e.soft = s.soft
            e.combo = s.combo
            e.lastCleared = s.lastCleared
            e.clearCount = s.clearRows.size.coerceAtMost(4)
            for (i in 0 until e.clearCount) e.clearRows[i] = s.clearRows[i]
            e.clearMsLeft = s.clearMsLeft
            e.danger = s.danger
            e.version = s.version
            e.refill()
            if (e.pieceKind != 0) e.updateLanding()
            return e
        }
    }
}
