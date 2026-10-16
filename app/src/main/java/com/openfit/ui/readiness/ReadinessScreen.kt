package com.openfit.ui.readiness

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openfit.R
import com.openfit.domain.ChartPeriod
import com.openfit.domain.FactorLevel
import com.openfit.domain.ReadinessLevel
import com.openfit.domain.ReadinessSummary
import com.openfit.domain.SleepFactor
import com.openfit.ui.detail.AxisLabel
import com.openfit.ui.detail.BarChart
import com.openfit.ui.detail.DayHeaderRow
import com.openfit.ui.detail.DetailFailed
import com.openfit.ui.detail.DetailList
import com.openfit.ui.detail.DetailLoading
import com.openfit.ui.detail.DetailScaffold
import com.openfit.ui.detail.GroupedCard
import com.openfit.ui.detail.GuideLegend
import com.openfit.ui.detail.InfoSheet
import com.openfit.ui.detail.LinkSection
import com.openfit.ui.detail.PagePadding
import com.openfit.ui.detail.PeriodNavigator
import com.openfit.ui.detail.SectionGap
import com.openfit.ui.detail.canGoForward
import com.openfit.ui.detail.chartXLabels
import com.openfit.ui.detail.periodLabel
import com.openfit.ui.detail.periodRows
import com.openfit.ui.theme.OpenFitColors
import com.openfit.ui.theme.SectionHeaderStyle
import com.openfit.ui.today.ArcGauge
import com.openfit.ui.today.IconBadge
import kotlin.math.abs

private val ACCENT = OpenFitColors.Lavender.accent
private val GUIDES = listOf(30.0, 65.0)

@Composable
fun ReadinessRoute(viewModel: ReadinessViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf<Int?>(null) }
    val ready = state as? ReadinessUiState.Ready
    DetailScaffold(
        title = stringResource(R.string.readiness),
        accent = ACCENT,
        period = viewModel.period,
        onPeriod = viewModel::selectPeriod,
        onBack = onBack,
    ) {
        when (val current = state) {
            ReadinessUiState.Loading -> DetailLoading(ACCENT)
            is ReadinessUiState.Failed -> DetailFailed(current.message)
            is ReadinessUiState.Ready -> DetailList(current.period, current.anchor) {
                item {
                    PeriodNavigator(
                        periodLabel(current.period, current.anchor, current.today),
                        canGoForward(current.period, current.anchor, current.today),
                        viewModel::shift,
                    )
                }
                if (current.period == ChartPeriod.Day) dayContent(current) else periodContent(current)
                item {
                    LinkSection(
                        title = stringResource(R.string.readiness_about_title),
                        action = stringResource(R.string.learn_more),
                        body = stringResource(R.string.readiness_about_body),
                        accent = OpenFitColors.Mint.accent,
                        onAction = { sheet = R.string.readiness_about_sheet },
                    )
                }
            }
        }
    }
    sheet?.let { InfoSheet(it) { sheet = null } }
}

private fun LazyListScope.dayContent(state: ReadinessUiState.Ready) {
    val readiness = state.readiness
    item {
        DayHeaderRow(
            value = readiness?.score?.toString() ?: "–",
            status = stringResource(
                when (readiness?.level) {
                    ReadinessLevel.High -> R.string.readiness_high_label
                    ReadinessLevel.Moderate -> R.string.readiness_moderate_label
                    ReadinessLevel.Low -> R.string.readiness_low_label
                    null -> R.string.no_score
                },
            ),
            description = stringResource(
                when (readiness?.level) {
                    ReadinessLevel.High -> R.string.readiness_day_high
                    ReadinessLevel.Moderate -> R.string.readiness_day_moderate
                    ReadinessLevel.Low -> R.string.readiness_day_low
                    null -> R.string.readiness_day_none
                },
            ),
        ) {
            if (readiness == null) {
                IconBadge(Icons.AutoMirrored.Outlined.DirectionsRun, OpenFitColors.Lavender)
            } else {
                ArcGauge(Icons.AutoMirrored.Outlined.DirectionsRun, OpenFitColors.Lavender, readiness.score / 100f, background = OpenFitColors.Background)
            }
        }
    }
    val breakdown = state.breakdown ?: return
    val factors = listOfNotNull(
        breakdown.hrv?.let { Triple(Icons.Outlined.FavoriteBorder, R.string.readiness_hrv_title, factorText(it)) },
        breakdown.sleep?.let {
            Triple(
                Icons.Outlined.Bedtime,
                R.string.readiness_sleep_title,
                when (it) {
                    SleepFactor.Good -> R.string.sleep_good
                    SleepFactor.Fair -> R.string.sleep_fair
                    SleepFactor.Poor -> R.string.sleep_poor
                },
            )
        },
    )
    // Fitbit grades resting heart rate on the same five levels as HRV ("About usual" on 15 Oct 2026, 61 against 60).
    val resting = breakdown.resting
    if (factors.isEmpty() && resting == null) return
    item {
        Column(Modifier.padding(start = PagePadding, end = PagePadding, top = 32.dp, bottom = 16.dp)) {
            Text(stringResource(R.string.readiness_impact_title), style = SectionHeaderStyle, color = OpenFitColors.OnSurface)
            Text(
                stringResource(R.string.readiness_impact_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = OpenFitColors.OnSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
    val rows: List<Pair<ImageVector, Pair<Int, @Composable () -> String>>> = factors.map { (icon, title, subtitle) ->
        icon to (title to @Composable { stringResource(subtitle) })
    } + listOfNotNull(
        resting?.let { level -> Icons.Outlined.FavoriteBorder to (R.string.readiness_rhr_title to @Composable { stringResource(factorText(level)) }) },
    )
    itemsIndexed(rows) { index, (icon, content) ->
        val (title, subtitle) = content
        GroupedCard(index, rows.size, minHeight = 76.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = OpenFitColors.OnSurface, modifier = Modifier.size(24.dp))
                Column(Modifier.padding(start = 16.dp)) {
                    Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface)
                    Text(subtitle(), style = MaterialTheme.typography.bodyLarge, color = OpenFitColors.OnSurfaceVariant)
                }
            }
        }
    }
}

@StringRes
private fun factorText(level: FactorLevel): Int = when (level) {
    FactorLevel.MuchLower -> R.string.factor_much_lower
    FactorLevel.Lower -> R.string.factor_lower
    FactorLevel.Usual -> R.string.factor_usual
    FactorLevel.Higher -> R.string.factor_higher
    FactorLevel.MuchHigher -> R.string.factor_much_higher
}

private fun LazyListScope.periodContent(state: ReadinessUiState.Ready) {
    item {
        Column(Modifier.fillMaxWidth().padding(start = PagePadding, end = PagePadding, top = 13.dp)) {
            val summary = state.summary
            Text(
                stringResource(
                    when (summary) {
                        ReadinessSummary.MostlyHigh -> R.string.readiness_mostly_high
                        ReadinessSummary.MostlyModerate -> R.string.readiness_mostly_moderate
                        ReadinessSummary.MostlyLow -> R.string.readiness_mostly_low
                        ReadinessSummary.Mixed -> R.string.readiness_mixed
                        null -> R.string.no_score
                    },
                ),
                style = MaterialTheme.typography.headlineMedium,
                color = OpenFitColors.OnSurface,
            )
            Text(
                stringResource(
                    when (summary) {
                        ReadinessSummary.MostlyHigh -> R.string.readiness_mostly_high_body
                        ReadinessSummary.MostlyModerate -> R.string.readiness_mostly_moderate_body
                        ReadinessSummary.MostlyLow -> R.string.readiness_mostly_low_body
                        ReadinessSummary.Mixed -> R.string.readiness_mixed_body
                        null -> R.string.readiness_day_none
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = OpenFitColors.OnSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    item {
        Column(Modifier.padding(top = 24.dp)) {
            BarChart(
                values = state.points.map { it.load },
                color = ACCENT,
                axisMax = 100.0,
                axisLabels = listOf(0, 30, 65, 100).map { AxisLabel(it.toDouble(), it.toString()) },
                xLabels = chartXLabels(state.period, state.anchor),
                guides = GUIDES,
            )
            GuideLegend(listOf(1 to stringResource(R.string.readiness_legend_moderate), 2 to stringResource(R.string.readiness_legend_high)))
        }
    }
    periodRows(state.period, state.anchor, state.today, state.rows)
}

