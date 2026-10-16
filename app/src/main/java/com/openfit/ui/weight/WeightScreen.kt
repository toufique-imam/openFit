@file:UseSerializers(InstantSerializer::class, DurationSerializer::class, LocalDateSerializer::class, IntRangeSerializer::class, LocalTimeSerializer::class)

package com.openfit.ui.weight

import com.openfit.ui.detail.keepAfterFailure
import com.openfit.ui.detail.SavedScreen
import com.openfit.data.SnapshotStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import com.openfit.domain.DurationSerializer
import com.openfit.domain.InstantSerializer
import com.openfit.domain.IntRangeSerializer
import com.openfit.domain.LocalDateSerializer
import com.openfit.domain.LocalTimeSerializer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.openfit.R
import com.openfit.domain.ChartPeriod
import com.openfit.domain.periodDays
import com.openfit.domain.shiftPeriod
import com.openfit.health.HealthConnectGateway
import com.openfit.health.HealthConnectVitals
import com.openfit.profile.Profile
import com.openfit.profile.ProfileStore
import com.openfit.profile.WeightUnit
import com.openfit.ui.detail.AxisLabel
import com.openfit.ui.detail.DetailFailed
import com.openfit.ui.detail.DetailList
import com.openfit.ui.detail.DetailLoading
import com.openfit.ui.detail.DetailScaffold
import com.openfit.ui.detail.GroupedCard
import com.openfit.ui.detail.HeaderValue
import com.openfit.ui.detail.LineDotsChart
import com.openfit.ui.detail.MenuAction
import com.openfit.ui.detail.SetGoalLink
import com.openfit.ui.goals.Goal
import com.openfit.ui.goals.GoalEditor
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.openfit.ui.detail.PagePadding
import com.openfit.ui.detail.PeriodNavigator
import com.openfit.ui.detail.SectionGap
import com.openfit.ui.detail.canGoForward
import com.openfit.ui.detail.chartXLabels
import com.openfit.ui.detail.periodLabel
import com.openfit.ui.theme.OpenFitColors
import com.openfit.ui.today.oneDecimal
import com.openfit.ui.today.weekdayDayMonth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

private val ACCENT = OpenFitColors.Blue
private const val POUNDS_PER_KG = 2.20462
private val PERIODS = listOf(ChartPeriod.Week, ChartPeriod.Month, ChartPeriod.ThreeMonths, ChartPeriod.Year)

sealed interface WeightUiState {
    data object Loading : WeightUiState
    data class Failed(val message: String) : WeightUiState
    @Serializable
    data class Ready(
        val period: ChartPeriod,
        val anchor: LocalDate,
        val today: LocalDate,
        val profile: Profile,
        /** Kilograms per day within the period. */
        val weights: Map<LocalDate, Double>,
        /** The most recent weight on or before the period's end, for the header and the goal. */
        val latest: Pair<LocalDate, Double>?,
    ) : WeightUiState
}

class WeightViewModel(gateway: HealthConnectGateway, private val profileStore: ProfileStore, snapshots: SnapshotStore) : ViewModel() {

    private val vitals = HealthConnectVitals(gateway)

    private val _state = MutableStateFlow<WeightUiState>(WeightUiState.Loading)
    val state: StateFlow<WeightUiState> = _state.asStateFlow()

    private val saved = SavedScreen(snapshots, "weight", WeightUiState.Ready.serializer())
    /** Which period (or day) the Ready state on screen is for. */
    private var shownName: String? = null

    /** Shows the saved state for [name] at once, unless it is already on screen. */
    private suspend fun restoreSaved(name: String) {
        if (shownName == name && _state.value is WeightUiState.Ready) return
        saved.restore(name)?.let { ready ->
            _state.value = ready.copy(today = LocalDate.now(), profile = profileStore.load())
            shownName = name
        }
    }

    private var period = ChartPeriod.Month
    private var anchor: LocalDate = LocalDate.now()
    private var loading: Job? = null

    fun open(date: LocalDate) {
        period = ChartPeriod.Month
        anchor = date
        _state.value = WeightUiState.Loading
        load()
    }

    fun saveProfile(profile: Profile) {
        profileStore.save(profile)
        load()
    }

    fun selectPeriod(selected: ChartPeriod) {
        if (selected == period) return
        period = selected
        load()
    }

    fun shift(steps: Long) {
        anchor = shiftPeriod(period, anchor, steps).coerceAtMost(LocalDate.now())
        load()
    }

    private fun load() {
        loading?.cancel()
        loading = viewModelScope.launch {
            val name = "$period:$anchor"
            restoreSaved(name)
            var kept = false
            _state.value = try {
                val today = LocalDate.now()
                val days = periodDays(period, anchor).filter { it <= today }
                // Look back a year so the header can show the latest weight even in a period without readings.
                val all = vitals.weights(days.first().minusYears(1), days.last())
                WeightUiState.Ready(
                    period = period,
                    anchor = anchor,
                    today = today,
                    profile = profileStore.load(),
                    weights = all.filterKeys { it in days },
                    latest = all.maxByOrNull { it.key }?.toPair(),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val current = _state.value
                kept = keepAfterFailure(error, current is WeightUiState.Ready && shownName == name)
                if (kept) current else WeightUiState.Failed(error.message ?: error.javaClass.simpleName)
            }
            (_state.value as? WeightUiState.Ready)?.takeIf { !kept }?.let { ready ->
                shownName = name
                saved.save(name, anchor, ready)
            }
        }
    }
}

@Composable
fun WeightRoute(viewModel: WeightViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ready = state as? WeightUiState.Ready
    var editingGoal by rememberSaveable { mutableStateOf(false) }
    val setGoal = { editingGoal = true }
    DetailScaffold(
        title = stringResource(R.string.tile_weight),
        accent = ACCENT.accent,
        period = ready?.period ?: ChartPeriod.Month,
        periods = PERIODS,
        onPeriod = viewModel::selectPeriod,
        onBack = onBack,
        menu = listOfNotNull(ready?.let { MenuAction(stringResource(if (it.profile.weightGoalKg == null) R.string.goal_set else R.string.goal_adjust), setGoal) }),
    ) {
        when (val current = state) {
            WeightUiState.Loading -> DetailLoading(ACCENT.accent)
            is WeightUiState.Failed -> DetailFailed(current.message)
            is WeightUiState.Ready -> DetailList(current.period, current.anchor) {
                item {
                    PeriodNavigator(
                        periodLabel(current.period, current.anchor, current.today),
                        canGoForward(current.period, current.anchor, current.today),
                        viewModel::shift,
                    )
                }
                item { Header(current, setGoal) }
                item { Chart(current) }
                val rows = current.weights.entries.sortedByDescending { it.key }
                if (rows.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.weight_list_header),
                            style = MaterialTheme.typography.titleMedium,
                            color = OpenFitColors.OnSurface,
                            modifier = Modifier.padding(start = PagePadding, end = PagePadding, top = SectionGap, bottom = 16.dp),
                        )
                    }
                    itemsIndexed(rows) { index, (day, kg) ->
                        GroupedCard(index, rows.size, minHeight = 68.dp) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(day.weekdayDayMonth(), style = MaterialTheme.typography.titleSmall, color = OpenFitColors.OnSurface, modifier = Modifier.weight(1f))
                                Text(
                                    "${oneDecimal(display(kg, current.profile))} ${stringResource(unit(current.profile))}",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = OpenFitColors.OnSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (editingGoal && ready != null) {
        GoalEditor(
            goal = Goal.Weight,
            profile = ready.profile,
            onSave = {
                editingGoal = false
                viewModel.saveProfile(it)
            },
            onDismiss = { editingGoal = false },
        )
    }
}

@Composable
private fun Header(state: WeightUiState.Ready, onSetGoal: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = PagePadding, end = PagePadding, top = 13.dp)) {
        val latest = state.latest
        val unit = stringResource(unit(state.profile))
        HeaderValue(latest?.let { oneDecimal(display(it.second, state.profile)) } ?: "–", unit)
        val ordered = state.weights.entries.sortedBy { it.key }
        val change = if (ordered.size >= 2) ordered.last().value - ordered.first().value else null
        Text(
            when {
                ordered.isEmpty() -> stringResource(R.string.weight_no_data)
                change == null || abs(change) < 0.05 -> stringResource(R.string.weight_unchanged_over_period)
                change < 0 -> stringResource(R.string.weight_lost_over_period, "${oneDecimal(display(-change, state.profile))} $unit")
                else -> stringResource(R.string.weight_gained_over_period, "${oneDecimal(display(change, state.profile))} $unit")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = OpenFitColors.OnSurfaceVariant,
        )
        goalText(latest?.second, state.profile)?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant)
        }
        if (state.profile.weightGoalKg == null) SetGoalLink(ACCENT.accent, onClick = onSetGoal)
    }
}

@Composable
private fun Chart(state: WeightUiState.Ready) {
    val values = when (state.period) {
        ChartPeriod.Year -> (1..12).map { month ->
            YearMonth.of(state.anchor.year, month).let { ym -> (1..ym.lengthOfMonth()).mapNotNull { state.weights[ym.atDay(it)] } }.takeIf { it.isNotEmpty() }?.average()
        }
        ChartPeriod.ThreeMonths -> periodDays(state.period, state.anchor).chunked(7).map { week -> week.mapNotNull { state.weights[it] }.takeIf { it.isNotEmpty() }?.average() }
        else -> periodDays(state.period, state.anchor).map { state.weights[it] }
    }.map { it?.let { kg -> display(kg, state.profile) } }
    val goal = state.profile.weightGoalKg?.let { display(it, state.profile) }
    val all = values.filterNotNull() + listOfNotNull(goal)
    val low = floor((all.minOrNull() ?: 60.0) - 1)
    val high = ceil((all.maxOrNull() ?: 80.0) + 1)
    LineDotsChart(
        values = values,
        color = ACCENT.accent,
        axisMin = low,
        axisMax = high,
        axisLabels = listOf(low, (low + high) / 2, high).map { AxisLabel(it, oneDecimal(it)) },
        xLabels = chartXLabels(state.period, state.anchor),
        goal = goal,
        modifier = Modifier.padding(top = 32.dp),
    )
}

/** Fitbit's goal line on the weight tile and screen: "9 kg left to lose", "left to gain" or "goal met". */
@Composable
fun goalText(latestKg: Double?, profile: Profile): String? {
    val goal = profile.weightGoalKg ?: return null
    latestKg ?: return null
    val difference = latestKg - goal
    val unit = stringResource(unit(profile))
    val amount = "${oneDecimal(display(abs(difference), profile))} $unit"
    return when {
        abs(difference) < GOAL_TOLERANCE_KG -> stringResource(R.string.weight_goal_met)
        difference > 0 -> stringResource(R.string.weight_left_to_lose, amount)
        else -> stringResource(R.string.weight_left_to_gain, amount)
    }
}

private const val GOAL_TOLERANCE_KG = 0.25

private fun display(kg: Double, profile: Profile): Double = if (profile.weightUnit == WeightUnit.Pounds) kg * POUNDS_PER_KG else kg

private fun unit(profile: Profile): Int = if (profile.weightUnit == WeightUnit.Pounds) R.string.unit_lb else R.string.unit_kg
