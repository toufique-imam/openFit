package com.openfit.ui.exercise

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openfit.R
import com.openfit.domain.ChartPeriod
import com.openfit.health.ExerciseSession
import com.openfit.profile.UnitSystem
import com.openfit.ui.detail.AxisLabel
import com.openfit.ui.detail.BarChart
import com.openfit.ui.detail.DetailFailed
import com.openfit.ui.detail.DetailList
import com.openfit.ui.detail.DetailLoading
import com.openfit.ui.detail.DetailScaffold
import com.openfit.ui.detail.GroupedCard
import com.openfit.ui.detail.HeaderValue
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
import com.openfit.ui.detail.isCurrentPeriod
import com.openfit.ui.detail.periodLabel
import com.openfit.ui.theme.OpenFitColors
import com.openfit.ui.today.count
import com.openfit.ui.today.minutesLabel
import com.openfit.ui.today.twoDecimals
import com.openfit.ui.today.valueText
import com.openfit.ui.today.weekRange
import com.openfit.ui.today.weekdayDayMonth
import com.openfit.ui.today.whole
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.ceil

private val ACCENT @Composable get() = OpenFitColors.Mint
private const val METERS_PER_MILE = 1_609.344

@Composable
fun ExerciseRoute(viewModel: ExerciseViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ready = state as? ExerciseUiState.Ready
    var editingGoal by rememberSaveable { mutableStateOf(false) }
    val setGoal = { editingGoal = true }
    DetailScaffold(
        title = stringResource(R.string.exercise_title),
        accent = ACCENT.accent,
        period = viewModel.period,
        periods = listOf(ChartPeriod.Week, ChartPeriod.Month),
        onPeriod = viewModel::selectPeriod,
        onBack = onBack,
        menu = listOfNotNull(
            ready?.let { MenuAction(stringResource(if (it.profile.weeklyExerciseDaysGoal == null) R.string.goal_set else R.string.goal_adjust), setGoal) },
        ),
    ) {
        when (val current = state) {
            ExerciseUiState.Loading -> DetailLoading(ACCENT.accent)
            is ExerciseUiState.Failed -> DetailFailed(current.message)
            is ExerciseUiState.Ready -> DetailList(current.period, current.anchor) {
                item {
                    PeriodNavigator(
                        periodLabel(current.period, current.anchor, current.today),
                        canGoForward(current.period, current.anchor, current.today),
                        viewModel::shift,
                        period = current.period,
                    )
                }
                content(current, viewModel::selectMode, setGoal)
            }
        }
    }
    if (editingGoal && ready != null) {
        GoalEditor(
            goal = Goal.ExerciseDays,
            profile = ready.profile,
            onSave = {
                editingGoal = false
                viewModel.saveProfile(it)
            },
            onDismiss = { editingGoal = false },
        )
    }
}

private fun LazyListScope.content(state: ExerciseUiState.Ready, onMode: (ExerciseMode) -> Unit, onSetGoal: () -> Unit) {
    val current = isCurrentPeriod(state.period, state.anchor, state.today)
    val week = state.period == ChartPeriod.Week
    item {
        Column(Modifier.fillMaxWidth().padding(start = PagePadding, end = PagePadding, top = 13.dp)) {
            val days = state.exerciseDays.size
            val goal = state.profile.weeklyExerciseDaysGoal
            if (week && goal != null) {
                HeaderValue(valueText { count(days, goal, smallTotal = true) }, stringResource(R.string.exercise_days_unit))
            } else {
                HeaderValue(days.toString(), stringResource(R.string.exercise_days_unit))
            }
            Text(description(state, current), style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant)
            if (week && goal == null) SetGoalLink(ACCENT.accent, onClick = onSetGoal)
        }
    }
    item {
        Column(Modifier.padding(top = 24.dp)) {
            when (state.mode) {
                ExerciseMode.Days -> if (week) DayPills(state) else MonthCalendar(state)
                ExerciseMode.Duration -> ModeBars(state, state.days.map { day -> state.sessions[day]?.sumOf { it.duration.toMinutes() }?.toDouble() }) { minutesLabel(it.toLong()) }
                ExerciseMode.Distance -> ModeBars(state, state.days.map { day -> state.sessions[day]?.mapNotNull { it.distanceMeters }?.sum()?.let { distance(it, state) } }) { twoDecimals(it) }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ModeChip(stringResource(R.string.exercise_chip_days), state.mode == ExerciseMode.Days) { onMode(ExerciseMode.Days) }
                ModeChip(stringResource(R.string.exercise_chip_duration), state.mode == ExerciseMode.Duration) { onMode(ExerciseMode.Duration) }
                ModeChip(stringResource(R.string.exercise_chip_distance), state.mode == ExerciseMode.Distance) { onMode(ExerciseMode.Distance) }
            }
        }
    }
    if (week) sessionList(state) else weekList(state, current)
}

@Composable
private fun description(state: ExerciseUiState.Ready, current: Boolean): String = when (state.mode) {
    ExerciseMode.Days -> pluralStringResource(R.plurals.exercise_times, state.count, state.count)
    ExerciseMode.Duration -> {
        val total = minutesLabel(state.sessions.values.flatten().sumOf { it.duration.toMinutes() })
        stringResource(
            when {
                !current -> R.string.exercise_duration_past
                state.period == ChartPeriod.Week -> R.string.exercise_duration_week
                else -> R.string.exercise_duration_month
            },
            total,
        )
    }
    ExerciseMode.Distance -> {
        val total = "${twoDecimals(distance(state.sessions.values.flatten().mapNotNull { it.distanceMeters }.sum(), state))} ${stringResource(distanceUnit(state))}"
        stringResource(
            when {
                !current -> R.string.exercise_distance_past
                state.period == ChartPeriod.Week -> R.string.exercise_distance_week
                else -> R.string.exercise_distance_month
            },
            total,
        )
    }
}

/** Week view: a tall pill per day, filled with a check on days with exercise. */
@Composable
private fun DayPills(state: ExerciseUiState.Ready) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        state.days.forEach { day ->
            val done = day in state.exerciseDays
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(width = 28.dp, height = 44.dp).clip(CircleShape).background(if (done) ACCENT.accent else ACCENT.track),
                    contentAlignment = Alignment.Center,
                ) {
                    if (done) Icon(Icons.Filled.Check, contentDescription = null, tint = OpenFitColors.Background, modifier = Modifier.size(18.dp))
                }
                Text(
                    day.dayOfWeek.getDisplayName(TextStyle.NARROW, com.openfit.ui.currentLocale()),
                    style = MaterialTheme.typography.bodySmall,
                    color = OpenFitColors.OnSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/** Month view: a calendar with exercise days filled in and today outlined; weekday initials underneath. */
@Composable
private fun MonthCalendar(state: ExerciseUiState.Ready) {
    val month = YearMonth.from(state.anchor)
    val lead = (month.atDay(1).dayOfWeek.value % 7)
    val cells: List<LocalDate?> = List(lead) { null } + (1..month.lengthOfMonth()).map(month::atDay)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { day -> CalendarCell(day, state, Modifier.weight(1f)) }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            com.openfit.domain.WeekStart.days.forEach { weekday ->
                Text(
                    weekday.getDisplayName(TextStyle.NARROW, com.openfit.ui.currentLocale()),
                    style = MaterialTheme.typography.bodySmall,
                    color = OpenFitColors.OnSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun CalendarCell(day: LocalDate?, state: ExerciseUiState.Ready, modifier: Modifier) {
    Box(modifier.height(46.dp), contentAlignment = Alignment.Center) {
        if (day == null) return@Box
        val done = day in state.exerciseDays
        val circle = Modifier.size(34.dp).clip(CircleShape)
        Box(
            when {
                done -> circle.background(ACCENT.accent)
                day == state.today -> circle.border(1.dp, OpenFitColors.OnSurface, CircleShape)
                else -> circle
            },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                day.dayOfMonth.toString(),
                style = MaterialTheme.typography.titleSmall,
                color = if (done) OpenFitColors.Background else OpenFitColors.OnSurface,
            )
        }
    }
}

@Composable
private fun ModeBars(state: ExerciseUiState.Ready, values: List<Double?>, format: (Double) -> String) {
    val highest = values.maxOf { it ?: 0.0 }
    val top = if (highest <= 0) 1.0 else ceil(highest * 1.2)
    BarChart(
        values = values,
        color = ACCENT.accent,
        axisMax = top,
        axisLabels = listOf(0.0, top / 2, top).map { AxisLabel(it, format(it)) },
        xLabels = chartXLabels(state.period, state.anchor),
    )
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1) },
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null, tint = ACCENT.accent) }
        } else {
            null
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = OpenFitColors.PrimaryContainer,
            selectedLabelColor = ACCENT.accent,
            labelColor = OpenFitColors.OnSurface,
        ),
    )
}

private fun LazyListScope.sessionList(state: ExerciseUiState.Ready) {
    state.sessions.toSortedMap(compareByDescending { it }).forEach { (day, sessions) ->
        item(key = "header-$day") {
            Text(
                dayName(state, day),
                style = MaterialTheme.typography.titleMedium,
                color = OpenFitColors.OnSurface,
                modifier = Modifier.padding(start = PagePadding, end = PagePadding, top = SectionGap, bottom = 16.dp),
            )
        }
        val newestFirst = sessions.sortedByDescending { it.start }
        itemsIndexed(newestFirst, key = { _, session -> session.start.toString() }) { index, session ->
            GroupedCard(index, newestFirst.size, minHeight = 92.dp) {
                Row(Modifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    val time = session.start.atZone(java.time.ZoneId.systemDefault()).toLocalTime().format(CLOCK_24H)
                    Text(time, style = MaterialTheme.typography.titleSmall, color = OpenFitColors.OnSurface)
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Text(session.name, style = MaterialTheme.typography.headlineMedium, color = OpenFitColors.OnSurface, maxLines = 1)
                        Text(
                            stringResource(R.string.exercise_detail, sessionMeasure(session, state), minutesLabel(session.duration.toMinutes())),
                            style = MaterialTheme.typography.bodyMedium,
                            color = OpenFitColors.OnSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Fitbit shows distance for walks, runs, rides, hikes and swims, and calories for every other kind of workout. */
@Composable
private fun sessionMeasure(session: ExerciseSession, state: ExerciseUiState.Ready): String {
    val meters = session.distanceMeters
    return when {
        session.type in DISTANCE_TYPES && meters != null && meters >= MIN_DISTANCE_METERS ->
            "${twoDecimals(distance(meters, state))} ${stringResource(distanceUnit(state))}"
        session.caloriesKcal != null -> "${whole(session.caloriesKcal)} ${stringResource(R.string.unit_cal)}"
        else -> ""
    }
}

private const val MIN_DISTANCE_METERS = 10.0
private val CLOCK_24H = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
private val DISTANCE_TYPES = setOf(
    ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
    ExerciseSessionRecord.EXERCISE_TYPE_HIKING,
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY,
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER,
    ExerciseSessionRecord.EXERCISE_TYPE_WHEELCHAIR,
)

private fun LazyListScope.weekList(state: ExerciseUiState.Ready, current: Boolean) {
    if (state.weeks.isEmpty()) return
    item {
        Row(Modifier.fillMaxWidth().padding(start = PagePadding, end = PagePadding, top = SectionGap, bottom = 16.dp)) {
            Text(
                if (current) stringResource(R.string.this_month) else state.anchor.month.getDisplayName(TextStyle.FULL_STANDALONE, com.openfit.ui.currentLocale()),
                style = MaterialTheme.typography.titleMedium,
                color = OpenFitColors.OnSurface,
                modifier = Modifier.weight(1f),
            )
            Text(stringResource(R.string.exercise_list_header), style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface)
        }
    }
    itemsIndexed(state.weeks) { index, week ->
        GroupedCard(index, state.weeks.size, minHeight = 92.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (state.today in week.start..week.end) stringResource(R.string.this_week_label) else weekRange(week.start, week.end),
                    style = MaterialTheme.typography.titleSmall,
                    color = OpenFitColors.OnSurface,
                    modifier = Modifier.weight(1f),
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text(week.count.toString(), style = MaterialTheme.typography.headlineMedium, color = OpenFitColors.OnSurface)
                    Text(pluralStringResource(R.plurals.exercise_types, week.types, week.types), style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun dayName(state: ExerciseUiState.Ready, day: LocalDate): String = when (ChronoUnit.DAYS.between(day, state.today)) {
    0L -> stringResource(R.string.today)
    1L -> stringResource(R.string.yesterday)
    else -> day.weekdayDayMonth()
}

private fun distance(meters: Double, state: ExerciseUiState.Ready): Double =
    if (state.profile.units == UnitSystem.Imperial) meters / METERS_PER_MILE else meters / 1_000

private fun distanceUnit(state: ExerciseUiState.Ready): Int = if (state.profile.units == UnitSystem.Imperial) R.string.unit_mi else R.string.unit_km
