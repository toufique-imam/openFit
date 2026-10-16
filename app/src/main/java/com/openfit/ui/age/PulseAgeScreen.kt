package com.openfit.ui.age

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.openfit.R
import com.openfit.domain.AgeEffect
import com.openfit.domain.AgeFactor
import com.openfit.domain.AgeGroup
import com.openfit.domain.PulseAge
import com.openfit.domain.Sex
import com.openfit.domain.ageDirection
import com.openfit.domain.ageLnHr
import com.openfit.health.DayLoadCache
import com.openfit.health.HealthConnectGateway
import com.openfit.health.PulseAgeSource
import com.openfit.profile.Profile
import com.openfit.profile.ProfileStore
import com.openfit.ui.detail.DetailFailed
import com.openfit.ui.detail.DetailLoading
import com.openfit.ui.detail.GroupedCard
import com.openfit.ui.detail.HeaderValue
import com.openfit.ui.detail.InfoSheet
import com.openfit.ui.detail.LinkSection
import com.openfit.ui.detail.PagePadding
import com.openfit.ui.theme.OpenFitColors
import com.openfit.ui.today.oneDecimal
import com.openfit.ui.today.whole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

/** Better than the reference reads mint, worse reads amber, as on Pulse's scales. */
val OLDER = Color(0xFFF2A65A)
private val YOUNGER = OpenFitColors.Mint.accent

sealed interface PulseAgeUiState {
    data object Loading : PulseAgeUiState
    data class Failed(val message: String) : PulseAgeUiState

    /** The profile has no age or sex, which every reference depends on. */
    data object NeedsProfile : PulseAgeUiState
    data class Ready(val profile: Profile, val result: PulseAge?, val inputs: Map<AgeFactor, Double>) : PulseAgeUiState
}

class PulseAgeViewModel(gateway: HealthConnectGateway, private val profileStore: ProfileStore, dayLoadCache: DayLoadCache) : ViewModel() {

    private val source = PulseAgeSource(gateway, dayLoadCache)

    private val _state = MutableStateFlow<PulseAgeUiState>(PulseAgeUiState.Loading)
    val state: StateFlow<PulseAgeUiState> = _state.asStateFlow()

    fun open(date: LocalDate) {
        _state.value = PulseAgeUiState.Loading
        viewModelScope.launch {
            _state.value = try {
                val profile = profileStore.load()
                val age = profile.age
                val sex = profile.sex
                if (age == null || sex == null) {
                    PulseAgeUiState.NeedsProfile
                } else {
                    val inputs = source.inputs(date.minusDays(1), profile)
                    PulseAgeUiState.Ready(profile, com.openfit.domain.pulseAge(age, sex, inputs, source.estimated(inputs)), inputs)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                PulseAgeUiState.Failed(error.message ?: error.javaClass.simpleName)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PulseAgeRoute(viewModel: PulseAgeViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        containerColor = OpenFitColors.Background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.age_title), style = MaterialTheme.typography.titleLarge) },
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
                PulseAgeUiState.Loading -> DetailLoading(YOUNGER)
                is PulseAgeUiState.Failed -> DetailFailed(current.message)
                PulseAgeUiState.NeedsProfile -> DetailFailed(stringResource(R.string.age_needs_profile))
                is PulseAgeUiState.Ready -> Content(current) { sheet = true }
            }
        }
    }
    if (sheet) InfoSheet(R.string.age_sheet) { sheet = false }
}

@Composable
private fun Content(state: PulseAgeUiState.Ready, onLearnMore: () -> Unit) {
    val age = state.profile.age ?: return
    val sex = state.profile.sex ?: return
    val result = state.result
    LazyColumn(contentPadding = PaddingValues(bottom = 48.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(start = PagePadding, end = PagePadding, top = 13.dp)) {
                HeaderValue(result?.let { fixed(it.age) } ?: "–", stringResource(R.string.age_years))
                Text(
                    if (result == null) stringResource(R.string.age_not_enough, state.inputs.size) else differenceText(result.difference, age),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenFitColors.OnSurfaceVariant,
                )
                Text(
                    stringResource(R.string.age_based_on, state.inputs.size, AgeFactor.entries.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenFitColors.OnSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        AgeGroup.entries.forEach { group ->
            val factors = AgeFactor.entries.filter { it.group == group }
            item(key = "group-${group.name}") {
                Text(
                    stringResource(groupTitle(group)),
                    style = MaterialTheme.typography.titleMedium,
                    color = OpenFitColors.OnSurface,
                    modifier = Modifier.padding(start = PagePadding, end = PagePadding, top = 32.dp, bottom = 12.dp),
                )
            }
            itemsIndexed(factors, key = { _, factor -> factor.name }) { index, factor ->
                FactorRow(factor, state.inputs[factor], result?.effects?.firstOrNull { it.factor == factor }, age, sex, index, factors.size)
            }
        }
        item {
            LinkSection(
                title = stringResource(R.string.age_about_title),
                action = stringResource(R.string.learn_more),
                body = stringResource(R.string.age_about_body),
                accent = OpenFitColors.Mint.accent,
                onAction = onLearnMore,
            )
        }
    }
}

@Composable
private fun FactorRow(factor: AgeFactor, value: Double?, effect: AgeEffect?, age: Int, sex: Sex, index: Int, count: Int) {
    GroupedCard(index, count, minHeight = 104.dp) {
        Row(Modifier.fillMaxWidth().heightIn(min = 104.dp).padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(factorTitle(factor)), style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface)
                Text(
                    when {
                        value == null -> stringResource(R.string.no_data)
                        effect?.estimated == true -> stringResource(R.string.age_estimated, format(factor, value))
                        else -> stringResource(R.string.age_average, format(factor, value))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenFitColors.OnSurfaceVariant,
                )
                Scale(factor, value, age, sex, Modifier.padding(top = 10.dp))
            }
            Column(Modifier.width(76.dp), horizontalAlignment = Alignment.End) {
                if (effect != null) {
                    val years = effect.years
                    Text(
                        (if (years > 0.05) "+" else if (years < -0.05) "−" else "") + fixed(abs(years)),
                        style = MaterialTheme.typography.headlineSmall,
                        color = when (ageDirection(years)) {
                            -1 -> YOUNGER
                            1 -> OLDER
                            else -> OpenFitColors.OnSurfaceVariant
                        },
                    )
                    Text(stringResource(R.string.age_years), style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant)
                }
            }
        }
    }
}

/**
 * Ten cells across the factor's range, each coloured by the risk at its middle against the reference (amber worse,
 * mint better), a marker above the user's value and a tick at the reference.
 */
@Composable
private fun Scale(factor: AgeFactor, value: Double?, age: Int, sex: Sex, modifier: Modifier = Modifier) {
    val (low, high) = SCALES.getValue(factor)
    val reference = com.openfit.domain.ageReference(factor, age, sex)
    Canvas(modifier.fillMaxWidth().height(26.dp)) {
        val cells = 10
        val gap = 2.dp.toPx()
        val top = 8.dp.toPx()
        val height = size.height - top
        val cell = (size.width - gap * (cells - 1)) / cells
        fun x(v: Double) = (((v - low) / (high - low)).coerceIn(0.0, 1.0) * size.width).toFloat()
        for (i in 0 until cells) {
            val middle = low + (high - low) * (i + 0.5) / cells
            val risk = ageLnHr(factor, middle, age, sex)
            val t = (0.5 - risk / RISK_SPAN).coerceIn(0.0, 1.0).toFloat()
            drawRoundRect(lerp(OLDER, YOUNGER, t).copy(alpha = 0.55f), Offset(i * (cell + gap), top), Size(cell, height), CornerRadius(3.dp.toPx()))
        }
        drawLine(OpenFitColors.OnSurface.copy(alpha = 0.6f), Offset(x(reference), top), Offset(x(reference), size.height), strokeWidth = 2.dp.toPx())
        value?.let {
            val markerX = x(it)
            val marker = Path().apply {
                moveTo(markerX - 5.dp.toPx(), 0f)
                lineTo(markerX + 5.dp.toPx(), 0f)
                lineTo(markerX, top - 1.dp.toPx())
                close()
            }
            drawPath(marker, OpenFitColors.OnSurface)
        }
    }
}

/** The ln HR that spans half the colour scale. */
private const val RISK_SPAN = 0.4

/** Each factor's scale, low to high, in its units. */
private val SCALES = mapOf(
    AgeFactor.SleepHours to (4.0 to 10.0),
    AgeFactor.SleepConsistency to (0.0 to 100.0),
    AgeFactor.ModerateMinutes to (0.0 to 300.0),
    AgeFactor.VigorousMinutes to (0.0 to 150.0),
    AgeFactor.StrengthMinutes to (0.0 to 150.0),
    AgeFactor.Steps to (0.0 to 15_000.0),
    AgeFactor.Vo2Max to (15.0 to 70.0),
    AgeFactor.RestingHeartRate to (40.0 to 90.0),
    AgeFactor.BodyFat to (5.0 to 50.0),
)

@Composable
private fun differenceText(years: Double, age: Int): String = when (ageDirection(years)) {
    -1 -> stringResource(R.string.age_younger, fixed(-years), age)
    1 -> stringResource(R.string.age_older, fixed(years), age)
    else -> stringResource(R.string.age_same, age)
}

@Composable
fun ageDifferenceShort(years: Double): String = when (ageDirection(years)) {
    -1 -> stringResource(R.string.age_younger_short, fixed(-years))
    1 -> stringResource(R.string.age_older_short, fixed(years))
    else -> stringResource(R.string.age_same_short)
}

private fun hoursMinutes(minutes: Double): String {
    val total = minutes.roundToInt()
    return "${total / 60}h ${total % 60}m"
}

@Composable
private fun format(factor: AgeFactor, value: Double): String = when (factor) {
    AgeFactor.SleepHours -> hoursMinutes(value * 60)
    AgeFactor.SleepConsistency -> whole(value.roundToInt())
    AgeFactor.ModerateMinutes, AgeFactor.VigorousMinutes, AgeFactor.StrengthMinutes -> stringResource(R.string.age_per_week, hoursMinutes(value))
    AgeFactor.Steps -> stringResource(R.string.age_per_day, whole(value.roundToInt()))
    AgeFactor.Vo2Max -> oneDecimal(value)
    AgeFactor.RestingHeartRate -> "${value.roundToInt()} ${stringResource(R.string.unit_bpm)}"
    AgeFactor.BodyFat -> "${oneDecimal(value)}%"
}

private fun factorTitle(factor: AgeFactor): Int = when (factor) {
    AgeFactor.SleepHours -> R.string.age_factor_sleep_hours
    AgeFactor.SleepConsistency -> R.string.age_factor_sleep_consistency
    AgeFactor.ModerateMinutes -> R.string.age_factor_moderate
    AgeFactor.VigorousMinutes -> R.string.age_factor_vigorous
    AgeFactor.StrengthMinutes -> R.string.age_factor_strength
    AgeFactor.Steps -> R.string.age_factor_steps
    AgeFactor.Vo2Max -> R.string.age_factor_vo2max
    AgeFactor.RestingHeartRate -> R.string.age_factor_resting_hr
    AgeFactor.BodyFat -> R.string.age_factor_body_fat
}

private fun groupTitle(group: AgeGroup): Int = when (group) {
    AgeGroup.Sleep -> R.string.age_group_sleep
    AgeGroup.Activity -> R.string.age_group_activity
    AgeGroup.Fitness -> R.string.age_group_fitness
}

/** Pulse Age always shows one decimal: 33.0, +0.4. */
fun fixed(value: Double): String = "%.1f".format(value)
