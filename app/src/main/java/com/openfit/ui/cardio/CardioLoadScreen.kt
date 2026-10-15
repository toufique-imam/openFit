package com.openfit.ui.cardio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openfit.R
import com.openfit.domain.ChartPeriod
import com.openfit.domain.FitnessGoal
import com.openfit.domain.TargetStatus
import com.openfit.domain.TrainingStatus
import com.openfit.domain.dayChartAxis
import com.openfit.domain.trainingBarPosition
import com.openfit.ui.detail.ChartLegend
import com.openfit.ui.detail.DayChart
import com.openfit.ui.detail.DetailFailed
import com.openfit.ui.detail.DetailList
import com.openfit.ui.detail.DetailLoading
import com.openfit.ui.detail.DayHeaderRow
import com.openfit.ui.detail.DetailScaffold
import com.openfit.ui.detail.GroupedCard
import com.openfit.ui.detail.HeaderValue
import com.openfit.ui.detail.InfoSheet
import com.openfit.ui.detail.LinkSection
import com.openfit.ui.detail.MenuAction
import com.openfit.ui.detail.PagePadding
import com.openfit.ui.detail.PeriodChart
import com.openfit.ui.detail.PeriodNavigator
import com.openfit.ui.detail.WorkoutYellow
import com.openfit.ui.detail.dayStartMinute
import com.openfit.ui.detail.epochMinute
import com.openfit.ui.detail.SectionGap
import com.openfit.ui.detail.SectionTitle
import com.openfit.ui.detail.TitleWithAction
import com.openfit.ui.detail.chartXLabels
import com.openfit.ui.detail.periodLabel
import com.openfit.ui.detail.periodRows
import com.openfit.ui.theme.OpenFitColors
import com.openfit.ui.theme.SectionHeaderStyle
import com.openfit.ui.today.ArcGauge
import com.openfit.ui.today.truncated
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val ACCENT = OpenFitColors.Mint.accent
private val TEAL = Color(0xFF008773)
private val TRAINING_COLORS = listOf(WorkoutYellow, ACCENT, ACCENT, TEAL, TEAL, WorkoutYellow)
private val CLOCK = DateTimeFormatter.ofPattern("h:mm a")
private val BREAKDOWN_TRACK = Color(0xFF292D2C)

@Composable
fun CardioLoadRoute(viewModel: CardioLoadViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CardioLoadScreen(state, onBack, viewModel::selectPeriod, viewModel::shift, viewModel::setGoal)
}

@Composable
fun CardioLoadScreen(
    state: CardioLoadUiState,
    onBack: () -> Unit,
    onPeriod: (ChartPeriod) -> Unit,
    onShift: (Long) -> Unit,
    onGoal: (FitnessGoal) -> Unit,
) {
    var choosingGoal by rememberSaveable { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf<Int?>(null) }
    val ready = state as? CardioLoadUiState.Ready

    DetailScaffold(
        title = stringResource(R.string.cardio_load),
        accent = ACCENT,
        period = ready?.period ?: ChartPeriod.Day,
        onPeriod = onPeriod,
        onBack = onBack,
        menu = listOf(MenuAction(stringResource(R.string.cardio_activity_settings)) { choosingGoal = true }),
    ) {
        when (state) {
            CardioLoadUiState.Loading -> DetailLoading(ACCENT)
            is CardioLoadUiState.Failed -> DetailFailed(state.message)
            is CardioLoadUiState.Ready -> DetailList(state.period, state.anchor) {
                item { PeriodNavigator(periodLabel(state.period, state.anchor, state.today), state.canGoForward, onShift) }
                when (state.period) {
                    ChartPeriod.Day -> dayContent(state, onAdjustTarget = { choosingGoal = true }, onLearnMore = { sheet = it })
                    else -> periodContent(state)
                }
            }
        }
    }

    if (choosingGoal && ready != null) {
        FitnessTargetDialog(
            selected = ready.profile.fitnessGoal,
            onDismiss = { choosingGoal = false },
            onSelect = {
                choosingGoal = false
                onGoal(it)
            },
        )
    }
    sheet?.let { InfoSheet(it) { sheet = null } }
}

private fun LazyListScope.dayContent(state: CardioLoadUiState.Ready, onAdjustTarget: () -> Unit, onLearnMore: (Int) -> Unit) {
    val day = state.day ?: return
    val isToday = state.anchor == state.today
    item { DayHeader(day, isToday) }
    item {
        val detail = day.detail
        val start = dayStartMinute(state.anchor)
        Column(Modifier.padding(top = 32.dp)) {
            DayChart(
                cumulative = detail?.cumulative().orEmpty(),
                dayStartMinute = start,
                endMinute = if (isToday) epochMinute(Instant.now()) else start + 24 * 60,
                range = day.range,
                workouts = detail?.workouts.orEmpty(),
                axis = dayChartAxis(day.range, day.load ?: 0.0),
            )
            ChartLegend(
                buildList {
                    if (day.range != null) add(ACCENT to stringResource(R.string.cardio_legend_target_range))
                    if (detail?.workouts?.isNotEmpty() == true) add(WorkoutYellow to stringResource(R.string.cardio_legend_logged_workouts))
                },
            )
        }
    }
    val workouts = day.detail?.workouts.orEmpty().sortedByDescending { it.start }
    if (workouts.isNotEmpty()) {
        item { SectionTitle(stringResource(R.string.cardio_workouts), Modifier.padding(top = SectionGap, bottom = 16.dp)) }
        itemsIndexed(workouts) { index, workout ->
            GroupedCard(index, workouts.size, minHeight = 92.dp) {
                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        workout.start.atZone(ZoneId.systemDefault()).toLocalTime().format(CLOCK).lowercase(Locale.getDefault()),
                        style = MaterialTheme.typography.titleSmall,
                        color = OpenFitColors.OnSurface,
                    )
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Text(workout.name, style = MaterialTheme.typography.headlineMedium, color = OpenFitColors.OnSurface, maxLines = 1)
                        Text(
                            stringResource(R.string.cardio_workout_load, truncated(workout.load)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = OpenFitColors.OnSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    // Fitbit hides the breakdown when every bit of load came from logged workouts.
    day.detail?.takeIf { it.total > 0 && (it.workoutShare == 0.0 || it.workoutShare < 1.0) }?.let { detail ->
        item {
            Column(Modifier.padding(start = PagePadding, end = PagePadding, top = SectionGap)) {
                Text(stringResource(R.string.cardio_breakdown_title), style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface)
                Text(
                    stringResource(R.string.cardio_breakdown_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenFitColors.OnSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                val logged = (detail.workoutShare * 100).roundToInt()
                ShareBar(stringResource(R.string.cardio_breakdown_logged, logged), logged / 100f, Modifier.padding(top = 24.dp))
                ShareBar(stringResource(R.string.cardio_breakdown_general, 100 - logged), (100 - logged) / 100f, Modifier.padding(top = 16.dp))
            }
        }
    }
    item { TrainingSection(state.training, onLearnMore = { onLearnMore(R.string.cardio_training_explainer) }) }
    item {
        LinkSection(
            title = stringResource(R.string.cardio_fitness_target_title),
            action = stringResource(R.string.cardio_adjust_target),
            body = stringResource(
                if (state.profile.fitnessGoal == FitnessGoal.Improve) R.string.cardio_fitness_target_improving else R.string.cardio_fitness_target_maintaining,
            ),
            accent = ACCENT,
            onAction = onAdjustTarget,
        )
    }
    item {
        LinkSection(
            title = stringResource(R.string.cardio_about_title),
            action = stringResource(R.string.learn_more),
            body = stringResource(R.string.cardio_about_body),
            accent = ACCENT,
            onAction = { onLearnMore(R.string.cardio_about_sheet) },
        )
    }
}

@Composable
private fun DayHeader(day: CardioDayView, isToday: Boolean) {
    val range = day.range
    val load = day.load ?: 0.0
    val low = range?.let { truncated(it.low) }
    val high = range?.let { truncated(it.high) }
    val status = when {
        range == null -> stringResource(R.string.no_target)
        day.status == TargetStatus.InTarget -> stringResource(R.string.cardio_in_target)
        day.status == TargetStatus.Above -> stringResource(R.string.cardio_above_target)
        isToday -> stringResource(R.string.target_range, low!!, high!!)
        else -> stringResource(R.string.cardio_below_target)
    }
    val description = when {
        range == null -> stringResource(R.string.cardio_day_no_target)
        day.status == TargetStatus.InTarget ->
            stringResource(if (isToday) R.string.cardio_day_in_target_today else R.string.cardio_day_in_target_past, low!!, high!!)
        day.status == TargetStatus.Above ->
            stringResource(if (isToday) R.string.cardio_day_above_today else R.string.cardio_day_above_past, low!!, high!!)
        !isToday -> stringResource(R.string.cardio_day_below_past, low!!, high!!)
        load > 0 -> stringResource(R.string.cardio_day_below_today)
        else -> stringResource(R.string.cardio_day_below_today_zero)
    }
    DayHeaderRow(truncated(load), status, description) {
        ArcGauge(
            Icons.Outlined.MonitorHeart,
            OpenFitColors.Mint,
            range?.let { (load / it.scale).toFloat() } ?: 0f,
            range = range?.let { (it.low / it.scale).toFloat()..(it.high / it.scale).toFloat() },
            background = OpenFitColors.Background,
        )
    }
}

@Composable
private fun ShareBar(label: String, fraction: Float, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface)
        // A 4dp track with a 10dp pill on top, as Fitbit draws the breakdown.
        Canvas(Modifier.fillMaxWidth().padding(top = 10.dp).height(10.dp)) {
            val track = 4.dp.toPx()
            drawRoundRect(BREAKDOWN_TRACK, Offset(0f, (size.height - track) / 2), Size(size.width, track), CornerRadius(track / 2))
            if (fraction > 0f) drawRoundRect(ACCENT, size = Size(maxOf(size.width * fraction, size.height), size.height), cornerRadius = CornerRadius(size.height / 2))
        }
    }
}

@Composable
private fun TrainingSection(training: TrainingView, onLearnMore: () -> Unit) {
    Column(Modifier.padding(start = PagePadding, end = PagePadding, top = SectionGap)) {
        TitleWithAction(stringResource(R.string.cardio_training_title), stringResource(R.string.learn_more), ACCENT, onLearnMore)
        val status = training.status
        Text(
            stringResource(status?.let(::trainingDescription) ?: R.string.cardio_training_uncalibrated),
            style = MaterialTheme.typography.bodyMedium,
            color = OpenFitColors.OnSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        val ratio = training.ratio
        if (status != null && ratio != null) {
            TrainingBar(trainingBarPosition(ratio).toFloat(), Modifier.padding(top = 24.dp))
            Text(
                stringResource(trainingLabel(status)),
                style = MaterialTheme.typography.bodyMedium,
                color = OpenFitColors.OnSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun TrainingBar(position: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(14.dp)) {
        val gap = 1.dp.toPx()
        val barHeight = 10.dp.toPx()
        val top = (size.height - barHeight) / 2
        val segment = (size.width - gap * (TRAINING_COLORS.size - 1)) / TRAINING_COLORS.size
        val end = CornerRadius(barHeight / 2)
        TRAINING_COLORS.forEachIndexed { index, color ->
            val left = index * (segment + gap)
            val first = if (index == 0) end else CornerRadius.Zero
            val last = if (index == TRAINING_COLORS.lastIndex) end else CornerRadius.Zero
            val path = Path().apply {
                addRoundRect(RoundRect(left, top, left + segment, top + barHeight, first, last, last, first))
            }
            drawPath(path, color)
        }
        val center = Offset(position.coerceIn(0f, 1f) * size.width, size.height / 2)
        drawCircle(OpenFitColors.Background, radius = 6.dp.toPx(), center = center)
        drawCircle(OpenFitColors.OnSurface, radius = 4.dp.toPx(), center = center)
    }
}

private fun LazyListScope.periodContent(state: CardioLoadUiState.Ready) {
    val chart = state.chart ?: return
    item { PeriodHeader(state, chart) }
    item {
        Column(Modifier.padding(top = 32.dp)) {
            PeriodChart(chart.points, chart.axis, chartXLabels(state.period, state.anchor))
            ChartLegend(listOf(ACCENT to stringResource(R.string.cardio_legend_target_range)))
        }
    }
    periodRows(state.period, state.anchor, state.today, chart.rows) { truncated(it.load) }
}

@Composable
private fun PeriodHeader(state: CardioLoadUiState.Ready, chart: CardioPeriodView) {
    Column(Modifier.fillMaxWidth().padding(start = PagePadding, end = PagePadding, top = 24.dp)) {
        val average = chart.averagePerDay
        val monthStatus = chart.monthStatus
        if (state.period == ChartPeriod.Month && monthStatus != null && average != null) {
            Text(stringResource(monthTitle(monthStatus, state.isCurrent)), style = MaterialTheme.typography.headlineMedium, color = OpenFitColors.OnSurface)
            Text(
                stringResource(monthBody(monthStatus, state.isCurrent), truncated(average)),
                style = MaterialTheme.typography.bodyMedium,
                color = OpenFitColors.OnSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            return@Column
        }
        HeaderValue(average?.let(::truncated) ?: "0", stringResource(R.string.cardio_per_day_avg))
        val loads = chart.points.mapNotNull { it.load }
        val description = when {
            state.period == ChartPeriod.Year -> chart.monthlyAverages?.let { stringResource(R.string.cardio_year_range, truncated(it.start), truncated(it.endInclusive)) }
                ?: stringResource(R.string.cardio_year_empty)
            loads.size < 2 -> stringResource(if (state.period == ChartPeriod.Week) R.string.cardio_week_empty else R.string.cardio_month_empty)
            chart.points.any { it.range != null } -> pluralStringResource(R.plurals.cardio_week_in_target, chart.daysInTarget, chart.daysInTarget)
            else -> stringResource(R.string.cardio_week_range, truncated(loads.min()), truncated(loads.max()))
        }
        Text(description, style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
        if (state.backfilling) {
            Text(stringResource(R.string.cardio_backfilling), style = MaterialTheme.typography.bodySmall, color = OpenFitColors.OnSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

private fun trainingDescription(status: TrainingStatus): Int = when (status) {
    TrainingStatus.Undertraining -> R.string.cardio_training_undertraining
    TrainingStatus.RiskOfUndertraining -> R.string.cardio_training_risk_under
    TrainingStatus.Maintaining -> R.string.cardio_training_maintaining
    TrainingStatus.Improving -> R.string.cardio_training_improving
    TrainingStatus.RiskOfOvertraining -> R.string.cardio_training_risk_over
    TrainingStatus.Overtraining -> R.string.cardio_training_overtraining
}

private fun trainingLabel(status: TrainingStatus): Int = when (status) {
    TrainingStatus.Undertraining -> R.string.cardio_training_undertraining_label
    TrainingStatus.RiskOfUndertraining -> R.string.cardio_training_risk_under_label
    TrainingStatus.Maintaining -> R.string.cardio_training_maintaining_label
    TrainingStatus.Improving -> R.string.cardio_training_improving_label
    TrainingStatus.RiskOfOvertraining -> R.string.cardio_training_risk_over_label
    TrainingStatus.Overtraining -> R.string.cardio_training_overtraining_label
}

private fun monthTitle(status: TrainingStatus, current: Boolean): Int = when (status) {
    TrainingStatus.Improving -> if (current) R.string.cardio_month_improving_current else R.string.cardio_month_improving_past
    TrainingStatus.Overtraining, TrainingStatus.RiskOfOvertraining ->
        if (current) R.string.cardio_month_overtraining_current else R.string.cardio_month_overtraining_past
    TrainingStatus.Undertraining, TrainingStatus.RiskOfUndertraining ->
        if (current) R.string.cardio_month_undertraining_current else R.string.cardio_month_undertraining_past
    TrainingStatus.Maintaining -> if (current) R.string.cardio_month_maintaining_current else R.string.cardio_month_maintaining_past
}

private fun monthBody(status: TrainingStatus, current: Boolean): Int = when (status) {
    TrainingStatus.Improving -> if (current) R.string.cardio_month_improving_current_body else R.string.cardio_month_improving_past_body
    TrainingStatus.Overtraining, TrainingStatus.RiskOfOvertraining ->
        if (current) R.string.cardio_month_overtraining_current_body else R.string.cardio_month_overtraining_past_body
    TrainingStatus.Undertraining, TrainingStatus.RiskOfUndertraining ->
        if (current) R.string.cardio_month_undertraining_current_body else R.string.cardio_month_undertraining_past_body
    TrainingStatus.Maintaining -> if (current) R.string.cardio_month_maintaining_current_body else R.string.cardio_month_maintaining_past_body
}

@Composable
fun FitnessTargetDialog(selected: FitnessGoal, onDismiss: () -> Unit, onSelect: (FitnessGoal) -> Unit) {
    var choice by rememberSaveable { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OpenFitColors.CardHigh,
        title = { Text(stringResource(R.string.fitness_target_question)) },
        text = {
            Column {
                Text(stringResource(R.string.fitness_target_body), style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                listOf(
                    Triple(FitnessGoal.Improve, R.string.fitness_target_improving_title, R.string.fitness_target_improving_description),
                    Triple(FitnessGoal.Maintain, R.string.fitness_target_maintaining_title, R.string.fitness_target_maintaining_description),
                ).forEach { (goal, title, description) ->
                    Row(Modifier.fillMaxWidth().clickable { choice = goal }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = choice == goal, onClick = { choice = goal }, colors = RadioButtonDefaults.colors(selectedColor = ACCENT))
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface)
                            Text(stringResource(description), style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSelect(choice) }) { Text(stringResource(R.string.save), color = ACCENT) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = ACCENT) } },
    )
}
