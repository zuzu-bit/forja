package com.forja.app.feature.workout

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.forja.app.ForjaApp
import com.forja.app.core.data.db.ExerciseEntity
import com.forja.app.core.data.db.PlanEntity
import com.forja.app.core.data.db.SetLogEntity
import com.forja.app.core.data.db.WorkoutSessionEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    val toastKey: Int = 0
) {
    val current: ExerciseEntity? get() = exercises.getOrNull(exPos)
    val next: ExerciseEntity? get() = exercises.getOrNull(exPos + 1)
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

    init {
        viewModelScope.launch {
            dao.plans().collect { list ->
                _plans.value = list
                if (list.isNotEmpty()) loadPlan(_planIdx.value.coerceIn(0, list.size - 1))
            }
        }
    }

    fun selectPlan(idx: Int) {
        _planIdx.value = idx
        viewModelScope.launch { loadPlan(idx) }
    }

    private suspend fun loadPlan(idx: Int) {
        val plan = _plans.value.getOrNull(idx) ?: return
        _planExercises.value = dao.exercisesForPlan(plan.id)
    }

    fun startSession(fromExercise: Int = 0) {
        val plan = _plans.value.getOrNull(_planIdx.value) ?: return
        val exs = _planExercises.value
        if (exs.isEmpty()) return
        _live.value = LiveState(
            exercises = exs,
            planName = plan.name,
            exPos = fromExercise.coerceIn(0, exs.size - 1),
            startedAt = System.currentTimeMillis()
        )
        viewModelScope.launch {
            sessionId = dao.insertSession(
                WorkoutSessionEntity(planId = plan.id, planName = plan.name, startedAt = System.currentTimeMillis())
            )
        }
    }

    private fun toast(msg: String) {
        _live.value = _live.value.copy(toast = msg, toastKey = _live.value.toastKey + 1)
    }

    fun toggleAngle() {
        _live.value = _live.value.copy(angleFront = !_live.value.angleFront)
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
            _live.value = s.copy(resting = true, restLeft = 90, totalSetsDone = done)
            toast("Serie salvată. Încă ${ex.sets - s.setNo} și ai terminat.")
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
                    _live.value = s.copy(restLeft = s.restLeft - 1)
                }
            }
        }
    }

    fun addRest() {
        val s = _live.value
        _live.value = s.copy(restLeft = (s.restLeft + 15).coerceAtMost(180))
    }

    fun skipRest() = endRest()

    private fun endRest() {
        restJob?.cancel()
        val s = _live.value
        _live.value = s.copy(resting = false, restLeft = 90, setNo = s.setNo + 1)
    }

    /** Ultima serie a exercițiului → auto-avans; ultimul exercițiu → înapoi în hub + rezumat. */
    private fun advance(done: Int) {
        restJob?.cancel()
        val s = _live.value
        if (s.exPos < s.exercises.size - 1) {
            val next = s.exercises[s.exPos + 1]
            _live.value = s.copy(
                resting = false, restLeft = 90, setNo = 1,
                exPos = s.exPos + 1, totalSetsDone = done
            )
            toast("Exercițiu terminat. Urmează: ${next.name}.")
        } else {
            val durS = (System.currentTimeMillis() - s.startedAt) / 1000
            val min = durS / 60
            val sec = durS % 60
            _live.value = s.copy(resting = false, finished = true, totalSetsDone = done)
            toast("Sesiune încheiată. %d:%02d · bravo.".format(min, sec))
            viewModelScope.launch {
                dao.session(sessionId)?.let {
                    dao.updateSession(it.copy(endedAt = System.currentTimeMillis(), totalSets = done))
                }
            }
        }
    }

    fun endEarly() {
        restJob?.cancel()
        val s = _live.value
        if (s.totalSetsDone > 0) {
            viewModelScope.launch {
                dao.session(sessionId)?.let {
                    dao.updateSession(it.copy(endedAt = System.currentTimeMillis(), totalSets = s.totalSetsDone))
                }
            }
        }
    }
}
