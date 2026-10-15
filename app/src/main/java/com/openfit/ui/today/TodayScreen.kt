package com.openfit.ui.today

import android.app.PendingIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openfit.R
import com.openfit.ui.MetricPage
import com.openfit.domain.FitnessGoal
import com.openfit.domain.Sex
import com.openfit.profile.Profile
import com.openfit.profile.UnitSystem
import com.openfit.profile.WeightUnit
import com.openfit.ui.theme.OpenFitColors
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun TodayRoute(viewModel: TodayViewModel, onOpenHealthConnectStore: () -> Unit, onOpen: (MetricPage, LocalDate) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { viewModel.refresh() }
    val requestPermissions = { permissions: Set<String> -> permissionLauncher.launch(permissions) }
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        viewModel.refresh()
    }
    val scope = rememberCoroutineScope()
    val launchConsent = { consent: PendingIntent -> consentLauncher.launch(IntentSenderRequest.Builder(consent).build()) }
    val connectGoogleHealth: () -> Unit = {
        scope.launch { viewModel.connectGoogleHealth()?.let(launchConsent) }
    }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshIfStale()
        onPauseOrDispose { }
    }

    Scaffold(containerColor = OpenFitColors.Background) { padding ->
        Column(Modifier.padding(padding)) {
            when (val current = state) {
                TodayUiState.Loading -> CenteredMessage { CircularProgressIndicator() }
                is TodayUiState.Unavailable -> CenteredMessage(
                    text = stringResource(if (current.updateRequired) R.string.hc_update_required else R.string.hc_unavailable),
                    action = if (current.updateRequired) stringResource(R.string.hc_open_store) to onOpenHealthConnectStore else null,
                )
                is TodayUiState.PermissionsRequired -> CenteredMessage(
                    text = stringResource(R.string.permissions_rationale),
                    action = stringResource(R.string.permissions_grant) to { requestPermissions(current.permissions) },
                )
                is TodayUiState.Failed -> CenteredMessage(
                    text = current.message,
                    action = stringResource(R.string.retry) to viewModel::refresh,
                )
                is TodayUiState.Ready -> TodayScreen(
                    state = current,
                    onRefresh = viewModel::refresh,
                    onShowDate = viewModel::showDate,
                    onRequestPermissions = { requestPermissions(current.missingPermissions) },
                    onSaveProfile = viewModel::saveProfile,
                    onConnectGoogleHealth = connectGoogleHealth,
                    onDisconnectGoogleHealth = viewModel::disconnectGoogleHealth,
                    onOpen = { page -> onOpen(page, current.date) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodayScreen(
    state: TodayUiState.Ready,
    onRefresh: () -> Unit,
    onShowDate: (LocalDate) -> Unit,
    onRequestPermissions: () -> Unit,
    onSaveProfile: (Profile) -> Unit,
    onConnectGoogleHealth: () -> Unit,
    onDisconnectGoogleHealth: () -> Unit,
    onOpen: (MetricPage) -> Unit,
) {
    var editingProfile by rememberSaveable { mutableStateOf(false) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }

    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { Header(onOpenProfile = { editingProfile = true }) }
            item {
                DateBar(
                    state = state,
                    onPrevious = { onShowDate(state.date.minusDays(1)) },
                    onNext = { onShowDate(state.date.plusDays(1)) },
                    onToday = { onShowDate(LocalDate.now()) },
                    onPick = { pickingDate = true },
                )
            }
            if (state.missingPermissions.isNotEmpty()) {
                item { Notice(stringResource(R.string.permissions_partial), stringResource(R.string.permissions_grant), onRequestPermissions) }
            }
            item { FocusSection(state, onOpen) }
            todaySections(state, onOpen)
        }
    }

    if (pickingDate) {
        DatePickerSheet(
            selected = state.date,
            onDismiss = { pickingDate = false },
            onPick = {
                pickingDate = false
                onShowDate(it)
            },
        )
    }

    if (editingProfile) {
        ProfileDialog(
            profile = state.profile,
            googleHealthConnected = state.googleHealth != GoogleHealthStatus.Off,
            googleHealthProblem = googleHealthNotice(state.googleHealth)?.let { it.message ?: stringResource(it.messageRes) },
            onConnectGoogleHealth = {
                editingProfile = false
                onConnectGoogleHealth()
            },
            onDisconnectGoogleHealth = {
                editingProfile = false
                onDisconnectGoogleHealth()
            },
            onDismiss = { editingProfile = false },
            onSave = {
                editingProfile = false
                onSaveProfile(it)
            },
        )
    }
}

@Composable
private fun Header(onOpenProfile: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.size(48.dp))
        Text(
            stringResource(R.string.app_name),
            fontSize = 26.sp,
            color = OpenFitColors.OnSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onOpenProfile) {
            Icon(Icons.Outlined.AccountCircle, stringResource(R.string.profile), tint = OpenFitColors.OnSurface, modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
private fun DateBar(state: TodayUiState.Ready, onPrevious: () -> Unit, onNext: () -> Unit, onToday: () -> Unit, onPick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(DATE_BAR_SLOT)) {
            IconButton(onClick = onPrevious) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.previous_day), tint = OpenFitColors.OnSurface)
            }
        }
        Text(
            if (state.isToday) stringResource(R.string.today) else state.date.weekdayDayMonth(),
            style = MaterialTheme.typography.titleMedium,
            color = OpenFitColors.OnSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).clickable(onClickLabel = stringResource(R.string.pick_date), onClick = onPick).padding(vertical = 12.dp),
        )
        Row(Modifier.width(DATE_BAR_SLOT), horizontalArrangement = Arrangement.End) {
            if (!state.isToday) {
                IconButton(onClick = onToday) {
                    Icon(Icons.Outlined.CalendarToday, stringResource(R.string.jump_to_today), tint = OpenFitColors.OnSurface)
                }
                IconButton(onClick = onNext) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.next_day), tint = OpenFitColors.OnSurface)
                }
            }
        }
    }
}

private val DATE_BAR_SLOT = 96.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerSheet(selected: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = selected.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcDate(utcTimeMillis) <= today
            override fun isSelectableYear(year: Int): Boolean = year <= today.year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { pickerState.selectedDateMillis?.let { onPick(utcDate(it)) } ?: onDismiss() }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    ) {
        DatePicker(pickerState)
    }
}

private fun utcDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

@Composable
private fun Notice(message: String, action: String, onAction: () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = OpenFitColors.CardHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurface)
            TextButton(onClick = onAction, modifier = Modifier.align(Alignment.End)) { Text(action) }
        }
    }
}

private class NoticeText(@StringRes val messageRes: Int, val message: String? = null)

private fun googleHealthNotice(status: GoogleHealthStatus): NoticeText? = when (status) {
    GoogleHealthStatus.Connected -> null
    GoogleHealthStatus.Off -> null
    is GoogleHealthStatus.NeedsConsent -> NoticeText(R.string.google_health_reconnect)
    is GoogleHealthStatus.Failed -> NoticeText(R.string.google_health_reconnect, status.message)
}

@Composable
private fun ProfileDialog(
    profile: Profile,
    googleHealthConnected: Boolean,
    googleHealthProblem: String?,
    onConnectGoogleHealth: () -> Unit,
    onDisconnectGoogleHealth: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (Profile) -> Unit,
) {
    var age by remember { mutableStateOf(profile.age?.toString().orEmpty()) }
    var stepGoal by remember { mutableStateOf(profile.dailyStepGoal.toString()) }
    var fitnessGoal by remember { mutableStateOf(profile.fitnessGoal) }
    var sex by remember { mutableStateOf(profile.sex) }
    var sleepGoalHours by remember { mutableStateOf(oneDecimal(profile.sleepGoalMinutes / 60.0)) }
    var exerciseDaysGoal by remember { mutableStateOf(profile.weeklyExerciseDaysGoal.toString()) }
    var units by remember { mutableStateOf(profile.units) }
    var weightUnit by remember { mutableStateOf(profile.weightUnit) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { NumberField(stringResource(R.string.age), age) { age = it } }
                item {
                    ChoiceRow(stringResource(R.string.sex), listOf(Sex.Male to R.string.sex_male, Sex.Female to R.string.sex_female), sex) { sex = it }
                }
                item { NumberField(stringResource(R.string.daily_step_goal), stepGoal) { stepGoal = it } }
                item {
                    ChoiceRow(
                        stringResource(R.string.fitness_goal),
                        listOf(FitnessGoal.Maintain to R.string.fitness_goal_maintain, FitnessGoal.Improve to R.string.fitness_goal_improve),
                        fitnessGoal,
                    ) { fitnessGoal = it }
                }
                item { NumberField(stringResource(R.string.sleep_goal_hours), sleepGoalHours, decimal = true) { sleepGoalHours = it } }
                item { NumberField(stringResource(R.string.exercise_days_goal), exerciseDaysGoal) { exerciseDaysGoal = it } }
                item {
                    ChoiceRow(
                        stringResource(R.string.units_distance),
                        listOf(UnitSystem.Metric to R.string.units_metric, UnitSystem.Imperial to R.string.units_imperial),
                        units,
                    ) { units = it }
                }
                item {
                    ChoiceRow(
                        stringResource(R.string.units_weight),
                        listOf(WeightUnit.Kilograms to R.string.unit_kg, WeightUnit.Pounds to R.string.unit_lb),
                        weightUnit,
                    ) { weightUnit = it }
                }
                item {
                    if (googleHealthConnected) {
                        Column {
                            googleHealthProblem?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            Row {
                                if (googleHealthProblem != null) {
                                    TextButton(onClick = onConnectGoogleHealth) { Text(stringResource(R.string.google_health_connect)) }
                                }
                                TextButton(onClick = onDisconnectGoogleHealth) { Text(stringResource(R.string.google_health_disconnect)) }
                            }
                        }
                    } else {
                        Column {
                            Text(stringResource(R.string.google_health_offer), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = onConnectGoogleHealth) { Text(stringResource(R.string.google_health_connect)) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    profile.copy(
                        age = age.toIntOrNull()?.takeIf { it in 5..120 },
                        units = units,
                        weightUnit = weightUnit,
                        dailyStepGoal = stepGoal.toIntOrNull()?.takeIf { it > 0 } ?: profile.dailyStepGoal,
                        fitnessGoal = fitnessGoal,
                        sex = sex,
                        sleepGoalMinutes = sleepGoalHours.toDoubleOrNull()?.takeIf { it in 1.0..16.0 }?.let { (it * 60).toInt() }
                            ?: profile.sleepGoalMinutes,
                        weeklyExerciseDaysGoal = exerciseDaysGoal.toIntOrNull()?.takeIf { it in 1..7 } ?: profile.weeklyExerciseDaysGoal,
                    ),
                )
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun <T> ChoiceRow(label: String, options: List<Pair<T, Int>>, selected: T, onSelect: (T) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, text) ->
                FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(stringResource(text)) })
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, decimal: Boolean = false, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> onValueChange(input.filter { it.isDigit() || (decimal && it == '.') }) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
    )
}

@Composable
private fun CenteredMessage(
    text: String? = null,
    action: Pair<String, () -> Unit>? = null,
    content: @Composable () -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        content()
        text?.let { Text(it, textAlign = TextAlign.Center, color = OpenFitColors.OnSurface) }
        action?.let { (label, onClick) ->
            Spacer(Modifier.height(16.dp))
            Button(onClick = onClick) { Text(label) }
        }
    }
}
