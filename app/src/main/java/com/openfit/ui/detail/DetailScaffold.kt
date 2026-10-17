package com.openfit.ui.detail

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.TabIndicatorScope
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openfit.R
import com.openfit.domain.ChartPeriod
import com.openfit.domain.PeriodRow
import com.openfit.domain.periodDays
import com.openfit.ui.theme.OpenFitColors
import com.openfit.ui.theme.SectionHeaderStyle
import com.openfit.ui.today.weekRange
import com.openfit.ui.today.weekdayDayMonth
import com.openfit.ui.today.whole
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** Fitbit's detail screens: 24dp text margins, 16dp card margins, 40dp between sections. */
val PagePadding = 24.dp
val CardMargin = 16.dp
val SectionGap = 40.dp

data class MenuAction(val label: String, val onClick: () -> Unit)

/** The shared frame of Fitbit's metric screens: back arrow, title, overflow menu, Day/Week/Month/Year tabs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScaffold(
    title: String,
    accent: Color,
    period: ChartPeriod,
    periods: List<ChartPeriod> = DEFAULT_PERIODS,
    onPeriod: (ChartPeriod) -> Unit,
    onBack: () -> Unit,
    menu: List<MenuAction> = emptyList(),
    content: @Composable () -> Unit,
) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        containerColor = OpenFitColors.Background,
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    if (menu.isNotEmpty()) {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more_options)) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            menu.forEach { action ->
                                DropdownMenuItem(
                                    text = { Text(action.label) },
                                    onClick = {
                                        menuOpen = false
                                        action.onClick()
                                    },
                                )
                            }
                        }
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
            PeriodTabs(periods, period, accent, onPeriod)
            content()
        }
    }
}

private val DEFAULT_PERIODS = listOf(ChartPeriod.Day, ChartPeriod.Week, ChartPeriod.Month, ChartPeriod.Year)

/** Four fixed tabs, or Fitbit's scrolling row of 90dp tabs when a screen offers more (Heart). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodTabs(periods: List<ChartPeriod>, selected: ChartPeriod, accent: Color, onSelect: (ChartPeriod) -> Unit) {
    val index = periods.indexOf(selected).coerceAtLeast(0)
    val tabs: @Composable () -> Unit = {
        periods.forEach { period ->
            val label = stringResource(periodTabLabel(period))
            if (periods.size > DEFAULT_PERIODS.size) {
                Tab(
                    selected = period == selected,
                    onClick = { onSelect(period) },
                    selectedContentColor = accent,
                    unselectedContentColor = OpenFitColors.OnSurfaceVariant,
                    modifier = Modifier.widthIn(min = 90.dp),
                    text = { Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false) },
                )
            } else {
                // Material's text tab pads 16dp a side, which cuts "3 months" in a quarter of a 360dp screen.
                Tab(
                    selected = period == selected,
                    onClick = { onSelect(period) },
                    selectedContentColor = accent,
                    unselectedContentColor = OpenFitColors.OnSurfaceVariant,
                ) {
                    Box(Modifier.height(TAB_HEIGHT).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
    val indicator: @Composable TabIndicatorScope.() -> Unit = {
        TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(index), height = 2.dp, color = accent)
    }
    if (periods.size > DEFAULT_PERIODS.size) {
        SecondaryScrollableTabRow(
            selectedTabIndex = index,
            containerColor = OpenFitColors.Background,
            contentColor = accent,
            edgePadding = 0.dp,
            indicator = { TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(index, matchContentSize = false), height = 2.dp, color = accent) },
            divider = {},
            tabs = tabs,
        )
    } else {
        SecondaryTabRow(
            selectedTabIndex = index,
            containerColor = OpenFitColors.Background,
            contentColor = accent,
            indicator = indicator,
            divider = {},
            tabs = tabs,
        )
    }
}

private val TAB_HEIGHT = 48.dp

private fun periodTabLabel(period: ChartPeriod): Int = when (period) {
    ChartPeriod.Day -> R.string.cardio_tab_day
    ChartPeriod.Week -> R.string.cardio_tab_week
    ChartPeriod.Month -> R.string.cardio_tab_month
    ChartPeriod.ThreeMonths -> R.string.tab_three_months
    ChartPeriod.Year -> R.string.cardio_tab_year
}

@Composable
fun DetailLoading(accent: Color) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accent) }
}

@Composable
fun DetailFailed(message: String) {
    Box(Modifier.fillMaxSize().padding(PagePadding), contentAlignment = Alignment.Center) {
        Text(
            // Health Connect's own rate-limit message reads like a crash.
            if (message.contains("quota", ignoreCase = true)) stringResource(R.string.hc_rate_limited) else message,
            color = OpenFitColors.OnSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** The scrolling body of a period; scrolls back to the top whenever the period changes. */
@Composable
fun DetailList(period: ChartPeriod, anchor: LocalDate, content: LazyListScope.() -> Unit) {
    LazyColumn(
        state = key(period, anchor) { rememberLazyListState() },
        contentPadding = PaddingValues(bottom = 48.dp),
        content = content,
    )
}

@Composable
fun PeriodNavigator(label: String, canGoForward: Boolean, onShift: (Long) -> Unit, period: ChartPeriod = ChartPeriod.Day) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onShift(-1) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(periodArrowLabel(period, forward = false)), tint = OpenFitColors.OnSurface)
        }
        Text(label, style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        IconButton(onClick = { onShift(1) }, enabled = canGoForward) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                stringResource(periodArrowLabel(period, forward = true)),
                tint = if (canGoForward) OpenFitColors.OnSurface else OpenFitColors.OnSurface.copy(alpha = 0.38f),
            )
        }
    }
}

private fun periodArrowLabel(period: ChartPeriod, forward: Boolean): Int = when (period) {
    ChartPeriod.Day -> if (forward) R.string.next_day else R.string.previous_day
    ChartPeriod.Week -> if (forward) R.string.next_week else R.string.previous_week
    ChartPeriod.Month -> if (forward) R.string.next_month else R.string.previous_month
    ChartPeriod.ThreeMonths -> if (forward) R.string.next_three_months else R.string.previous_three_months
    ChartPeriod.Year -> if (forward) R.string.next_year else R.string.previous_year
}

fun isCurrentPeriod(period: ChartPeriod, anchor: LocalDate, today: LocalDate): Boolean = today in periodDays(period, anchor)

fun canGoForward(period: ChartPeriod, anchor: LocalDate, today: LocalDate): Boolean = periodDays(period, anchor).last() < today

@Composable
fun periodLabel(period: ChartPeriod, anchor: LocalDate, today: LocalDate): String {
    val days = periodDays(period, anchor)
    val current = today in days
    return when (period) {
        ChartPeriod.Day -> if (anchor == today) stringResource(R.string.today) else anchor.weekdayDayMonth()
        ChartPeriod.Week -> if (current) stringResource(R.string.this_week) else weekRange(days.first(), days.last())
        ChartPeriod.Month -> when {
            current -> stringResource(R.string.this_month)
            anchor.year == today.year -> anchor.month.getDisplayName(TextStyle.FULL_STANDALONE, com.openfit.ui.currentLocale())
            else -> "${anchor.month.getDisplayName(TextStyle.FULL_STANDALONE, com.openfit.ui.currentLocale())} ${anchor.year}"
        }
        ChartPeriod.ThreeMonths -> weekRange(days.first(), days.last())
        ChartPeriod.Year -> if (current) stringResource(R.string.this_year) else anchor.year.toString()
    }
}

/** Fitbit's grouped list: large corners at the ends of the group, small ones between rows. */
@Composable
fun GroupedCard(index: Int, count: Int, minHeight: Dp, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val outer = 16.dp
    val inner = 4.dp
    val shape = RoundedCornerShape(
        topStart = if (index == 0) outer else inner,
        topEnd = if (index == 0) outer else inner,
        bottomStart = if (index == count - 1) outer else inner,
        bottomEnd = if (index == count - 1) outer else inner,
    )
    Box(
        modifier
            .padding(horizontal = CardMargin)
            .padding(bottom = if (index == count - 1) 0.dp else 4.dp)
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clip(shape)
            .background(OpenFitColors.Card)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) { content() }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface, modifier = modifier.padding(horizontal = PagePadding))
}

@Composable
fun TitleWithAction(title: String, action: String, accent: Color, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface, modifier = Modifier.weight(1f))
        Text(
            action,
            style = MaterialTheme.typography.titleMedium,
            color = accent,
            modifier = Modifier.padding(start = 16.dp).clickable(onClick = onAction).padding(vertical = 8.dp),
        )
    }
}

@Composable
fun LinkSection(title: String, action: String, body: String, accent: Color, maxLines: Int = Int.MAX_VALUE, onAction: () -> Unit) {
    Column(Modifier.padding(start = PagePadding, end = PagePadding, top = SectionGap)) {
        TitleWithAction(title, action, accent, onAction)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = OpenFitColors.OnSurfaceVariant,
            maxLines = maxLines,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** A screen whose goal the user has not set yet: what the goal is for, and a button to set it. */
@Composable
fun SetGoalPrompt(body: String, accent: Color, onSetGoal: () -> Unit) {
    GroupedCard(0, 1, minHeight = 0.dp, modifier = Modifier.padding(top = 24.dp)) {
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp)) {
            Text(body, style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurface)
            TextButton(onClick = onSetGoal, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.goal_set), color = accent) }
        }
    }
}

/** "Set goal" in a header, where the goal would otherwise be ("of 10,000 steps"). */
@Composable
fun SetGoalLink(accent: Color, label: String = stringResource(R.string.goal_set), onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.titleSmall,
        color = accent,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 4.dp),
    )
}

/** The big number at the top of a period, with its unit or status beside it on the same baseline. */
@Composable
fun HeaderValue(value: String, label: String) = HeaderValue(AnnotatedString(value), label)

@Composable
fun HeaderValue(value: AnnotatedString, label: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, style = MaterialTheme.typography.displayMedium, color = OpenFitColors.OnSurface, modifier = Modifier.alignByBaseline())
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurface, modifier = Modifier.alignByBaseline())
    }
}

/**
 * Day tab header shared by metric screens: value and status on one baseline, description below (or [footer], such as a
 * "Set goal" link), gauge at the end.
 */
@Composable
fun DayHeaderRow(value: String, status: String, description: String?, footer: @Composable () -> Unit = {}, gauge: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = PagePadding, end = 21.dp, top = 13.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            HeaderValue(value, status)
            description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant) }
            footer()
        }
        Column(Modifier.padding(top = 14.dp)) { gauge() }
    }
}

/** The list under a Week/Month/Year chart: a heading row, then one grouped card per day, week or month. */
fun LazyListScope.periodRows(
    period: ChartPeriod,
    anchor: LocalDate,
    today: LocalDate,
    rows: List<PeriodRow>,
    goal: Double? = null,
    value: (PeriodRow) -> String = { whole(it.load) },
) {
    if (rows.isEmpty()) return
    item {
        Row(Modifier.fillMaxWidth().padding(start = PagePadding, end = PagePadding, top = SectionGap, bottom = 16.dp)) {
            val title = if (period == ChartPeriod.Week && today in periodDays(period, anchor)) stringResource(R.string.this_week) else listTitle(period, anchor)
            Text(title, style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface, modifier = Modifier.weight(1f))
            if (period != ChartPeriod.Week) {
                Text(stringResource(R.string.daily_average), style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface)
            }
        }
    }
    itemsIndexed(rows) { index, row ->
        GroupedCard(index, rows.size, minHeight = 68.dp) {
            Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(rowLabel(period, row, today), style = MaterialTheme.typography.titleSmall, color = OpenFitColors.OnSurface, modifier = Modifier.weight(1f))
                if (goal != null && period == ChartPeriod.Week && row.load >= goal) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = OpenFitColors.Mint.accent, modifier = Modifier.padding(end = 8.dp).size(20.dp))
                }
                Text(value(row), style = MaterialTheme.typography.headlineMedium, color = OpenFitColors.OnSurface)
            }
        }
    }
}

@Composable
private fun listTitle(period: ChartPeriod, anchor: LocalDate): String {
    val days = periodDays(period, anchor)
    return when (period) {
        ChartPeriod.Week, ChartPeriod.ThreeMonths -> weekRange(days.first(), days.last())
        ChartPeriod.Month -> anchor.month.getDisplayName(TextStyle.FULL_STANDALONE, com.openfit.ui.currentLocale())
        else -> anchor.year.toString()
    }
}

@Composable
private fun rowLabel(period: ChartPeriod, row: PeriodRow, today: LocalDate): String = when (period) {
    ChartPeriod.Week, ChartPeriod.Day -> when (row.start) {
        today -> stringResource(R.string.today)
        today.minusDays(1) -> stringResource(R.string.yesterday)
        else -> row.start.weekdayDayMonth()
    }
    ChartPeriod.Month, ChartPeriod.ThreeMonths -> weekRange(row.start, row.end)
    ChartPeriod.Year -> if (YearMonth.from(row.start) == YearMonth.from(today)) {
        stringResource(R.string.this_month)
    } else {
        row.start.month.getDisplayName(TextStyle.FULL_STANDALONE, com.openfit.ui.currentLocale())
    }
}

/** X-axis labels Fitbit uses on Week (S M T…), Month (1 5 10…) and Year (J F M…) charts, by slot index. */
fun chartXLabels(period: ChartPeriod, anchor: LocalDate): List<Pair<Int, String>> = when (period) {
    ChartPeriod.Week -> periodDays(ChartPeriod.Week, anchor).mapIndexed { index, day -> index to day.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()) }
    ChartPeriod.Month -> {
        val length = YearMonth.from(anchor).lengthOfMonth()
        (listOf(1) + (5..length step 5)).map { (it - 1) to it.toString() }
    }
    ChartPeriod.Year -> (1..12).map { month -> (month - 1) to java.time.Month.of(month).getDisplayName(TextStyle.NARROW, Locale.getDefault()) }
    // 3 months: label the first week of each month with the month's short name.
    ChartPeriod.ThreeMonths -> periodDays(period, anchor).chunked(7).mapIndexedNotNull { index, week ->
        week.firstOrNull { it.dayOfMonth == 1 }?.let { index to it.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
    }
    ChartPeriod.Day -> emptyList()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoSheet(@StringRes text: Int, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = OpenFitColors.CardHigh) {
        Text(
            stringResource(text),
            style = MaterialTheme.typography.bodyLarge,
            color = OpenFitColors.OnSurface,
            modifier = Modifier.padding(start = PagePadding, end = PagePadding, bottom = 40.dp),
        )
    }
}
