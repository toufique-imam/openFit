package com.openfit.ui.journal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openfit.domain.BehaviourImpact
import com.openfit.domain.behaviourImpact
import com.openfit.domain.readiness
import com.openfit.domain.sleepScore
import com.openfit.health.DayLoadCache
import com.openfit.health.HealthConnectGateway
import com.openfit.health.HealthConnectSleep
import com.openfit.health.HealthConnectSource
import com.openfit.journal.JournalStore
import com.openfit.profile.ProfileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/** The next-day score the insights compare: the next morning's Readiness or HRV, or that night's Sleep Score. */
enum class JournalOutcome { Readiness, SleepScore, Hrv }

data class JournalUiState(
    val date: LocalDate,
    val today: LocalDate,
    val behaviours: List<Behaviour>,
    val asked: List<Behaviour>,
    val answers: Map<String, Boolean>,
    /** The last seven days up to [date], and whether each has any answer. */
    val week: List<Pair<LocalDate, Boolean>>,
    val outcome: JournalOutcome,
    /** Per asked behaviour, for [outcome]; null while the scores are loading or could not be read. */
    val impacts: Map<String, BehaviourImpact>?,
) {
    val isToday: Boolean get() = date == today
}

class JournalViewModel(
    private val gateway: HealthConnectGateway,
    private val profileStore: ProfileStore,
    dayLoadCache: DayLoadCache,
    private val store: JournalStore,
) : ViewModel() {

    private val source = HealthConnectSource(gateway, dayLoadCache)
    private val sleep = HealthConnectSleep(gateway)

    private val _state = MutableStateFlow(build(LocalDate.now(), JournalOutcome.Readiness))
    val state: StateFlow<JournalUiState> = _state.asStateFlow()

    /** Daily outcome values over the insight window, loaded once per day. */
    private var outcomes: Map<JournalOutcome, Map<LocalDate, Double>>? = null
    private var outcomesDay: LocalDate? = null
    private var outcomesGeneration = -1
    private var loading: Job? = null

    fun refresh() {
        _state.value = build(_state.value.date.coerceAtMost(LocalDate.now()), _state.value.outcome)
        if (outcomesDay != LocalDate.now() || outcomesGeneration != gateway.store.revision) loadOutcomes()
    }

    fun showDate(date: LocalDate) {
        _state.value = build(date.coerceAtMost(LocalDate.now()), _state.value.outcome)
    }

    fun answer(key: String, value: Boolean?) {
        store.setAnswer(_state.value.date, key, value)
        _state.value = build(_state.value.date, _state.value.outcome)
    }

    fun selectOutcome(outcome: JournalOutcome) {
        _state.value = build(_state.value.date, outcome)
    }

    fun setAsked(key: String, asked: Boolean) {
        val current = store.asked()
        store.setAsked(if (asked) (current + key).distinct() else current - key)
        _state.value = build(_state.value.date, _state.value.outcome)
    }

    /** Adds the user's own behaviour; false when the label is empty or already used. */
    fun addCustom(label: String): Boolean {
        val added = store.addCustom(label) != null
        if (added) _state.value = build(_state.value.date, _state.value.outcome)
        return added
    }

    private fun build(date: LocalDate, outcome: JournalOutcome): JournalUiState {
        val today = LocalDate.now()
        val behaviours = allBehaviours(store.customLabels())
        val byKey = behaviours.associateBy { it.key }
        val asked = store.asked().mapNotNull(byKey::get)
        return JournalUiState(
            date = date,
            today = today,
            behaviours = behaviours,
            asked = asked,
            answers = store.answers(date),
            week = (6L downTo 0L).map { date.minusDays(it) }.map { day -> day to store.answers(day).isNotEmpty() },
            outcome = outcome,
            impacts = outcomes?.get(outcome)?.let { values -> impacts(asked, values, today) },
        )
    }

    private fun impacts(asked: List<Behaviour>, outcome: Map<LocalDate, Double>, today: LocalDate): Map<String, BehaviourImpact> {
        val answers = store.answersBetween(today.minusDays(INSIGHT_DAYS), today)
        return asked.associate { behaviour ->
            behaviour.key to behaviourImpact(answers.mapNotNull { (day, dayAnswers) -> dayAnswers[behaviour.key]?.let { day to it } }.toMap(), outcome)
        }
    }

    private fun loadOutcomes() {
        loading?.cancel()
        loading = viewModelScope.launch {
            try {
                val today = LocalDate.now()
                val generation = gateway.store.revision
                val from = today.minusDays(INSIGHT_DAYS)
                val history = source.history(from.minusDays(BASELINE_LEAD_DAYS), today, profileStore.load(), backfillDays = 0)
                val days = generateSequence(from) { it.plusDays(1) }.takeWhile { it <= today }.toList()
                outcomes = mapOf(
                    JournalOutcome.Readiness to days.mapNotNull { day -> readiness(day, history)?.let { day to it.score.toDouble() } }.toMap(),
                    JournalOutcome.SleepScore to sleep.nights(from, today).mapNotNull { (day, night) -> sleepScore(night)?.let { day to it.toDouble() } }.toMap(),
                    JournalOutcome.Hrv to history.hrvMs.filterKeys { it >= from },
                )
                outcomesDay = today
                outcomesGeneration = generation
                _state.value = build(_state.value.date, _state.value.outcome)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Insights stay unavailable; the check-in itself never needs Health Connect.
            }
        }
    }

    private companion object {
        /** Insights look at the last 90 days of answers. */
        const val INSIGHT_DAYS = 90L

        /** Readiness compares each day with the 30 days before it. */
        const val BASELINE_LEAD_DAYS = 31L
    }
}
