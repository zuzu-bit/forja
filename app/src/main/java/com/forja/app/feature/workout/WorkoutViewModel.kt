package com.forja.app.feature.workout

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.forja.app.ForjaApp
import com.forja.app.core.data.db.ExerciseEntity
import com.forja.app.core.data.db.PlanEntity
import com.forja.app.core.data.db.SetLogEntity
import com.forja.app.core.data.db.WorkoutSessionEntity
import com.forja.app.core.music.Mix
import com.forja.app.core.music.Music
import com.forja.app.core.music.MusicKind
import com.forja.app.core.music.MusicStarter
import com.forja.app.core.music.MusicSource
import com.forja.app.core.music.MusicStats
import com.forja.app.core.music.Playlist
import com.forja.app.core.music.Want
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Starea sesiunii live — logica exactă din prototip: serii → pauză 90s → auto-avans. */
data class LiveState(
    val exercises: List<ExerciseEntity> = emptyList(),
    val planName: String = "",
    val exPos: Int = 0,
    val setNo: Int = 1,
    val resting: Boolean = false,
    val restLeft: Int = 90,
    val angleFront: Boolean = true,
    val startedAt: Long = 0L,
    val totalSetsDone: Int = 0,
    val finished: Boolean = false,
    val toast: String = "",
    val toastKey: Int = 0,
    /** Pauză cerută de om („pauză” / butonul): cronometrul și pauza dintre serii stau pe loc. */
    val paused: Boolean = false,
    val pausedAt: Long = 0L,
    /** Cât a stat sesiunea în pauză până acum (fără pauza în curs). */
    val pausedMs: Long = 0L
) {
    val current: ExerciseEntity? get() = exercises.getOrNull(exPos)
    val next: ExerciseEntity? get() = exercises.getOrNull(exPos + 1)
    /** Secundele de antrenament efectiv (fără pauzele cerute). */
    fun elapsedSec(now: Long = System.currentTimeMillis()): Long {
        if (startedAt <= 0L) return 0L
        val pausing = if (paused && pausedAt > 0L) now - pausedAt else 0L
        return ((now - startedAt - pausedMs - pausing) / 1000L).coerceAtLeast(0L)
    }
    val plannedSets: Int get() = exercises.sumOf { it.sets }
}

class WorkoutViewModel(app: Application) : AndroidViewModel(app) {
    private val forja = app as ForjaApp
    private val dao = forja.db.workoutDao()

    val plans: StateFlow<List<PlanEntity>> get() = _plans
    private val _plans = MutableStateFlow<List<PlanEntity>>(emptyList())

    private val _planIdx = MutableStateFlow(0)
    val planIdx: StateFlow<Int> = _planIdx.asStateFlow()

    private val _planExercises = MutableStateFlow<List<ExerciseEntity>>(emptyList())
    val planExercises: StateFlow<List<ExerciseEntity>> = _planExercises.asStateFlow()

    private val _live = MutableStateFlow(LiveState())
    val live: StateFlow<LiveState> = _live.asStateFlow()

    private var sessionId: Long = 0
    private var restJob: Job? = null
    /** O sesiune e în curs (între „Începe sesiunea” și final / „Încheie” / Înapoi). */
    private var sessionLive = false
    /** Pauza cerută de om a oprit și muzica FORJA: la continuare o reia. */
    private var musicPausedByUs = false
    /** „Hei FORJA, începe antrenamentul” sosit înainte să fie planurile încărcate. */
    private var wantStart: WorkoutLink.Request.Start? = null

    /** Orice schimbare a stării live se vede și prin [WorkoutLink] (comenzile vocale). */
    private fun setLive(s: LiveState) {
        _live.value = s
        WorkoutLink.publish(if (sessionLive && !s.finished) s else null)
    }

    /** „Muzică” la Antrenament: comutatorul, lista aleasă, „Oprește la final”, listele pentru planul de azi. */
    private val _music = MutableStateFlow(WorkoutMusicState())
    val music: StateFlow<WorkoutMusicState> = _music.asStateFlow()
    private var musicJob: Job? = null

    init {
        WorkoutLink.takePendingStart()?.let { wantStart = it }
        viewModelScope.launch {
            dao.plans().collect { list ->
                _plans.value = list
                if (list.isNotEmpty()) {
                    loadPlan(_planIdx.value.coerceIn(0, list.size - 1))
                    wantStart?.let { wantStart = null; startByVoice(it) }
                }
            }
        }
        viewModelScope.launch { WorkoutLink.requests.collect { apply(it) } }
        viewModelScope.launch {
            combine(MusicStats.workoutMusicFlow(app), MusicStats.workoutMixFlow(app), MusicStats.workoutStopFlow(app)) { on, mix, stop ->
                Triple(on, mix, stop)
            }.collect { (on, mix, stop) ->
                val access = Music.hasAccess(forja)
                // Nealeasă: pornită dacă FORJA vede muzica (acces dat), oprită altfel.
                _music.value = _music.value.copy(on = on ?: access, access = access, mix = mix, stopAtEnd = stop)
            }
        }
    }

    /**
     * Recalculează listele (Mix/Noi/Vechi/Apreciate) pentru planul de azi; la deschiderea hubului și la revenire.
     * Întâi încălzește motorul (sesiunile, playerele), ca „Începe sesiunea” să găsească planul gata, iar la final
     * află dacă pornirea ar sări în Spotify (↗ lângă eticheta listei).
     */
    fun refreshMusic() {
        musicJob?.cancel()
        musicJob = viewModelScope.launch {
            try { MusicStarter.prewarm(forja) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            val access = Music.hasAccess(forja)
            val target = Playlist.targetMinutes(_planExercises.value.map { it.sets to it.reps })
            val result = try {
                withContext(Dispatchers.IO) {
                    val rows = MusicStats.rows(forja)
                    val lib = MusicStats.library(forja)
                    val now = System.currentTimeMillis()
                    val lists = Mix.entries.associateWith { Playlist.build(rows, lib, it, target, now) }
                    val pkg = lists.values.firstNotNullOfOrNull { it.playerPkg } ?: MusicStats.preferredPkg(forja) ?: MusicKind.SPOTIFY
                    lists to (pkg to (MusicKind.MUSIC_APPS[pkg] ?: pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val stored = try { MusicStats.workoutMusic(forja) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            val next = _music.value.copy(
                on = stored ?: access,
                access = access,
                lists = result?.first ?: _music.value.lists,
                counts = result?.first?.get(Mix.MIX)?.counts ?: _music.value.counts,
                player = result?.second?.second ?: _music.value.player,
                playerPkg = result?.second?.first ?: _music.value.playerPkg
            )
            // Situația de pe 29.09 (nicio sesiune Spotify, tasta media la YouTube): pornirea sare o clipă în Spotify.
            val hop = next.on && try {
                MusicStarter.willHop(Want.Workout(next.list?.takeIf { next.effective != Mix.LIKED }?.items?.firstOrNull()?.ref()))
            } catch (_: Exception) {
                false
            }
            _music.value = next.copy(hop = hop)
        }
    }

    fun setMusicOn(on: Boolean) {
        _music.value = _music.value.copy(on = on)
        viewModelScope.launch { try { MusicStats.setWorkoutMusic(forja, on) } catch (_: Exception) { } }
    }

    /** Alegerea unei liste pornește și muzica. */
    fun setMix(mix: Mix) {
        _music.value = _music.value.copy(mix = mix, on = true)
        viewModelScope.launch {
            try {
                MusicStats.setWorkoutMix(forja, mix)
                MusicStats.setWorkoutMusic(forja, true)
            } catch (_: Exception) { }
        }
    }

    fun setMusicStopAtEnd(on: Boolean) {
        _music.value = _music.value.copy(stopAtEnd = on)
        viewModelScope.launch { try { MusicStats.setWorkoutStop(forja, on) } catch (_: Exception) { } }
    }

    /** O atingere pe disc când nu cântă nimic: lista sesiunii, cu voie de salt în player (e o atingere). */
    fun startMusicNow() {
        val m = _music.value
        MusicStarter.startWorkout(forja, m.effective, targetMinutes(), tap = true, ready = m.lists[m.effective])
    }

    private fun targetMinutes(): Int = Playlist.targetMinutes(_live.value.exercises.ifEmpty { _planExercises.value }.map { it.sets to it.reps })

    fun selectPlan(idx: Int) {
        _planIdx.value = idx
        viewModelScope.launch { loadPlan(idx) }
    }

    /** Editare de către utilizator a seriilor/repetărilor/greutății, înainte de sesiune. */
    fun updateExercise(id: Int, sets: Int, reps: Int, load: String) {
        viewModelScope.launch {
            dao.updateExerciseParams(id, sets.coerceIn(1, 20), reps.coerceIn(1, 100), load.ifBlank { "corp" })
            loadPlan(_planIdx.value)
        }
    }

    private suspend fun loadPlan(idx: Int) {
        val plan = _plans.value.getOrNull(idx) ?: return
        _planExercises.value = dao.exercisesForPlan(plan.id)
        refreshMusic()
    }

    // ── „Hei FORJA” (4.9): cererile asistentului, aplicate pe firul principal, doar când au sens ──

    private fun apply(r: WorkoutLink.Request) {
        val s = _live.value
        when (r) {
            is WorkoutLink.Request.Start -> startByVoice(r)
            WorkoutLink.Request.Pause -> pause()
            WorkoutLink.Request.Resume -> resume()
            WorkoutLink.Request.FinishSet -> if (sessionLive && !s.finished && !s.paused && !s.resting) finishSet()
            WorkoutLink.Request.SkipRest -> if (sessionLive && s.resting && !s.paused) skipRest()
            WorkoutLink.Request.AddRest -> if (sessionLive && s.resting) addRest()
            WorkoutLink.Request.NextExercise -> nextExercise()
            WorkoutLink.Request.End -> if (sessionLive && !s.finished) endEarly()
        }
    }

    /** Pornirea cerută cu vocea: planul cerut (sau cel ales), încărcat dacă trebuie, apoi sesiunea de la primul exercițiu. */
    private fun startByVoice(r: WorkoutLink.Request.Start) {
        WorkoutLink.clearPendingStart()
        if (sessionLive && !_live.value.finished) return
        val plans = _plans.value
        if (plans.isEmpty()) { wantStart = r; return }
        viewModelScope.launch {
            val idx = r.planIdx?.coerceIn(0, plans.size - 1) ?: _planIdx.value
            if (idx != _planIdx.value || _planExercises.value.isEmpty()) {
                _planIdx.value = idx
                loadPlan(idx)
            }
            if (_planExercises.value.isEmpty()) return@launch
            startSession(0)
        }
    }

    /** Pauză cerută de om: cronometrul și pauza dintre serii stau; muzica pornită de FORJA tace și ea. */
    fun pause() {
        val s = _live.value
        if (!sessionLive || s.finished || s.paused) return
        restJob?.cancel()
        setLive(s.copy(paused = true, pausedAt = System.currentTimeMillis()))
        val track = Music.nowPlaying.value
        if (MusicStarter.origin.value == MusicSource.WORKOUT && track?.playing == true) {
            musicPausedByUs = true
            Music.pause(forja)
        }
    }

    /** Continuarea după pauză: timpul stat nu se numără; pauza dintre serii reia de unde a rămas. */
    fun resume() {
        val s = _live.value
        if (!sessionLive || !s.paused) return
        setLive(s.copy(paused = false, pausedAt = 0L, pausedMs = s.pausedMs + (System.currentTimeMillis() - s.pausedAt).coerceAtLeast(0L)))
        if (s.resting) startRestTimer()
        if (musicPausedByUs) {
            musicPausedByUs = false
            val id = Music.nowPlaying.value?.id
            if (id != null) MusicStarter.start(forja, Want.Resume(id), MusicSource.WORKOUT, tap = false) else startMusicNow()
        }
    }

    /** „Următorul exercițiu”: seriile rămase la cel curent se lasă; ultimul exercițiu încheie sesiunea. */
    fun nextExercise() {
        val s = _live.value
        if (!sessionLive || s.finished || s.paused) return
        advance(s.totalSetsDone)
    }

    fun startSession(fromExercise: Int = 0) {
        val plan = _plans.value.getOrNull(_planIdx.value) ?: return
        val exs = _planExercises.value
        if (exs.isEmpty()) return
        restJob?.cancel()
        musicPausedByUs = false
        sessionLive = true
        setLive(LiveState(
            exercises = exs,
            planName = plan.name,
            exPos = fromExercise.coerceIn(0, exs.size - 1),
            startedAt = System.currentTimeMillis()
        ))
        viewModelScope.launch {
            sessionId = dao.insertSession(
                WorkoutSessionEntity(planId = plan.id, planName = plan.name, startedAt = System.currentTimeMillis())
            )
        }
        // Cât ține sesiunea, un inventar terminat nu oprește muzica (nici pe a ta, nici pe cea pornită de FORJA).
        MusicStarter.workoutBegan()
        // Muzica pornește odată cu sesiunea, fără să țină nimic în loc; dacă muzica ta cântă deja, rămâne a ta.
        // „Începe sesiunea” e o atingere (4.4.1): fără nicio cale invizibilă, cel mult un salt în Spotify, în 1,5 s de la ea.
        // Lista e cea clădită de hub (cea arătată), deci pornirea n-o mai clădește în fereastra saltului.
        val m = _music.value
        if (m.on) MusicStarter.startWorkout(forja, m.effective, targetMinutes(), tap = true, ready = m.lists[m.effective])
    }

    private fun toast(msg: String) {
        setLive(_live.value.copy(toast = msg, toastKey = _live.value.toastKey + 1))
    }

    fun toggleAngle() {
        setLive(_live.value.copy(angleFront = !_live.value.angleFront))
    }

    /** „Termină seria": salvează în jurnal; pauză sau avans. */
    fun finishSet() {
        val s = _live.value
        val ex = s.current ?: return
        viewModelScope.launch {
            dao.insertSetLog(
                SetLogEntity(
                    sessionId = sessionId, exerciseId = ex.id, exerciseName = ex.name,
                    setNo = s.setNo, reps = ex.reps, load = ex.load, at = System.currentTimeMillis()
                )
            )
        }
        val done = s.totalSetsDone + 1
        if (s.setNo < ex.sets) {
            setLive(s.copy(resting = true, restLeft = 90, totalSetsDone = done))
            toast("Serie salvată. Încă ${ex.sets - s.setNo} la acest exercițiu.")
            startRestTimer()
        } else {
            advance(done)
        }
    }

    private fun startRestTimer() {
        restJob?.cancel()
        restJob = viewModelScope.launch {
            while (_live.value.resting && _live.value.restLeft > 0) {
                delay(1000)
                val s = _live.value
                if (!s.resting) return@launch
                if (s.restLeft <= 1) {
                    endRest()
                } else {
                    setLive(s.copy(restLeft = s.restLeft - 1))
                }
            }
        }
    }

    fun addRest() {
        val s = _live.value
        setLive(s.copy(restLeft = (s.restLeft + 15).coerceAtMost(180)))
    }

    fun skipRest() = endRest()

    private fun endRest() {
        restJob?.cancel()
        val s = _live.value
        setLive(s.copy(resting = false, restLeft = 90, setNo = s.setNo + 1))
    }

    /** Ultima serie a exercițiului → auto-avans; ultimul exercițiu → înapoi în hub + rezumat. */
    private fun advance(done: Int) {
        restJob?.cancel()
        val s = _live.value
        if (s.exPos < s.exercises.size - 1) {
            val next = s.exercises[s.exPos + 1]
            setLive(s.copy(
                resting = false, restLeft = 90, setNo = 1,
                exPos = s.exPos + 1, totalSetsDone = done
            ))
            toast("Exercițiu terminat. Urmează: ${next.name}.")
        } else {
            val durS = s.elapsedSec()
            val min = durS / 60
            val sec = durS % 60
            // Sesiunea s-a încheiat: nu mai e „în curs” pentru asistent, iar muzica pornită de FORJA se oprește
            // (dacă „Oprește la final”), apoi sunetul „misiune îndeplinită”.
            sessionLive = false
            musicPausedByUs = false
            setLive(s.copy(resting = false, finished = true, totalSetsDone = done))
            toast("Sesiune încheiată în %d:%02d. Misiune îndeplinită.".format(min, sec))
            MusicStarter.endWorkout(forja, finished = true)
            viewModelScope.launch {
                dao.session(sessionId)?.let {
                    dao.updateSession(it.copy(endedAt = System.currentTimeMillis(), totalSets = done))
                }
            }
        }
    }

    fun endEarly() {
        restJob?.cancel()
        // „Încheie” (sau Înapoi): se oprește doar muzica pornită de FORJA, fără sunet.
        sessionLive = false
        musicPausedByUs = false
        WorkoutLink.publish(null)
        MusicStarter.endWorkout(forja, finished = false)
        val s = _live.value
        if (s.totalSetsDone > 0) {
            viewModelScope.launch {
                dao.session(sessionId)?.let {
                    dao.updateSession(it.copy(endedAt = System.currentTimeMillis(), totalSets = s.totalSetsDone))
                }
            }
        }
    }

    /** Aplicația s-a închis în timpul sesiunii: coada FORJA și împrumutul nu trăiesc mai departe decât antrenamentul. */
    override fun onCleared() {
        WorkoutLink.publish(null)
        if (sessionLive) {
            sessionLive = false
            MusicStarter.endWorkout(forja, finished = false)
        }
    }
}
