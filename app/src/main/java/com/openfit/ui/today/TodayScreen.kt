package com.openfit.ui.today

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openfit.R
import com.openfit.health.HealthConnectGateway
import com.openfit.profile.Profile
import com.openfit.ui.theme.MetricColors
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun TodayRoute(viewModel: TodayViewModel, onOpenHealthConnectStore: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { viewModel.refresh() }
    val requestPermissions = { permissionLauncher.launch(HealthConnectGateway.PERMISSIONS) }

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    when (val current = state) {
        TodayUiState.Loading -> CenteredMessage { CircularProgressIndicator() }
        is TodayUiState.Unavailable -> CenteredMessage(
            text = stringResource(if (current.updateRequired) R.string.hc_update_required else R.string.hc_unavailable),
            action = if (current.updateRequired) stringResource(R.string.hc_open_store) to onOpenHealthConnectStore else null,
        )
        TodayUiState.PermissionsRequired -> CenteredMessage(
            text = stringResource(R.string.permissions_rationale),
            action = stringResource(R.string.permissions_grant) to requestPermissions,
        )
        is TodayUiState.Failed -> CenteredMessage(
            text = current.message,
            action = stringResource(R.string.retry) to viewModel::refresh,
        )
        is TodayUiState.Ready -> TodayScreen(
            state = current,
            onRefresh = viewModel::refresh,
            onRequestPermissions = requestPermissions,
            onSaveProfile = viewModel::saveProfile,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodayScreen(
    state: TodayUiState.Ready,
    onRefresh: () -> Unit,
    onRequestPermissions: () -> Unit,
    onSaveProfile: (Profile) -> Unit,
) {
    var editingProfile by rememberSaveable { mutableStateOf(false) }
    val summary = state.summary
    val profile = state.profile

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.today))
                        Text(
                            state.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { editingProfile = true }) { Text(stringResource(R.string.profile)) }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.padding(padding),
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    StepsHero(steps = summary.steps, goal = profile.dailyStepGoal)
                }
                if (state.missingPermissions) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(stringResource(R.string.permissions_partial))
                                TextButton(onClick = onRequestPermissions) { Text(stringResource(R.string.permissions_grant)) }
                            }
                        }
                    }
                }
                item {
                    RingTile(
                        label = stringResource(R.string.zone_minutes),
                        value = summary.zoneMinutes?.total?.toString() ?: "—",
                        caption = if (profile.age == null) {
                            stringResource(R.string.zone_minutes_needs_age)
                        } else {
                            stringResource(R.string.of_goal, profile.dailyZoneMinutesGoal)
                        },
                        progress = (summary.zoneMinutes?.total ?: 0).toFloat() / profile.dailyZoneMinutesGoal,
                        color = MetricColors.ZoneMinutes,
                    )
                }
                item {
                    StatTile(
                        label = stringResource(R.string.resting_heart_rate),
                        value = summary.restingBpm?.toString() ?: "—",
                        unit = stringResource(R.string.unit_bpm),
                        color = MetricColors.HeartRate,
                    )
                }
                item {
                    StatTile(
                        label = stringResource(R.string.distance),
                        value = summary.distanceMeters?.let { "%.2f".format(it / 1000) } ?: "—",
                        unit = stringResource(R.string.unit_km),
                        color = MetricColors.Steps,
                    )
                }
                item {
                    StatTile(
                        label = stringResource(R.string.floors),
                        value = summary.floors?.toInt()?.toString() ?: "—",
                        unit = "",
                        color = MetricColors.Steps,
                    )
                }
                item {
                    StatTile(
                        label = stringResource(R.string.calories),
                        value = summary.caloriesKcal?.let { "%,d".format(it.toInt()) } ?: "—",
                        unit = stringResource(R.string.unit_kcal),
                        color = MetricColors.ZoneMinutes,
                    )
                }
                item {
                    StatTile(
                        label = stringResource(R.string.sleep),
                        value = summary.asleep?.let(::formatDuration) ?: "—",
                        unit = "",
                        color = MetricColors.Sleep,
                    )
                }
            }
        }
    }

    if (editingProfile) {
        ProfileDialog(
            profile = profile,
            onDismiss = { editingProfile = false },
            onSave = {
                editingProfile = false
                onSaveProfile(it)
            },
        )
    }
}

@Composable
private fun StepsHero(steps: Long?, goal: Int) {
    Card(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            ProgressRing(progress = (steps ?: 0).toFloat() / goal, color = MetricColors.Steps, size = 180.dp, stroke = 14.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    steps?.let { "%,d".format(it) } ?: "—",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(stringResource(R.string.steps), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.of_goal_steps, goal), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun RingTile(label: String, value: String, caption: String, progress: Float, color: Color) {
    Card(Modifier.fillMaxWidth().height(160.dp)) {
        Column(
            Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(contentAlignment = Alignment.Center) {
                ProgressRing(progress = progress, color = color, size = 72.dp, stroke = 8.dp)
                Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(caption, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, unit: String, color: Color) {
    Card(Modifier.fillMaxWidth().height(160.dp)) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = color)
            Column {
                Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                if (unit.isNotEmpty()) Text(unit, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ProgressRing(progress: Float, color: Color, size: Dp, stroke: Dp) {
    Canvas(Modifier.size(size)) {
        val style = Stroke(width = stroke.toPx(), cap = StrokeCap.Round)
        drawArc(MetricColors.Track, startAngle = 0f, sweepAngle = 360f, useCenter = false, style = style)
        drawArc(color, startAngle = -90f, sweepAngle = 360f * progress.coerceIn(0f, 1f), useCenter = false, style = style)
    }
}

@Composable
private fun ProfileDialog(profile: Profile, onDismiss: () -> Unit, onSave: (Profile) -> Unit) {
    var age by remember { mutableStateOf(profile.age?.toString().orEmpty()) }
    var stepGoal by remember { mutableStateOf(profile.dailyStepGoal.toString()) }
    var zoneGoal by remember { mutableStateOf(profile.dailyZoneMinutesGoal.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(stringResource(R.string.age), age) { age = it }
                NumberField(stringResource(R.string.daily_step_goal), stepGoal) { stepGoal = it }
                NumberField(stringResource(R.string.daily_zone_minutes_goal), zoneGoal) { zoneGoal = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    Profile(
                        age = age.toIntOrNull()?.takeIf { it in 5..120 },
                        dailyStepGoal = stepGoal.toIntOrNull()?.takeIf { it > 0 } ?: profile.dailyStepGoal,
                        dailyZoneMinutesGoal = zoneGoal.toIntOrNull()?.takeIf { it > 0 } ?: profile.dailyZoneMinutesGoal,
                    ),
                )
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun NumberField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> onValueChange(input.filter(Char::isDigit)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
        text?.let { Text(it, textAlign = TextAlign.Center) }
        action?.let { (label, onClick) ->
            Spacer(Modifier.height(16.dp))
            Button(onClick = onClick) { Text(label) }
        }
    }
}

private fun formatDuration(duration: Duration): String = "${duration.toHours()}h ${duration.toMinutesPart()}m"
