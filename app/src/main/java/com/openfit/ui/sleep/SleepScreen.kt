package com.openfit.ui.sleep

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openfit.R
import com.openfit.domain.ChartPeriod
import com.openfit.domain.PeriodRow
import com.openfit.domain.SleepNight
import com.openfit.domain.SleepRating
import com.openfit.domain.SleepStage
import com.openfit.domain.TYPICAL_STAGE_PERCENT
import com.openfit.domain.averagePerDay
import com.openfit.domain.periodDays
import com.openfit.domain.sleepRating
import com.openfit.domain.weekStart
import com.openfit.ui.detail.AxisLabel
import com.openfit.ui.detail.BarChart
import com.openfit.ui.detail.DetailFailed
import com.openfit.ui.detail.DetailList
import com.openfit.ui.detail.DetailLoading
import com.openfit.ui.detail.DetailScaffold
import com.openfit.ui.detail.GroupedCard
import com.openfit.ui.detail.HeaderValue
import com.openfit.ui.detail.InfoSheet
import com.openfit.ui.detail.LinkSection
import com.openfit.ui.detail.PagePadding
import com.openfit.ui.detail.PeriodNavigator
import com.openfit.ui.detail.SectionGap
import com.openfit.ui.detail.TitleWithAction
import com.openfit.ui.detail.canGoForward
import com.openfit.ui.detail.chartXLabels
import com.openfit.ui.detail.isCurrentPeriod
import com.openfit.ui.detail.periodLabel
import com.openfit.ui.detail.MenuAction
import com.openfit.ui.detail.SetGoalLink
import com.openfit.ui.goals.Goal
import com.openfit.ui.goals.GoalEditor
import com.openfit.ui.theme.OpenFitColors
import com.openfit.ui.today.ArcGauge
import com.openfit.ui.today.IconBadge
import com.openfit.ui.today.duration
import com.openfit.ui.today.valueText
import com.openfit.ui.today.weekRange
import com.openfit.ui.today.weekdayDayMonth
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

private val ACCENT = OpenFitColors.Lavender
private val TRACK = Color(0xFF292D2C)
private val STAGE_COLORS = mapOf(
    SleepStage.Awake to Color(0xFFFED59D),
    SleepStage.Rem to Color(0xFFFEF7FF),
    SleepStage.Light to Color(0xFFBC9FE2),
    SleepStage.Deep to Color(0xFF856BAA),
)
private val STAGE_ORDER = listOf(SleepStage.Awake, SleepStage.Rem, SleepStage.Light, SleepStage.Deep)
private val CLOCK = DateTimeFormatter.ofPattern("h:mm a")
private val CLOCK_24H = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun SleepRoute(viewModel: SleepViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf<Int?>(null) }
    var editingGoal by rememberSaveable { mutableStateOf<Goal?>(null) }
    val setGoal: (Goal) -> Unit = { editingGoal = it }
    val ready = state as? SleepUiState.Ready
    DetailScaffold(
        title = stringResource(R.string.sleep_title),
        accent = ACCENT.accent,
        period = viewModel.period,
        onPeriod = viewModel::selectPeriod,
        onBack = onBack,
        menu = if (ready == null) {
            emptyList()
        } else {
            listOf(Goal.SleepDuration, Goal.SleepSchedule).map { goal -> MenuAction(stringResource(goal.title)) { setGoal(goal) } }
        },
    ) {
        when (val current = state) {
            SleepUiState.Loading -> DetailLoading(ACCENT.accent)
            is SleepUiState.Failed -> DetailFailed(current.message)
            is SleepUiState.Ready -> DetailList(current.period, current.anchor) {
                item {
                    PeriodNavigator(
                        periodLabel(current.period, current.anchor, current.today),
                        canGoForward(current.period, current.anchor, current.today),
                        viewModel::shift,
                    )
                }
                if (current.period == ChartPeriod.Day) {
                    dayContent(current, viewModel::selectStageMode, onLearnMore = { sheet = it }, onSetGoal = setGoal)
                } else {
                    periodContent(current, viewModel::selectChart, setGoal)
                }
            }
        }
    }
    sheet?.let { InfoSheet(it) { sheet = null } }
    val goal = editingGoal
    if (goal != null && ready != null) {
        GoalEditor(
            goal = goal,
            profile = ready.profile,
            onSave = {
                editingGoal = null
                viewModel.saveProfile(it)
            },
            onDismiss = { editingGoal = null },
        )
    }
}

private fun LazyListScope.dayContent(state: SleepUiState.Ready, onMode: (StageMode) -> Unit, onLearnMore: (Int) -> Unit, onSetGoal: (Goal) -> Unit) {
    val night = state.night
    val goal = state.profile.sleepGoalMinutes
    item {
        Row(Modifier.fillMaxWidth().padding(start = PagePadding, end = 21.dp, top = 13.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                if (night == null) {
                    HeaderValue("–", "")
                    Text(stringResource(R.string.sleep_no_night), style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant)
                } else {
                    HeaderValue(
                        valueText { duration(Duration.ofMinutes(night.totalAsleepMinutes.toLong()), false) },
                        goal?.let { stringResource(R.string.sleep_of_goal, goalLabel(it)) }.orEmpty(),
                    )
                }
                if (goal == null) SetGoalLink(ACCENT.accent) { onSetGoal(Goal.SleepDuration) }
            }
            Column(Modifier.padding(top = 14.dp)) {
                if (night == null || goal == null) {
                    IconBadge(Icons.Outlined.Bedtime, ACCENT)
                } else {
                    ArcGauge(Icons.Outlined.Bedtime, ACCENT, night.totalAsleepMinutes / goal.toFloat(), background = OpenFitColors.Background)
                }
            }
        }
    }
    if (night == null) return
    state.scores[state.anchor]?.let { score ->
        item {
            GroupedCard(0, 1, minHeight = 92.dp, modifier = Modifier.padding(top = 24.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.sleep_score_title), style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface, modifier = Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(score.toString(), style = MaterialTheme.typography.headlineMedium, color = OpenFitColors.OnSurface)
                        Text(stringResource(ratingName(sleepRating(score))), style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant)
                    }
                }
            }
        }
    }
    item {
        Column(Modifier.padding(start = PagePadding, end = PagePadding, top = SectionGap)) {
            TitleWithAction(stringResource(R.string.sleep_timeline), stringResource(R.string.learn_more), OpenFitColors.Mint.accent) {
                onLearnMore(R.string.sleep_timeline_sheet)
            }
            Text(timeRange(night), style = MaterialTheme.typography.headlineMedium, color = OpenFitColors.OnSurface, modifier = Modifier.padding(top = 8.dp))
            val schedule = state.schedule
            if (schedule == null) {
                SetGoalLink(ACCENT.accent, stringResource(R.string.sleep_set_schedule_link)) { onSetGoal(Goal.SleepSchedule) }
            } else {
                Text(
                    stringResource(R.string.sleep_schedule_current, scheduleTime(schedule.startMinute), scheduleTime(schedule.endMinute)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenFitColors.OnSurfaceVariant,
                )
            }
            when (state.stageMode) {
                StageMode.Stages -> Hypnogram(night, Modifier.padding(top = 12.dp))
                StageMode.Benchmark, StageMode.Average -> StageBars(night, state, Modifier.padding(top = 12.dp))
            }
        }
    }
    item {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ModeChip(stringResource(R.string.sleep_chip_stages), state.stageMode == StageMode.Stages) { onMode(StageMode.Stages) }
            ModeChip(stringResource(R.string.sleep_chip_benchmark), state.stageMode == StageMode.Benchmark) { onMode(StageMode.Benchmark) }
            ModeChip(stringResource(R.string.sleep_chip_average), state.stageMode == StageMode.Average) { onMode(StageMode.Average) }
        }
    }
    state.oxygenVariation?.let { spread ->
        item {
            Text(
                stringResource(R.string.sleep_more_title),
                style = MaterialTheme.typography.titleMedium,
                color = OpenFitColors.OnSurface,
                modifier = Modifier.padding(start = PagePadding, end = PagePadding, top = SectionGap, bottom = 16.dp),
            )
            GroupedCard(0, 1, minHeight = 76.dp) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Text(stringResource(R.string.sleep_oxygen_title), style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface)
                    Text(
                        stringResource(
                            when {
                                spread < LOW_OXYGEN_SPREAD -> R.string.sleep_oxygen_low
                                spread < HIGH_OXYGEN_SPREAD -> R.string.sleep_oxygen_moderate
                                else -> R.string.sleep_oxygen_high
                            },
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = OpenFitColors.OnSurfaceVariant,
                    )
                }
            }
        }
    }
    item {
        LinkSection(
            title = stringResource(R.string.sleep_tracking_title),
            action = stringResource(R.string.learn_more),
            body = stringResource(R.string.sleep_tracking_body),
            accent = OpenFitColors.Mint.accent,
            onAction = { onLearnMore(R.string.sleep_tracking_sheet) },
        )
    }
}

private const val LOW_OXYGEN_SPREAD = 1.5
private const val HIGH_OXYGEN_SPREAD = 3.0

/** Fitbit's sleep timeline: one row per stage with its segments on a track, joined by dashed lines at each change. */
@Composable
private fun Hypnogram(night: SleepNight, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.titleMedium.copy(color = OpenFitColors.OnSurface)
    val axisStyle = MaterialTheme.typography.bodySmall.copy(color = OpenFitColors.OnSurfaceVariant)
    val labels = STAGE_ORDER.associateWith { stage -> "${stringResource(stageName(stage))} • ${hoursMinutes(night.minutes(stage))}" }
    val zone = ZoneId.systemDefault()
    val middle = night.start.plusSeconds(Duration.between(night.start, night.end).seconds / 2)
    val times = listOf(night.start, middle, night.end).map { it.atZone(zone).toLocalTime().format(CLOCK).lowercase(Locale.getDefault()) }
    Canvas(modifier.fillMaxWidth().height(ROW_PITCH * 4 + 28.dp)) {
        val span = Duration.between(night.start, night.end).seconds.toFloat().coerceAtLeast(1f)
        fun x(instant: java.time.Instant): Float = Duration.between(night.start, instant).seconds / span * size.width
        fun trackY(row: Int): Float = (ROW_PITCH * row + LABEL_TO_TRACK).toPx()
        val bar = 10.dp.toPx()
        STAGE_ORDER.forEachIndexed { row, _ ->
            drawLine(TRACK, Offset(0f, trackY(row)), Offset(size.width, trackY(row)), strokeWidth = 2.dp.toPx())
        }
        val segments = night.segments.sortedBy { it.start }
        segments.zipWithNext().forEach { (from, to) ->
            if (from.stage == to.stage) return@forEach
            val xAt = x(to.start)
            val (top, bottom) = listOf(trackY(STAGE_ORDER.indexOf(from.stage)), trackY(STAGE_ORDER.indexOf(to.stage))).sorted()
            drawLine(
                STAGE_COLORS.getValue(to.stage).copy(alpha = 0.7f),
                Offset(xAt, top),
                Offset(xAt, bottom),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
            )
        }
        segments.forEach { segment ->
            val row = STAGE_ORDER.indexOf(segment.stage)
            val left = x(segment.start)
            val width = (x(segment.end) - left).coerceAtLeast(2.dp.toPx())
            drawRoundRect(
                STAGE_COLORS.getValue(segment.stage),
                Offset(left, trackY(row) - bar / 2),
                Size(width, bar),
                CornerRadius(minOf(bar, width) / 2),
            )
        }
        // Labels go on top with a background so the connectors never run through the text.
        STAGE_ORDER.forEachIndexed { row, stage ->
            val layout = measurer.measure(labels.getValue(stage), labelStyle)
            val top = (ROW_PITCH * row).toPx()
            drawRect(OpenFitColors.Background, Offset(0f, top), Size(layout.size.width + 6.dp.toPx(), layout.size.height.toFloat()))
            drawText(layout, topLeft = Offset(0f, top))
        }
        val axisY = (ROW_PITCH * 4 + 8.dp).toPx()
        times.forEachIndexed { index, label ->
            val layout = measurer.measure(label, axisStyle)
            val left = when (index) {
                0 -> 0f
                2 -> size.width - layout.size.width
                else -> size.width / 2 - layout.size.width / 2
            }
            drawText(layout, topLeft = Offset(left, axisY))
        }
    }
}

private val ROW_PITCH = 50.dp
private val LABEL_TO_TRACK = 32.dp

/** Benchmark and 30-day modes: each stage's share as a bar, with the typical range or the user's average marked. */
@Composable
private fun StageBars(night: SleepNight, state: SleepUiState.Ready, modifier: Modifier = Modifier) {
    Column(modifier) {
        STAGE_ORDER.forEach { stage ->
            val percent = night.percent(stage)
            Text(
                stringResource(R.string.sleep_stage_row_percent, stringResource(stageName(stage)), percent.roundToInt(), hoursMinutes(night.minutes(stage))),
                style = MaterialTheme.typography.titleMedium,
                color = OpenFitColors.OnSurface,
                modifier = Modifier.padding(top = 12.dp),
            )
            Canvas(Modifier.fillMaxWidth().padding(top = 8.dp).height(14.dp)) {
                val bar = 10.dp.toPx()
                val center = size.height / 2
                fun x(percent: Double): Float = (percent / 100).toFloat().coerceIn(0f, 1f) * size.width
                drawRoundRect(TRACK, Offset(0f, center - 2.dp.toPx()), Size(size.width, 4.dp.toPx()), CornerRadius(2.dp.toPx()))
                drawRoundRect(STAGE_COLORS.getValue(stage), Offset(0f, center - bar / 2), Size(x(percent).coerceAtLeast(bar), bar), CornerRadius(bar / 2))
                if (state.stageMode == StageMode.Benchmark) {
                    val typical = TYPICAL_STAGE_PERCENT.getValue(stage)
                    drawRoundRect(
                        STAGE_COLORS.getValue(stage),
                        Offset(x(typical.start), center - bar / 2 - 2.dp.toPx()),
                        Size(x(typical.endInclusive) - x(typical.start), bar + 4.dp.toPx()),
                        CornerRadius(bar / 2 + 2.dp.toPx()),
                        style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))),
                    )
                } else {
                    val average = Offset(x(state.averagePercent[stage] ?: 0.0), center)
                    drawCircle(OpenFitColors.Background, 6.dp.toPx(), average)
                    drawCircle(OpenFitColors.OnSurface, 4.dp.toPx(), average)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(width = 16.dp, height = 10.dp)) {
                if (state.stageMode == StageMode.Benchmark) {
                    drawRoundRect(
                        OpenFitColors.OnSurfaceVariant,
                        style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx()))),
                        cornerRadius = CornerRadius(3.dp.toPx()),
                    )
                } else {
                    drawCircle(OpenFitColors.OnSurface, 4.dp.toPx(), center)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(if (state.stageMode == StageMode.Benchmark) R.string.sleep_legend_typical else R.string.sleep_legend_average),
                style = MaterialTheme.typography.bodyMedium,
                color = OpenFitColors.OnSurfaceVariant,
            )
        }
    }
}

private fun LazyListScope.periodContent(state: SleepUiState.Ready, onChart: (SleepChart, SleepStage) -> Unit, onSetGoal: (Goal) -> Unit) {
    val current = isCurrentPeriod(state.period, state.anchor, state.today)
    val days = periodDays(state.period, state.anchor).filter { it <= state.today }
    val asleep = state.nights.mapValues { it.value.totalAsleepMinutes.toDouble() }
    val goal = state.profile.sleepGoalMinutes?.toDouble()
    item {
        Column(Modifier.fillMaxWidth().padding(start = PagePadding, end = PagePadding, top = 13.dp)) {
            when (state.chart) {
                SleepChart.Duration -> {
                    val average = averagePerDay(days, asleep)
                    HeaderValue(average?.let { valueText { duration(Duration.ofMinutes(it.roundToInt().toLong()), false) } } ?: AnnotatedString("–"), stringResource(R.string.sleep_per_day_avg))
                    if (goal == null) {
                        SetGoalLink(ACCENT.accent) { onSetGoal(Goal.SleepDuration) }
                    } else {
                        val goalDays = days.count { (asleep[it] ?: 0.0) >= goal }
                        Text(
                            pluralStringResource(if (current) R.plurals.sleep_goal_days_current else R.plurals.sleep_goal_days_past, goalDays, goalDays),
                            style = MaterialTheme.typography.bodyMedium,
                            color = OpenFitColors.OnSurfaceVariant,
                        )
                    }
                }
                SleepChart.Score -> {
                    val average = state.scores.values.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
                    HeaderValue(average?.toString() ?: "–", stringResource(R.string.sleep_score_avg_label))
                    if (average != null) {
                        val over = stringResource(
                            when (state.period) {
                                ChartPeriod.Week -> R.string.sleep_over_week
                                ChartPeriod.Month -> R.string.sleep_over_month
                                else -> R.string.sleep_over_year
                            },
                        )
                        Text(
                            stringResource(
                                when (sleepRating(average)) {
                                    SleepRating.Excellent -> R.string.sleep_avg_excellent
                                    SleepRating.Good -> R.string.sleep_avg_good
                                    SleepRating.Fair -> R.string.sleep_avg_fair
                                    SleepRating.Poor -> R.string.sleep_avg_poor
                                },
                                over,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = OpenFitColors.OnSurfaceVariant,
                        )
                    }
                }
                SleepChart.Schedule -> {
                    val schedule = state.schedule
                    HeaderValue(schedule?.let { s -> state.nights.values.count { onSchedule(it, s) }.toString() } ?: "–", stringResource(R.string.sleep_on_schedule))
                    if (schedule == null) SetGoalLink(ACCENT.accent, stringResource(R.string.sleep_set_schedule_link)) { onSetGoal(Goal.SleepSchedule) }
                    val nights = state.nights.values
                    if (nights.isNotEmpty()) {
                        val zone = ZoneId.systemDefault()
                        val start = nights.map { SleepViewModel.minutesAfterSixPm(it.start.atZone(zone).toLocalTime()) }.average().roundToInt()
                        val end = nights.map { SleepViewModel.minutesAfterSixPm(it.end.atZone(zone).toLocalTime()) }.average().roundToInt()
                        Text(
                            stringResource(R.string.sleep_schedule_average, scheduleTime(start, CLOCK_24H), scheduleTime(end, CLOCK_24H)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = OpenFitColors.OnSurfaceVariant,
                        )
                    }
                }
                SleepChart.Stage -> {
                    val average = averagePerDay(days, state.nights.mapValues { it.value.minutes(state.chartStage).toDouble() })
                    HeaderValue(
                        average?.let { valueText { duration(Duration.ofMinutes(it.roundToInt().toLong()), false) } } ?: AnnotatedString("–"),
                        stringResource(R.string.sleep_stage_avg, stringResource(stageName(state.chartStage)).lowercase(Locale.getDefault()).let { if (state.chartStage == SleepStage.Rem) "REM" else it }),
                    )
                }
            }
        }
    }
    item {
        Column(Modifier.padding(top = 24.dp)) {
            val labels = chartXLabels(state.period, state.anchor)
            when (state.chart) {
                SleepChart.Duration -> {
                    val values = bars(state, asleep)
                    val top = maxOf(600.0, (values.maxOf { it ?: 0.0 } / 60).let { kotlin.math.ceil(it) * 60 })
                    BarChart(
                        values = values,
                        color = ACCENT.accent,
                        axisMax = top,
                        axisLabels = if (goal == null) {
                            listOf(AxisLabel(0.0, "0m"), AxisLabel(top / 2, hoursLabel(top / 2)), AxisLabel(top, hoursLabel(top)))
                        } else {
                            listOf(
                                AxisLabel(0.0, "0m"),
                                AxisLabel(goal / 2, hoursLabel(goal / 2)),
                                AxisLabel(goal, hoursLabel(goal), highlight = true),
                                AxisLabel(top, hoursLabel(top)),
                            )
                        },
                        xLabels = labels,
                        goal = goal,
                    )
                }
                SleepChart.Score -> BarChart(
                    values = bars(state, state.scores.mapValues { it.value.toDouble() }),
                    color = ACCENT.accent,
                    axisMax = 90.0,
                    axisLabels = listOf(0, 45, 90).map { AxisLabel(it.toDouble(), it.toString()) },
                    xLabels = labels,
                )
                SleepChart.Schedule -> ScheduleChart(state, labels)
                SleepChart.Stage -> {
                    val values = bars(state, state.nights.mapValues { it.value.minutes(state.chartStage).toDouble() })
                    val top = maxOf(120.0, kotlin.math.ceil(values.maxOf { it ?: 0.0 } / 60) * 60)
                    BarChart(
                        values = values,
                        color = STAGE_COLORS.getValue(state.chartStage).takeIf { state.chartStage != SleepStage.Rem } ?: ACCENT.accent,
                        axisMax = top,
                        axisLabels = listOf(AxisLabel(0.0, "0m"), AxisLabel(top / 2, hoursLabel(top / 2)), AxisLabel(top, hoursLabel(top))),
                        xLabels = labels,
                    )
                }
            }
            ChartChips(state, onChart)
        }
    }
    sleepRows(state, current)
}

private fun bars(state: SleepUiState.Ready, values: Map<LocalDate, Double>): List<Double?> = when (state.period) {
    ChartPeriod.Year -> (1..12).map { month -> YearMonth.of(state.anchor.year, month).let { ym -> averagePerDay((1..ym.lengthOfMonth()).map(ym::atDay), values) } }
    else -> periodDays(state.period, state.anchor).map { values[it] }
}

@Composable
private fun ChartChips(state: SleepUiState.Ready, onChart: (SleepChart, SleepStage) -> Unit) {
    var choosingStage by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ModeChip(stringResource(R.string.sleep_chip_duration), state.chart == SleepChart.Duration) { onChart(SleepChart.Duration, state.chartStage) }
        ModeChip(stringResource(R.string.sleep_chip_score), state.chart == SleepChart.Score) { onChart(SleepChart.Score, state.chartStage) }
        if (state.period == ChartPeriod.Week || state.period == ChartPeriod.Month) {
            ModeChip(stringResource(R.string.sleep_chip_schedule), state.chart == SleepChart.Schedule) { onChart(SleepChart.Schedule, state.chartStage) }
        }
        Box {
            val stage = stringResource(stageName(state.chartStage)).let { if (state.chartStage == SleepStage.Rem) it else it }
            ModeChip(stringResource(R.string.sleep_chip_stage, stage), state.chart == SleepChart.Stage, dropdown = true) {
                if (state.chart == SleepChart.Stage) choosingStage = true else onChart(SleepChart.Stage, state.chartStage)
            }
            DropdownMenu(expanded = choosingStage, onDismissRequest = { choosingStage = false }) {
                listOf(SleepStage.Deep, SleepStage.Rem, SleepStage.Light, SleepStage.Awake).forEach { option ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sleep_chip_stage, stringResource(stageName(option)))) },
                        onClick = {
                            choosingStage = false
                            onChart(SleepChart.Stage, option)
                        },
                    )
                }
            }
        }
    }
}

/** Week and Month "Schedule": a bar from bedtime up to wake time for each night over the dotted usual schedule. */
@Composable
private fun ScheduleChart(state: SleepUiState.Ready, labels: List<Pair<Int, String>>) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.bodySmall.copy(color = OpenFitColors.OnSurfaceVariant)
    val zone = ZoneId.systemDefault()
    val days = periodDays(state.period, state.anchor)
    val spans = days.map { day ->
        state.nights[day]?.let {
            SleepViewModel.minutesAfterSixPm(it.start.atZone(zone).toLocalTime()) to SleepViewModel.minutesAfterSixPm(it.end.atZone(zone).toLocalTime())
        }
    }
    val schedule = state.schedule
    val all = spans.filterNotNull().flatMap { listOf(it.first, it.second) } + listOfNotNull(schedule?.startMinute, schedule?.endMinute)
    val low = ((all.minOrNull() ?: 300) - SCHEDULE_MARGIN).coerceAtLeast(0)
    val high = (all.maxOrNull() ?: 900) + SCHEDULE_MARGIN
    Column {
        Canvas(Modifier.fillMaxWidth().height(201.dp)) {
            val left = 24.dp.toPx()
            val width = 288.dp.toPx()
            val plot = 171.dp.toPx()
            val slot = width / days.size
            fun y(minute: Int): Float = plot - (minute - low).toFloat() / (high - low) * plot
            schedule?.let {
                listOf(it.startMinute, it.endMinute).forEach { minute ->
                    drawLine(
                        OpenFitColors.OnSurface,
                        Offset(left, y(minute)),
                        Offset(left + width, y(minute)),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.01f, 5.dp.toPx())),
                    )
                }
            }
            val bar = if (days.size > 12) 3.5.dp.toPx() else 16.dp.toPx()
            spans.forEachIndexed { index, span ->
                span ?: return@forEachIndexed
                val x = left + (index + 0.5f) * slot
                drawRoundRect(ACCENT.accent, Offset(x - bar / 2, y(span.second)), Size(bar, (y(span.first) - y(span.second)).coerceAtLeast(bar)), CornerRadius(bar / 2))
            }
            val labelX = (24 + 288 + 14).dp.toPx()
            listOfNotNull(high, schedule?.endMinute, low).forEach { minute ->
                val layout = measurer.measure(scheduleTimeText(minute), labelStyle)
                drawText(layout, topLeft = Offset(labelX, y(minute) - layout.size.height / 2f))
            }
            labels.forEach { (index, label) ->
                val layout = measurer.measure(label, labelStyle)
                drawText(layout, topLeft = Offset(left + (index + 0.5f) * slot - layout.size.width / 2f, plot + 11.dp.toPx()))
            }
        }
        com.openfit.ui.detail.ChartLegend(listOf(OpenFitColors.OnSurface to stringResource(R.string.cardio_legend_target_range)))
    }
}

private const val SCHEDULE_MARGIN = 45
private const val ON_SCHEDULE_MINUTES = 30

private fun onSchedule(night: SleepNight, schedule: SleepSchedule): Boolean {
    val zone = ZoneId.systemDefault()
    val start = SleepViewModel.minutesAfterSixPm(night.start.atZone(zone).toLocalTime())
    val end = SleepViewModel.minutesAfterSixPm(night.end.atZone(zone).toLocalTime())
    return kotlin.math.abs(start - schedule.startMinute) <= ON_SCHEDULE_MINUTES && kotlin.math.abs(end - schedule.endMinute) <= ON_SCHEDULE_MINUTES
}

private fun LazyListScope.sleepRows(state: SleepUiState.Ready, current: Boolean) {
    val goal = state.profile.sleepGoalMinutes
    val days = periodDays(state.period, state.anchor).filter { it <= state.today }
    val rows: List<Triple<PeriodRow, Int?, Int?>> = when (state.period) {
        ChartPeriod.Week -> days.sortedDescending().map { day ->
            Triple(PeriodRow(day, day, state.nights[day]?.totalAsleepMinutes?.toDouble() ?: -1.0), state.scores[day], null)
        }
        ChartPeriod.Month -> days.groupBy(::weekStart).map { (start, week) ->
            Triple(
                PeriodRow(start, start.plusDays(6), averagePerDay(week, state.nights.mapValues { it.value.totalAsleepMinutes.toDouble() }) ?: -1.0),
                week.mapNotNull { state.scores[it] }.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
                null,
            )
        }.reversed()
        else -> (12 downTo 1).mapNotNull { month ->
            val ym = YearMonth.of(state.anchor.year, month)
            val monthDays = (1..ym.lengthOfMonth()).map(ym::atDay)
            averagePerDay(monthDays, state.nights.mapValues { it.value.totalAsleepMinutes.toDouble() })?.let { average ->
                Triple(PeriodRow(ym.atDay(1), ym.atEndOfMonth(), average), monthDays.mapNotNull { state.scores[it] }.takeIf { it.isNotEmpty() }?.average()?.roundToInt(), null)
            }
        }
    }
    if (rows.isEmpty()) return
    item {
        Row(Modifier.fillMaxWidth().padding(start = PagePadding, end = PagePadding, top = SectionGap, bottom = 16.dp)) {
            Text(
                when {
                    current -> periodLabel(state.period, state.anchor, state.today)
                    state.period == ChartPeriod.Month -> state.anchor.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault())
                    state.period == ChartPeriod.Year -> state.anchor.year.toString()
                    else -> periodLabel(state.period, state.anchor, state.today)
                },
                style = MaterialTheme.typography.titleMedium,
                color = OpenFitColors.OnSurface,
                modifier = Modifier.weight(1f),
            )
            when (state.period) {
                ChartPeriod.Month -> Text(stringResource(R.string.sleep_weekly_average), style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface)
                ChartPeriod.Year -> Text(stringResource(R.string.sleep_monthly_average), style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface)
                else -> Unit
            }
        }
    }
    itemsIndexed(rows) { index, (row, score, _) ->
        GroupedCard(index, rows.size, minHeight = 68.dp) {
            Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(sleepRowLabel(state, row), style = MaterialTheme.typography.titleSmall, color = OpenFitColors.OnSurface, modifier = Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    if (row.load < 0) {
                        Text(stringResource(R.string.no_data), style = MaterialTheme.typography.headlineMedium, color = OpenFitColors.OnSurface)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (state.period == ChartPeriod.Week && goal != null && row.load >= goal) {
                                Icon(Icons.Filled.Check, contentDescription = null, tint = ACCENT.accent, modifier = Modifier.size(18.dp).padding(end = 4.dp))
                            }
                            Text(valueText { duration(Duration.ofMinutes(row.load.roundToInt().toLong()), true) }, style = MaterialTheme.typography.headlineMedium, color = OpenFitColors.OnSurface)
                        }
                        score?.let {
                            Text(
                                stringResource(R.string.sleep_row_score, it, stringResource(ratingName(sleepRating(it)))),
                                style = MaterialTheme.typography.bodyMedium,
                                color = OpenFitColors.OnSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun sleepRowLabel(state: SleepUiState.Ready, row: PeriodRow): String = when (state.period) {
    ChartPeriod.Week -> when (ChronoUnit.DAYS.between(row.start, state.today)) {
        0L -> stringResource(R.string.today)
        1L -> stringResource(R.string.yesterday)
        else -> row.start.weekdayDayMonth()
    }
    ChartPeriod.Month -> if (state.today in row.start..row.end) stringResource(R.string.this_week_label) else weekRange(row.start, row.end)
    else -> if (YearMonth.from(row.start) == YearMonth.from(state.today)) {
        stringResource(R.string.this_month)
    } else {
        row.start.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault())
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, dropdown: Boolean = false, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1) },
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null, tint = ACCENT.accent) }
        } else {
            null
        },
        trailingIcon = if (dropdown && selected) {
            { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = ACCENT.accent) }
        } else {
            null
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Color(0xFF2E2A36),
            selectedLabelColor = ACCENT.accent,
            labelColor = OpenFitColors.OnSurface,
        ),
    )
}

private fun stageName(stage: SleepStage): Int = when (stage) {
    SleepStage.Awake -> R.string.sleep_stage_awake
    SleepStage.Rem -> R.string.sleep_stage_rem
    SleepStage.Light -> R.string.sleep_stage_light
    SleepStage.Deep -> R.string.sleep_stage_deep
}

private fun ratingName(rating: SleepRating): Int = when (rating) {
    SleepRating.Excellent -> R.string.sleep_rating_excellent
    SleepRating.Good -> R.string.sleep_rating_good
    SleepRating.Fair -> R.string.sleep_rating_fair
    SleepRating.Poor -> R.string.sleep_rating_poor
}

/** "2:21 – 9:09 am": Fitbit drops the first am/pm when both ends share it. */
private fun timeRange(night: SleepNight): String {
    val zone = ZoneId.systemDefault()
    val start = night.start.atZone(zone).toLocalTime()
    val end = night.end.atZone(zone).toLocalTime()
    val startText = start.format(CLOCK).lowercase(Locale.getDefault())
    val endText = end.format(CLOCK).lowercase(Locale.getDefault())
    val sameHalf = (start.hour < 12) == (end.hour < 12)
    return if (sameHalf) "${startText.substringBeforeLast(' ')} – $endText" else "$startText – $endText"
}

private fun scheduleTime(minuteAfterSixPm: Int, format: DateTimeFormatter = CLOCK): String =
    LocalTime.of(18, 0).plusMinutes(minuteAfterSixPm.toLong()).format(format).lowercase(Locale.getDefault())

private fun scheduleTimeText(minuteAfterSixPm: Int): String = scheduleTime(minuteAfterSixPm)

/** "1 h 40 m", "54 min" as on the timeline rows. */
private fun hoursMinutes(minutes: Long): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0L -> "${minutes / 60} h"
    else -> "${minutes / 60} h ${minutes % 60} m"
}

private fun goalLabel(minutes: Int): String = if (minutes % 60 == 0) "${minutes / 60} h" else hoursMinutes(minutes.toLong())

private fun hoursLabel(minutes: Double): String = when {
    minutes <= 0 -> "0m"
    minutes % 60 == 0.0 -> "${(minutes / 60).toInt()}h"
    else -> "${(minutes / 60).toInt()}h ${(minutes % 60).toInt()}m"
}
