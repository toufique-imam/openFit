package com.openfit.ui.coach

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openfit.R
import com.openfit.domain.ReadinessLevel
import com.openfit.ui.theme.OpenFitColors
import com.openfit.ui.theme.SectionHeaderStyle
import com.openfit.ui.today.TodayUiState
import com.openfit.ui.today.TodayViewModel
import com.openfit.ui.today.truncated

/**
 * Fitbit's Coach tab is a Premium workout library; openFit cannot offer those videos, so it shows the day's
 * training guidance from readiness and the cardio load target instead.
 */
@Composable
fun CoachRoute(viewModel: TodayViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ready = state as? TodayUiState.Ready
    Column(Modifier.fillMaxSize().background(OpenFitColors.Background).statusBarsPadding().padding(horizontal = 16.dp)) {
        Text(
            stringResource(R.string.tab_coach),
            fontSize = 26.sp,
            color = OpenFitColors.OnSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        )
        Text(stringResource(R.string.coach_today), style = SectionHeaderStyle, color = OpenFitColors.OnSurface, modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 16.dp))
        Surface(shape = RoundedCornerShape(16.dp), color = OpenFitColors.Card, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                val range = ready?.cardioRange
                val readiness = ready?.readiness
                Text(
                    when {
                        range != null -> stringResource(R.string.coach_target, truncated(range.low), truncated(range.high))
                        else -> stringResource(R.string.coach_no_target)
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = OpenFitColors.OnSurface,
                )
                Text(
                    stringResource(
                        when (readiness?.level) {
                            ReadinessLevel.High -> R.string.coach_high
                            ReadinessLevel.Moderate -> R.string.coach_moderate
                            ReadinessLevel.Low -> R.string.coach_low
                            null -> R.string.coach_unknown
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenFitColors.OnSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
