package com.openfit.ui.vitals

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openfit.R
import com.openfit.domain.HealthMetric
import com.openfit.ui.detail.DetailFailed
import com.openfit.ui.detail.DetailLoading
import com.openfit.ui.detail.GroupedCard
import com.openfit.ui.detail.HeaderValue
import com.openfit.ui.detail.PagePadding
import com.openfit.ui.detail.PeriodNavigator
import com.openfit.ui.theme.OpenFitColors
import com.openfit.ui.today.SegmentedGauge
import com.openfit.ui.today.count
import com.openfit.ui.today.relativeDay
import com.openfit.ui.today.valueText

private val ACCENT = OpenFitColors.Blue
private val OUT_OF_RANGE = OpenFitColors.Pink.accent

/** Fitbit's Health metrics page: a day's five metrics against their personal ranges; each opens its own chart. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthMetricsRoute(viewModel: HealthMetricsViewModel, onBack: () -> Unit, onOpenMetric: (HealthMetric) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = OpenFitColors.Background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.hm_screen_title), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = OpenFitColors.Background,
                    titleContentColor = OpenFitColors.OnSurface,
                    navigationIconContentColor = OpenFitColors.OnSurface,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (val current = state) {
                HealthMetricsUiState.Loading -> DetailLoading(ACCENT.accent)
                is HealthMetricsUiState.Failed -> DetailFailed(current.message)
                is HealthMetricsUiState.Ready -> Content(current, viewModel::shift, onOpenMetric)
            }
        }
    }
}

@Composable
private fun Content(state: HealthMetricsUiState.Ready, onShift: (Long) -> Unit, onOpenMetric: (HealthMetric) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = 48.dp)) {
        item { PeriodNavigator(relativeDay(state.date), state.date < state.today, onShift) }
        item {
            val ranged = state.ranged
            val inRange = ranged.count { it.inRange == true }
            Row(Modifier.fillMaxWidth().padding(start = PagePadding, end = 21.dp, top = 13.dp, bottom = 24.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    if (ranged.isEmpty()) {
                        HeaderValue(stringResource(R.string.no_data), "")
                    } else {
                        HeaderValue(valueText { count(inRange, ranged.size) }, "")
                    }
                    Text(
                        stringResource(
                            when {
                                ranged.isEmpty() -> R.string.hm_keep_wearing
                                inRange == ranged.size -> R.string.hm_in_personal_range
                                else -> R.string.hm_out_of_personal_range
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = OpenFitColors.OnSurfaceVariant,
                    )
                }
                Column(Modifier.padding(top = 14.dp)) {
                    SegmentedGauge(Icons.Outlined.BarChart, ACCENT, ranged.size.coerceAtLeast(1), inRange)
                }
            }
        }
        itemsIndexed(state.rows, key = { _, row -> row.metric.name }) { index, row ->
            GroupedCard(index, state.rows.size, minHeight = 80.dp, onClick = { onOpenMetric(row.metric) }) {
                Row(Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(listTitle(row.metric)), style = MaterialTheme.typography.titleSmall, color = OpenFitColors.OnSurface, modifier = Modifier.weight(1f).padding(end = 12.dp))
                    val value = row.value
                    Text(
                        if (value == null) stringResource(R.string.no_data) else "${format(row.metric, display(row.metric, value, state.profile))} ${stringResource(unit(row.metric, state.profile))}",
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (row.inRange == false) OUT_OF_RANGE else OpenFitColors.OnSurface,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}
