package com.openfit.ui.cardio

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openfit.R
import com.openfit.domain.CardioPeriod
import com.openfit.domain.FitnessGoal
import com.openfit.domain.PeriodRow
import com.openfit.domain.TargetStatus
import com.openfit.domain.TrainingStatus
import com.openfit.domain.dayChartAxis
import com.openfit.domain.periodDays
import com.openfit.domain.trainingBarPosition
import com.openfit.ui.theme.OpenFitColors
import com.openfit.ui.theme.SectionHeaderStyle
import com.openfit.ui.today.ArcGauge
import com.openfit.ui.today.weekRange
import com.openfit.ui.today.weekdayDayMonth
import com.openfit.ui.today.whole
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

private val PAGE_PADDING = 24.dp
private val CARD_MARGIN = 16.dp
private val SECTION_GAP = 40.dp
private val TEAL = Color(0xFF008773)
private val TRAINING_COLORS = listOf(WorkoutYellow, OpenFitColors.Mint.accent, OpenFitColors.Mint.accent, TEAL, TEAL, WorkoutYellow)
private val CLOCK = DateTimeFormatter.ofPattern("h:mm a")

@Composable
fun CardioLoadRoute(viewModel: CardioLoadViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CardioLoadScreen(state, onBack, viewModel::selectPeriod, viewModel::shift, viewModel::setGoal)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardioLoadScreen(
    state: CardioLoadUiState,
    onBack: () -> Unit,
    onPeriod: (CardioPeriod) -> Unit,
    onShift: (Long) -> Unit,
    onGoal: (FitnessGoal) -> Unit,
) {
    var choosingGoal by rememberSaveable { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf<Int?>(null) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = OpenFitColors.Background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cardio_load), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more_options)) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.cardio_activity_settings)) },
                            onClick = {
                                menuOpen = false
                                choosingGoal = true
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = OpenFitColors.Background,
                    titleContentColor = OpenFitColors.OnSurface,
                    navigationIconContentColor = OpenFitColors.OnSurface,
                    actionIconContentColor = OpenFitColors.OnSurface,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val period = (state as? CardioLoadUiState.Ready)?.period ?: CardioPeriod.Day
            PeriodTabs(period, onPeriod)
            when (state) {
                CardioLoadUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = OpenFitColors.Mint.accent)
                }
                is CardioLoadUiState.Failed -> Box(Modifier.fillMaxSize().padding(PAGE_PADDING), contentAlignment = Alignment.Center) {
                    Text(state.message, color = OpenFitColors.OnSurfaceVariant, textAlign = TextAlign.Center)
                }
                is CardioLoadUiState.Ready -> LazyColumn(
                    state = key(state.period, state.anchor) { rememberLazyListState() },
                    contentPadding = PaddingValues(bottom = 48.dp),
                ) {
                    item { PeriodNavigator(state, onShift) }
                    when (state.period) {
                        CardioPeriod.Day -> dayContent(state, onAdjustTarget = { choosingGoal = true }, onLearnMore = { sheet = it })
                        else -> periodContent(state)
                    }
                }
            }
        }
    }

    val ready = state as? CardioLoadUiState.Ready
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
    sheet?.let { body ->
        ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = OpenFitColors.CardHigh) {
            Text(
                stringResource(body),
                style = MaterialTheme.typography.bodyLarge,
                color = OpenFitColors.OnSurface,
                modifier = Modifier.padding(start = PAGE_PADDING, end = PAGE_PADDING, bottom = 40.dp),
            )
        }
    }
}

@Composable
private fun PeriodTabs(selected: CardioPeriod, onSelect: (CardioPeriod) -> Unit) {
    val tabs = listOf(
        CardioPeriod.Day to R.string.cardio_tab_day,
        CardioPeriod.Week to R.string.cardio_tab_week,
        CardioPeriod.Month to R.string.cardio_tab_month,
        CardioPeriod.Year to R.string.cardio_tab_year,
    )
    SecondaryTabRow(
        selectedTabIndex = selected.ordinal,
        containerColor = OpenFitColors.Background,
        contentColor = OpenFitColors.Mint.accent,
        indicator = {
            TabRowDefaults.SecondaryIndicator(
                Modifier.tabIndicatorOffset(selected.ordinal).clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)),
                height = 3.dp,
                color = OpenFitColors.Mint.accent,
            )
        },
        divider = {},
    ) {
        tabs.forEach { (period, label) ->
            Tab(
                selected = period == selected,
                onClick = { onSelect(period) },
                selectedContentColor = OpenFitColors.Mint.accent,
                unselectedContentColor = OpenFitColors.OnSurface,
                text = { Text(stringResource(label), style = MaterialTheme.typography.titleMedium) },
            )
        }
    }
}

@Composable
private fun PeriodNavigator(state: CardioLoadUiState.Ready, onShift: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onShift(-1) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.previous_day), tint = OpenFitColors.OnSurface)
        }
        Text(
            periodLabel(state),
            style = MaterialTheme.typography.titleMedium,
            color = OpenFitColors.OnSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onShift(1) }, enabled = state.canGoForward) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                stringResource(R.string.next_day),
                tint = if (state.canGoForward) OpenFitColors.OnSurface else OpenFitColors.OnSurface.copy(alpha = 0.38f),
            )
        }
    }
}

@Composable
private fun periodLabel(state: CardioLoadUiState.Ready): String {
    val days = periodDays(state.period, state.anchor)
    return when (state.period) {
        CardioPeriod.Day -> if (state.anchor == state.today) stringResource(R.string.today) else state.anchor.weekdayDayMonth()
        CardioPeriod.Week -> if (state.isCurrent) stringResource(R.string.this_week) else weekRange(days.first(), days.last())
        CardioPeriod.Month -> when {
            state.isCurrent -> stringResource(R.string.this_month)
            state.anchor.year == state.today.year -> state.anchor.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault())
            else -> "${state.anchor.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault())} ${state.anchor.year}"
        }
        CardioPeriod.Year -> if (state.isCurrent) stringResource(R.string.this_year) else state.anchor.year.toString()
    }
}

private fun LazyListScope.dayContent(state: CardioLoadUiState.Ready, onAdjustTarget: () -> Unit, onLearnMore: (Int) -> Unit) {
    val day = state.day ?: return
    val isToday = state.anchor == state.today
    item { DayHeader(day, isToday) }
    item {
        val detail = day.detail
        val start = dayStartMinute(state.anchor)
        val end = if (isToday) epochMinute(java.time.Instant.now()) else start + 24 * 60
        val axis = dayChartAxis(day.range, day.load ?: 0.0)
        Column(Modifier.padding(top = 32.dp)) {
            DayChart(
                cumulative = detail?.cumulative().orEmpty(),
                dayStartMinute = start,
                endMinute = end,
                range = day.range,
                workouts = detail?.workouts.orEmpty(),
                axis = axis,
            )
            ChartLegend(
                buildList {
                    if (day.range != null) add(OpenFitColors.Mint.accent to stringResource(R.string.cardio_legend_target_range))
                    if (detail?.workouts?.isNotEmpty() == true) add(WorkoutYellow to stringResource(R.string.cardio_legend_logged_workouts))
                },
            )
        }
    }
    val workouts = day.detail?.workouts.orEmpty().sortedByDescending { it.start }
    if (workouts.isNotEmpty()) {
        item { SectionTitle(R.string.cardio_workouts, Modifier.padding(top = SECTION_GAP, bottom = 16.dp)) }
        itemsIndexed(workouts) { index, workout ->
            GroupedCard(index, workouts.size, height = 92.dp) {
                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        workout.start.atZone(ZoneId.systemDefault()).toLocalTime().format(CLOCK).lowercase(Locale.getDefault()),
                        style = MaterialTheme.typography.bodyLarge,
                        color = OpenFitColors.OnSurface,
                    )
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Text(workout.name, style = MaterialTheme.typography.headlineMedium, color = OpenFitColors.OnSurface, maxLines = 1)
                        Text(
                            stringResource(R.string.cardio_workout_load, whole(workout.load)),
                            style = MaterialTheme.typography.bodyLarge,
                            color = OpenFitColors.OnSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    day.detail?.takeIf { it.total > 0 }?.let { detail ->
        item {
            Column(Modifier.padding(start = PAGE_PADDING, end = PAGE_PADDING, top = SECTION_GAP)) {
                Text(stringResource(R.string.cardio_breakdown_title), style = SectionHeaderStyle, color = OpenFitColors.OnSurface)
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
            title = R.string.cardio_fitness_target_title,
            action = R.string.cardio_adjust_target,
            body = if (state.profile.fitnessGoal == FitnessGoal.Improve) R.string.cardio_fitness_target_improving else R.string.cardio_fitness_target_maintaining,
            onAction = onAdjustTarget,
        )
    }
    item { LinkSection(R.string.cardio_about_title, R.string.learn_more, R.string.cardio_about_body, onAction = { onLearnMore(R.string.cardio_about_sheet) }) }
}

@Composable
private fun DayHeader(day: CardioDayView, isToday: Boolean) {
    val range = day.range
    val load = day.load ?: 0.0
    val low = range?.let { whole(it.low) }
    val high = range?.let { whole(it.high) }
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
    Row(Modifier.fillMaxWidth().padding(start = PAGE_PADDING, end = 21.dp, top = 13.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    whole(load),
                    style = MaterialTheme.typography.displayMedium,
                    color = OpenFitColors.OnSurface,
                    modifier = Modifier.alignByBaseline(),
                )
                Spacer(Modifier.width(6.dp))
                Text(status, style = MaterialTheme.typography.bodyLarge, color = OpenFitColors.OnSurface, modifier = Modifier.alignByBaseline())
            }
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = OpenFitColors.OnSurfaceVariant,
            )
        }
        ArcGauge(
            Icons.Outlined.MonitorHeart,
            OpenFitColors.Mint,
            range?.let { (load / it.scale).toFloat() } ?: 0f,
            range = range?.let { (it.low / it.scale).toFloat()..(it.high / it.scale).toFloat() },
            background = OpenFitColors.Background,
            modifier = Modifier.padding(top = 14.dp),
        )
    }
}

@Composable
private fun ShareBar(label: String, fraction: Float, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface)
        Canvas(Modifier.fillMaxWidth().padding(top = 10.dp).height(8.dp)) {
            val radius = CornerRadius(size.height / 2)
            drawRoundRect(OpenFitColors.Mint.track, cornerRadius = radius)
            if (fraction > 0f) drawRoundRect(OpenFitColors.Mint.accent, size = Size(size.width * fraction.coerceIn(0.03f, 1f), size.height), cornerRadius = radius)
        }
    }
}

@Composable
private fun TrainingSection(training: TrainingView, onLearnMore: () -> Unit) {
    Column(Modifier.padding(start = PAGE_PADDING, end = PAGE_PADDING, top = SECTION_GAP)) {
        TitleWithAction(R.string.cardio_training_title, R.string.learn_more, onLearnMore)
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
        val gap = 2.dp.toPx()
        val barHeight = 10.dp.toPx()
        val top = (size.height - barHeight) / 2
        val segment = (size.width - gap * (TRAINING_COLORS.size - 1)) / TRAINING_COLORS.size
        TRAINING_COLORS.forEachIndexed { index, color ->
            val left = index * (segment + gap)
            val first = index == 0
            val last = index == TRAINING_COLORS.lastIndex
            val path = androidx.compose.ui.graphics.Path().apply {
                addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        left = left,
                        top = top,
                        right = left + segment,
                        bottom = top + barHeight,
                        topLeftCornerRadius = CornerRadius(if (first) barHeight / 2 else 0f),
                        bottomLeftCornerRadius = CornerRadius(if (first) barHeight / 2 else 0f),
                        topRightCornerRadius = CornerRadius(if (last) barHeight / 2 else 0f),
                        bottomRightCornerRadius = CornerRadius(if (last) barHeight / 2 else 0f),
                    ),
                )
            }
            drawPath(path, color)
        }
        val center = Offset(position.coerceIn(0f, 1f) * size.width, size.height / 2)
        drawCircle(OpenFitColors.Background, radius = 7.dp.toPx(), center = center)
        drawCircle(OpenFitColors.OnSurface, radius = 4.dp.toPx(), center = center)
    }
}

@Composable
private fun LinkSection(@StringRes title: Int, @StringRes action: Int, @StringRes body: Int, onAction: () -> Unit) {
    Column(Modifier.padding(start = PAGE_PADDING, end = PAGE_PADDING, top = SECTION_GAP)) {
        TitleWithAction(title, action, onAction)
        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun TitleWithAction(@StringRes title: Int, @StringRes action: Int, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(title), style = SectionHeaderStyle, color = OpenFitColors.OnSurface, modifier = Modifier.weight(1f))
        Text(
            stringResource(action),
            style = MaterialTheme.typography.titleMedium,
            color = OpenFitColors.Mint.accent,
            modifier = Modifier.padding(start = 16.dp).clickable(onClick = onAction).padding(vertical = 8.dp),
        )
    }
}

@Composable
private fun SectionTitle(@StringRes title: Int, modifier: Modifier = Modifier) {
    Text(stringResource(title), style = SectionHeaderStyle, color = OpenFitColors.OnSurface, modifier = modifier.padding(horizontal = PAGE_PADDING))
}

/** Fitbit's grouped list: large corners at the ends of the group, small ones between rows. */
@Composable
private fun GroupedCard(index: Int, count: Int, height: Dp, content: @Composable () -> Unit) {
    val outer = 20.dp
    val inner = 4.dp
    val shape = RoundedCornerShape(
        topStart = if (index == 0) outer else inner,
        topEnd = if (index == 0) outer else inner,
        bottomStart = if (index == count - 1) outer else inner,
        bottomEnd = if (index == count - 1) outer else inner,
    )
    Box(
        Modifier
            .padding(horizontal = CARD_MARGIN)
            .padding(bottom = if (index == count - 1) 0.dp else 4.dp)
            .fillMaxWidth()
            .heightIn(min = height)
            .clip(shape)
            .background(OpenFitColors.Card),
    ) { content() }
}

private fun LazyListScope.periodContent(state: CardioLoadUiState.Ready) {
    val chart = state.chart ?: return
    item { PeriodHeader(state, chart) }
    item {
        Column(Modifier.padding(top = 32.dp)) {
            PeriodChart(chart.points, chart.axis, xLabels(state))
            ChartLegend(listOf(OpenFitColors.Mint.accent to stringResource(R.string.cardio_legend_target_range)))
        }
    }
    if (chart.rows.isNotEmpty()) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = PAGE_PADDING, end = PAGE_PADDING, top = SECTION_GAP, bottom = 16.dp)) {
                Text(listTitle(state), style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface, modifier = Modifier.weight(1f))
                if (state.period != CardioPeriod.Week) {
                    Text(stringResource(R.string.daily_average), style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface)
                }
            }
        }
        itemsIndexed(chart.rows) { index, row ->
            GroupedCard(index, chart.rows.size, height = 68.dp) {
                Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(rowLabel(state, row), style = MaterialTheme.typography.titleSmall, color = OpenFitColors.OnSurface, modifier = Modifier.weight(1f))
                    Text(whole(row.load), style = MaterialTheme.typography.headlineMedium, color = OpenFitColors.OnSurface)
                }
            }
        }
    }
}

@Composable
private fun PeriodHeader(state: CardioLoadUiState.Ready, chart: CardioPeriodView) {
    Column(Modifier.fillMaxWidth().padding(start = PAGE_PADDING, end = PAGE_PADDING, top = 24.dp)) {
        val average = chart.averagePerDay
        val monthStatus = chart.monthStatus
        if (state.period == CardioPeriod.Month && monthStatus != null && average != null) {
            Text(stringResource(monthTitle(monthStatus, state.isCurrent)), style = MaterialTheme.typography.headlineMedium, color = OpenFitColors.OnSurface)
            Text(
                stringResource(monthBody(monthStatus, state.isCurrent), whole(average)),
                style = MaterialTheme.typography.bodyMedium,
                color = OpenFitColors.OnSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            return@Column
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(average?.let(::whole) ?: "0", style = MaterialTheme.typography.displayMedium, color = OpenFitColors.OnSurface, modifier = Modifier.alignByBaseline())
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.cardio_per_day_avg), style = MaterialTheme.typography.bodyLarge, color = OpenFitColors.OnSurface, modifier = Modifier.alignByBaseline())
        }
        val loads = chart.points.mapNotNull { it.load }
        val description = when {
            state.period == CardioPeriod.Year -> chart.monthlyAverages?.let { stringResource(R.string.cardio_year_range, whole(it.start), whole(it.endInclusive)) }
                ?: stringResource(R.string.cardio_year_empty)
            loads.size < 2 -> stringResource(if (state.period == CardioPeriod.Week) R.string.cardio_week_empty else R.string.cardio_month_empty)
            chart.points.any { it.range != null } -> pluralStringResource(R.plurals.cardio_week_in_target, chart.daysInTarget, chart.daysInTarget)
            else -> stringResource(R.string.cardio_week_range, whole(loads.min()), whole(loads.max()))
        }
        Text(description, style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
        if (state.backfilling) {
            Text(stringResource(R.string.cardio_backfilling), style = MaterialTheme.typography.bodySmall, color = OpenFitColors.OnSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

private fun xLabels(state: CardioLoadUiState.Ready): List<Pair<Int, String>> = when (state.period) {
    CardioPeriod.Week -> periodDays(CardioPeriod.Week, state.anchor).mapIndexed { index, day ->
        index to day.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault())
    }
    CardioPeriod.Month -> {
        val length = YearMonth.from(state.anchor).lengthOfMonth()
        (listOf(1) + (5..length step 5)).map { (it - 1) to it.toString() }
    }
    CardioPeriod.Year -> (1..12).map { month ->
        (month - 1) to java.time.Month.of(month).getDisplayName(TextStyle.NARROW, Locale.getDefault())
    }
    CardioPeriod.Day -> emptyList()
}

@Composable
private fun listTitle(state: CardioLoadUiState.Ready): String {
    val days = periodDays(state.period, state.anchor)
    return when (state.period) {
        CardioPeriod.Week -> weekRange(days.first(), days.last())
        CardioPeriod.Month -> state.anchor.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault())
        else -> state.anchor.year.toString()
    }
}

@Composable
private fun rowLabel(state: CardioLoadUiState.Ready, row: PeriodRow): String = when (state.period) {
    CardioPeriod.Week -> row.start.weekdayDayMonth()
    CardioPeriod.Month -> weekRange(row.start, row.end)
    else -> if (YearMonth.from(row.start) == YearMonth.from(state.today)) {
        stringResource(R.string.this_month)
    } else {
        row.start.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault())
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
                        RadioButton(
                            selected = choice == goal,
                            onClick = { choice = goal },
                            colors = RadioButtonDefaults.colors(selectedColor = OpenFitColors.Mint.accent),
                        )
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface)
                            Text(stringResource(description), style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSelect(choice) }) { Text(stringResource(R.string.save), color = OpenFitColors.Mint.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = OpenFitColors.Mint.accent) } },
    )
}
