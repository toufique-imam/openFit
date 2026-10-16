package com.openfit.ui.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.openfit.R
import com.openfit.domain.FitnessGoal
import com.openfit.profile.Profile
import com.openfit.profile.UnitSystem
import com.openfit.profile.WeightUnit
import com.openfit.ui.theme.OpenFitColors
import com.openfit.ui.today.oneDecimal
import com.openfit.ui.today.twoDecimals
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private const val METERS_PER_MILE = 1_609.344
private const val FL_OZ_PER_LITER = 33.814
private const val POUNDS_PER_KG = 2.20462
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Sets or changes one goal. Every value comes from the user: an empty field leaves the goal unset, and "Reset goal"
 * clears a goal that is set.
 */
@Composable
fun GoalEditor(goal: Goal, profile: Profile, onSave: (Profile) -> Unit, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf(profile) }
    var valid by remember { mutableStateOf(goal.isSet(profile)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(goal.title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GoalInput(goal, profile) { updated ->
                    valid = updated != null
                    if (updated != null) draft = updated
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }, enabled = valid) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row {
                if (goal.resettable && goal.isSet(profile)) {
                    TextButton(onClick = { onSave(goal.reset(profile)) }) { Text(stringResource(R.string.goal_reset)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

/** The inputs for one goal; reports the updated profile, or null while the input is not a usable goal. */
@Composable
private fun GoalInput(goal: Goal, profile: Profile, onChange: (Profile?) -> Unit) {
    val imperial = profile.units == UnitSystem.Imperial
    val pounds = profile.weightUnit == WeightUnit.Pounds
    when (goal) {
        Goal.Steps -> NumberGoal(R.string.goal_input_steps, profile.dailyStepGoal?.toString(), 1.0..200_000.0) {
            onChange(it?.let { value -> profile.copy(dailyStepGoal = value.roundToInt()) })
        }
        Goal.Distance -> NumberGoal(
            if (imperial) R.string.goal_input_distance_mi else R.string.goal_input_distance_km,
            profile.dailyDistanceGoalMeters?.let { twoDecimals(if (imperial) it / METERS_PER_MILE else it / 1_000) },
            0.01..500.0,
            decimal = true,
        ) { onChange(it?.let { value -> profile.copy(dailyDistanceGoalMeters = if (imperial) value * METERS_PER_MILE else value * 1_000) }) }
        Goal.Energy -> NumberGoal(R.string.goal_input_energy, profile.dailyCaloriesOutGoal?.toString(), 1.0..20_000.0) {
            onChange(it?.let { value -> profile.copy(dailyCaloriesOutGoal = value.roundToInt()) })
        }
        Goal.Floors -> NumberGoal(R.string.goal_input_floors, profile.dailyFloorsGoal?.toString(), 1.0..1_000.0) {
            onChange(it?.let { value -> profile.copy(dailyFloorsGoal = value.roundToInt()) })
        }
        Goal.ExerciseDays -> NumberGoal(R.string.goal_input_exercise_days, profile.weeklyExerciseDaysGoal?.toString(), 1.0..7.0) {
            onChange(it?.let { value -> profile.copy(weeklyExerciseDaysGoal = value.roundToInt()) })
        }
        Goal.Food -> NumberGoal(R.string.goal_input_food, profile.dailyCaloriesInGoal?.toString(), 1.0..20_000.0) {
            onChange(it?.let { value -> profile.copy(dailyCaloriesInGoal = value.roundToInt()) })
        }
        Goal.Water -> NumberGoal(
            if (imperial) R.string.goal_input_water_oz else R.string.goal_input_water_ml,
            profile.dailyWaterGoalLiters?.let { if (imperial) oneDecimal(it * FL_OZ_PER_LITER) else (it * 1_000).roundToInt().toString() },
            1.0..20_000.0,
            decimal = imperial,
        ) { onChange(it?.let { value -> profile.copy(dailyWaterGoalLiters = if (imperial) value / FL_OZ_PER_LITER else value / 1_000) }) }
        Goal.Weight -> NumberGoal(
            if (pounds) R.string.goal_input_weight_lb else R.string.goal_input_weight_kg,
            profile.weightGoalKg?.let { oneDecimal(if (pounds) it * POUNDS_PER_KG else it) },
            1.0..700.0,
            decimal = true,
        ) { onChange(it?.let { value -> profile.copy(weightGoalKg = if (pounds) value / POUNDS_PER_KG else value) }) }
        Goal.SleepDuration -> SleepDurationInput(profile, onChange)
        Goal.SleepSchedule -> SleepScheduleInput(profile, onChange)
        Goal.Bedtime -> InlineTime(profile.bedtime) { onChange(profile.copy(bedtime = it)) }
        Goal.WakeTime -> InlineTime(profile.wakeTime) { onChange(profile.copy(wakeTime = it)) }
        Goal.HourlyActivity -> HourlyInput(profile, onChange)
        Goal.Fitness, Goal.ZoneMinutes -> FitnessInput(profile, onChange)
    }
}

@Composable
private fun NumberGoal(label: Int, initial: String?, range: ClosedFloatingPointRange<Double>, decimal: Boolean = false, onChange: (Double?) -> Unit) {
    var text by remember { mutableStateOf(initial.orEmpty().filter { it.isDigit() || it == '.' }) }
    NumberField(stringResource(label), text, decimal) { input ->
        text = input
        onChange(input.toDoubleOrNull()?.takeIf { it in range })
    }
}

@Composable
private fun NumberField(label: String, value: String, decimal: Boolean, modifier: Modifier = Modifier, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> onValueChange(input.filter { it.isDigit() || (decimal && it == '.') }) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun SleepDurationInput(profile: Profile, onChange: (Profile?) -> Unit) {
    var hours by remember { mutableStateOf(profile.sleepGoalMinutes?.let { (it / 60).toString() }.orEmpty()) }
    var minutes by remember { mutableStateOf(profile.sleepGoalMinutes?.let { (it % 60).toString() }.orEmpty()) }
    fun report() {
        val total = (hours.toIntOrNull() ?: 0) * 60 + (minutes.toIntOrNull() ?: 0)
        val usable = hours.isNotEmpty() && (minutes.toIntOrNull() ?: 0) < 60 && total in MIN_SLEEP_GOAL..MAX_SLEEP_GOAL
        onChange(if (usable) profile.copy(sleepGoalMinutes = total) else null)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(stringResource(R.string.goal_input_hours), hours, decimal = false, modifier = Modifier.weight(1f)) {
            hours = it
            report()
        }
        NumberField(stringResource(R.string.goal_input_minutes), minutes, decimal = false, modifier = Modifier.weight(1f)) {
            minutes = it
            report()
        }
    }
}

private const val MIN_SLEEP_GOAL = 60
private const val MAX_SLEEP_GOAL = 16 * 60

@Composable
private fun SleepScheduleInput(profile: Profile, onChange: (Profile?) -> Unit) {
    var bedtime by remember { mutableStateOf(profile.bedtime) }
    var wake by remember { mutableStateOf(profile.wakeTime) }
    fun report() = onChange(if (bedtime != null && wake != null && bedtime != wake) profile.copy(bedtime = bedtime, wakeTime = wake) else null)
    TimeField(stringResource(R.string.goal_input_bedtime), bedtime) {
        bedtime = it
        report()
    }
    TimeField(stringResource(R.string.goal_input_wake), wake) {
        wake = it
        report()
    }
}

/** A single time goal (bedtime, wake time): the clock sits in the dialog itself, and Save keeps what it shows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InlineTime(initial: LocalTime?, onChange: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initialHour = initial?.hour ?: 0, initialMinute = initial?.minute ?: 0, is24Hour = true)
    LaunchedEffect(state) {
        snapshotFlow { LocalTime.of(state.hour, state.minute) }.collect(onChange)
    }
    TimePicker(state)
}

/** A labelled time that opens a clock picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeField(label: String, initial: LocalTime?, onPick: (LocalTime) -> Unit) {
    var time by remember { mutableStateOf(initial) }
    var picking by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { picking = true }) { Text(time?.format(CLOCK) ?: stringResource(R.string.goal_input_choose)) }
    }
    if (picking) {
        val state = rememberTimePickerState(initialHour = time?.hour ?: 0, initialMinute = time?.minute ?: 0, is24Hour = true)
        AlertDialog(
            onDismissRequest = { picking = false },
            text = { TimePicker(state) },
            confirmButton = {
                TextButton(onClick = {
                    picking = false
                    LocalTime.of(state.hour, state.minute).let {
                        time = it
                        onPick(it)
                    }
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Fitbit's hourly goal: a start and an end hour; each hour in between needs 250 steps. */
@Composable
private fun HourlyInput(profile: Profile, onChange: (Profile?) -> Unit) {
    var first by remember { mutableStateOf(profile.activeHours?.first) }
    var end by remember { mutableStateOf(profile.activeHours?.last?.plus(1)) }
    fun report() {
        val from = first
        val until = end
        onChange(if (from != null && until != null && until > from) profile.copy(activeHours = from until until) else null)
    }
    HourField(stringResource(R.string.goal_input_start), first, 0..23) {
        first = it
        report()
    }
    HourField(stringResource(R.string.goal_input_end), end, 1..24) {
        end = it
        report()
    }
}

@Composable
private fun HourField(label: String, hour: Int?, options: IntRange, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { open = true }) { Text(hour?.let(::hourText) ?: stringResource(R.string.goal_input_choose)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(hourText(option)) },
                        onClick = {
                            open = false
                            onPick(option)
                        },
                    )
                }
            }
        }
    }
}

fun hourText(hour: Int): String = "%02d:00".format(hour % 24)

@Composable
private fun FitnessInput(profile: Profile, onChange: (Profile?) -> Unit) {
    var selected by remember { mutableStateOf(profile.fitnessGoal) }
    Column {
        Text(stringResource(R.string.goal_fitness_body), style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant)
        listOf(FitnessGoal.Maintain to R.string.fitness_goal_maintain, FitnessGoal.Improve to R.string.fitness_goal_improve).forEach { (option, text) ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = option == selected, onClick = {
                    selected = option
                    onChange(profile.copy(fitnessGoal = option))
                }).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = option == selected, onClick = null)
                Text(stringResource(text), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}
