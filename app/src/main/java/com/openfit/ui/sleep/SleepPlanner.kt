package com.openfit.ui.sleep

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openfit.R
import com.openfit.domain.sleepPlan
import com.openfit.profile.Profile
import com.openfit.ui.currentLocale
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepPlanner(profile: Profile, onSave: (Profile) -> Unit, onDismiss: () -> Unit) {
    var minutes by rememberSaveable { mutableIntStateOf((profile.sleepGoalMinutes ?: 480).coerceIn(180, 720)) }
    var windDown by rememberSaveable { mutableIntStateOf(30) }
    var hour by rememberSaveable { mutableIntStateOf(profile.wakeTime?.hour ?: 7) }
    var minute by rememberSaveable { mutableIntStateOf(profile.wakeTime?.minute ?: 0) }
    var editingWake by rememberSaveable { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val now = ZonedDateTime.now(zone)
    val wakeTime = LocalTime.of(hour, minute)
    val wakeDate = LocalDate.now(zone).let { if (wakeTime <= now.toLocalTime()) it.plusDays(1) else it }
    val plan = sleepPlan(wakeDate.atTime(wakeTime).atZone(zone), minutes, windDown)
    val format = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(currentLocale())
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.sleep_planner), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.sleep_planner_body), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { editingWake = true }) {
                Text(stringResource(R.string.sleep_planner_wake, wakeTime.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(currentLocale()))))
            }
            PlannerQuantity(stringResource(R.string.sleep_planner_duration, minutes / 60, minutes % 60), minutes > 180, minutes < 720,
                onLess = { minutes = (minutes - 15).coerceAtLeast(180) }, onMore = { minutes = (minutes + 15).coerceAtMost(720) })
            PlannerQuantity(stringResource(R.string.sleep_planner_wind_down, windDown), windDown > 0, windDown < 120,
                onLess = { windDown -= 15 }, onMore = { windDown += 15 })
            HorizontalDivider()
            Text(stringResource(R.string.sleep_planner_plan, plan.windDown.format(format), plan.bedtime.format(format), plan.wake.format(format)), style = MaterialTheme.typography.bodyLarge)
            if (plan.bedtime < now) Text(stringResource(R.string.sleep_planner_past), color = MaterialTheme.colorScheme.error)
            Button(onClick = {
                onSave(profile.copy(sleepGoalMinutes = minutes, bedtime = plan.bedtime.toLocalTime(), wakeTime = wakeTime))
                onDismiss()
            }) { Text(stringResource(R.string.sleep_planner_save)) }
        }
    }
    if (editingWake) {
        val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = android.text.format.DateFormat.is24HourFormat(androidx.compose.ui.platform.LocalContext.current))
        AlertDialog(onDismissRequest = { editingWake = false }, title = { Text(stringResource(R.string.goal_wake_time)) },
            text = { TimeInput(state) },
            confirmButton = { TextButton(onClick = { hour = state.hour; minute = state.minute; editingWake = false }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { editingWake = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun PlannerQuantity(label: String, lessEnabled: Boolean, moreEnabled: Boolean, onLess: () -> Unit, onMore: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        TextButton(onClick = onLess, enabled = lessEnabled) { Text(stringResource(R.string.planner_less)) }
        TextButton(onClick = onMore, enabled = moreEnabled) { Text(stringResource(R.string.planner_more)) }
    }
}
