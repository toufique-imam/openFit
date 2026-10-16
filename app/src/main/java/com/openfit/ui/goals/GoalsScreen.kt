package com.openfit.ui.goals

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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openfit.R
import com.openfit.domain.FitnessGoal
import com.openfit.profile.Profile
import com.openfit.profile.ProfileStore
import com.openfit.profile.UnitSystem
import com.openfit.profile.WeightUnit
import com.openfit.ui.detail.GroupedCard
import com.openfit.ui.detail.PagePadding
import com.openfit.ui.theme.OpenFitColors
import com.openfit.ui.today.oneDecimal
import com.openfit.ui.today.shortDuration
import com.openfit.ui.today.twoDecimals
import com.openfit.ui.today.whole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

private const val METERS_PER_MILE = 1_609.344
private const val FL_OZ_PER_LITER = 33.814
private const val POUNDS_PER_KG = 2.20462
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

class GoalsViewModel(private val profileStore: ProfileStore) : ViewModel() {

    private val _profile = MutableStateFlow(profileStore.load())
    val profile: StateFlow<Profile> = _profile.asStateFlow()

    fun reload() {
        _profile.value = profileStore.load()
    }

    fun save(profile: Profile) {
        profileStore.save(profile)
        _profile.value = profile
    }
}

/** Fitbit's Goals screen: each category's goals with their value, or a "Set goal" button when unset. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalsRoute(viewModel: GoalsViewModel, onBack: () -> Unit) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf<Goal?>(null) }
    Scaffold(
        containerColor = OpenFitColors.Background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.you_goals), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = OpenFitColors.Background,
                    titleContentColor = OpenFitColors.OnSurface,
                    navigationIconContentColor = OpenFitColors.OnSurface,
                ),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
            GoalCategory.entries.forEach { category ->
                val goals = Goal.entries.filter { it.listed && it.category == category }
                item(key = category.name) {
                    Text(
                        stringResource(category.title),
                        style = MaterialTheme.typography.titleMedium,
                        color = OpenFitColors.OnSurface,
                        modifier = Modifier.padding(start = PagePadding, end = PagePadding, top = 24.dp, bottom = 12.dp),
                    )
                }
                itemsIndexed(goals, key = { _, goal -> goal.name }) { index, goal ->
                    GoalRow(goal, profile, index, goals.size) { editing = goal }
                }
            }
        }
    }
    editing?.let { goal ->
        GoalEditor(
            goal = goal,
            profile = profile,
            onSave = {
                editing = null
                viewModel.save(it)
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun GoalRow(goal: Goal, profile: Profile, index: Int, count: Int, onClick: () -> Unit) {
    GroupedCard(index, count, minHeight = 72.dp, onClick = onClick) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(goal.title), style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface, modifier = Modifier.weight(1f))
            val value = goalValue(goal, profile)
            if (value == null) {
                OutlinedButton(onClick = onClick, colors = ButtonDefaults.outlinedButtonColors(contentColor = OpenFitColors.Mint.accent)) {
                    Text(stringResource(R.string.goal_set))
                }
            } else {
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 16.dp)) {
                    Text(value, style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface, textAlign = TextAlign.End)
                    goalMeta(goal)?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant) }
                }
            }
        }
    }
}

/** The goal as the Goals list shows it, or null when it is not set. */
@Composable
fun goalValue(goal: Goal, profile: Profile): String? {
    val imperial = profile.units == UnitSystem.Imperial
    return when (goal) {
        Goal.SleepSchedule -> {
            val bedtime = profile.bedtime ?: return null
            val wake = profile.wakeTime ?: return null
            "${bedtime.format(CLOCK)} – ${wake.format(CLOCK)}"
        }
        Goal.Bedtime -> profile.bedtime?.format(CLOCK)
        Goal.WakeTime -> profile.wakeTime?.format(CLOCK)
        Goal.ZoneMinutes -> ceil(profile.fitnessGoal.weeklyZoneMinutes / 7.0).toInt().toString()
        Goal.SleepDuration -> profile.sleepGoalMinutes?.let(::shortDuration)
        Goal.Steps -> profile.dailyStepGoal?.let { "${whole(it)} ${stringResource(R.string.unit_steps)}" }
        Goal.Distance -> profile.dailyDistanceGoalMeters?.let {
            if (imperial) "${twoDecimals(it / METERS_PER_MILE)} ${stringResource(R.string.unit_mi)}" else "${twoDecimals(it / 1_000)} ${stringResource(R.string.unit_km)}"
        }
        Goal.Energy -> profile.dailyCaloriesOutGoal?.let { "${whole(it)} ${stringResource(R.string.unit_cal)}" }
        Goal.Floors -> profile.dailyFloorsGoal?.let { "${whole(it)} ${stringResource(R.string.unit_floors)}" }
        Goal.ExerciseDays -> profile.weeklyExerciseDaysGoal?.let { stringResource(R.string.goal_value_days, it) }
        Goal.MindfulDays -> profile.weeklyMindfulDaysGoal?.let { stringResource(R.string.goal_value_days, it) }
        Goal.HourlyActivity -> profile.activeHours?.let { "${hourText(it.first)} – ${hourText(it.last + 1)}" }
        Goal.Fitness -> stringResource(if (profile.fitnessGoal == FitnessGoal.Improve) R.string.goal_fitness_improve else R.string.goal_fitness_maintain)
        Goal.Food -> profile.dailyCaloriesInGoal?.let { "${whole(it)} ${stringResource(R.string.unit_cal)}" }
        Goal.Water -> profile.dailyWaterGoalLiters?.let {
            if (imperial) "${oneDecimal(it * FL_OZ_PER_LITER)} ${stringResource(R.string.unit_fl_oz)}" else "${whole(it * 1_000)} ${stringResource(R.string.unit_ml)}"
        }
        Goal.Weight -> profile.weightGoalKg?.let {
            if (profile.weightUnit == WeightUnit.Pounds) "${oneDecimal(it * POUNDS_PER_KG)} ${stringResource(R.string.unit_lb)}" else "${oneDecimal(it)} ${stringResource(R.string.unit_kg)}"
        }
    }
}

/** Fitbit's line under a goal's value: how often it applies ("Daily", "Weekly", "Target value"). */
private fun goalMeta(goal: Goal): Int? = when (goal) {
    Goal.ExerciseDays, Goal.MindfulDays -> R.string.goal_meta_weekly
    Goal.Weight -> R.string.goal_meta_target_value
    Goal.Fitness -> null
    else -> R.string.goal_meta_daily
}
